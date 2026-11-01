package org.balch.orpheus.djapp.ai.songref

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.balch.orpheus.djapp.ai.songref.TabParserFixtures.RIFF_BARS
import org.balch.orpheus.djapp.ai.songref.TabParserFixtures.RIFF_NO_BARS
import org.balch.orpheus.djapp.ai.songref.TabParserFixtures.SKANK

/** "Kettle Morning" by The Teapots: an invented song, as an Ultimate Guitar page and as a plain sheet. */
internal object SyntheticSong {
    val UG_CONTENT: String = listOf(
        "[Intro]",
        "[tab]" + SKANK.joinToString("\r\n") + "[/tab]",
        "",
        "[Verse 1]",
        "[tab][ch]Am[/ch]                [ch]D[/ch]",
        "\"Wake up slow and put the kettle on\"",
        "[ch]Am[/ch]              [ch]D[/ch]",
        "\"Steam is rising, morning almost gone\"[/tab]",
        "",
        "[Chorus]",
        "[ch]C[/ch]          [ch]G[/ch]",
        "Pour another cup for me",
        "[ch]Am[/ch]         [ch]D[/ch]",
        "Pour another cup, let it be",
        "(x2)",
        "",
        "[Riff]",
        "[tab]" + RIFF_BARS.joinToString("\r\n") + "[/tab]",
        "",
        "[Chorus]",
    ).joinToString("\r\n")

    val PLAIN: String = (
        listOf("Intro:") + SKANK + listOf(
            "",
            "VERSE 1",
            "Am                D",
            "Wake up slow and put the kettle on",
            "Am              D",
            "Steam is rising, morning almost gone",
            "",
            "Chorus",
            "C          G",
            "Pour another cup for me",
            "Am         D",
            "Pour another cup, let it be",
            "(x2)",
            "",
            "Riff",
        ) + RIFF_NO_BARS + listOf("", "Chorus")
    ).joinToString("\n")

    fun ugPage(
        content: String = UG_CONTENT,
        tonality: String? = "Am",
        capo: Int = 0,
        noise: Boolean = false,
        title: String = "Kettle Morning",
    ): String {
        // Extra fields of mixed types, including a decoy "content" key, before and after the real ones.
        fun JsonObjectBuilder.junk(tag: String) {
            if (!noise) return
            repeat(30) { k ->
                when (k % 3) {
                    0 -> put("${tag}_$k", JsonPrimitive("n\"o<i>s&e '$k'"))
                    1 -> put("${tag}_$k", k)
                    else -> putJsonObject("${tag}_$k") { put("content", "decoy $k") }
                }
            }
        }
        val body = if (noise) content + "\r\nTabbed by Ann & Bob's caf\u00E9" else content
        val store = buildJsonObject {
            junk("a")
            putJsonObject("store") {
                junk("a")
                putJsonObject("page") {
                    junk("a")
                    putJsonObject("data") {
                        junk("a")
                        putJsonObject("tab") {
                            junk("a")
                            put("song_name", title)
                            put("artist_name", "The Teapots")
                            put("tonality_name", tonality)
                            junk("z")
                        }
                        putJsonObject("tab_view") {
                            junk("a")
                            putJsonObject("meta") { junk("a"); put("capo", capo); junk("z") }
                            putJsonObject("wiki_tab") { junk("a"); put("content", body); junk("z") }
                            junk("z")
                        }
                        junk("z")
                    }
                    junk("z")
                }
                junk("z")
            }
            junk("z")
        }
        val decoy = buildJsonObject { putJsonObject("store") { putJsonObject("page") { put("title", "Other") } } }
        return buildString {
            append("<!DOCTYPE html>\n<html><head><title>Kettle Morning</title></head><body>\n")
            append("<script>window.tracking = { on: true };</script>\n")
            if (noise) append("<div class=\"js-ad\" data-content=\"").append(escape(decoy.toString())).append("\"></div>\n")
            append("<div class=\"js-store\" data-content=\"").append(escape(store.toString())).append("\"></div>\n")
            append("</body></html>\n")
        }
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#039;")
}
