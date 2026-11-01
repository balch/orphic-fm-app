package org.balch.orpheus.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VibeSourceTraceTest {
    private val statuses = mapOf("FIRE_SKY" to "LIVE", "WIP_ONE" to "WIP", "SHELVED" to "SHELF")

    private fun vibe(vararg names: String) =
        names.joinToString("\n") { "    override val name: VibeName = VibeNames.$it" }

    @Test
    fun liveOnlyFileIsKept() {
        val r = VibeSourceTrace.removable(mapOf("A.kt" to vibe("FIRE_SKY")), statuses)
        assertTrue(r.removable.isEmpty())
        assertEquals(1, r.vibeFiles)
    }

    @Test
    fun allNonLiveFileIsRemovable() {
        val r = VibeSourceTrace.removable(mapOf("A.kt" to vibe("WIP_ONE", "SHELVED")), statuses)
        assertEquals(setOf("A.kt"), r.removable)
        assertEquals(2, r.names)
    }

    @Test
    fun mixedFileIsReported() {
        val r = VibeSourceTrace.removable(mapOf("A.kt" to vibe("FIRE_SKY", "WIP_ONE")), statuses)
        assertTrue(r.removable.isEmpty())
        assertEquals(setOf("WIP_ONE"), r.mixed["A.kt"])
    }

    @Test
    fun exclusiveFileNeedsTheSwitchOnEveryBuild() {
        val files = mapOf("E.kt" to vibe("SPECIAL"), "W.kt" to vibe("WIP_ONE"))
        val withExclusive = statuses + ("SPECIAL" to "EXCLUSIVE")
        fun cut(liveOnly: Boolean, exclusive: Boolean) =
            VibeSourceTrace.removable(files, withExclusive, VibeSourceTrace.Cut(liveOnly, exclusive)).removable
        assertEquals(setOf("E.kt"), cut(liveOnly = false, exclusive = false))
        assertEquals(emptySet(), cut(liveOnly = false, exclusive = true))
        assertEquals(setOf("E.kt", "W.kt"), cut(liveOnly = true, exclusive = false))
        assertEquals(setOf("W.kt"), cut(liveOnly = true, exclusive = true))
    }

    @Test
    fun commentMentionIsNotAReference() {
        assertTrue(VibeSourceTrace.referencesTo(setOf("FooVibe"), "// see FooVibe\n/* FooVibe */").isEmpty())
    }

    @Test
    fun codeMentionIsAReference() {
        assertEquals(setOf("FooVibe"), VibeSourceTrace.referencesTo(setOf("FooVibe", "Bar"), "val x = FooVibe()\nval FooVibes = 1"))
    }

    @Test
    fun uncatalogedNameCountsAsNonLive() {
        val r = VibeSourceTrace.removable(mapOf("A.kt" to vibe("MYSTERY")), statuses)
        assertEquals(setOf("A.kt"), r.removable)
    }

    @Test
    fun nameWithTypeAnnotationIsFound() {
        assertEquals(setOf("X"), VibeSourceTrace.vibeNamesIn("override val name: VibeName = VibeNames.X"))
        assertEquals(setOf("Y"), VibeSourceTrace.vibeNamesIn("override val name = VibeNames.Y"))
    }

    @Test
    fun catalogStatusesParsesEntries() {
        val text = """
            // VibeNames.OLD to CatalogEntry(VibeStatus.LIVE)
            VibeNames.DOG_HOUSE to CatalogEntry(VibeStatus.LIVE, tags = listOf("a")),
            VibeNames.X to CatalogEntry(
                VibeStatus.WIP,
            ),
        """.trimIndent()
        assertEquals(mapOf("DOG_HOUSE" to "LIVE", "X" to "WIP"), VibeSourceTrace.catalogStatuses(text))
    }

    @Test
    fun providerContributionsCountsLiveAnnotations() {
        val src = """
            @Inject
            @ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
            class A : VibeProvider
            // @ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
            /* @ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>()) */
            @ContributesIntoSet(
                FeatureScope::class,
                binding = binding<VibeProvider>(),
            )
            class B : VibeProvider
            @ContributesIntoSet(HeaderPanelScope::class, binding = binding<FeaturePanel>())
            class C : FeaturePanel
        """.trimIndent()
        assertEquals(2, VibeSourceTrace.providerContributions(src))
    }

    @Test
    fun catalogEntryCallsSkipsDeclarationAndComments() {
        val text = """
            data class CatalogEntry(val status: VibeStatus)
            // VibeNames.OLD to CatalogEntry(VibeStatus.LIVE)
            VibeNames.A to CatalogEntry(VibeStatus.LIVE),
            VibeNames.B to CatalogEntry(status = VibeStatus.WIP),
        """.trimIndent()
        assertEquals(2, VibeSourceTrace.catalogEntryCalls(text))
        assertEquals(1, VibeSourceTrace.catalogStatuses(text).size)
    }

    @Test
    fun topLevelDeclarationsFindsKinds() {
        val src = """
            package a
            @Inject
            internal class FooVibe : Vibe
            object bar
            private interface Baz
            private val sectionList = 1
            data class Qux(val a: Int)
            internal val lowerThing = 1
            fun helper() {}
            fun Foo.ext() {}
            class Outer {
                class Nested
                val member = 1
            }
        """.trimIndent()
        val d = VibeSourceTrace.topLevelDeclarations(src)
        assertTrue(d.containsAll(setOf("FooVibe", "bar", "Qux", "lowerThing", "helper", "ext", "Outer")), d.toString())
        assertTrue("Baz" !in d && "sectionList" !in d && "Nested" !in d && "member" !in d)
    }
}
