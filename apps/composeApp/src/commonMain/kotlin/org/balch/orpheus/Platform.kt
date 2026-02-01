package org.balch.orpheus

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform