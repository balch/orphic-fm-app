#include "OboeEngine.h"
#include <oboe/OboeExtensions.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include <sys/resource.h>
#include <cstring>
#include <chrono>

#define LOG_TAG "OboeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

OboeEngine::OboeEngine() = default;

OboeEngine::~OboeEngine() {
    stop();
}

// Android TV boxes (verified on Amlogic / Chromecast with Google TV) hand out an MMAP
// stream that opens cleanly, reports healthy and consumes frames, yet emits silence.
// LowLatency is what selects the MMAP endpoint - sharing mode does not, as those devices
// route even a Shared stream through AAudioServiceEndpointMMAP - so PerformanceMode::None
// is the only reliable way off it. Costs latency (~384 -> ~2050 frame bursts), so it is
// gated to TV and never affects phones or tablets.
static bool isTelevisionDevice() {
    char value[PROP_VALUE_MAX] = {0};
    __system_property_get("ro.build.characteristics", value);
    return std::strstr(value, "tv") != nullptr;
}

oboe::Result OboeEngine::openStream() {
    const bool isTv = isTelevisionDevice();
    oboe::AudioStreamBuilder builder;
    builder.setSharingMode(isTv ? oboe::SharingMode::Shared : oboe::SharingMode::Exclusive)
        ->setPerformanceMode(isTv ? oboe::PerformanceMode::None : oboe::PerformanceMode::LowLatency)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setChannelCount(2)
        ->setDirection(oboe::Direction::Output)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    return builder.openStream(mStream);
}

oboe::Result OboeEngine::open() {
    std::lock_guard<std::mutex> lock(mLifecycleMutex);
    oboe::Result result = openStream();
    if (result != oboe::Result::OK) {
        LOGE("Failed to open stream: %s", oboe::convertToText(result));
        return result;
    }

    float sr = static_cast<float>(mStream->getSampleRate());
    mCreatedSampleRate = mStream->getSampleRate();
    OrpheusEngine* created = orpheus_engine_create(sr);
    // A failed reopen keeps its engine with no stream rendering it, so the slot may not be empty.
    engine_.replace(created);

    // Log what we actually got, not what we asked for: sharing/performance mode and the
    // MMAP verdict are negotiated by the HAL and are the first thing to check on silence.
    LOGI("Stream opened: sampleRate=%d, framesPerBurst=%d, sharing=%s, perf=%s, mmap=%d, dsp_engine=%p",
         mStream->getSampleRate(), mStream->getFramesPerBurst(),
         oboe::convertToText(mStream->getSharingMode()),
         oboe::convertToText(mStream->getPerformanceMode()),
         static_cast<int>(oboe::OboeExtensions::isMMapUsed(mStream.get())),
         created);
    return oboe::Result::OK;
}

int OboeEngine::loadGraph(const uint8_t* data, size_t length) {
    return engine_.with(-100, [&](OrpheusEngine* e) { return orpheus_engine_load_patch(e, data, length); });
}

oboe::Result OboeEngine::requestStart() {
    std::lock_guard<std::mutex> lock(mLifecycleMutex);
    mIsRunning.store(true);
    oboe::Result result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(result));
        mIsRunning.store(false);
    } else {
        mWantRunning = true;
        LOGI("Stream started successfully");
    }
    return result;
}

oboe::Result OboeEngine::stop() {
    std::lock_guard<std::mutex> lock(mLifecycleMutex);
    mWantRunning = false;
    mIsRunning.store(false);

    oboe::Result result = oboe::Result::OK;
    if (mStream) {
        result = mStream->stop();
        mStream->close();
        mStream.reset();
    }

    // The stream is closed, so only JNI borrows can still hold the engine; this waits them out.
    engine_.replace(nullptr);

    return result;
}

bool OboeEngine::isRunning() const { return mIsRunning.load(); }
int32_t OboeEngine::getSampleRate() const {
    std::lock_guard<std::mutex> lock(mLifecycleMutex);
    return mStream ? mStream->getSampleRate() : 0;
}
int32_t OboeEngine::getFramesPerBuffer() const {
    std::lock_guard<std::mutex> lock(mLifecycleMutex);
    return mStream ? mStream->getFramesPerBurst() : 0;
}
double OboeEngine::getCpuLoad() const { return mCpuLoad.load(); }
int32_t OboeEngine::getXRunCount() const { return mXRunCount.load(std::memory_order_relaxed); }

