package org.balch.orpheus.djapp.ai.songref

internal enum class LineKind { BLANK, LABEL, CHORDS, TAB, LYRIC, REPEAT, META }

private val IGNORE_CASE = RegexOption.IGNORE_CASE
private val WHITESPACE = Regex("""\s+""")
private val DIGITS = Regex("""\d+""")

// Regexes with overlapping whitespace only ever see lines capped at MAX_SHORT_LINE, so they cannot backtrack for long.
private const val MAX_SHORT_LINE = 60
private const val MAX_LABEL = 40
// Longer text, inline chords aside, is prose or a page dump run together, not a sung line.
private const val MAX_LYRIC_LINE = 160

// Longest first, so the alternation never stops at "chorus" inside "pre-chorus".
private const val SECTION_WORDS =
    "pre-chorus|pre chorus|prechorus|instrumental|interlude|refrain|chorus|bridge|middle|ending|intro|verse|outro|break|hook|solo|riff|coda"
private const val LABEL_MODIFIERS = "guitar|bass|piano|keys|lead|vocal|first|second|third|fourth|fifth|final|last|next"
private const val MARK = """(?:[x\u00D7]\s*\d{1,2}|\d{1,2}\s*[x\u00D7])"""
private val KEYWORD_LABEL = Regex(
    """^(?:(?:$LABEL_MODIFIERS)\s+){0,2}(?:$SECTION_WORDS)(?:(?:\s*[/&+]\s*|\s+and\s+|[\s-]+)(?:$SECTION_WORDS))*""" +
        """[\s\d:.]*(?:\(?\s*$MARK\s*\)?)?\s*:?\s*$""",
    IGNORE_CASE,
)
private val TRAILING_MARK = Regex("""\s*\(?\s*($MARK)\s*\)?\s*:?\s*$""", IGNORE_CASE)
private val MARK_TOKEN = Regex("""^[(\[]?(?:[x\u00D7]\d{1,2}|\d{1,2}[x\u00D7])[)\]]?$""", IGNORE_CASE)
private val META_PREFIX = Regex(
    "^(?:tuning|capo|key:|tempo|bpm|tabbed|tab by|transcribed|artist|title|album|e-?mail|chords used|difficulty|author|" +
        "let ring|palm mute|pm|thanks|thank you|submitted|corrections|guitar solo|solo\\s*:)(?![a-z])",
    IGNORE_CASE,
)
// Whole-line instructions only, so "End of the road..." and "Fade into..." stay lyrics.
private val INSTRUCTION = Regex(
    "^(?:(?:the )?end(?: of (?:the )?(?:song|tab))?|(?:repeat (?:and|to) )?fade(?: out| away)?(?: to end| slowly)?|" +
        "(?:until|till) (?:the )?(?:song|music|track) fades(?: out| away)?|strum(?:ming)?(?: ?:.*| pattern\\b.*)?)$",
)
// "Figure 2c: (Play 5 times)" or "Riff 2: (x4)"; the digit keeps "Figure it out" a lyric. A bare "Riff 2:" is a label.
private val CAPTION = Regex("""^(?:figure|fig\.?|riff|lick)\s*\d{1,2}[a-z]?\s*:?\s*(.*)$""", IGNORE_CASE)
private val CAPO_LINE = Regex("""^capo\b[\s:]*(?:on\s+)?(?:(?:the\s+)?fret\s+)?(\d{1,2})(?:st|nd|rd|th)?(?![0-9])""", IGNORE_CASE)
private val INLINE_CHORD = Regex("""\[([^\[\]\s]{1,12})]""")
private const val QUOTES = "\"\u201C\u201D'\u2018\u2019"
private val NON_WORD = Regex("""[^A-Za-z']+""")

private val KIND_WORDS = listOf(
    "pre-chorus" to SectionKind.PRE_CHORUS, "pre chorus" to SectionKind.PRE_CHORUS, "prechorus" to SectionKind.PRE_CHORUS,
    "chorus" to SectionKind.CHORUS, "refrain" to SectionKind.CHORUS, "hook" to SectionKind.CHORUS,
    "verse" to SectionKind.VERSE, "intro" to SectionKind.INTRO,
    "bridge" to SectionKind.BRIDGE, "middle" to SectionKind.BRIDGE,
    "solo" to SectionKind.SOLO, "riff" to SectionKind.RIFF,
    "interlude" to SectionKind.INTERLUDE, "instrumental" to SectionKind.INTERLUDE, "break" to SectionKind.INTERLUDE,
    "outro" to SectionKind.OUTRO, "coda" to SectionKind.OUTRO, "ending" to SectionKind.OUTRO,
)

