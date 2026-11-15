package de.yurtemre.sonora.devicesync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
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

    /**
     * Registers the sender on the LAN. Closing unregisters; closing twice is a no-op.
     * Throws [IOException] if registration cannot be started; later failures are reported via [onFailure]
     * with the NSD error code.
     */
    fun advertise(port: Int, deviceId: String, deviceName: String, onFailure: (Int) -> Unit = {}): AutoCloseable {
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
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = onFailure(errorCode)
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            throw IOException("advertise_failed", e)
        }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                runCatching { manager.unregisterService(listener) }
            }
        }
    }

    /**
     * Emits the current list of senders on every change (latest wins); a lost service is removed.
     * [excludeServiceName] hides our own advertisement. The flow fails with `IOException("discovery_failed:<code>")`
     * if discovery cannot start, so the UI can offer manual IP:port.
     */
    fun discover(excludeServiceName: String? = null): Flow<List<DiscoveredSender>> = callbackFlow {
        val lockObj = Any()
        val senders = LinkedHashMap<String, DiscoveredSender>()
        val alive = HashSet<String>() // services currently announced; a late resolve must not re-add a lost one
        val infoCallbacks = HashMap<String, Any>() // API 34+: NsdManager.ServiceInfoCallback per service
        val resolveQueue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        var closed = false
        var lock: WifiManager.MulticastLock? = null
        var manager: NsdManager? = null
        var discoveryListener: NsdManager.DiscoveryListener? = null

        // Called with lockObj held so emissions are ordered; the channel is conflated.
        fun publishLocked() {
            trySend(senders.values.toList())
        }

        fun unregisterInfoCallback(cb: Any) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { manager?.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) }
            }
        }

        fun cleanup() {
            val callbacks = synchronized(lockObj) {
                if (closed) return
                closed = true
                resolveQueue.clear()
                infoCallbacks.values.toList().also { infoCallbacks.clear() }
            }
            discoveryListener?.let { l -> runCatching { manager?.stopServiceDiscovery(l) } }
            callbacks.forEach { unregisterInfoCallback(it) }
            runCatching { if (lock?.isHeld == true) lock?.release() }
        }

        fun onResolved(serviceName: String, resolved: NsdServiceInfo) {
            val sender = toSender(serviceName, resolved)
            synchronized(lockObj) {
                if (closed || serviceName !in alive) return
                if (sender != null) senders[serviceName] = sender else senders.remove(serviceName)
                publishLocked()
            }
        }

        fun removeService(name: String) {
            val cb = synchronized(lockObj) {
                if (closed) return
                alive.remove(name)
                resolveQueue.removeAll { it.serviceName == name }
                senders.remove(name)
                publishLocked()
                infoCallbacks.remove(name)
            }
            if (cb != null) unregisterInfoCallback(cb)
        }

        // Pre-34: resolveService supports only one active resolve at a time, so queue them.
        @Suppress("DEPRECATION")
        fun resolveNext() {
            val next = synchronized(lockObj) {
                if (resolving || closed) return
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
                    manager?.resolveService(next, listener)
                } catch (_: RuntimeException) {
                    finish()
                }
            }
            start(true)
        }

        try {
            val m = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            manager = m
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wifi?.createMulticastLock("sonora-device-sync")?.apply {
                setReferenceCounted(false)
                acquire()
            }

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    close(IOException("discovery_failed:$errorCode"))
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val name = serviceInfo.serviceName
                    if (name == excludeServiceName) return
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val callback = object : NsdManager.ServiceInfoCallback {
                            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = onResolved(name, serviceInfo)
                            override fun onServiceLost() = removeService(name)
                            override fun onServiceInfoCallbackUnregistered() = Unit
                        }
                        val old = synchronized(lockObj) {
                            if (closed) return
                            alive.add(name)
                            infoCallbacks.put(name, callback)
                        }
                        if (old != null) unregisterInfoCallback(old)
                        try {
                            m.registerServiceInfoCallback(serviceInfo, Executor { it.run() }, callback)
                        } catch (_: RuntimeException) {
                            // Skip this service; never throw on the NSD thread.
                            synchronized(lockObj) { infoCallbacks.remove(name, callback) }
                        }
                    } else {
                        synchronized(lockObj) {
                            if (closed) return
                            alive.add(name)
                            resolveQueue.addLast(serviceInfo)
                        }
                        resolveNext()
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = removeService(serviceInfo.serviceName)
            }
            discoveryListener = listener

            trySend(emptyList())
            m.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            cleanup()
            close(if (e is IOException) e else IOException("discovery_failed:${e.message}", e))
            awaitClose { }
            return@callbackFlow
        }

        awaitClose { cleanup() }
    }.buffer(Channel.CONFLATED)

    /** The device's site-local IPv4 on Wi-Fi (wlan* preferred), for showing "IP:port" on the sender. */
    fun localIpv4(): String? {
        val candidates = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        // Prefer Wi-Fi (wlan*) or hotspot (ap*) interfaces, falling back to any other.
        val sorted = candidates.sortedByDescending { it.name.startsWith("wlan") || it.name.startsWith("ap") }
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
        // IPv6 zone ids (fe80::1%wlan0) are stripped, which can make link-local hosts unroutable; only used as a fallback.
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
            if (parts[0] == "0.0.0.0" || parts[0] == "255.255.255.255") return null
            return parts[0] to port
        }
    }
}
