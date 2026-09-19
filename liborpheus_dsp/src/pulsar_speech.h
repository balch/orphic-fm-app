#pragma once

// Vibe speech: the cue bank's wire rows, when a cue fires inside a loop-cycle, and the
// clip player that renders the phrase. Engine-free so the timing math tests on its own.

#include <algorithm>
#include <cmath>

static constexpr int kMaxSpeechClips = 4;
static constexpr int kMaxSpeechClipFrames = 48000 * 8;   // per slot, at the clip's source rate
static constexpr int kMaxSpeechCueRows = 24;
static constexpr int kSpeechCueRowFields = 8;
static constexpr int kSpeechCueBankSize = kMaxSpeechCueRows * kSpeechCueRowFields;
static_assert(kSpeechCueBankSize == 192, "speech cue bank is 24 rows x 8 fields");

struct SpeechCueRow {
    int section = -1;
    int phrase = -1;        // -1 = padding row
    float beat = -1.0f;     // < 0 = the loop's end
    bool align_end = true;
    int every_loops = 1;
    int loop_phase = 0;
    float chance = 1.0f;
    float level = 0.8f;
};

// Wire row: [section, phrase + 1, beat, align_end, every_loops, loop_phase, chance, level].
// phrase + 1 so an all-zero padding row decodes as unauthored rather than phrase 0.
inline SpeechCueRow speech_cue_row_from_wire(const float* f) {
    SpeechCueRow r;
    r.section = static_cast<int>(f[0]);
    r.phrase = static_cast<int>(f[1]) - 1;
    r.beat = f[2];
    r.align_end = f[3] > 0.5f;
    r.every_loops = std::max(1, static_cast<int>(f[4]));
    r.loop_phase = static_cast<int>(f[5]);
    r.chance = std::min(1.0f, std::max(0.0f, f[6]));
    r.level = std::min(1.0f, std::max(0.0f, f[7]));
    return r;
}

inline bool speech_cue_eligible(const SpeechCueRow& r, int cycle) {
    return r.phrase >= 0 && cycle >= 0 && (cycle % r.every_loops) == r.loop_phase;
}

// The beat a cue anchors to: its authored beat, or the loop's end.
inline double speech_cue_anchor_beats(const SpeechCueRow& r, int step_count) {
    return r.beat < 0.0f ? step_count / 4.0 : static_cast<double>(r.beat);
}

// Frames from the loop-cycle's first boundary to the clip's first frame. Clamped at 0, so a
// clip longer than its anchor starts with the cycle and ends late.
inline double speech_cue_start_frame(const SpeechCueRow& r, int step_count,
                                     double samples_per_step, double clip_frames) {
    const double anchor = speech_cue_anchor_beats(r, step_count) * 4.0 * samples_per_step;
    const double start = r.align_end ? anchor - clip_frames : anchor;
    return start < 0.0 ? 0.0 : start;
}

// True when the phrase's last syllable lands on the next loop-cycle's downbeat.
inline bool speech_cue_ends_on_downbeat(const SpeechCueRow& r, int step_count) {
    return r.align_end && std::fabs(speech_cue_anchor_beats(r, step_count) - step_count / 4.0) < 1e-4;
}

namespace speech {

static constexpr float kFadeSeconds = 0.005f;
// One section authors at most SpeechCue.MAX_PER_SECTION (4) cues, so a single loop-cycle
// plans at most 4 starts -- but a start-aligned loop-end cue, or one pushed past the
// wrap, can still be pending when the next cycle plans its own 4, so this is double
// the per-section cap for headroom. No allocation: a fixed pending array, not a queue class.
static constexpr int kMaxPendingSpeechStarts = 8;

struct ClipVoice {
    const float* data = nullptr;
    int length = 0;
    double pos = 0.0;
    double step = 1.0;
    float level = 0.0f;
    long delay = -1;         // pending voice only: frames until the first sample; -1 = none
    bool playing = false;
    float fade_out = 1.0f;   // < 1 while fading out after being replaced
};

// One phrase at a time. A phrase that starts while another sounds fades the old one out
// over kFadeSeconds rather than cutting it. Multiple starts can be queued ahead of time;
// each plays in turn, cutting whatever is currently playing when its own delay elapses.
struct ClipPlayer {
    void Init(float sample_rate) {
        fade_frames_ = std::max(1, static_cast<int>(kFadeSeconds * sample_rate));
        Stop();
    }

