#pragma once
#include <cstdint>

// Voice-pool allocation for notated-score playback. Dependency-free so the suite can drive
// the policy with no engine and no audio. Must be C++, not Kotlin: allocation happens on the
// audio thread and the iOS render callback is pure C++ for that reason.

static constexpr int kMaxScoreVoices = 24;

struct ScoreVoice {
    int      note_id = -1;          // < 0 = free
    int      part = -1;
    uint8_t  pitch = 0;
    uint8_t  velocity = 0;
    bool     gate = false;
    int32_t  release_remaining = 0; // samples left after the gate drops
    int32_t  end_tick = 0;          // score tick the written note ends on
    uint32_t age = 0;               // tie-breaks among held voices
};

struct ScoreVoicePool {
    ScoreVoice voices[kMaxScoreVoices];
    int        count = kMaxScoreVoices;
    uint32_t   next_age = 0;
    // A nonzero held count means the pool is undersized. Never read on the audio path.
    uint32_t   steals_from_release = 0;
    uint32_t   steals_from_held = 0;
};

inline void score_voice_pool_reset(ScoreVoicePool& pool) {
    for (int v = 0; v < kMaxScoreVoices; v++) pool.voices[v] = ScoreVoice{};
    pool.next_age = 0;
    pool.steals_from_release = 0;
    pool.steals_from_held = 0;
}

inline bool score_voice_is_free(const ScoreVoice& v) { return v.note_id < 0; }

// Call once per block BEFORE allocating, or a voice that finished releasing this block gets
// stolen instead of reused and the steal counters overstate.
inline void score_voices_advance(ScoreVoicePool& pool, int frames) {
    if (frames <= 0) return;
    for (int v = 0; v < pool.count; v++) {
        ScoreVoice& sv = pool.voices[v];
        if (score_voice_is_free(sv) || sv.gate) continue;
        sv.release_remaining -= frames;
        if (sv.release_remaining <= 0) sv = ScoreVoice{};
    }
}

// No-op for an unknown note: a stolen voice's original note-off still arrives later and must
// not release whatever took its slot.
inline void score_voice_release(ScoreVoicePool& pool, int note_id, int32_t release_samples) {
    for (int v = 0; v < pool.count; v++) {
        ScoreVoice& sv = pool.voices[v];
        if (sv.note_id != note_id || !sv.gate) continue;
        sv.gate = false;
        sv.release_remaining = release_samples > 0 ? release_samples : 1;
        return;
    }
}

// Furthest into release: free voice, else least release left (quietest, least audible steal),
// else oldest held. That last rung is the tutti case and is counted separately.
inline int score_voice_alloc(ScoreVoicePool& pool) {
    if (pool.count <= 0) return -1;

    int      free_idx = -1;
    int      release_idx = -1;
    int32_t  least_release = 0;
    int      held_idx = -1;
    uint32_t oldest_held = 0;

    for (int v = 0; v < pool.count; v++) {
        const ScoreVoice& sv = pool.voices[v];
        if (score_voice_is_free(sv)) {
            if (free_idx < 0) free_idx = v;
        } else if (!sv.gate) {
            if (release_idx < 0 || sv.release_remaining < least_release) {
                least_release = sv.release_remaining;
                release_idx = v;
            }
        } else {
            if (held_idx < 0 || sv.age < oldest_held) {
                oldest_held = sv.age;
                held_idx = v;
            }
        }
    }

    if (free_idx >= 0) return free_idx;
    if (release_idx >= 0) { pool.steals_from_release++; return release_idx; }
    pool.steals_from_held++;
    return held_idx;
}

inline int score_voice_start(ScoreVoicePool& pool,
                             int note_id,
                             int part,
                             uint8_t pitch,
                             uint8_t velocity,
                             int32_t end_tick = 0) {
    const int v = score_voice_alloc(pool);
    if (v < 0) return -1;
    ScoreVoice& sv = pool.voices[v];
    sv.note_id = note_id;
    sv.part = part;
    sv.pitch = pitch;
    sv.velocity = velocity;
    sv.gate = true;
    sv.release_remaining = 0;
    sv.end_tick = end_tick;
    sv.age = pool.next_age++;
    return v;
}

// Drops the gate on every voice whose written note has ended. Duration lives in score
// ticks while release lives in samples, so the two cannot share a countdown -- this is
// what turns "the note is over" into "start releasing".
inline void score_voices_expire(ScoreVoicePool& pool, int now_tick, int32_t release_samples) {
    for (int v = 0; v < pool.count; v++) {
        ScoreVoice& sv = pool.voices[v];
        if (score_voice_is_free(sv) || !sv.gate) continue;
        if (now_tick >= sv.end_tick) {
            sv.gate = false;
            sv.release_remaining = release_samples > 0 ? release_samples : 1;
        }
    }
}

/** Gated or still releasing. For tests and metering. */
inline int score_voices_sounding(const ScoreVoicePool& pool) {
    int n = 0;
    for (int v = 0; v < pool.count; v++) if (!score_voice_is_free(pool.voices[v])) n++;
    return n;
}
