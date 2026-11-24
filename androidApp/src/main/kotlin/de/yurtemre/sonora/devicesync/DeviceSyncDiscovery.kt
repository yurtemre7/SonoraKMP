package de.yurtemre.sonora.devicesync

import android.content.Context
import android.net.nsd.NsdServiceInfo
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.flow.Flow

/** A sender found via mDNS (DSYNC-02). [serviceName] is the unique NSD service name. */
data class DiscoveredSender(val serviceName: String, val name: String, val host: String, val port: Int)

/**
 * LAN discovery for device sync (DSYNC-02): advertises a sender as `_sonora-sync._tcp` and discovers
 * senders, plus the manual IP:port helpers for networks that block mDNS (R-17).
 */
class DeviceSyncDiscovery(context: Context) {
    private val nsd = NsdServices(context)

    /**
     * Registers the sender on the LAN. Closing unregisters; closing twice is a no-op.
     * Throws [IOException] if registration cannot be started; later failures are reported via [onFailure]
     * with the NSD error code.
     */
    fun advertise(port: Int, deviceId: String, deviceName: String, onFailure: (Int) -> Unit = {}): AutoCloseable {
        val info = NsdServiceInfo().apply {
            serviceName = serviceNameFor(deviceId)
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute(TXT_VERSION, PROTOCOL_VERSION)
            setAttribute(TXT_NAME, truncateName(deviceName))
        }
        return nsd.advertise(info, onFailure)
    }

    /**
     * Emits the current list of senders on every change (latest wins); a lost service is removed.
     * [excludeServiceName] hides our own advertisement. The flow fails with `IOException("discovery_failed:<code>")`
     * if discovery cannot start, so the UI can offer manual IP:port.
     */
    fun discover(excludeServiceName: String? = null): Flow<List<DiscoveredSender>> =
        nsd.discover(SERVICE_TYPE, "sonora-device-sync", excludeServiceName, ::toSender)

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
        val host = NsdServices.pickHost(info) ?: return null
        if (info.port <= 0) return null
        return DiscoveredSender(serviceName, name, host, info.port)
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
