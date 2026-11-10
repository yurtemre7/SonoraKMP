package de.yurtemre.sonora.shared.model

/** An enum persisted by a stable string (matching Flutter's stored values). */
interface StorageEnum {
    val storageName: String
}

/** Looks up [value] by [StorageEnum.storageName], falling back to [default]. */
inline fun <reified E> enumFromStorage(value: String?, default: E): E
    where E : Enum<E>, E : StorageEnum =
    enumValues<E>().firstOrNull { it.storageName == value } ?: default
