package org.balch.songe

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform