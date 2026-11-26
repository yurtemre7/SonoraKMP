package de.yurtemre.sonora.shared.net

import java.net.ServerSocket

/**
 * Binds a listening socket on all interfaces, preferring [preferredPort] (the port this phone listened on last time,
 * so a remembered "ip:port" stays valid) and falling back to a free one when it is unset, outside the unprivileged
 * range or taken.
 */
fun bindServerSocket(preferredPort: Int = 0): ServerSocket {
    if (preferredPort in MIN_PORT..MAX_PORT) {
        try {
            return ServerSocket(preferredPort)
        } catch (_: java.io.IOException) {
            // taken: any free port
        }
    }
    return ServerSocket(0)
}

private const val MIN_PORT = 1024
private const val MAX_PORT = 65535
