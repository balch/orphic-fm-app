package org.balch.orpheus.djapp.widget

import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeIntervalSinceReferenceDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class DjWidgetStoreTest {

    // NSFileManager.temporaryDirectory isn't visible under that name here;
    // NSTemporaryDirectory() is the stable Kotlin/Native equivalent.
    private val dir: NSURL = NSURL.fileURLWithPath(NSTemporaryDirectory())
        .URLByAppendingPathComponent("djwidget-test-${kotlin.random.Random.nextInt()}")!!

    init {
        NSFileManager.defaultManager.createDirectoryAtURL(dir, true, null, null)
    }

    @AfterTest
    fun cleanup() {
        NSFileManager.defaultManager.removeItemAtURL(dir, null)
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32Premul(1080, 1080))
        bitmap.erase(0xFF3A1D14.toInt())
        return Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private fun snapshot(vibe: String) = DjWidgetSnapshot(
        currentVibe = vibe,
        albumTitle = "Zero to One",
        nextVibe = "—",
        isPlaying = true,
        timerRunning = false,
        timerRemainingSeconds = 0L,
        timerStatus = "IDLE",
        artworkPng = png(),
    )

    private fun files(): List<String> {
        val fm = NSFileManager.defaultManager
        @Suppress("UNCHECKED_CAST")
        val names = fm.contentsOfDirectoryAtPath(dir.path!!, null) as? List<String>
        return names ?: emptyList()
    }

    /** Path of the artwork file a given vibe would be written to, via the same
     *  slug the production code uses — not a hardcoded guess at its format. */
    private fun artworkPath(vibe: String): String =
        dir.URLByAppendingPathComponent("artwork-${DjWidgetWireSnapshot.artworkSlug(vibe)}.png")!!.path!!

    private fun modifiedAt(path: String): Double =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
            ?.get("NSFileModificationDate") as? NSDate)?.timeIntervalSince1970 ?: 0.0

    // This toolchain's NSDate exposes no 1970-epoch constructor or factory function,
    // only `timeIntervalSinceReferenceDate`; derive the fixed 1970->2001 offset once
    // from `NSDate()`/`.timeIntervalSince1970`, both already used above.
    private val referenceDateOffset: Double =
        NSDate().let { it.timeIntervalSince1970 - it.timeIntervalSinceReferenceDate }

    /** Pins a file's mtime so retention/reuse tests don't depend on wall-clock
     *  timing or filesystem timestamp resolution. */
    private fun pinModifiedAt(path: String, epochSeconds: Double) {
        val date = NSDate(timeIntervalSinceReferenceDate = epochSeconds - referenceDateOffset)
        NSFileManager.defaultManager.setAttributes(
            mapOf("NSFileModificationDate" to date),
            ofItemAtPath = path,
            error = null,
        )
    }

    @Test
    fun `writeTo emits a decodable snapshot naming its artwork`() {
        DjWidgetStore.writeTo(dir, snapshot("Rust Belt"))

        val url = dir.URLByAppendingPathComponent(DjWidgetStore.SNAPSHOT_FILE)!!
        val text = NSString.stringWithContentsOfURL(url, NSUTF8StringEncoding, null)
        val wire = assertNotNull(DjWidgetWire.decode(assertNotNull(text)))

        assertEquals("Rust Belt", wire.currentVibe)
        assertEquals("artwork-rust-belt.png", wire.artworkFile)
        assertTrue("artwork-rust-belt.png" in files())
    }

    @Test
    fun `writeTo does not re-encode artwork it already wrote for a vibe`() {
        DjWidgetStore.writeTo(dir, snapshot("Rust Belt"))
        val path = artworkPath("Rust Belt")
        pinModifiedAt(path, 1_700_000_000.0)
        val pinned = modifiedAt(path)

        DjWidgetStore.writeTo(dir, snapshot("Rust Belt"))

        assertEquals(pinned, modifiedAt(path), "a second write for the same vibe must not rewrite the file")
    }

    @Test
    fun `artwork retention keeps the most recent files not just a count`() {
        val base = 1_700_000_000.0
        repeat(DjWidgetStore.MAX_ARTWORK_FILES + 3) { i ->
            DjWidgetStore.writeTo(dir, snapshot("Vibe $i"))
            pinModifiedAt(artworkPath("Vibe $i"), base + i)
        }
        val artwork = files().filter { it.startsWith("artwork-") }

        assertEquals(DjWidgetStore.MAX_ARTWORK_FILES, artwork.size)
        assertFalse("artwork-vibe-0.png" in artwork, "oldest artwork should have been pruned")
        assertTrue("artwork-vibe-10.png" in artwork, "newest artwork should survive pruning")
    }
}
