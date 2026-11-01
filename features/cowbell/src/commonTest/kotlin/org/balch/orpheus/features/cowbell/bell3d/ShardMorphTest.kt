package org.balch.orpheus.features.cowbell.bell3d

import org.balch.orpheus.features.cowbell.BellMorphStyle
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShardMorphTest {

    private fun corner(shard: Shard, k: Int, m: Float): Vec3 {
        val out = FloatArray(3)
        shardCorner(shard, k, m, out)
        return Vec3(out[0], out[1], out[2])
    }

    private fun assertVecEquals(expected: Vec3, actual: Vec3, eps: Float = 1e-5f, what: String = "") {
        assertEquals(expected.x, actual.x, eps, "$what x")
        assertEquals(expected.y, actual.y, eps, "$what y")
        assertEquals(expected.z, actual.z, eps, "$what z")
    }

    private fun length(v: Vec3): Float = sqrt(v dot v)

    /** A planar quad's area, as two triangles. */
    private fun area(q: List<Vec3>): Float =
        (length((q[1] - q[0]) cross (q[2] - q[0])) + length((q[2] - q[0]) cross (q[3] - q[0]))) / 2f

    @Test
    fun `every face splits into a 4x4 grid`() {
        assertEquals(FACES.size * 16, SHARD_COUNT)
        assertEquals(SHARD_COUNT, SHARD_TABLE.size)
    }

    @Test
    fun `at rest the shards tile the faces exactly`() {
        FACES.forEachIndexed { fi, face ->
            val shards = SHARD_TABLE.subList(fi * 16, fi * 16 + 16)
            val placed = shards.map { s -> List(4) { corner(s, it, 0f) } }
            shards.zip(placed).forEach { (s, q) -> q.indices.forEach { assertVecEquals(s.corners[it], q[it], what = "face $fi") } }
            assertEquals(area(face), placed.sumOf { area(it).toDouble() }.toFloat(), 1e-4f, "face $fi area")
            // The outer corners of the grid are the face's own corners.
            assertVecEquals(face[0], placed[0][0], what = "face $fi first corner")
            assertVecEquals(face[2], placed[15][2], what = "face $fi last corner")
            // Every corner lies in the face's plane.
            val n = faceNormal(face)
            placed.flatten().forEach { assertEquals(0f, (it - face[0]) dot n, 1e-5f, "face $fi off plane") }
        }
    }

    @Test
    fun `at full burst a shard is pushed out along its normal at half size`() {
        SHARD_TABLE.forEach { s ->
            val q = List(4) { corner(s, it, 1f) }
            val centre = centroid(q)
            val push = 0.35f + 0.6f * s.seed
            assertVecEquals(s.centre + s.normal * push, centre, 1e-4f, "centre")
            q.indices.forEach { k ->
                assertEquals(length(s.corners[k] - s.centre) * 0.5f, length(q[k] - centre), 1e-4f, "corner $k distance")
                // The spin is about the normal, so the shard stays in its face's plane.
                assertEquals(0f, (q[k] - centre) dot s.normal, 1e-4f, "corner $k off plane")
            }
        }
    }

    @Test
    fun `the burst is zero at both ends and peaks half way`() {
        assertEquals(0f, shardAmount(0f))
        assertTrue(shardAmount(1f) < 1e-6f, "the burst ends at ${shardAmount(1f)}")
        assertEquals(1f, shardAmount(0.5f), 1e-6f)
        assertTrue((1..99).all { shardAmount(it / 100f) > 0f })
        assertEquals(1f, shardZoom(0f))
        assertEquals(1f, shardMeshAlpha(shardAmount(1f)), 1e-5f)
        assertEquals(0f, shardMeshAlpha(1f))
    }

    @Test
    fun `the float bend matches bend`() {
        val v = FloatArray(3)
        for (pull in listOf(-1.4f, -1f, -0.3f, 0f, 0.6f, 1f)) {
            for (p in SHARD_TABLE.flatMap { it.corners } + HANDLE) {
                v[0] = p.x; v[1] = p.y; v[2] = p.z
                bendInPlace(v, pull)
                assertVecEquals(bend(p, pull), Vec3(v[0], v[1], v[2]), what = "pull $pull")
            }
        }
    }

    @Test
    fun `each style has its own length and an unsupported one turns plainly`() {
        assertEquals(SHARDS_SECONDS, morphSeconds(BellMorphStyle.SHARDS, liquidSupported = false, shardsSupported = true))
        assertEquals(LIQUID_SECONDS, morphSeconds(BellMorphStyle.LIQUID, liquidSupported = true, shardsSupported = true))
        assertEquals(PLAIN_TURN_SECONDS, morphSeconds(BellMorphStyle.SHARDS, liquidSupported = true, shardsSupported = false))
        assertEquals(PLAIN_TURN_SECONDS, morphSeconds(BellMorphStyle.LIQUID, liquidSupported = false, shardsSupported = true))
    }

    @Test
    fun `seeds are fixed and spread`() {
        val seeds = SHARD_TABLE.map { it.seed }
        assertTrue(seeds.all { it in 0f..1f })
        assertTrue(seeds.max() - seeds.min() > 0.8f, "seeds bunch up: ${seeds.min()}..${seeds.max()}")
        assertTrue(abs(seeds.average() - 0.5) < 0.15, "seed mean ${seeds.average()}")
    }
}
