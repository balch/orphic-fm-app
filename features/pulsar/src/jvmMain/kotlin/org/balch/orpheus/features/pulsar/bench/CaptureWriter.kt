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

@Serializable
data class CaptureFile(
    val vibe: String,
    val slug: String,
    val source: String?,
    val baselineHash: String,
    val currentHash: String?,
    val stale: Boolean,
    val capturedAt: String,
    val rows: List<CaptureRow>,
)

/** Writes `build/mix-bench/captures/<slug>-<timestamp>.json` under [repoRoot]. */
class CaptureWriter(private val repoRoot: File) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val vibesDir = File(repoRoot, "features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes")

    /** [baselineHash] is the source hash when the bench loaded the vibe; stale means the file changed since. */
    fun write(vibe: Vibe, rows: List<CaptureRow>, baselineHash: String): File {
        val src = findVibeSource(vibesDir, vibe.name)
        val currentHash = src?.let(::sha256)
        val slug = vibe.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val now = LocalDateTime.now()
        val body = CaptureFile(
            vibe = vibe.name,
            slug = slug,
            source = src?.relativeTo(repoRoot)?.invariantSeparatorsPath,
            baselineHash = baselineHash,
            currentHash = currentHash,
            stale = currentHash != null && currentHash != baselineHash,
            capturedAt = now.toString(),
            rows = rows,
        )
        val dir = File(repoRoot, "build/mix-bench/captures").apply { mkdirs() }
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        var out = File(dir, "$slug-$stamp.json")
        var n = 2
        while (!out.createNewFile()) out = File(dir, "$slug-$stamp-${n++}.json")
        out.writeText(json.encodeToString(CaptureFile.serializer(), body))
        return out
    }
}
