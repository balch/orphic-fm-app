package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LickRotationTest {
    private val l1 = Lick(listOf(LickStep(0, 0.5f)), loopLength = 8)
    private val l2 = Lick(listOf(LickStep(2, 0.5f)), loopLength = 8)

    @Test
    fun pool_must_not_be_empty() {
        assertFailsWith<IllegalArgumentException> { LickRotation(pool = emptyList()) }
    }

    @Test
    fun pool_within_max_pool() {
        // pool alone must fit MAX_LICK_POOL = 8; 9 members overflow. (The LickAnomaly slot that
        // also shares the bank is validated together in Vibe.init, not here.)
        assertFailsWith<IllegalArgumentException> {
            LickRotation(pool = List(9) { l1 })
        }
    }

    @Test
    fun lick_rotation_serializes_round_trip() {
        val r = LickRotation(pool = listOf(l1, l2))
        val json = Json.encodeToString(LickRotation.serializer(), r)
        val back = Json.decodeFromString(LickRotation.serializer(), json)
        assertEquals(r, back)
    }

    @Test
    fun `pool accepts eight members`() {
        val step = LickStep(scaleDegree = 0, duration = 0.5f)
        val lick = Lick(steps = listOf(step))
        val rotation = LickRotation(pool = List(8) { lick })
        assertEquals(8, rotation.pool.size)
    }

    @Test
    fun `pool rejects nine members`() {
        val step = LickStep(scaleDegree = 0, duration = 0.5f)
        val lick = Lick(steps = listOf(step))
        assertFailsWith<IllegalArgumentException> {
            LickRotation(pool = List(9) { lick })
        }
    }
}