/** A repeat instruction: [count] plays, of the named section [kind] or, when null, of the current one. */
internal data class RepeatMark(val count: Int, val kind: SectionKind?)

/** A section label, with any chords written on the same line (`Intro: Am G`, `[Chorus] C G`). */
internal data class LabelLine(val name: String, val repeats: Int, val chords: List<ParsedChord> = emptyList())

internal fun classifyLine(line: String): LineKind {
    val t = line.trim()
    if (t.isEmpty()) return LineKind.BLANK
    if (isTabLine(line)) return LineKind.TAB
    if (t.length >= 2 && t.first() == '*' && t.last() == '*') return LineKind.META
    if (parseLabelLine(t) != null) return LineKind.LABEL
    if (captionOf(t) != null) return LineKind.META
    if (repeatOf(t) != null) return LineKind.REPEAT
    if (isMetaText(t)) return LineKind.META
    if (chordLineChords(t) != null) return LineKind.CHORDS
    // Judged without inline ChordPro chords, and after the chord check so "F#7  C#7" stays chords.
    var chordChars = 0
    val text = INLINE_CHORD.replace(t) { m ->
        if (parseChordName(m.groupValues[1]) != null) { chordChars += m.value.length; " " } else m.value
    }
    if (t.length - chordChars > MAX_LYRIC_LINE) return LineKind.META
    if (text.count { it.isLetter() } * 2 < text.count { !it.isWhitespace() }) return LineKind.META
    return if (text.split(WHITESPACE).count { w -> w.any { it.isLetter() } } >= 2) LineKind.LYRIC else LineKind.META
}

private fun isMetaText(t: String): Boolean =
    META_PREFIX.containsMatchIn(t) || isInstruction(t) ||
        t.contains("http", ignoreCase = true) || t.contains("www.", ignoreCase = true) || '@' in t ||
        t.all { it in "-=_*#~ " }

private fun isInstruction(t: String): Boolean {
    if (t.length > MAX_SHORT_LINE) return false
    val n = unwrap(t.replace(WHITESPACE, " ").trim()).trim().trimEnd('.', '!').lowercase()
    return INSTRUCTION.matches(n)
}

/** A figure caption; [count] is the plays its `(Play N times)` asks for, null when it names none. */
internal data class Caption(val count: Int?)

internal fun captionOf(line: String): Caption? {
    val t = line.replace(WHITESPACE, " ").trim()
    if (t.length > MAX_SHORT_LINE) return null
    val rest = CAPTION.matchEntire(t)?.groupValues?.get(1)?.trim() ?: return null
    return Caption(repeatOf(rest)?.takeIf { it.kind == null && rest.startsWith("(") }?.count)
}

/** A lyric line written as a quoted cue, the way cue-style tab pages mark the words. */
internal fun isQuotedCue(line: String): Boolean {
    val t = INLINE_CHORD.replace(line) { m -> if (parseChordName(m.groupValues[1]) != null) "" else m.value }.trim()
    return t.length >= 2 && t.first() in QUOTES && t.last() in QUOTES
}

private fun unwrap(tok: String): String =
    if (tok.length > 2 && ((tok.first() == '[' && tok.last() == ']') || (tok.first() == '(' && tok.last() == ')'))) tok.substring(1, tok.length - 1) else tok

private fun isNoChord(tok: String): Boolean = unwrap(tok).trimEnd('.').replace(".", "").equals("NC", ignoreCase = true)

/** A chord token, allowing bar lines and ChordPro brackets or parentheses around it. */
internal fun chordToken(tok: String): ParsedChord? = parseChordName(unwrap(tok.trim('|')))

