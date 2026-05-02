#include "OboeEngine.h"
#include <android/log.h>
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

oboe::Result OboeEngine::openStream() {
    oboe::AudioStreamBuilder builder;
    builder.setSharingMode(oboe::SharingMode::Exclusive)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setChannelCount(2)
        ->setDirection(oboe::Direction::Output)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    return builder.openStream(mStream);
}

oboe::Result OboeEngine::open() {
    oboe::Result result = openStream();
    if (result != oboe::Result::OK) {
        LOGE("Failed to open stream: %s", oboe::convertToText(result));
        return result;
    }

    float sr = static_cast<float>(mStream->getSampleRate());
    mCreatedSampleRate = mStream->getSampleRate();
    dsp_engine_.store(orpheus_engine_create(sr), std::memory_order_release);

    LOGI("Stream opened: sampleRate=%d, framesPerBurst=%d, dsp_engine=%p",
         mStream->getSampleRate(), mStream->getFramesPerBurst(),
         dsp_engine_.load(std::memory_order_relaxed));
    return oboe::Result::OK;
}

int OboeEngine::loadGraph(const uint8_t* data, size_t length) {
    OrpheusEngine* engine = dsp_engine_.load(std::memory_order_acquire);
    if (!engine) return -100;
    return orpheus_engine_load_patch(engine, data, length);
}

oboe::Result OboeEngine::requestStart() {
    mIsRunning.store(true);
    oboe::Result result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(result));
        mIsRunning.store(false);
    } else {
        LOGI("Stream started successfully");
    }
    return result;
}

oboe::Result OboeEngine::stop() {
    mIsRunning.store(false);

    oboe::Result result = oboe::Result::OK;
    if (mStream) {
        result = mStream->stop();
        mStream->close();
        mStream.reset();
    }

    OrpheusEngine* old_engine = dsp_engine_.exchange(nullptr, std::memory_order_acq_rel);
    if (old_engine) {
        orpheus_engine_destroy(old_engine);
    }

    return result;
}

bool OboeEngine::isRunning() const { return mIsRunning.load(); }
int32_t OboeEngine::getSampleRate() const { return mStream ? mStream->getSampleRate() : 0; }
int32_t OboeEngine::getFramesPerBuffer() const { return mStream ? mStream->getFramesPerBurst() : 0; }
double OboeEngine::getCpuLoad() const { return mCpuLoad.load(); }

oboe::DataCallbackResult OboeEngine::onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    OrpheusEngine* engine = dsp_engine_.load(std::memory_order_acquire);
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

    return oboe::DataCallbackResult::Continue;
}

void OboeEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    LOGE("Stream disconnected: %s — reopening", oboe::convertToText(error));
    if (mIsRunning.load()) {
        int32_t old_sr = mCreatedSampleRate;
        oboe::Result result = openStream();
        if (result == oboe::Result::OK) {
            int32_t new_sr = mStream->getSampleRate();
            bool engineRecreated = false;
            // Recreate DSP engine if sample rate changed (e.g. speaker → Bluetooth).
            // Atomically null the pointer BEFORE destroying to prevent use-after-free
            // from concurrent JNI calls (getMonitor, setPort, etc.).
            if (new_sr != old_sr) {
                LOGI("Sample rate changed %d → %d — recreating DSP engine", old_sr, new_sr);
                OrpheusEngine* old_engine = dsp_engine_.exchange(nullptr, std::memory_order_acq_rel);
                if (old_engine) orpheus_engine_destroy(old_engine);
                dsp_engine_.store(orpheus_engine_create(static_cast<float>(new_sr)),
                                  std::memory_order_release);
                mCreatedSampleRate = new_sr;
                engineRecreated = true;
            }
            mIsRunning.store(true);
            mStream->requestStart();
            LOGI("Stream reopened: sampleRate=%d, framesPerBurst=%d",
                 new_sr, mStream->getFramesPerBurst());
            // Notify Kotlin to reload the graph + re-push port state into the
            // brand-new engine. Without this, audio stays silent: the new
            // engine has no graph and no port writes have hit it yet.
            if (engineRecreated && mEngineRecreatedCallback) {
                mEngineRecreatedCallback();
            }
        }
    }
}

