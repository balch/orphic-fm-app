#include "test_harness.h"
#include "score_voice_alloc.h"
#include <cstdio>

// Fills the pool with gated notes 0..count-1 and returns it.
static ScoreVoicePool full_pool(int count) {
    ScoreVoicePool pool{};
    pool.count = count;
    score_voice_pool_reset(pool);
    pool.count = count;
    for (int i = 0; i < count; i++) {
        score_voice_start(pool, /*note_id=*/i, /*part=*/0, /*pitch=*/60 + i, /*velocity=*/100);
    }
    return pool;
}

static bool test_free_voices_are_taken_before_anything_is_stolen() {
    printf("\n=== Test: a free voice is preferred, and nothing is counted as a steal ===\n");
    ScoreVoicePool pool{};
    score_voice_pool_reset(pool);
    bool ok = true;
    for (int i = 0; i < kMaxScoreVoices; i++) {
        if (score_voice_start(pool, i, 0, 60, 100) < 0) ok = false;
    }
    ok = ok && pool.steals_from_release == 0 && pool.steals_from_held == 0;
    ok = ok && score_voices_sounding(pool) == kMaxScoreVoices;
    printf("  sounding=%d steals=%u/%u -- %s\n", score_voices_sounding(pool),
           pool.steals_from_release, pool.steals_from_held, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_a_releasing_voice_is_stolen_before_a_held_one() {
    printf("\n=== Test: a releasing voice dies before any gated voice ===\n");
    ScoreVoicePool pool = full_pool(4);
    // Release voice 2 only. It is NOT the oldest, so an age-based policy would miss it.
    score_voice_release(pool, /*note_id=*/2, /*release_samples=*/48000);

    const int chosen = score_voice_alloc(pool);
    const bool ok = chosen == 2 && pool.steals_from_release == 1 && pool.steals_from_held == 0;
    printf("  chose=%d (want 2) release_steals=%u held_steals=%u -- %s\n",
           chosen, pool.steals_from_release, pool.steals_from_held, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_the_quietest_release_is_chosen_not_the_oldest() {
    printf("\n=== Test: among releasing voices, least release left wins ===\n");
    ScoreVoicePool pool = full_pool(4);
    // Voice 0 is oldest and released first but has the most release left; voice 3 is
    // youngest yet closest to silent. This distinguishes the two policies.
    score_voice_release(pool, /*note_id=*/0, /*release_samples=*/48000);
    score_voice_release(pool, /*note_id=*/3, /*release_samples=*/128);

    const int chosen = score_voice_alloc(pool);
    const bool ok = chosen == 3 && pool.steals_from_release == 1;
    printf("  chose=%d (want 3, the quietest) -- %s\n", chosen, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_all_held_steals_the_oldest_and_is_counted_apart() {
    printf("\n=== Test: with every voice gated, the oldest goes and is counted apart ===\n");
    ScoreVoicePool pool = full_pool(4);
    const int chosen = score_voice_alloc(pool);
    const bool ok = chosen == 0 && pool.steals_from_held == 1 && pool.steals_from_release == 0;
    printf("  chose=%d (want 0, oldest) held_steals=%u -- %s\n",
           chosen, pool.steals_from_held, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_release_expiry_frees_the_slot() {
    printf("\n=== Test: a spent release frees its slot rather than lingering ===\n");
    ScoreVoicePool pool = full_pool(2);
    score_voice_release(pool, /*note_id=*/1, /*release_samples=*/1000);

    score_voices_advance(pool, 512);
    const bool still_held = score_voices_sounding(pool) == 2;
    score_voices_advance(pool, 512);   // 1024 > 1000: expired
    const bool freed = score_voices_sounding(pool) == 1;

    // And the freed slot is now taken without counting a steal.
    const uint32_t before = pool.steals_from_release + pool.steals_from_held;
    score_voice_start(pool, 99, 0, 72, 100);
    const bool no_steal = (pool.steals_from_release + pool.steals_from_held) == before;

    const bool ok = still_held && freed && no_steal;
    printf("  held_at_512=%d freed_at_1024=%d reused_free=%d -- %s\n",
           still_held, freed, no_steal, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_advance_leaves_gated_voices_alone() {
    printf("\n=== Test: advancing does not age a voice whose gate is still down ===\n");
    ScoreVoicePool pool = full_pool(3);
    for (int i = 0; i < 200; i++) score_voices_advance(pool, 512);
    const bool ok = score_voices_sounding(pool) == 3;
    printf("  sounding=%d after 200 blocks -- %s\n", score_voices_sounding(pool),
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_releasing_an_unknown_note_is_a_no_op() {
    printf("\n=== Test: a note-off for a note that was already stolen changes nothing ===\n");
    // Real case: a stolen voice's original note-off still arrives and must not release
    // whatever took its slot.
    ScoreVoicePool pool = full_pool(2);
    score_voice_release(pool, /*note_id=*/77, /*release_samples=*/100);
    bool ok = pool.voices[0].gate && pool.voices[1].gate;
    printf("  both still gated=%d -- %s\n", ok, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_steal_does_not_leak_the_previous_notes_release() {
    printf("\n=== Test: a stolen slot starts gated, not mid-release ===\n");
    ScoreVoicePool pool = full_pool(2);
    score_voice_release(pool, /*note_id=*/0, /*release_samples=*/5000);
    const int v = score_voice_start(pool, /*note_id=*/42, /*part=*/1, /*pitch=*/64, /*velocity=*/90);
    const ScoreVoice& sv = pool.voices[v];
    const bool ok = v == 0 && sv.gate && sv.release_remaining == 0 &&
                    sv.note_id == 42 && sv.part == 1 && sv.pitch == 64;
    printf("  v=%d gate=%d release_remaining=%d note=%d -- %s\n",
           v, sv.gate, sv.release_remaining, sv.note_id, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_tutti_of_22_fits_without_stealing_held_voices() {
    printf("\n=== Test: the measured 22-note tutti fits in 24 with no held-voice steal ===\n");
    // 22 simultaneous notes at the note-off floor. A 24-pool must absorb that without
    // killing anything gated, or the peak of the piece is where it thins out.
    ScoreVoicePool pool{};
    score_voice_pool_reset(pool);
    for (int i = 0; i < 22; i++) score_voice_start(pool, i, i % 12, 48 + i, 100);
    const bool ok = pool.steals_from_held == 0 && score_voices_sounding(pool) == 22;
    printf("  sounding=%d held_steals=%u -- %s\n", score_voices_sounding(pool),
           pool.steals_from_held, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_long_releases_force_held_steals_at_the_44_load() {
    printf("\n=== Test: the 44-voice release-tail load provably overruns a 24 pool ===\n");
    // Hold every voice through a release tail and the same passage needs ~44 slots, so a
    // 24-pool MUST steal. If this stops failing over, the sizing argument changed.
    ScoreVoicePool pool{};
    score_voice_pool_reset(pool);
    for (int i = 0; i < 22; i++) score_voice_start(pool, i, 0, 48 + i, 100);
    for (int i = 0; i < 22; i++) score_voice_release(pool, i, /*release_samples=*/48000);
    // The next 22 notes arrive while all 22 tails are still sounding.
    for (int i = 0; i < 22; i++) score_voice_start(pool, 100 + i, 0, 48 + i, 100);

    const bool ok = pool.steals_from_release > 0 &&
                    score_voices_sounding(pool) == kMaxScoreVoices;
    printf("  release_steals=%u held_steals=%u sounding=%d/%d -- %s\n",
           pool.steals_from_release, pool.steals_from_held,
           score_voices_sounding(pool), kMaxScoreVoices, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_reset_clears_voices_and_counters() {
    printf("\n=== Test: reset clears slots, ages and steal counters ===\n");
    ScoreVoicePool pool = full_pool(4);
    score_voice_alloc(pool);              // force a held steal so a counter is nonzero
    score_voice_pool_reset(pool);
    const bool ok = score_voices_sounding(pool) == 0 && pool.next_age == 0 &&
                    pool.steals_from_release == 0 && pool.steals_from_held == 0;
    printf("  sounding=%d age=%u steals=%u/%u -- %s\n",
           score_voices_sounding(pool), pool.next_age,
           pool.steals_from_release, pool.steals_from_held, ok ? "PASS" : "FAIL");
    return ok;
}

bool run_score_voice_alloc_tests() {
    printf("\n========== Score Voice Allocation ==========\n");
    int passed = 0, failed = 0;
    auto run = [&](bool (*fn)()) { if (fn()) passed++; else failed++; };
    run(test_free_voices_are_taken_before_anything_is_stolen);
    run(test_a_releasing_voice_is_stolen_before_a_held_one);
    run(test_the_quietest_release_is_chosen_not_the_oldest);
    run(test_all_held_steals_the_oldest_and_is_counted_apart);
    run(test_release_expiry_frees_the_slot);
    run(test_advance_leaves_gated_voices_alone);
    run(test_releasing_an_unknown_note_is_a_no_op);
    run(test_steal_does_not_leak_the_previous_notes_release);
    run(test_tutti_of_22_fits_without_stealing_held_voices);
    run(test_long_releases_force_held_steals_at_the_44_load);
    run(test_reset_clears_voices_and_counters);
    printf("\n  Score Voice Allocation: %d passed, %d failed\n", passed, failed);
    TEST_SUITE_RETURN(passed, failed);
}
