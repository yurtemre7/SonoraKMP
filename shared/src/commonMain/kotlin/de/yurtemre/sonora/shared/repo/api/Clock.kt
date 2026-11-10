package de.yurtemre.sonora.shared.repo.api

/** Wall-clock source, injectable for tests (STAT-01 virtual clock, LIB-16 playlist ids, BAK-01 createdAt). */
fun interface Clock {
    fun nowMs(): Long
}
