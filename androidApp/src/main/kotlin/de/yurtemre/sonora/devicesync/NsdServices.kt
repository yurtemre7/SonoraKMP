package de.yurtemre.sonora.devicesync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
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
     * registration cannot be started. A registration that fails later is retried with a fresh listener
     * ([RegistrationRetry]); [onFailure] gets the NSD error code of every failed attempt. The host holds a multicast
     * lock while advertising, so the Wi-Fi chip keeps passing guests' mDNS queries up when the screen is off.
     */
    fun advertise(info: NsdServiceInfo, onFailure: (Int) -> Unit): AutoCloseable {
        val manager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val closed = AtomicBoolean(false)
        val lock = (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("sonora-advertise")?.apply { setReferenceCounted(false) }
        val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nsd-register").apply { isDaemon = true } }
        val active = java.util.concurrent.atomic.AtomicReference<NsdManager.RegistrationListener?>()

        fun register(attempt: Int) {
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    onFailure(errorCode)
                    val delay = RegistrationRetry.delayMs(attempt)
                    if (delay == null || closed.get()) return
                    try {
                        timer.schedule({ if (!closed.get()) runCatching { register(attempt + 1) } }, delay, TimeUnit.MILLISECONDS)
                    } catch (_: RejectedExecutionException) {
                        // closing
                    }
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            }
            active.set(listener)
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }

        try {
            runCatching { lock?.acquire() }
            register(0)
        } catch (e: RuntimeException) {
            runCatching { if (lock?.isHeld == true) lock.release() }
            timer.shutdownNow()
            throw IOException("advertise_failed", e)
        }
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                timer.shutdownNow()
                active.get()?.let { runCatching { manager.unregisterService(it) } }
                runCatching { if (lock?.isHeld == true) lock.release() }
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
        var closed = false
        val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "nsd-resolve").apply { isDaemon = true }
        }
        var lock: WifiManager.MulticastLock? = null
        var manager: NsdManager? = null
        var discoveryListener: NsdManager.DiscoveryListener? = null

        // Called with lockObj held so emissions are ordered; the channel is conflated.
        fun publishLocked() {
            trySend(senders.values.toList())
        }

        fun onResolved(serviceName: String, resolved: NsdServiceInfo) {
            val sender = toItem(serviceName, resolved)
            synchronized(lockObj) {
                if (closed || serviceName !in alive) return
                if (sender != null) senders[serviceName] = sender else senders.remove(serviceName)
                publishLocked()
            }
        }

        // resolveService allows one active resolve before Android 14, and is the fallback when an info callback
        // cannot be registered: queued, de-duplicated, with a timeout and retries ([ResolveQueue]).
        @Suppress("DEPRECATION")
        val resolver = ResolveQueue<NsdServiceInfo>(
            key = { it.serviceName },
            resolve = { info, done ->
                manager?.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done(false)
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        onResolved(info.serviceName, serviceInfo)
                        done(true)
                    }
                }) ?: done(false)
            },
            schedule = { delayMs, block ->
                val task = try {
                    timers.schedule({ runCatching(block) }, delayMs, TimeUnit.MILLISECONDS)
                } catch (_: RejectedExecutionException) {
                    null // closing
                }
                AutoCloseable { task?.cancel(false) }
            },
        )

        fun unregisterInfoCallback(cb: Any) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { manager?.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) }
            }
        }

        fun cleanup() {
            val callbacks = synchronized(lockObj) {
                if (closed) return
                closed = true
                infoCallbacks.values.toList().also { infoCallbacks.clear() }
            }
            resolver.close()
            timers.shutdownNow()
            discoveryListener?.let { l -> runCatching { manager?.stopServiceDiscovery(l) } }
            callbacks.forEach { unregisterInfoCallback(it) }
            runCatching { if (lock?.isHeld == true) lock?.release() }
        }

        fun removeService(name: String) {
            val cb = synchronized(lockObj) {
                if (closed) return
                alive.remove(name)
                senders.remove(name)
                publishLocked()
                infoCallbacks.remove(name)
            }
            resolver.remove(name)
            if (cb != null) unregisterInfoCallback(cb)
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
                    synchronized(lockObj) {
                        if (closed || !alive.add(name)) return // NSD re-announces a service once per interface
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        resolver.enqueue(serviceInfo)
                        return
                    }
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = resolver.enqueue(serviceInfo)
                        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = onResolved(name, serviceInfo)
                        override fun onServiceLost() = removeService(name)
                        override fun onServiceInfoCallbackUnregistered() = Unit
                    }
                    synchronized(lockObj) { infoCallbacks.put(name, callback) }
                    try {
                        m.registerServiceInfoCallback(serviceInfo, Executor { it.run() }, callback)
                    } catch (_: RuntimeException) {
                        // Never throw on the NSD thread: fall back to a one-shot resolve.
                        synchronized(lockObj) { infoCallbacks.remove(name, callback) }
                        resolver.enqueue(serviceInfo)
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

/** Retry schedule for a failed NSD registration: 3 retries after 0.5 s, 1.5 s, 3 s, then give up (manual IP:port stays). */
internal object RegistrationRetry {
    private val DELAYS_MS = longArrayOf(500, 1_500, 3_000)

    /** Delay before retry number [attempt]+1 (attempt 0 = the first try failed), or null when out of retries. */
    fun delayMs(attempt: Int): Long? = DELAYS_MS.getOrNull(attempt)
}
