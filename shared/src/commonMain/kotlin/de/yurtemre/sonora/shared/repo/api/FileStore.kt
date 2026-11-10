package de.yurtemre.sonora.shared.repo.api

/**
 * App-private file access (SPEC §5.4): playlist covers (LIB-17), M3U share files (LIB-18),
 * embedded art (LIB-06), backup documents (BAK-01/02). Paths are absolute strings; uri args are
 * platform document URIs (SAF on Android).
 */
interface FileStore {
    /** Absolute path of `filesDir/<relative>`. */
    fun filesPath(relative: String): String
    /** Absolute path of `cacheDir/<relative>`. */
    fun cachePath(relative: String): String
    suspend fun exists(path: String): Boolean
    suspend fun readText(path: String): String
    /** Writes via `.tmp` + rename. */
    suspend fun writeText(path: String, text: String)
    suspend fun writeBytes(path: String, bytes: ByteArray)
    suspend fun delete(path: String): Boolean
    suspend fun readUriBytes(uri: String): ByteArray
    suspend fun writeUriText(uri: String, text: String)
}
