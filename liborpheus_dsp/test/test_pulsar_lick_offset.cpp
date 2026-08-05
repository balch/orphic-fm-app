// Per-track lick scale-degree offset (parallel harmony).
//
// A track with lick_degree_offset = N renders the shared vibe lick with every
// sounding step's scale degree shifted by N before quantization — parallel
// diatonic harmony against the lead (e.g. fourths below for a twin track),
// reusing the existing octave fold in generate_lick_pattern. Rests are
// unaffected: the offset applies to sounding notes only.
//
// Path under test:
//   1. Kotlin pushes "track_N_lick_degree_offset" through orpheus_engine_set_port.
//   2. Routing stores it in engine->pulsar_track_lick_degree_offset[N].
//   3. load_vibe() reads the atomic and passes it into the lick render, so the
//      offset track's steps come out diatonically shifted vs the lead track.
#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include <cstdio>
#include <cstring>

static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";

// Write a lick straight into the engine atomics (the routing decode has its own
// marshalling suite); lick_length last per the release-fence contract.
static void write_lick_atomics(OrpheusEngine* engine,
                               const int8_t* degrees, int count) {
    for (int i = 0; i < count; i++) {
        engine->pulsar_lick[i].scale_degree = degrees[i];
        engine->pulsar_lick[i].duration = 1.0f;   // quarter note = 4 sequencer slots
        engine->pulsar_lick[i].velocity = 0.8f;
        engine->pulsar_lick[i].glide_rate = -1.0f;
    }
    engine->pulsar_lick_loop_length.store(0, std::memory_order_relaxed);
    engine->pulsar_lick_length.store(count, std::memory_order_release);
}

static OrpheusEngine* make_offset_engine() {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);   // root D(2), scale 0 = minor [0,2,3,5,7,8,10]
    engine->pulsar_seed.store(424242, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);  // deterministic render
    // Tracks 4, 5, 7 are MELODIC in the baseline fixture; give them all the FILL lick.
    engine->pulsar_track_lick_mode[4].store(2, std::memory_order_relaxed);  // FILL, offset 0 (lead)
    engine->pulsar_track_lick_mode[5].store(2, std::memory_order_relaxed);  // FILL, offset +2
    engine->pulsar_track_lick_mode[7].store(2, std::memory_order_relaxed);  // FILL, offset -3
    return engine;
}

