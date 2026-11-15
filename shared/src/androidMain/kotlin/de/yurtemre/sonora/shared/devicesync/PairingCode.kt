package de.yurtemre.sonora.shared.devicesync

import java.security.SecureRandom

/** The one-time device sync code: 6 decimal digits, only ever used as the J-PAKE password (D-73). */
object PairingCode {
    const val LENGTH = 6

    fun generate(random: SecureRandom = SecureRandom()): String =
        random.nextInt(1_000_000).toString().padStart(LENGTH, '0')
}
