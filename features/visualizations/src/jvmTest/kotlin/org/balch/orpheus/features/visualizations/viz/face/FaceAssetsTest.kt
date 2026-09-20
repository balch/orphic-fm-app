package org.balch.orpheus.features.visualizations.viz.face

import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The test task's working dir is the module dir, so resources are read straight off disk. */
internal val faceDrawableDir = File("src/commonMain/composeResources/drawable")

internal fun faceFiles(): List<File> =
    generateSequence(0) { it + 1 }
        .map { File(faceDrawableDir, "face_$it.webp") }
        .takeWhile { it.exists() }
        .toList()

class FaceAssetsTest {
    @Test
    fun `keyframes are contiguous square and equally sized`() {
        val files = faceFiles()
        assertTrue(files.size >= 2, "need at least two keyframes, found ${files.size}")
        val stray = faceDrawableDir.listFiles { f -> f.name.startsWith("face_") }!!.size
        assertEquals(files.size, stray, "face_N.webp numbering has a gap")

        val sizes = files.map { Image.makeFromEncoded(it.readBytes()).run { width to height } }
        sizes.forEach { (w, h) -> assertEquals(w, h, "keyframe is not square") }
        assertEquals(1, sizes.distinct().size, "keyframes differ in size: $sizes")
    }
}
