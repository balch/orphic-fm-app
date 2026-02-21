package org.balch.orpheus.core.mediapipe

/**
 * Platform-agnostic camera frame data.
 * Pixels are stored as BGRA_8888 packed into a ByteArray
 * (matches Skia ColorType.BGRA_8888 on Desktop and Android Bitmap ARGB_8888).
 */
data class CameraFrame(
    val pixels: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CameraFrame) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = pixels.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}