/** The chords of a chord line, or null when [line] is not one; a trailing note such as "(let ring)" is allowed. */
internal fun chordLineChords(line: String): List<ParsedChord>? {
    val tokens = line.trim().split(WHITESPACE)
    val chords = mutableListOf<ParsedChord>()
    for ((k, tok) in tokens.withIndex()) {
        if (tok.isEmpty() || tok.all { it in "|-/.:" } || isNoChord(tok) || MARK_TOKEN.matches(tok)) continue
        val chord = chordToken(tok)
        if (chord != null) { chords += chord; continue }
        if (tok.startsWith("(") && chords.isNotEmpty()) {
            val note = tokens.subList(k, tokens.size).joinToString(" ")
            if (note.length <= MAX_LABEL && note.lastIndexOf('(') == 0 && note.indexOf(')') == note.length - 1) break
        }
        return null
    }
    return chords.takeIf { it.isNotEmpty() }
}

/** (name, repeats) for `[Name]` whose content is not chords, or a short line naming a section. */
internal fun parseLabel(line: String): Pair<String, Int>? = parseLabelLine(line)?.let { it.name to it.repeats }

internal fun parseLabelLine(line: String): LabelLine? {
    val t = line.trim()
    if (t.startsWith("[")) {
        val close = t.indexOf(']')
        if (close < 2 || close > MAX_LABEL + 1) return null
        val inner = t.substring(1, close).trim()
        if ('[' in inner || inner.split(WHITESPACE).all { chordToken(it) != null || isNoChord(it) }) return null
        return withChords(inner, t.substring(close + 1))
    }
    val colon = t.indexOf(':')
    if (colon in 1..MAX_LABEL && colon < t.length - 1 && KEYWORD_LABEL.matches(t.substring(0, colon))) {
        withChords(t.substring(0, colon), t.substring(colon + 1))?.let { return it }
    }
    if (t.length > MAX_LABEL || !KEYWORD_LABEL.matches(t)) return null
    return nameAndRepeats(t)?.let { (name, n) -> LabelLine(name, n) }
}

// Text after a label may be chords, a repeat mark or a parenthesised note; anything else means no label.
private fun withChords(label: String, rest: String): LabelLine? {
    val (name, n) = nameAndRepeats(label) ?: return null
    val r = rest.trim()
    if (r.isEmpty()) return LabelLine(name, n)
    repeatOf(r)?.takeIf { it.kind == null }?.let { return LabelLine(name, it.count) }
    if (r.length <= MAX_LABEL && r.lastIndexOf('(') == 0 && r.indexOf(')') == r.length - 1) return LabelLine(name, n)
    return chordLineChords(r)?.let { LabelLine(name, n, it) }
}

private fun nameAndRepeats(s: String): Pair<String, Int>? {
    val m = TRAILING_MARK.find(s)
    val count = m?.let { DIGITS.find(it.groupValues[1])?.value?.toIntOrNull() } ?: 1
    val name = (if (m != null) s.substring(0, m.range.first) else s).trim().trimEnd(':').trim()
    return if (name.isEmpty()) null else name to count.coerceIn(1, 99)
}

/** `(x2)`, `2x`, `4 times`, `repeat x3`, `play 3 times` or `Repeat chorus x2`; null when the line is not one. */
internal fun repeatOf(line: String): RepeatMark? {
    val t = line.replace(WHITESPACE, " ").trim()
    if (t.isEmpty() || t.length > MAX_SHORT_LINE) return null
    val words = unwrap(t).split(' ').map { it.trim('(', ')', '[', ']', ':', '.', ',', '!').lowercase() }.filter { it.isNotEmpty() }
    val (count, used) = trailingCount(words) ?: (null to 0)
    val head = words.subList(0, words.size - used)
    if (head.isEmpty()) return count?.let { RepeatMark(it, null) }
    val lead = head.first()
    if (lead != "repeat" && !(lead == "play" && count != null)) return null
    val rest = head.drop(1).joinToString(" ")
    if (rest.isEmpty()) return RepeatMark(count ?: 2, null)
    // "Repeat after me" is a lyric; only a named section makes this a repeat.
    val kind = sectionKindOf(rest).takeIf { it != SectionKind.OTHER && rest.length <= 30 } ?: return null
    return RepeatMark(count ?: 1, kind)
}

