package de.yurtemre.sonorakmp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
