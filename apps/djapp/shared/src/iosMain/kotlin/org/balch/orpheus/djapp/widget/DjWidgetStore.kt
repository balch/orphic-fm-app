package org.balch.orpheus.djapp.widget

import com.diamondedge.logging.logging
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLContentModificationDateKey
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToURL

/**
 * Writes the widget's view of the world into the App Group container. The
 * extension links no Kotlin, so this file pair is the whole interface between
 * the two processes.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object DjWidgetStore {

    const val APP_GROUP = "group.org.balch.djapp"
    const val SNAPSHOT_FILE = "snapshot.json"
    const val MAX_ARTWORK_FILES = 8

    private val log = logging("DjWidgetStore")

    private fun container(): NSURL? =
        NSFileManager.defaultManager
            .containerURLForSecurityApplicationGroupIdentifier(APP_GROUP)

    /** Writes artwork (when the vibe changed) then the snapshot. Never throws. */
    fun write(snapshot: DjWidgetSnapshot) {
        val dir = container() ?: run {
            log.warn { "no app group container — is the entitlement present?" }
            return
        }
        writeTo(dir, snapshot)
    }

    /** Directory-injected so tests can drive a temp dir: a unit-test process has
     *  no App Group container. */
    internal fun writeTo(dir: NSURL, snapshot: DjWidgetSnapshot) {
        val artworkFile = snapshot.artworkPng?.let { writeArtwork(dir, snapshot.currentVibe, it) }
        val wire = DjWidgetWireSnapshot.from(
            snapshot = snapshot,
            artworkFile = artworkFile,
            writtenAtEpochMs = (NSDate().timeIntervalSince1970 * 1000.0).toLong(),
        )
        val url = dir.URLByAppendingPathComponent(SNAPSHOT_FILE) ?: return
        runCatching { DjWidgetWire.encode(wire).toNSData().writeToURL(url, atomically = true) }
            .onFailure { log.warn(it) { "snapshot write failed" } }
    }

    private fun writeArtwork(dir: NSURL, vibe: String, png: ByteArray): String? {
        val name = "artwork-${DjWidgetWireSnapshot.artworkSlug(vibe)}.png"
        val url = dir.URLByAppendingPathComponent(name) ?: return null
        val path = url.path ?: return null
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) return name

        val scaled = IosArtwork.downscale(png) ?: return null
        val ok = runCatching { scaled.toNSData().writeToURL(url, atomically = true) }
            .onFailure { log.warn(it) { "artwork write failed" } }
            .getOrDefault(false)
        if (!ok) return null
        pruneArtwork(dir)
        return name
    }

    /** Keeps the newest [MAX_ARTWORK_FILES]; the container is not a cache Apple manages. */
    private fun pruneArtwork(dir: NSURL) {
        val fm = NSFileManager.defaultManager
        val entries = fm.contentsOfDirectoryAtURL(
            dir, listOf(NSURLContentModificationDateKey), 0uL, null
        ) ?: return
        val artwork = entries.filterIsInstance<NSURL>()
            .filter { (it.lastPathComponent ?: "").startsWith("artwork-") }
        if (artwork.size <= MAX_ARTWORK_FILES) return
        artwork
            .sortedByDescending { url -> url.path?.let { fm.modifiedAt(it) } ?: 0.0 }
            .drop(MAX_ARTWORK_FILES)
            .forEach { fm.removeItemAtURL(it, null) }
    }

    private fun NSFileManager.modifiedAt(path: String): Double =
        (attributesOfItemAtPath(path, null)?.get("NSFileModificationDate") as? NSDate)
            ?.timeIntervalSince1970 ?: 0.0

    private fun String.toNSData(): NSData = encodeToByteArray().toNSData()

    /** [addressOf] throws on an empty array; every caller here passes a non-empty
     *  encoded payload, but that invariant lives in the caller, not here. */
    private fun ByteArray.toNSData(): NSData = usePinned {
        NSData.create(bytes = it.addressOf(0), length = size.toULong())
    }
}
