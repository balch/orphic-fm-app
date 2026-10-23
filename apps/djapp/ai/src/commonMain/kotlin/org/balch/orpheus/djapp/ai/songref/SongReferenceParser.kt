package org.balch.orpheus.djapp.ai.songref

import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType

private const val MAX_SECTIONS = 64
private const val MAX_PHRASE_SLOTS = 96
private const val MAX_MELODIES = 24
private const val MAX_SLOT_TEXT = 120
private const val NOTHING_MUSICAL = "Nothing musical found: no chord names or tab notes in the text."

private class SectionDraft(val index: Int, val name: String, val kind: SectionKind, var repeats: Int, var sameAs: Int?) {
    // A compound label such as "Verse/Bridge" answers for each kind it names.
    val kinds: List<SectionKind> = sectionKindsOf(name).ifEmpty { listOf(kind) }
    val chords = mutableListOf<ParsedChord>()
    val slots = mutableListOf<Int>()
    val blocks = mutableListOf<MelodyBlock>()
    val chordalSystems = mutableListOf<TabSystem>()
    val melodies = mutableListOf<Int>()
    var hasContent = false
}

/** Melodic tab systems that run on without a label or lyric between them. */
private class MelodyBlock(val section: SectionDraft, val phraseSlot: Int?) {
    val systems = mutableListOf<TabSystem>()
}

private class ChordTally(val chord: ParsedChord, var count: Int = 0, var shape: String? = null)

/** Parses a pasted song page; never throws, a failure comes back as `ok = false`. */
fun parseSongReference(raw: String, rootNote: RootNote? = null, scaleType: ScaleType? = null): SongReference =
    try {
        parse(raw, rootNote, scaleType)
    } catch (e: Exception) {
        SongReference(ok = false, message = "Could not read the page: ${e.message ?: e::class.simpleName}")
    }

