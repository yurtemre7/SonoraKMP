package de.yurtemre.sonora.devicesync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * mDNS (NSD) advertising and discovery shared by device sync ([DeviceSyncDiscovery], `_sonora-sync._tcp`) and the
 * Jam (`JamDiscovery`, `_sonora-jam._tcp`).
 */
internal class NsdServices(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Registers [info] on the LAN. Closing unregisters; closing twice is a no-op. Throws [IOException] if
     * registration cannot be started; later failures are reported via [onFailure] with the NSD error code.
     */
    fun advertise(info: NsdServiceInfo, onFailure: (Int) -> Unit): AutoCloseable {
        val manager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
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
     * Emits the current list of [serviceType] services, each mapped by [toItem] (null hides it), on every change
     * (latest wins); a lost service is removed. [excludeServiceName] hides our own advertisement. The flow fails
     * with `IOException("discovery_failed:<code>")` if discovery cannot start, so the UI can offer manual IP:port.
     */
    fun <T : Any> discover(
        serviceType: String,
        multicastLockTag: String,
        excludeServiceName: String?,
        toItem: (serviceName: String, info: NsdServiceInfo) -> T?,
    ): Flow<List<T>> = callbackFlow {
        val lockObj = Any()
        val senders = LinkedHashMap<String, T>()
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
            val sender = toItem(serviceName, resolved)
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
            lock = wifi?.createMulticastLock(multicastLockTag)?.apply {
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
            m.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            cleanup()
            close(if (e is IOException) e else IOException("discovery_failed:${e.message}", e))
            awaitClose { }
            return@callbackFlow
        }

        awaitClose { cleanup() }
    }.buffer(Channel.CONFLATED)

    companion object {
        /** The service's address (IPv4 preferred) without an IPv6 zone id, or null. */
        @Suppress("DEPRECATION")
        fun pickHost(info: NsdServiceInfo): String? {
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                info.hostAddresses
            } else {
                listOfNotNull(info.host)
            }
            val chosen = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
            // IPv6 zone ids (fe80::1%wlan0) are stripped, which can make link-local hosts unroutable; only used as a fallback.
            return chosen?.hostAddress?.substringBefore('%')
        }
    }
}
