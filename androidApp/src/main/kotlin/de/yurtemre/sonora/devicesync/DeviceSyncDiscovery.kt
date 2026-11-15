package de.yurtemre.sonora.devicesync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A sender found via mDNS (DSYNC-02). [serviceName] is the unique NSD service name. */
data class DiscoveredSender(val serviceName: String, val name: String, val host: String, val port: Int)

/**
 * LAN discovery for device sync (DSYNC-02): advertises a sender as `_sonora-sync._tcp` and discovers
 * senders, plus the manual IP:port helpers for networks that block mDNS (R-17).
 */
class DeviceSyncDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsd get() = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Registers the sender on the LAN. Closing unregisters; closing twice is a no-op. */
    fun advertise(port: Int, deviceId: String, deviceName: String): AutoCloseable {
        val manager = nsd
        val info = NsdServiceInfo().apply {
            serviceName = serviceNameFor(deviceId)
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute(TXT_VERSION, PROTOCOL_VERSION)
            setAttribute(TXT_NAME, truncateName(deviceName))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                runCatching { manager.unregisterService(listener) }
            }
        }
    }

    /** Emits the current list of senders on every change; a lost service is removed. */
    fun discover(): Flow<List<DiscoveredSender>> = callbackFlow {
        val manager = nsd
        val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("sonora-device-sync")?.apply { setReferenceCounted(false) }
        runCatching { lock?.acquire() }

        val lockObj = Any()
        val senders = LinkedHashMap<String, DiscoveredSender>()
        val infoCallbacks = HashMap<String, Any>() // API 34+: NsdManager.ServiceInfoCallback per service
        var stopped = false

        fun publish() {
            val snapshot = synchronized(lockObj) { senders.values.toList() }
            trySend(snapshot)
        }

        fun onResolved(serviceName: String, resolved: NsdServiceInfo) {
            val sender = toSender(serviceName, resolved) ?: run {
                synchronized(lockObj) { senders.remove(serviceName) }
                publish()
                return
            }
            synchronized(lockObj) { if (!stopped) senders[serviceName] = sender }
            publish()
        }

        // Pre-34: resolveService supports only one active resolve at a time, so queue them.
        val resolveQueue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        @Suppress("DEPRECATION")
        fun resolveNext() {
            val next = synchronized(lockObj) {
                if (resolving || stopped) return
                val item = resolveQueue.removeFirstOrNull() ?: return
                resolving = true
                item
            }
            fun finish() {
                synchronized(lockObj) { resolving = false }
                resolveNext()
            }
            fun start(retry: Boolean) {
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && retry) start(false) else finish()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        onResolved(next.serviceName, serviceInfo)
                        finish()
                    }
                }
                try {
                    manager.resolveService(next, listener)
                } catch (_: IllegalArgumentException) {
                    finish()
                }
            }
            start(true)
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = onResolved(name, serviceInfo)
                        override fun onServiceLost() = removeService(name)
                        override fun onServiceInfoCallbackUnregistered() = Unit
                    }
                    val executor = Executor { it.run() }
                    synchronized(lockObj) {
                        if (stopped) return
                        infoCallbacks.put(name, callback)
                    }?.let { old -> runCatching { manager.unregisterServiceInfoCallback(old as NsdManager.ServiceInfoCallback) } }
                    manager.registerServiceInfoCallback(serviceInfo, executor, callback)
                } else {
                    synchronized(lockObj) { resolveQueue.addLast(serviceInfo) }
                    resolveNext()
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = removeService(serviceInfo.serviceName)

            private fun removeService(name: String) {
                val cb = synchronized(lockObj) {
                    resolveQueue.removeAll { it.serviceName == name }
                    senders.remove(name)
                    infoCallbacks.remove(name)
                }
                if (cb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    runCatching { manager.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) }
                }
                publish()
            }
        }

        trySend(emptyList())
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose {
            val callbacks = synchronized(lockObj) {
                stopped = true
                resolveQueue.clear()
                infoCallbacks.values.toList().also { infoCallbacks.clear() }
            }
            runCatching { manager.stopServiceDiscovery(discoveryListener) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                callbacks.forEach { runCatching { manager.unregisterServiceInfoCallback(it as NsdManager.ServiceInfoCallback) } }
            }
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    /** The device's site-local IPv4 on Wi-Fi (wlan* preferred), for showing "IP:port" on the sender. */
    fun localIpv4(): String? {
        val candidates = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        val sorted = candidates.sortedByDescending { it.name.startsWith("wlan") }
        for (iface in sorted) {
            val addr = iface.inetAddresses.toList()
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
            if (addr != null) return addr.hostAddress
        }
        return null
    }

    private fun toSender(serviceName: String, info: NsdServiceInfo): DiscoveredSender? {
        val attrs = info.attributes
        val version = attrs[TXT_VERSION]?.let { String(it, Charsets.UTF_8) }
        if (attrs.containsKey(TXT_VERSION) && version != PROTOCOL_VERSION) return null
        val name = attrs[TXT_NAME]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() } ?: serviceName
        val host = pickHost(info) ?: return null
        if (info.port <= 0) return null
        return DiscoveredSender(serviceName, name, host, info.port)
    }

    @Suppress("DEPRECATION")
    private fun pickHost(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            listOfNotNull(info.host)
        }
        val chosen = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        return chosen?.hostAddress?.substringBefore('%')
    }

    companion object {
        const val SERVICE_TYPE = "_sonora-sync._tcp"
        private const val TXT_VERSION = "v"
        private const val TXT_NAME = "name"
        private const val PROTOCOL_VERSION = "1"
        private const val MAX_NAME_BYTES = 60

        internal fun serviceNameFor(deviceId: String): String =
            "Sonora-" + deviceId.replace("-", "").take(4).lowercase()

        /** Truncates [name] to at most 60 UTF-8 bytes without splitting a character. */
        internal fun truncateName(name: String): String {
            var bytes = 0
            var i = 0
            while (i < name.length) {
                val cp = name.codePointAt(i)
                val size = when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + size > MAX_NAME_BYTES) break
                bytes += size
                i += Character.charCount(cp)
            }
            return name.substring(0, i)
        }

        /** Parses "a.b.c.d:port" (IPv4 literal, port 1..65535) with surrounding whitespace; else null. */
        fun parseManual(text: String): Pair<String, Int>? {
            val parts = text.trim().split(':')
            if (parts.size != 2) return null
            val octets = parts[0].split('.')
            if (octets.size != 4) return null
            if (octets.any { it.isEmpty() || it.length > 3 || !it.all { c -> c in '0'..'9' } || it.toInt() > 255 }) return null
            val portText = parts[1]
            if (portText.isEmpty() || portText.length > 5 || !portText.all { it in '0'..'9' }) return null
            val port = portText.toInt()
            if (port !in 1..65535) return null
            return parts[0] to port
        }
    }
}
