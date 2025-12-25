@file:OptIn(ExperimentalWasmJsInterop::class)

package org.balch.orpheus.util

@JsFun("() => Date.now()")
private external fun dateNow(): Double

actual fun currentTimeMillis(): Long = dateNow().toLong()
