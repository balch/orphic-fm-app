#pragma once

// iOS audio host: owns AVAudioEngine + AVAudioSourceNode with a pure-C
// render block. Exists so no Kotlin/Native code ever runs on the CoreAudio
// real-time thread — K/N GC pauses would stall the render callback and
// cause underruns (crackles). Mirrors the Android design where the Oboe
// callback lives entirely in C++.
//
// Pure C header: consumed by Kotlin/Native cinterop.

#ifdef __cplusplus
extern "C" {
#endif

typedef struct OrpheusEngine OrpheusEngine;
typedef struct OrpheusIosAudio OrpheusIosAudio;

// Build the AVAudioEngine graph (source node -> main mixer) but do not
// start it. `engine` must outlive the host; the host reads it only from
// the render thread while running. `sample_rate` must match the current
// AVAudioSession rate.
OrpheusIosAudio* orpheus_ios_audio_create(OrpheusEngine* engine,
                                          double sample_rate);

// Start rendering. Returns 0 on success. On failure returns the
// AVFoundation NSError code, or -1 when the host is null or no NSError
// was produced.
int orpheus_ios_audio_start(OrpheusIosAudio* host);

// Stop rendering. Synchronizes with the render thread: when this returns,
// no render block invocation is in flight and none will start until the
// next orpheus_ios_audio_start.
void orpheus_ios_audio_stop(OrpheusIosAudio* host);

// Stop, tear down the AVAudioEngine graph, and free the host. After this
// returns it is safe to destroy the OrpheusEngine.
void orpheus_ios_audio_destroy(OrpheusIosAudio* host);

// 1 while the AVAudioEngine is running, else 0.
int orpheus_ios_audio_is_running(OrpheusIosAudio* host);

#ifdef __cplusplus
}
#endif