oboe::DataCallbackResult OboeEngine::onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    OrpheusEngine* engine = engine_.audio_thread_engine();
    if (!mIsRunning.load() || !engine) {
        memset(audioData, 0, numFrames * 2 * sizeof(float));
        return oboe::DataCallbackResult::Stop;
    }

    auto start = std::chrono::steady_clock::now();

    // Direct C++ DSP — no JNI, no Kotlin, no GC
    orpheus_engine_process(engine, static_cast<float*>(audioData), numFrames);

    auto end = std::chrono::steady_clock::now();
    double us = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
    double budget = static_cast<double>(numFrames) / stream->getSampleRate() * 1e6;
    mCpuLoad.store(us / budget);

    // Mirror the stream's underrun counter (lock-free read of an internal
    // AAudio counter). Polled from Kotlin's monitor loop — no logging here.
    auto xruns = stream->getXRunCount();
    if (xruns) mXRunCount.store(xruns.value(), std::memory_order_relaxed);

    return oboe::DataCallbackResult::Continue;
}

void OboeEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    LOGE("Stream disconnected: %s", oboe::convertToText(error));
    bool engineRecreated = false;
    {
        std::lock_guard<std::mutex> lock(mLifecycleMutex);
        // A stop() that got the lock first owns the outcome, and so does an open() that has
        // since replaced this stream; reopening over either leaks a running stream.
        if (!mIsRunning.load() || stream != mStream.get()) {
            LOGI("Not reopening: the stream was stopped or replaced");
            return;
        }
        reopenLocked(&engineRecreated);
    }
    // Notify Kotlin to reload the graph + re-push port state into the
    // brand-new engine. Without this, audio stays silent: the new
    // engine has no graph and no port writes have hit it yet.
    // Called unlocked: it upcalls into the JVM and takes the bridge's own lock.
    void (*cb)() = mEngineRecreatedCallback.load();
    if (engineRecreated && cb) cb();
}

void OboeEngine::ensureRunning() {
    bool engineRecreated = false;
    {
        std::lock_guard<std::mutex> lock(mLifecycleMutex);
        if (!mWantRunning || mIsRunning.load()) return;
        LOGI("Repairing: the last reopen failed, retrying");
        reopenLocked(&engineRecreated);
    }
    void (*cb)() = mEngineRecreatedCallback.load();
    if (engineRecreated && cb) cb();
}

// On failure leaves no stream and mIsRunning clear, with the engine kept for ensureRunning(). An
// engine rebuilt before a failed start still reports engineRecreated, as it needs its graph.
void OboeEngine::reopenLocked(bool* engineRecreated) {
    int32_t old_sr = mCreatedSampleRate;
    oboe::Result result = openStream();
    if (result != oboe::Result::OK) {
        LOGE("Reopen failed: %s", oboe::convertToText(result));
        mIsRunning.store(false);
        return;
    }
    int32_t new_sr = mStream->getSampleRate();
    // Recreate DSP engine if sample rate changed (e.g. speaker → Bluetooth). The old
    // stream is closed and the new one not started, so no audio callback holds it;
    // replace() waits out JNI calls still inside it (scope, spectrum, setPort...).
    if (new_sr != old_sr) {
        LOGI("Sample rate changed %d → %d — recreating DSP engine", old_sr, new_sr);
        engine_.replace(nullptr);
        engine_.publish(orpheus_engine_create(static_cast<float>(new_sr)));
        mCreatedSampleRate = new_sr;
        *engineRecreated = true;
    }
    mIsRunning.store(true);
    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Reopened stream failed to start: %s", oboe::convertToText(result));
        mIsRunning.store(false);
        mStream->close();
        mStream.reset();
        return;
    }
    LOGI("Stream reopened: sampleRate=%d, framesPerBurst=%d",
         new_sr, mStream->getFramesPerBurst());
}

