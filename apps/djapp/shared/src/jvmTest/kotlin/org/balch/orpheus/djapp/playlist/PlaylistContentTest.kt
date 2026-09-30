package org.balch.orpheus.djapp.playlist

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.playback.PlaylistEdit
import org.balch.orpheus.features.pulsar.playback.PlaylistView
import org.balch.orpheus.features.pulsar.playback.VibeRotation
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistContentTest {
    private val order = listOf("Rust Belt", "Dog House", "Bell Tolls", "Techno Wobble", "Filter Funk")
    private val albums = mapOf(
        Album.STEALTH to listOf("Filter Funk"), Album.RIF to listOf("Bell Tolls", "Techno Wobble"),
        Album.ZERO_TO_ONE to listOf("Dog House"), Album.ANOMALIES to listOf("Rust Belt"),
    )
    // Techno Wobble set aside, Dog House playing.
    private val view = PlaylistView(rotation = VibeRotation(order, order - "Techno Wobble"), albums = albums)

    private class Sheet(view: PlaylistView, current: String) {
        val edits = mutableListOf<PlaylistEdit>()
        val played = mutableListOf<String>()
        var shuffles = 0
        val scene = ImageComposeScene(400, 900, Density(1f)) {
            OrpheusTheme {
                PlaylistContent(
                    view = view, current = current, phrase = "Outlook groovy",
                    onEdit = { edits += it }, onShuffle = { shuffles++ }, onPlay = { played += it },
                )
            }
        }.also { it.render() }

        private fun nodes(): List<SemanticsNode> {
            val out = mutableListOf<SemanticsNode>()
            fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
            walk(scene.semanticsOwners.first().rootSemanticsNode)
            return out
        }

        fun find(label: String): SemanticsNode? = nodes().firstOrNull { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }

        fun labelled(label: String): SemanticsNode = assertNotNull(find(label), "no node labelled \"$label\"")

        fun click(label: String) {
            assertNotNull(labelled(label).config.getOrNull(SemanticsActions.OnClick), "\"$label\" is not clickable").action?.invoke()
            scene.render()
        }

        fun close() = scene.close()
    }

    @Test
    fun showsNowUpNextAndSetAside() {
        val sheet = Sheet(view, "Dog House")
        try {
            // Section headers render in capitals.
            sheet.labelled("♪ Dog House")
            sheet.labelled("UP NEXT · 3")
            sheet.labelled("SET ASIDE · 1")
        } finally { sheet.close() }
    }

    @Test
    fun minusSetsAVibeAsideAndPlusBringsItBack() {
        val sheet = Sheet(view, "Dog House")
        try {
            sheet.click("Set aside Bell Tolls")
            sheet.click("Bring back Techno Wobble")
            assertEquals(
                listOf<PlaylistEdit>(PlaylistEdit.SetIncluded("Bell Tolls", false), PlaylistEdit.SetIncluded("Techno Wobble", true)),
                sheet.edits,
            )
        } finally { sheet.close() }
    }

    @Test
    fun theLastUpNextRowCannotBeSetAside() {
        val lone = view.copy(rotation = VibeRotation(order, listOf("Bell Tolls")))
        val sheet = Sheet(lone, "Dog House")
        try {
            assertTrue(sheet.labelled("Set aside Bell Tolls").config.contains(SemanticsProperties.Disabled))
        } finally { sheet.close() }
    }

    // Two vibes play, but NOW is one of them: Up next has a single row, so − stays off.
    @Test
    fun theLastUpNextRowCannotBeSetAsideWhileNowPlays() {
        val pair = view.copy(rotation = VibeRotation(order, listOf("Dog House", "Bell Tolls")))
        val sheet = Sheet(pair, "Dog House")
        try {
            sheet.labelled("UP NEXT · 1")
            assertTrue(sheet.labelled("Set aside Bell Tolls").config.contains(SemanticsProperties.Disabled))
        } finally { sheet.close() }
    }

    @Test
    fun noAlbumsNoChips() {
        val sheet = Sheet(view.copy(albums = emptyMap()), "Dog House")
        try {
            Album.entries.forEach { assertNull(sheet.find(it.title), "${it.title} has no vibes to queue") }
        } finally { sheet.close() }
    }

    // The listing comes in AlbumCatalog's order, which need not be the enum's.
    @Test
    fun chipsComeInTheListingsOrder() {
        val listed = listOf(Album.ANOMALIES, Album.STEALTH, Album.ZERO_TO_ONE, Album.RIF)
        val sheet = Sheet(view.copy(albums = listed.associateWith { albums.getValue(it) }), "Dog House")
        try {
            assertEquals(listed.map { it.title }, listed.map { it.title }.sortedBy { sheet.labelled(it).boundsInRoot.left })
        } finally { sheet.close() }
    }

    @Test
    fun anAlbumWithNoVibesHasNoChip() {
        val sheet = Sheet(view.copy(albums = albums - Album.ZERO_TO_ONE), "Dog House")
        try {
            sheet.labelled("RIF")
            assertNull(sheet.find(Album.ZERO_TO_ONE.title))
        } finally { sheet.close() }
    }

    @Test
    fun shuffleResetAndTapToPlay() {
        val sheet = Sheet(view, "Dog House")
        try {
            sheet.click("Shuffle")
            sheet.click("Reset order")
            sheet.click("Rust Belt")
            assertEquals(1, sheet.shuffles)
            assertEquals(listOf<PlaylistEdit>(PlaylistEdit.Reset), sheet.edits)
            assertEquals(listOf("Rust Belt"), sheet.played)
        } finally { sheet.close() }
    }
}