// ── Test 1: positive/negative offsets render parallel diatonic harmony ─────
static bool test_lick_degree_offset_parallel_harmony() {
    printf("\n=== Test: track lick_degree_offset renders parallel diatonic harmony ===\n");

    OrpheusEngine* engine = make_offset_engine();
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    // Lick degrees 0,1,2,4 (all sounding, quarter notes -> 4 slots each).
    const int8_t degrees[] = {0, 1, 2, 4};
    write_lick_atomics(engine, degrees, 4);

    // Offsets pushed through the routing layer, like Kotlin will.
    orpheus_engine_set_port(engine, PULSAR_URI, "track_5_lick_degree_offset", 2.0f);
    orpheus_engine_set_port(engine, PULSAR_URI, "track_7_lick_degree_offset", -3.0f);

    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, 64, 48000.0f);

    PulsarState* ps = engine->pulsar_state;
    if (!ps) {
        printf("  FAIL: PulsarState null\n");
        orpheus_engine_destroy(engine);
        return false;
    }

    PulsarTrackState& lead = ps->tracks[4];
    PulsarTrackState& up2  = ps->tracks[5];
    PulsarTrackState& dn3  = ps->tracks[7];

    bool ok = lead.step_count > 0
           && lead.step_count == up2.step_count
           && lead.step_count == dn3.step_count;
    printf("  step_count lead=%d up2=%d dn3=%d -- %s\n",
           lead.step_count, up2.step_count, dn3.step_count, ok ? "OK" : "FAIL");

    // Expected semitone deltas per lick note, minor scale [0,2,3,5,7,8,10]:
    //   +2 degrees: deg0->2:+3, deg1->3:+3, deg2->4:+4, deg4->6:+3
    //   -3 degrees: deg0->-3 folds to deg4-12:-5, deg1->-2 folds:-6,
    //               deg2->-1 folds:-5, deg4->1:-5
    const int expect_up2[] = {3, 3, 4, 3};
    const int expect_dn3[] = {-5, -6, -5, -5};

    for (int i = 0; ok && i < lead.step_count; i++) {
        int note_idx = (i / 4) % 4;  // 4 slots per lick note, 4-note lick
        bool gates_match = (lead.steps[i].gate == up2.steps[i].gate)
                        && (lead.steps[i].gate == dn3.steps[i].gate);
        if (!gates_match) {
            printf("  FAIL step %d: gate mismatch lead=%d up2=%d dn3=%d\n", i,
                   lead.steps[i].gate, up2.steps[i].gate, dn3.steps[i].gate);
            ok = false;
            break;
        }
        if (!lead.steps[i].gate) continue;
        int d_up = static_cast<int>(up2.steps[i].note) - static_cast<int>(lead.steps[i].note);
        int d_dn = static_cast<int>(dn3.steps[i].note) - static_cast<int>(lead.steps[i].note);
        if (d_up != expect_up2[note_idx] || d_dn != expect_dn3[note_idx]) {
            printf("  FAIL step %d (lick note %d): lead=%d up2=%d (delta %d, want %d) "
                   "dn3=%d (delta %d, want %d)\n",
                   i, note_idx, lead.steps[i].note,
                   up2.steps[i].note, d_up, expect_up2[note_idx],
                   dn3.steps[i].note, d_dn, expect_dn3[note_idx]);
            ok = false;
        }
        // Harmony must not alter velocity — only pitch.
        if (ok && up2.steps[i].velocity != lead.steps[i].velocity) {
            printf("  FAIL step %d: velocity changed by offset\n", i);
            ok = false;
        }
    }
    printf("  parallel harmony deltas (+2 deg, -3 deg) -- %s\n", ok ? "OK" : "FAIL");
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── Test 2: rests stay rests under a degree offset ──────────────────────────
static bool test_lick_degree_offset_preserves_rests() {
    printf("\n=== Test: lick_degree_offset leaves rest steps silent ===\n");

    OrpheusEngine* engine = make_offset_engine();
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    // Second lick note is a rest (-1). Offset +2 must NOT turn it into degree 1.
    const int8_t degrees[] = {0, -1, 2, 4};
    write_lick_atomics(engine, degrees, 4);
    orpheus_engine_set_port(engine, PULSAR_URI, "track_5_lick_degree_offset", 2.0f);

    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, 64, 48000.0f);

    PulsarState* ps = engine->pulsar_state;
    bool ok = (ps != nullptr);
    if (ok) {
        PulsarTrackState& lead = ps->tracks[4];
        PulsarTrackState& up2  = ps->tracks[5];
        ok = lead.step_count > 0 && lead.step_count == up2.step_count;
        for (int i = 0; ok && i < lead.step_count; i++) {
            if (lead.steps[i].gate != up2.steps[i].gate) {
                printf("  FAIL step %d: rest/gate divergence lead=%d up2=%d\n",
                       i, lead.steps[i].gate, up2.steps[i].gate);
                ok = false;
            }
            // Slots 4..7 hold the rest note; both tracks must be silent there.
            if (ok && (i / 4) % 4 == 1 && lead.steps[i].gate) {
                printf("  FAIL step %d: rest slot is gated\n", i);
                ok = false;
            }
        }
    } else {
        printf("  FAIL: PulsarState null\n");
    }
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_lick_offset_tests() {
    printf("\n========== PULSAR LICK DEGREE OFFSET TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_lick_degree_offset_parallel_harmony());
    tally(test_lick_degree_offset_preserves_rests());
    printf("\nPulsar lick degree offset tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