    void Stop() {
        current_ = ClipVoice{};
        fading_ = ClipVoice{};
        for (auto& p : pending_) p = ClipVoice{};
    }

    // Arm `clip` to start `delay_frames` after the start of the next Render call, queued
    // behind any pending starts that have not fired yet. Returns false when the clip is
    // rejected (null/too short/bad rate) or the pending queue is already full.
    bool Schedule(const float* clip, int clip_length, int source_rate, float engine_rate,
                  float level, long delay_frames) {
        if (!clip || clip_length < 2 || engine_rate <= 0.0f) return false;
        for (auto& p : pending_) {
            if (p.delay >= 0) continue;
            ClipVoice v;
            v.data = clip;
            v.length = clip_length;
            v.step = static_cast<double>(source_rate > 0 ? source_rate : 48000) / engine_rate;
            v.level = level;
            v.delay = delay_frames < 0 ? 0 : delay_frames;
            p = v;
            return true;
        }
        return false;   // queue full
    }

    bool active() const {
        if (current_.playing || fading_.playing) return true;
        for (const auto& p : pending_) if (p.delay >= 0) return true;
        return false;
    }

    // ADDS mono speech into `out`.
    void Render(float* out, int n) {
        for (int i = 0; i < n; i++) {
            // Each pending entry counts down on its own; within this sample, any that
            // reach 0 start in array order, so a same-sample tie still cuts in sequence.
            for (auto& p : pending_) {
                if (p.delay != 0) continue;
                Start(p);
                p = ClipVoice{};
            }
            for (auto& p : pending_) {
                if (p.delay > 0) p.delay--;
            }
            out[i] += Tick(current_) + Tick(fading_);
        }
    }

 private:
    // Promote `v` to current, fading out whatever was playing through the existing path.
    void Start(const ClipVoice& v) {
        if (current_.playing) {
            fading_ = current_;
            fading_.fade_out = 1.0f - 1.0f / static_cast<float>(fade_frames_);
        }
        current_ = v;
        current_.delay = -1;
        current_.playing = true;
        current_.pos = 0.0;
    }

    float Tick(ClipVoice& v) {
        if (!v.playing) return 0.0f;
        const int i0 = static_cast<int>(v.pos);
        if (i0 >= v.length - 1) { v = ClipVoice{}; return 0.0f; }
        const float frac = static_cast<float>(v.pos - i0);
        const float sample = v.data[i0] + (v.data[i0 + 1] - v.data[i0]) * frac;
        // 5 ms in and out at the clip's own edges, so a phrase never clicks.
        const double played = v.pos / v.step;
        const double remaining = (v.length - 1 - v.pos) / v.step;
        float edge = 1.0f;
        if (played < fade_frames_) edge = static_cast<float>(played / fade_frames_);
        if (remaining < fade_frames_) edge = std::min(edge, static_cast<float>(remaining / fade_frames_));
        float gain = v.level * edge;
        if (v.fade_out < 1.0f) {
            gain *= v.fade_out;
            v.fade_out -= 1.0f / static_cast<float>(fade_frames_);
            if (v.fade_out <= 0.0f) { v = ClipVoice{}; return sample * gain; }
        }
        v.pos += v.step;
        return sample * gain;
    }

    ClipVoice current_;
    ClipVoice fading_;
    ClipVoice pending_[kMaxPendingSpeechStarts];
    int fade_frames_ = 240;
};

}  // namespace speech
