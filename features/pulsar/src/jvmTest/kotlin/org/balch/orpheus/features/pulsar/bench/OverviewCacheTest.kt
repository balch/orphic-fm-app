package org.balch.orpheus.features.pulsar.bench

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class OverviewCacheTest {
    private val json = """{"seconds": 240, "sections": [{"index": 0, "name": "libation", "start_s": 0.0},
        {"index": 1, "name": "sirens", "start_s": 31.5}], "env": [[-0.1, 0.2], [-0.3, 0.4]]}"""

    private fun repo(): File = kotlin.io.path.createTempDirectory("overview").toFile()

    @Test
    fun parsesAndCaches() = runTest {
        val root = repo()
        val dir = File(root, "build/mix-bench/overview").also { it.mkdirs() }
        File(dir, "orphic_lore-abc123.overview.json").writeText(json)
        val cache = OverviewCache(root) { error("must not launch a process") }
        val o = assertNotNull(cache.get("Orphic Lore", "abc123"))
        assertEquals(240, o.seconds)
        assertEquals(listOf("libation", "sirens"), o.sections.map { it.name })
        assertEquals(31.5f, o.sections[1].startS)
        assertEquals(2, o.env.size)
        assertEquals(0.4f, o.env[1][1])
    }

    @Test
    fun missRunsScriptThenCachesUnderHash() = runTest {
        val root = repo()
        var calls = 0
        val cache = OverviewCache(root) { cmd ->
            calls++
            assertEquals("scripts/mix/measure.sh", cmd.last { it.endsWith("measure.sh") })
            File(root, "build/mix-bench/overview/orphic_lore.overview.json").also { it.parentFile.mkdirs() }.writeText(json)
            0
        }
        assertNotNull(cache.get("Orphic Lore", "h1"))
        assertNotNull(cache.get("Orphic Lore", "h1"))
        assertEquals(1, calls)
        assertEquals(true, File(root, "build/mix-bench/overview/orphic_lore-h1.overview.json").exists())
    }

    @Test
    fun failureReturnsNull() = runTest {
        assertNull(OverviewCache(repo()) { 1 }.get("Orphic Lore", "h"))
        assertNull(OverviewCache(repo()) { error("boom") }.get("Orphic Lore", "h"))
    }

    @Test
    fun corruptCachedFileIsDeletedAndRerendered() = runTest {
        val root = repo()
        val dir = File(root, "build/mix-bench/overview").also { it.mkdirs() }
        File(dir, "orphic_lore-h.overview.json").writeText("{half")
        var calls = 0
        val cache = OverviewCache(root) {
            calls++
            File(dir, "orphic_lore.overview.json").writeText(json)
            0
        }
        assertNotNull(cache.get("Orphic Lore", "h"))
        assertEquals(1, calls)
    }

    @Test
    fun concurrentGetsRenderOnce() = runTest {
        val root = repo()
        var calls = 0
        val cache = OverviewCache(root) {
            calls++
            Thread.sleep(100)
            File(root, "build/mix-bench/overview/orphic_lore.overview.json").writeText(json)
            0
        }
        val all = List(4) { async { cache.get("Orphic Lore", "h") } }.awaitAll()
        assertEquals(1, calls)
        assertEquals(4, all.count { it != null })
    }

    @Test
    fun commandClearsInheritedKnobs() = runTest {
        val root = repo()
        var cmd = emptyList<String>()
        OverviewCache(root) { cmd = it; 1 }.get("Orphic Lore", "h")
        assertEquals(true, cmd.containsAll(listOf("-u", "VIBE_TAG", "VIBE_SET", "VIBE_TRIM", "VIBE_OVERVIEW=1")))
        assertEquals("Orphic Lore", cmd.last())
    }
}
