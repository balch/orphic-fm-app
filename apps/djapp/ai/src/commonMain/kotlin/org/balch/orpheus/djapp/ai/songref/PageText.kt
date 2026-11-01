package org.balch.orpheus.djapp.ai.songref

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The song text of a pasted page, with whatever the page said about the song. */
internal data class PageText(
    val text: String,
    val format: String,
    val title: String? = null,
    val artist: String? = null,
    val tonality: String? = null,
    val capo: Int = 0,
)

private val DATA_CONTENT = Regex("""data-content\s*=\s*"([^"]*)"""")
private val HTML_HINT = Regex("""</?(?:!doctype|html|head|body|pre|div|p|br|span|table|li)\b""", RegexOption.IGNORE_CASE)
private val LINE_BREAK_TAGS = setOf("br", "/p", "/div", "/pre", "/li")
private val UG_MARKUP = Regex("""\[/?(?:ch|tab)]""")
private val ENTITY = Regex("""&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});""")
private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "quot" to "\"", "lt" to "<", "gt" to ">", "apos" to "'", "nbsp" to " ",
    "rsquo" to "\u2019", "lsquo" to "\u2018", "ldquo" to "\u201C", "rdquo" to "\u201D",
    "hellip" to "\u2026", "ndash" to "\u2013", "mdash" to "\u2014",
)

// Unicode space and line separators that `\s` or `lines()` miss on JVM and iOS; all read as spaces.
private fun isOddSpace(c: Char): Boolean =
    c == '\u00A0' || c == '\u1680' || c in '\u2000'..'\u200A' || c == '\u202F' || c == '\u205F' || c == '\u3000' ||
        c == '\u2028' || c == '\u2029' || c == '\u0085'

/** Ultimate Guitar's js-store JSON when present, else any HTML stripped to text, else the paste as is. */
internal fun extractPageText(raw: String): PageText {
    val page = ultimateGuitar(raw)
        ?: if (HTML_HINT.containsMatchIn(raw)) PageText(htmlToText(raw), "html") else PageText(raw, "plain")
    val text = page.text.replace("\r\n", "\n").replace('\r', '\n').replace(UG_MARKUP, "").replace("\t", "    ")
        .let { s -> if (s.none(::isOddSpace)) s else buildString(s.length) { for (c in s) append(if (isOddSpace(c)) ' ' else c) } }
    return page.copy(text = text)
}

// Tries every data-content attribute in order; one that does not parse or lacks the content path is skipped.
private fun ultimateGuitar(raw: String): PageText? {
    if (!raw.contains("js-store")) return null
    for (match in DATA_CONTENT.findAll(raw)) {
        val json = try {
            Json.parseToJsonElement(htmlUnescape(match.groupValues[1])) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: continue
        val data = json.obj("store")?.obj("page")?.obj("data") ?: continue
        val view = data.obj("tab_view")
        val content = view?.obj("wiki_tab")?.text("content") ?: continue
        val tab = data.obj("tab")
        val capo = view.obj("meta")?.text("capo")?.trim()?.toDoubleOrNull()?.toInt()?.takeIf { it in 0..24 } ?: 0
        return PageText(
            text = content,
            format = "ultimate-guitar",
            title = tab?.text("song_name")?.takeIf { it.isNotBlank() },
            artist = tab?.text("artist_name")?.takeIf { it.isNotBlank() },
            tonality = tab?.text("tonality_name")?.takeIf { it.isNotBlank() },
            capo = capo,
        )
    }
    return null
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

// Hand-rolled scans rather than regexes, so unclosed tags and scripts stay linear.
private fun htmlToText(html: String): String = htmlUnescape(stripTags(dropScriptsAndStyles(html)))

private fun dropScriptsAndStyles(html: String): String {
    val out = StringBuilder(html.length)
    var i = 0
    var nextScript = html.indexOf("<script", 0, ignoreCase = true)
    var nextStyle = html.indexOf("<style", 0, ignoreCase = true)
    while (i < html.length) {
        if (nextScript in 0 until i) nextScript = html.indexOf("<script", i, ignoreCase = true)
        if (nextStyle in 0 until i) nextStyle = html.indexOf("<style", i, ignoreCase = true)
        val start = listOf(nextScript, nextStyle).filter { it >= 0 }.minOrNull() ?: break
        out.append(html, i, start)
        val close = html.indexOf(if (start == nextScript) "</script" else "</style", start, ignoreCase = true)
        val end = if (close < 0) -1 else html.indexOf('>', close)
        i = if (end < 0) html.length else end + 1
    }
    if (i < html.length) out.append(html, i, html.length)
    return out.toString()
}

private fun stripTags(html: String): String {
    val out = StringBuilder(html.length)
    var i = 0
    while (i < html.length) {
        val c = html[i]
        if (c != '<') { out.append(c); i++; continue }
        val end = html.indexOf('>', i + 1)
        if (end < 0) { out.append(html, i, html.length); break }
        val inner = html.substring(i + 1, end).trim()
        val name = if (inner.startsWith("/")) "/" + inner.drop(1).trimStart().takeWhile { it.isLetterOrDigit() } else inner.takeWhile { it.isLetterOrDigit() }
        if (name.lowercase() in LINE_BREAK_TAGS) out.append('\n')
        i = end + 1
    }
    return out.toString()
}

internal fun htmlUnescape(s: String): String {
    if ('&' !in s) return s
    return ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> codePointText(e.substring(2).toIntOrNull(16))
            e.startsWith("#") -> codePointText(e.substring(1).toIntOrNull())
            else -> NAMED_ENTITIES[e]
        } ?: m.value
    }
}

private fun codePointText(cp: Int?): String? = when {
    cp == null || cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF -> null
    cp < 0x10000 -> Char(cp).toString()
    else -> (cp - 0x10000).let { v -> charArrayOf(Char(0xD800 + (v shr 10)), Char(0xDC00 + (v and 0x3FF))).concatToString() }
}