private fun parse(raw: String, rootNote: RootNote?, scaleType: ScaleType?): SongReference {
    val page = extractPageText(raw)
    val lines = page.text.lines()
    val capo = page.capo.takeIf { it > 0 } ?: lines.firstNotNullOfOrNull(::capoOf) ?: 0
    val warnings = mutableListOf<String>()
    if (capo > 0) warnings += "capo $capo: chords and notes are transposed up $capo semitones to concert pitch"
    // Unknown whether the page key names the shapes or concert pitch, and a pinned wrong root could not be outvoted.
    val tonality = page.tonality.takeIf { capo == 0 }
    if (capo > 0 && page.tonality != null) {
        warnings += "the page key (${page.tonality}) was not used because of the capo; the key is guessed from the transposed chords"
    }
    val source = SongSource(page.format, page.title, page.artist, lines.size, capo)
    val systems = findTabSystems(lines, capo).associateBy { it.firstLine }

    val sections = mutableListOf<SectionDraft>()
    val slots = mutableListOf<PhraseSlot>()
    val occurrences = mutableListOf<Pair<ParsedChord, String?>>()
    var current: SectionDraft? = null
    var block: MelodyBlock? = null
    var sectionsCapped = false
    var slotsCapped = false

    // An empty labelled section repeats the first earlier one of its kind that has content.
    fun close(s: SectionDraft) {
        if (s.hasContent || s.sameAs != null) return
        s.sameAs = sections.firstOrNull {
            it !== s && it.hasContent && s.kind in it.kinds && (s.kind != SectionKind.OTHER || it.name.equals(s.name, ignoreCase = true))
        }?.index
    }

    fun open(name: String, kind: SectionKind, repeats: Int, sameAs: Int? = null): Boolean {
        current?.let(::close)
        block = null
        if (sections.size >= MAX_SECTIONS) { sectionsCapped = true; return false }
        current = SectionDraft(sections.size, name, kind, repeats, sameAs).also { sections += it }
        return true
    }

    fun section(): SectionDraft? = current ?: if (open("Song", SectionKind.OTHER, 1)) current else null

    fun addChord(chord: ParsedChord, shape: String?): Boolean {
        val s = section() ?: return false
        s.chords += chord
        s.hasContent = true
        occurrences += chord to shape
        return true
    }

    val kinds = lines.map(::classifyLine)
    // On a page of quoted cues (at least 2, and half the lyric lines), unquoted text is prose unless it sits under a chord line.
    val lyricLines = lines.indices.filter { kinds[it] == LineKind.LYRIC }
    val cueLines = lyricLines.count { isQuotedCue(lines[it]) }
    val cuePage = cueLines >= 2 && cueLines * 2 >= lyricLines.size
    var above: List<ParsedChord>? = null
    // A slot with no chords of its own takes those of the chordal system that follows it.
    var bareSlot: Int? = null
    walk@ for ((i, line) in lines.withIndex()) {
        val chordsAbove = above
        above = null
        when (kinds[i]) {
            LineKind.BLANK -> {}
            LineKind.META -> captionOf(line)?.count?.let { n -> current?.repeats = n }
            LineKind.LABEL -> {
                bareSlot = null
                val label = parseLabelLine(line) ?: continue@walk
                if (!open(label.name, sectionKindOf(label.name), label.repeats)) break@walk
                val chords = label.chords.map { it.transposed(capo) }
                for (c in chords) if (!addChord(c, null)) break@walk
                // `Verse: Am D` sits over the lyric below it the way a chord line would.
                if (chords.isNotEmpty()) above = chords
            }
            LineKind.REPEAT -> {
                val mark = repeatOf(line) ?: continue@walk
                val target = mark.kind?.let { k -> sections.firstOrNull { k in it.kinds && it.hasContent } ?: sections.firstOrNull { k in it.kinds } }
                if (target != null) {
                    bareSlot = null
                    if (!open(target.name, target.kind, mark.count, target.index)) break@walk
                } else if (mark.kind == null) {
                    current?.repeats = mark.count
                }
            }
            LineKind.CHORDS -> {
                bareSlot = null
                val chords = chordLineChords(line).orEmpty().map { it.transposed(capo) }
                for (c in chords) if (!addChord(c, null)) break@walk
                above = chords
            }
            LineKind.LYRIC -> {
                val (whole, inline) = splitLyric(line)
                val text = whole.take(MAX_SLOT_TEXT).trimEnd()
                if (cuePage && chordsAbove == null && inline.isEmpty() && !isQuotedCue(line)) continue@walk
                block = null
                bareSlot = null
                val chords = inline.map { it.transposed(capo) }
                for (c in chords) if (!addChord(c, null)) break@walk
                if (text.isEmpty()) continue@walk
                val s = section() ?: break@walk
                s.hasContent = true
                if (slots.size >= MAX_PHRASE_SLOTS) { slotsCapped = true; continue@walk }
                val names = (chordsAbove.orEmpty() + chords).map { it.name }
                if (names.isEmpty()) bareSlot = slots.size
                s.slots += slots.size
                slots += PhraseSlot(slots.size, s.index, text, countSyllables(text), names)
            }
            LineKind.TAB -> {
                val system = systems[i] ?: continue@walk
                val s = section() ?: break@walk
                s.hasContent = true
                if (system.isChordal()) {
                    block = null
                    s.chordalSystems += system
                    val events = chordEvents(system)
                    for ((c, shape) in events) if (!addChord(c, shape)) break@walk
                    bareSlot?.let { k -> slots[k] = slots[k].copy(chords = events.map { it.first.name }) }
                } else {
                    val b = block?.takeIf { it.section === s }
                        ?: MelodyBlock(s, s.slots.lastOrNull()).also { s.blocks += it; block = it }
                    b.systems += system
                }
                bareSlot = null
            }
        }
    }
    current?.let(::close)
    if (sectionsCapped) warnings += "only the first $MAX_SECTIONS sections are kept; the rest of the page was not read"
    if (slotsCapped) warnings += "only the first $MAX_PHRASE_SLOTS lyric lines are kept as phrase slots"

    val blocks = sections.flatMap { it.blocks }
    val timed = blocks.map { timeNotes(it.systems) }
    val noteMidis = timed.flatMap { (notes, _) -> notes.mapNotNull { it.midi } }
    if (occurrences.isEmpty() && noteMidis.isEmpty()) return SongReference(ok = false, message = NOTHING_MUSICAL, source = source, warnings = warnings)
    val key = guessKey(occurrences.map { it.first }, noteMidis, tonality, rootNote, scaleType)
        ?: return SongReference(ok = false, message = NOTHING_MUSICAL, source = source, warnings = warnings)

    val melodies = mutableListOf<SongMelody>()
    var melodiesCapped = false
    blocks@ for ((b, t) in blocks.zip(timed)) {
        val parts = toMelodies(t.first, t.second, key, b.section.name, b.section.index, b.phraseSlot, firstIndex = melodies.size)
        for (m in parts) {
            if (melodies.size >= MAX_MELODIES) { melodiesCapped = true; break@blocks }
            melodies += m
            b.section.melodies += m.index
        }
    }
    if (melodiesCapped) {
        melodies[melodies.lastIndex] = melodies.last().copy(truncated = true)
        warnings += "only the first $MAX_MELODIES melodies are kept"
    }

    val tallies = LinkedHashMap<String, ChordTally>()
    for ((c, shape) in occurrences) {
        val tally = tallies.getOrPut(c.name) { ChordTally(c) }
        tally.count++
        if (tally.shape == null) tally.shape = shape
    }
    val chords = tallies.values.map {
        SongChord(it.chord.name, rootNoteOf(it.chord.root), it.chord.quality, chordDegree(it.chord.root, key), it.count, it.shape)
    }
    val songSections = sections.map { s ->
        SongSection(
            index = s.index,
            name = s.name,
            kind = s.kind,
            repeats = s.repeats,
            sameAs = s.sameAs,
            chords = s.chords.map { it.name },
            degrees = s.chords.map { chordDegree(it.root, key) },
            phraseSlots = s.slots.toList(),
            melodies = s.melodies.toList(),
            pluck = pluckOf(s.chordalSystems),
        )
    }
    val message = listOf(
        plural(songSections.size, "section", "sections"),
        plural(chords.size, "chord", "chords"),
        plural(slots.size, "phrase slot", "phrase slots"),
        plural(melodies.size, "melody", "melodies"),
    ).joinToString(", ") + " in ${key.name}"
    return SongReference(true, message, source, key, chords, songSections, slots, melodies, warnings)
}

private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

// Renames with sharps; a chord as written keeps its spelling when there is no capo.
private fun ParsedChord.transposed(semitones: Int): ParsedChord {
    if (semitones == 0) return this
    val r = (root + semitones).mod(12)
    val b = bass?.let { (it + semitones).mod(12) }
    return ParsedChord(pitchClassName(r) + quality + (b?.let { "/" + pitchClassName(it) } ?: ""), r, quality, b)
}
