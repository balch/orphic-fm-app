@file:OptIn(ExperimentalWasmJsInterop::class)

package org.balch.songe.util

import kotlin.js.ExperimentalWasmJsInterop

@JsFun("() => Date.now()")
private external fun dateNow(): Double

actual fun currentTimeMillis(): Long = dateNow().toLong()
