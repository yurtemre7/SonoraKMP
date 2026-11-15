package de.yurtemre.sonora.shared.devicesync

import java.io.InputStream

/** Sender side: reads the bytes of a file from the sender's own manifest (never any other path). */
interface SyncFileSource {
    /** Opens [file] from the start. Throws [java.io.IOException] if it cannot be read. */
    fun open(file: ManifestFile): InputStream
}
