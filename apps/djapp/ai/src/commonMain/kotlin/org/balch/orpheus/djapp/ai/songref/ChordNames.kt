package org.balch.orpheus.djapp.ai.songref

internal data class ParsedChord(val name: String, val root: Int, val quality: String, val bass: Int? = null)

// Canonical qualities in template order, with the chord tones as intervals from the root.
private val QUALITY_TONES: List<Pair<String, IntArray>> = listOf(
    "" to intArrayOf(0, 4, 7),
    "m" to intArrayOf(0, 3, 7),
    "5" to intArrayOf(0, 7),
    "7" to intArrayOf(0, 4, 7, 10),
    "m7" to intArrayOf(0, 3, 7, 10),
    "maj7" to intArrayOf(0, 4, 7, 11),
    "6" to intArrayOf(0, 4, 7, 9),
    "m6" to intArrayOf(0, 3, 7, 9),
    "9" to intArrayOf(0, 2, 4, 7, 10),
    "m9" to intArrayOf(0, 2, 3, 7, 10),
    "add9" to intArrayOf(0, 2, 4, 7),
    "sus2" to intArrayOf(0, 2, 7),
    "sus4" to intArrayOf(0, 5, 7),
    "7sus4" to intArrayOf(0, 5, 7, 10),
    "dim" to intArrayOf(0, 3, 6),
    "dim7" to intArrayOf(0, 3, 6, 9),
    "aug" to intArrayOf(0, 4, 8),
    "m7b5" to intArrayOf(0, 3, 6, 10),
)

private val QUALITY_ALIASES: Map<String, String> = buildMap {
    QUALITY_TONES.forEach { (q, _) -> put(q, q) }
    put("min", "m"); put("-", "m"); put("mi", "m")
    put("min7", "m7"); put("-7", "m7")
    put("M7", "maj7"); put("Maj7", "maj7"); put("Δ", "maj7"); put("Δ7", "maj7")
    put("°", "dim"); put("°7", "dim7")
    put("+", "aug")
    put("min6", "m6"); put("min9", "m9"); put("add2", "add9"); put("sus", "sus4")
    put("ø", "m7b5"); put("m7-5", "m7b5")
}

/** Parses a whole token like "F#m7" or "C/E"; null when any part is unknown. */
internal fun parseChordName(token: String): ParsedChord? {
    if (token.isEmpty()) return null
    val slash = token.indexOf('/')
    val head = if (slash >= 0) token.substring(0, slash) else token
    val bassText = if (slash >= 0) token.substring(slash + 1) else null
    val rootLen = if (head.length > 1 && (head[1] == '#' || head[1] == 'b')) 2 else 1
    if (head.length < rootLen) return null
    val rootText = head.substring(0, rootLen)
    val root = pitchClassOf(rootText) ?: return null
    val quality = QUALITY_ALIASES[head.substring(rootLen)] ?: return null
    val bass = bassText?.let { pitchClassOf(it) ?: return null }
    return ParsedChord(rootText + quality + (bassText?.let { "/$it" } ?: ""), root, quality, bass)
}

internal fun chordTones(quality: String): IntArray =
    QUALITY_TONES.firstOrNull { it.first == quality }?.second ?: intArrayOf(0, 4, 7)

/** Names a set of fretted midi notes: root = bass first, then any root as a slash chord. */
internal fun nameChordShape(midis: List<Int>): ParsedChord? {
    if (midis.isEmpty()) return null
    val bass = midis.min() % 12
    val pcs = midis.map { it % 12 }.toSet()
    fun matches(root: Int, tones: IntArray) = tones.map { (root + it) % 12 }.toSet() == pcs
    for ((q, tones) in QUALITY_TONES) {
        if (matches(bass, tones)) return ParsedChord(pitchClassName(bass) + q, bass, q)
    }
    for ((q, tones) in QUALITY_TONES) {
        for (root in pcs) {
            if (root != bass && q != "5" && matches(root, tones)) {
                return ParsedChord(pitchClassName(root) + q + "/" + pitchClassName(bass), root, q, bass)
            }
        }
    }
    return null
}