// ── C API pass-throughs ──────────────────────────────
// Each method loads the atomic dsp_engine_ pointer once to avoid use-after-free
// during BT reconnection (onErrorAfterClose destroys/recreates the engine).
void OboeEngine::setPort(const char* uri, const char* sym, float value) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_port(e, uri, sym, value);
}
float OboeEngine::getPort(const char* uri, const char* sym) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) return orpheus_engine_get_port(e, uri, sym);
    return 0.0f;
}
void OboeEngine::setVoiceGate(int index, int active) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_voice_gate(e, index, active);
}
void OboeEngine::setVoiceTune(int index, float tune) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_voice_tune(e, index, tune);
}
void OboeEngine::setVoiceEngine(int index, int engineIndex) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_voice_engine(e, index, engineIndex);
}
void OboeEngine::setVoiceHarmonics(int index, float value) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_voice_harmonics(e, index, value);
}
void OboeEngine::setVoiceTimbre(int index, float value) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_voice_timbre(e, index, value);
}
void OboeEngine::setVoiceMorph(int index, float value) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_voice_morph(e, index, value);
}
void OboeEngine::setVoiceDecay(int index, float value) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_voice_decay(e, index, value);
}
void OboeEngine::setVoiceActive(int index, int active) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_voice_active(e, index, active);
}
void OboeEngine::setVoiceHold(int index, float level) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_voice_hold(e, index, level);
}
void OboeEngine::triggerDrum(int drumIndex, float accent) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_trigger_drum(e, drumIndex, accent);
}
void OboeEngine::setMasterVolume(float v) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_master_volume(e, v);
}
void OboeEngine::setDrive(float v) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_drive(e, v);
}
void OboeEngine::setDelayMix(float v) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_delay_mix(e, v);
}
void OboeEngine::setVibrato(float v) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_vibrato(e, v);
}
void OboeEngine::setVibratoRate(float hz) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_vibrato_rate(e, hz);
}
void OboeEngine::setBend(float v) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_bend(e, v);
}
void OboeEngine::getMonitor(OrpheusMonitorData* out) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) {
        orpheus_engine_get_monitor(e, out);
    } else {
        memset(out, 0, sizeof(OrpheusMonitorData));
    }
}
int OboeEngine::getViz(int channel, float* outBuf, int maxSamples, int* lastReadPos) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) return orpheus_engine_get_viz(e, channel, outBuf, maxSamples, lastReadPos);
    return 0;
}
void OboeEngine::getPulsarViz(int* gatesOut, float* velocitiesOut, int* playheadsOut, int* stepCountsOut) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_get_pulsar_viz(e, gatesOut, velocitiesOut, playheadsOut, stepCountsOut);
}
void OboeEngine::getPulsarArrangement(int* out) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_get_pulsar_arrangement(e, out);
    else out[0] = -1;
}
void OboeEngine::getTurntableViz(int deck, float* outBuf) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_get_turntable_viz(e, deck, outBuf);
    else memset(outBuf, 0, 129 * sizeof(float));
}
void OboeEngine::setAutomation(int target, int voiceIndex, const float* times, const float* values, int count) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_set_automation(e, target, voiceIndex, times, values, count);
}
void OboeEngine::clearAutomation(int target, int voiceIndex) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_clear_automation(e, target, voiceIndex);
}
void OboeEngine::loadTtsAudio(const float* samples, int count, int sampleRate) {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_load_tts_audio(e, samples, count, sampleRate);
}
void OboeEngine::playTts() {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_play_tts(e);
}
void OboeEngine::stopTts() {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) orpheus_engine_stop_tts(e);
}
int OboeEngine::isTtsPlaying() {
    if (auto* e = dsp_engine_.load(std::memory_order_acquire)) return orpheus_engine_is_tts_playing(e);
    return 0;
}