// ── C API pass-throughs ──────────────────────────────
// Each call borrows the engine from engine_ for its whole duration, so onErrorAfterClose or
// stop() can't destroy it mid-call (they wait for the borrow to end).
void OboeEngine::setPort(const char* uri, const char* sym, float value) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_port(e, uri, sym, value); });
}
float OboeEngine::getPort(const char* uri, const char* sym) {
    return engine_.with(0.0f, [&](OrpheusEngine* e) { return orpheus_engine_get_port(e, uri, sym); });
}
void OboeEngine::setVoiceGate(int index, int active) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_voice_gate(e, index, active); });
}
void OboeEngine::setVoiceTune(int index, float tune) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_voice_tune(e, index, tune); });
}
void OboeEngine::setVoiceEngine(int index, int engineIndex) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_voice_engine(e, index, engineIndex); });
}
void OboeEngine::setVoiceHarmonics(int index, float value) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_voice_harmonics(e, index, value); });
}
void OboeEngine::setVoiceTimbre(int index, float value) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_voice_timbre(e, index, value); });
}
void OboeEngine::setVoiceMorph(int index, float value) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_voice_morph(e, index, value); });
}
void OboeEngine::setVoiceDecay(int index, float value) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_voice_decay(e, index, value); });
}
void OboeEngine::setVoiceActive(int index, int active) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_voice_active(e, index, active); });
}
void OboeEngine::setVoiceHold(int index, float level) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_voice_hold(e, index, level); });
}
void OboeEngine::triggerDrum(int drumIndex, float accent) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_trigger_drum(e, drumIndex, accent); });
}
void OboeEngine::setMasterVolume(float v) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_master_volume(e, v); });
}
void OboeEngine::masterFade(float target, int samples, int curve) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_master_fade(e, target, samples, curve); });
}
void OboeEngine::masterTapeStop(int samples) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_master_tape_stop(e, samples); });
}
void OboeEngine::masterScratch(int samples) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_master_scratch(e, samples); });
}
void OboeEngine::masterFilter(int samples) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_master_filter(e, samples); });
}
float OboeEngine::masterVolumeNow() {
    return engine_.with(0.0f, [](OrpheusEngine* e) { return orpheus_engine_master_volume_now(e); });
}
void OboeEngine::setDrive(float v) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_drive(e, v); });
}
void OboeEngine::setDelayMix(float v) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_delay_mix(e, v); });
}
void OboeEngine::setVibrato(float v) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_vibrato(e, v); });
}
void OboeEngine::setVibratoRate(float hz) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_vibrato_rate(e, hz); });
}
void OboeEngine::setBend(float v) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_bend(e, v); });
}
void OboeEngine::getMonitor(OrpheusMonitorData* out) {
    bool read = engine_.with(false, [&](OrpheusEngine* e) { orpheus_engine_get_monitor(e, out); return true; });
    if (!read) memset(out, 0, sizeof(OrpheusMonitorData));
}
int OboeEngine::getViz(int channel, float* outBuf, int maxSamples, int* lastReadPos) {
    return engine_.with(0, [&](OrpheusEngine* e) {
        return orpheus_engine_get_viz(e, channel, outBuf, maxSamples, lastReadPos);
    });
}
int OboeEngine::getSpectrum(float* bands, int numBands) {
    return engine_.with(0, [&](OrpheusEngine* e) { return orpheus_engine_get_spectrum(e, bands, numBands); });
}
int OboeEngine::getScope(float* out, int numPoints, float windowMs) {
    return engine_.with(-1, [&](OrpheusEngine* e) { return orpheus_engine_get_scope(e, out, numPoints, windowMs); });
}
void OboeEngine::getPulsarViz(int* gatesOut, float* velocitiesOut, int* playheadsOut, int* stepCountsOut) {
    engine_.with([&](OrpheusEngine* e) {
        orpheus_engine_get_pulsar_viz(e, gatesOut, velocitiesOut, playheadsOut, stepCountsOut);
    });
}
void OboeEngine::getPulsarActiveEngines(int* out) {
    bool read = engine_.with(false, [&](OrpheusEngine* e) { orpheus_engine_get_pulsar_active_engines(e, out); return true; });
    if (!read) for (int t = 0; t < 8; t++) out[t] = -1;
}
void OboeEngine::getPulsarArrangement(int* out) {
    bool read = engine_.with(false, [&](OrpheusEngine* e) { orpheus_engine_get_pulsar_arrangement(e, out); return true; });
    if (!read) out[0] = -1;
}
void OboeEngine::getTurntableViz(int deck, float* outBuf) {
    bool read = engine_.with(false, [&](OrpheusEngine* e) { orpheus_engine_get_turntable_viz(e, deck, outBuf); return true; });
    if (!read) memset(outBuf, 0, 129 * sizeof(float));
}
void OboeEngine::setAutomation(int target, int voiceIndex, const float* times, const float* values, int count) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_set_automation(e, target, voiceIndex, times, values, count); });
}
void OboeEngine::clearAutomation(int target, int voiceIndex) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_clear_automation(e, target, voiceIndex); });
}
void OboeEngine::loadTtsAudio(const float* samples, int count, int sampleRate) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_load_tts_audio(e, samples, count, sampleRate); });
}
void OboeEngine::loadPulsarClip(int slot, const float* samples, int count, int sampleRate) {
    engine_.with([&](OrpheusEngine* e) { orpheus_engine_load_pulsar_clip(e, slot, samples, count, sampleRate); });
}
void OboeEngine::playTts() {
    engine_.with([](OrpheusEngine* e) { orpheus_engine_play_tts(e); });
}
void OboeEngine::stopTts() {
    engine_.with([](OrpheusEngine* e) { orpheus_engine_stop_tts(e); });
}
int OboeEngine::isTtsPlaying() {
    return engine_.with(0, [](OrpheusEngine* e) { return orpheus_engine_is_tts_playing(e); });
}
