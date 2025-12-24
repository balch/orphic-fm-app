package org.balch.songe

class WasmPlatform : Platform {
    override val name: String = "WebAssembly"
}

actual fun getPlatform(): Platform = WasmPlatform()
