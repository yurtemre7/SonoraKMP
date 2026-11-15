package de.yurtemre.sonora.shared.devicesync

import java.io.OutputStream

/**
 * Receiver side: where incoming files are written (DSYNC-11). For each file exactly one of [commit] / [abort]
 * follows [begin], after the returned stream has been closed.
 */
interface SyncFileSink {
    /** Starts writing [file] (e.g. to a temp file). */
    fun begin(file: ManifestFile): OutputStream

    /** Size and full SHA-256 ([sha256Hex]) were verified: make the file final. */
    fun commit(file: ManifestFile, sha256Hex: String)

    /** The transfer failed or was cancelled: discard what [begin] wrote. */
    fun abort(file: ManifestFile)
}
