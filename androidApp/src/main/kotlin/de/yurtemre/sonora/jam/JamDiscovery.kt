package de.yurtemre.sonora.jam

import android.content.Context
import android.net.nsd.NsdServiceInfo
import de.yurtemre.sonora.devicesync.DeviceSyncDiscovery
import de.yurtemre.sonora.devicesync.NsdServices
import de.yurtemre.sonora.shared.jam.JAM_PROTOCOL_VERSION
import java.io.IOException
import kotlinx.coroutines.flow.Flow

/**
 * A Jam found via mDNS. [protocol] is the host's [JAM_PROTOCOL_VERSION] from its TXT record (null when missing), so
 * the UI can show a version mismatch before joining; [hostName] is the host's display name.
 */
data class DiscoveredJam(val serviceName: String, val hostName: String, val host: String, val port: Int, val protocol: Int?)

/**
 * LAN discovery for the Jam: the device sync pattern ([DeviceSyncDiscovery], same NSD core) with service type
 * `_sonora-jam._tcp`. The TXT record carries only the protocol version and the host's display name, never the
 * Jam code. When mDNS is blocked, a guest joins by "IP:port" ([parseManual]) and `JamClient.join(host, port, …)`.
 */
class JamDiscovery(context: Context) {
    private val nsd = NsdServices(context)

    /**
     * Advertises a Jam on [port] as [hostName]. [instanceId] makes the NSD service name unique (e.g. a random id
     * per Jam). Closing unregisters; closing twice is a no-op. Throws [IOException] if registration cannot start.
     */
    fun advertise(port: Int, instanceId: String, hostName: String, onFailure: (Int) -> Unit = {}): AutoCloseable {
        val info = NsdServiceInfo().apply {
            serviceName = serviceNameFor(instanceId)
            serviceType = SERVICE_TYPE
            this.port = port
            txtRecord(hostName).forEach { (key, value) -> setAttribute(key, value) }
        }
        return nsd.advertise(info, onFailure)
    }

    /**
     * Emits the Jams on the LAN on every change (latest wins). [excludeServiceName] hides our own. Fails with
     * `IOException("discovery_failed:<code>")` if discovery cannot start, so the UI can offer manual IP:port.
     */
    fun discover(excludeServiceName: String? = null): Flow<List<DiscoveredJam>> =
        nsd.discover(SERVICE_TYPE, "sonora-jam", excludeServiceName, ::toJam)

    private fun toJam(serviceName: String, info: NsdServiceInfo): DiscoveredJam? {
        val attrs = info.attributes
        val protocol = attrs[TXT_VERSION]?.let { String(it, Charsets.UTF_8).toIntOrNull() }
        val name = attrs[TXT_NAME]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() } ?: serviceName
        val host = NsdServices.pickHost(info) ?: return null
        if (info.port <= 0) return null
        return DiscoveredJam(serviceName, name, host, info.port, protocol)
    }

    companion object {
        const val SERVICE_TYPE = "_sonora-jam._tcp"
        internal const val TXT_VERSION = "v"
        internal const val TXT_NAME = "name"

        internal fun serviceNameFor(instanceId: String): String =
            "SonoraJam-" + instanceId.replace("-", "").take(4).lowercase()

        /** Everything the advertisement publishes: protocol version and display name, nothing else. */
        internal fun txtRecord(hostName: String): Map<String, String> = mapOf(
            TXT_VERSION to JAM_PROTOCOL_VERSION.toString(),
            TXT_NAME to DeviceSyncDiscovery.truncateName(hostName),
        )

        /** Manual join: "a.b.c.d:port" → (host, port), same rules as device sync. */
        fun parseManual(text: String): Pair<String, Int>? = DeviceSyncDiscovery.parseManual(text)
    }
}