// (count, words used) for a trailing x2, 2x, x 2, 2 x, 2 times or twice.
private fun trailingCount(words: List<String>): Pair<Int, Int>? {
    val last = words.lastOrNull() ?: return null
    val prev = words.getOrNull(words.size - 2)
    fun num(s: String) = DIGITS.find(s)?.value?.toIntOrNull()?.coerceIn(1, 99)
    return when {
        last == "twice" -> 2 to 1
        last == "times" && prev != null && prev.all { it.isDigit() } && prev.length <= 2 -> num(prev)!! to 2
        MARK_TOKEN.matches(last) -> num(last)!! to 1
        (last == "x" || last == "\u00D7") && prev != null && prev.all { it.isDigit() } && prev.length <= 2 -> num(prev)!! to 2
        last.all { it.isDigit() } && last.length <= 2 && (prev == "x" || prev == "\u00D7") -> num(last)!! to 2
        else -> null
    }
}

/** The capo fret named by a `Capo 2` or `Capo on 2nd fret` line, else null. */
internal fun capoOf(line: String): Int? {
    val t = line.replace(WHITESPACE, " ").trim()
    if (t.length > MAX_SHORT_LINE || !t.startsWith("capo", ignoreCase = true)) return null
    return CAPO_LINE.find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..12 }
}

/** The kind named by the earliest section word in [name]. */
internal fun sectionKindOf(name: String): SectionKind = sectionKindsOf(name).firstOrNull() ?: SectionKind.OTHER

/** Every kind [name] names, in order (`Intro/Verse` names INTRO then VERSE); "pre-chorus" does not also name CHORUS. */
internal fun sectionKindsOf(name: String): List<SectionKind> {
    val n = name.lowercase()
    val hits = KIND_WORDS.flatMap { (word, kind) ->
        generateSequence(n.indexOf(word).takeIf { it >= 0 }) { at -> n.indexOf(word, at + 1).takeIf { it >= 0 } }
            .map { Triple(it, it + word.length, kind) }.toList()
    }.sortedWith(compareBy<Triple<Int, Int, SectionKind>> { it.first }.thenByDescending { it.second })
    val kinds = mutableListOf<SectionKind>()
    var end = -1
    for ((start, stop, kind) in hits) {
        if (start < end) continue
        end = stop
        if (kind !in kinds) kinds += kind
    }
    return kinds
}

/** A lyric line's text without ChordPro `[Am]` chords or surrounding quotes, and those chords. */
internal fun splitLyric(line: String): Pair<String, List<ParsedChord>> {
    val chords = mutableListOf<ParsedChord>()
    val stripped = INLINE_CHORD.replace(line) { m ->
        parseChordName(m.groupValues[1])?.let { chords += it; "" } ?: m.value
    }
    var text = stripped.replace(WHITESPACE, " ").trim()
    while (text.length >= 2 && text.first() in QUOTES && text.last() in QUOTES) text = text.substring(1, text.length - 1).trim()
    return text to chords
}

internal fun countSyllables(text: String): Int =
    text.split(NON_WORD).sumOf { word -> wordSyllables(word.lowercase().filter { it in 'a'..'z' }) }

// Vowel groups, less a silent final e or -es/-ed; never under one for a real word.
private fun wordSyllables(w: String): Int {
    if (w.isEmpty()) return 0
    var groups = 0
    var prevVowel = false
    w.forEachIndexed { i, c ->
        val vowel = c in "aeiou" || (c == 'y' && i > 0)
        if (vowel && !prevVowel) groups++
        prevVowel = vowel
    }
    val n = w.length
    fun vowelAt(i: Int) = i >= 0 && w[i] in "aeiouy"
    val silent = when {
        n < 3 -> false
        w.endsWith("le") -> vowelAt(n - 3)
        w.endsWith("e") -> !vowelAt(n - 2)
        w.endsWith("es") -> !vowelAt(n - 3) && w[n - 3] !in "sxzcg" && !w.endsWith("ches") && !w.endsWith("shes")
        w.endsWith("ed") -> !vowelAt(n - 3) && w[n - 3] !in "td"
        else -> false
    }
    if (silent && groups > 1) groups--
    return groups.coerceAtLeast(1)
}
