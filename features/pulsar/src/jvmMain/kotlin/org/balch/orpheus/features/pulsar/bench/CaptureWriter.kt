package org.balch.orpheus.features.pulsar.bench

import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.balch.orpheus.features.pulsar.models.Vibe

private val NAME_LINE = Regex("""val\s+(\w+)\s*=\s*VibeName\("((?:[^"\\]|\\.)*)"\)""")

/**
 * The `.kt` under [vibesDir] whose provider declares [name], or null if none does
 * (the capture is still written then, with `source` null). Names resolve through `VibeNames.kt`.
 */
fun findVibeSource(vibesDir: File, name: String): File? {
    val names = File(vibesDir, "VibeNames.kt").takeIf { it.isFile } ?: return null
    val constant = NAME_LINE.findAll(names.readText())
        .firstOrNull { it.groupValues[2].replace("\\\"", "\"").replace("\\'", "'") == name }
        ?.groupValues?.get(1) ?: return null
    val use = Regex("""\bname\b[^=\n]*=\s*VibeNames\.$constant\b""")
    return vibesDir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" && it.name != "VibeNames.kt" }
        .sortedBy { it.path }
        .firstOrNull { use.containsMatchIn(it.readText()) }
}

fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

/**
 * One capture file. [source], [baselineHash] and [currentHash] need a repo checkout and a catalog vibe;
 * [stale] is null when it can't be told: no source file, or no baseline. [vibeJson] is the vibe the rows were read from.
 */
@Serializable
data class CaptureFile(
    val vibe: String,
    val slug: String,
    val source: String?,
    val baselineHash: String?,
    val currentHash: String?,
    val stale: Boolean?,
    val aiVibe: Boolean = false,
    val capturedAt: String,
    val rows: List<CaptureRow>,
    val vibeJson: String = "",
)

/** A capture on disk: where it went and what it holds, so callers need not read it back. */
data class WrittenCapture(val file: File, val capture: CaptureFile)

/** Writes `<slug>-<timestamp>.json` into [captureDir]; [repoRoot] (null without a checkout) finds vibe sources. */
class CaptureWriter(private val captureDir: File, private val repoRoot: File?) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    // Same encoding as the AI vibe tools (encodeDefaults, no pretty print), so the import pipeline reads it.
    private val vibeJson = Json { encodeDefaults = true }
    private val vibesDir = repoRoot?.let {
        File(it, "features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes")
    }

    /** The source hash of the vibe named [name], or null without a repo or a source file. */
    fun sourceHash(name: String): String? = vibesDir?.let { findVibeSource(it, name) }?.let(::sha256)

    /**
     * [baselineHash] is the source hash when the vibe was loaded, null if unknown; stale means the file changed since.
     * An [aiVibe] has no source file, so its source fields stay null.
     */
    fun write(vibe: Vibe, rows: List<CaptureRow>, baselineHash: String?, aiVibe: Boolean = false): WrittenCapture {
        val src = if (aiVibe) null else vibesDir?.let { findVibeSource(it, vibe.name) }
        val currentHash = src?.let(::sha256)
        val slug = vibe.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "vibe" }
        val now = LocalDateTime.now()
        val body = CaptureFile(
            vibe = vibe.name,
            slug = slug,
            source = src?.relativeTo(repoRoot!!)?.invariantSeparatorsPath,
            baselineHash = baselineHash.takeIf { src != null },
            currentHash = currentHash,
            stale = if (currentHash == null || baselineHash == null) null else currentHash != baselineHash,
            aiVibe = aiVibe,
            capturedAt = now.toString(),
            rows = rows,
            vibeJson = vibeJson.encodeToString(Vibe.serializer(), vibe),
        )
        captureDir.mkdirs()
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        var out = File(captureDir, "$slug-$stamp.json")
        var n = 2
        while (!out.createNewFile()) out = File(captureDir, "$slug-$stamp-${n++}.json")
        out.writeText(json.encodeToString(CaptureFile.serializer(), body))
        return WrittenCapture(out, body)
    }
}
