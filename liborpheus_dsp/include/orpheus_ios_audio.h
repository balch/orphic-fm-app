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

// Register a callback fired when AVAudioEngine reports a configuration change.
// Per AVAudioEngine.h, the engine STOPS ITSELF before posting this, so the
// callback is the only in-band notice that audio has died.
//
// CRITICAL: the callback runs on AVAudioEngine's internal dispatch queue.
// It must only signal. Destroying the host from inside it deadlocks against
// the engine's synchronous teardown.
//
// May be invoked with a null ctx during host teardown. The callback must
// tolerate that rather than assume a live context.
//
// `ctx` must outlive orpheus_ios_audio_destroy. The retraction order inside
// destroy prevents a torn cb/ctx pair, but an invocation already past its cb
// load can still read the live ctx and call through it after destroy has
// returned. So ctx must NOT be freed in response to destroy: bind it to
// something that lives as long as the owner of the host (on Kotlin/Native,
// one long-lived StableRef, never one per host instance).
void orpheus_ios_audio_set_config_change_callback(OrpheusIosAudio* host,
                                                  void (*cb)(void*),
                                                  void* ctx);

#ifdef __cplusplus
}
#endif
