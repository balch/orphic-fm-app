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
    dsp_engine_ = orpheus_engine_create(sr);

    LOGI("Stream opened: sampleRate=%d, framesPerBurst=%d, dsp_engine=%p",
         mStream->getSampleRate(), mStream->getFramesPerBurst(), dsp_engine_);
    return oboe::Result::OK;
}

int OboeEngine::loadGraph(const uint8_t* data, size_t length) {
    if (!dsp_engine_) return -100;
    return orpheus_engine_load_patch(dsp_engine_, data, length);
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

    if (dsp_engine_) {
        orpheus_engine_destroy(dsp_engine_);
        dsp_engine_ = nullptr;
    }

    return result;
}

bool OboeEngine::isRunning() const { return mIsRunning.load(); }
int32_t OboeEngine::getSampleRate() const { return mStream ? mStream->getSampleRate() : 0; }
int32_t OboeEngine::getFramesPerBuffer() const { return mStream ? mStream->getFramesPerBurst() : 0; }
double OboeEngine::getCpuLoad() const { return mCpuLoad.load(); }

oboe::DataCallbackResult OboeEngine::onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    if (!mIsRunning.load() || !dsp_engine_) {
        memset(audioData, 0, numFrames * 2 * sizeof(float));
        return oboe::DataCallbackResult::Stop;
    }

    auto start = std::chrono::steady_clock::now();

    // Direct C++ DSP — no JNI, no Kotlin, no GC
    orpheus_engine_process(dsp_engine_, static_cast<float*>(audioData), numFrames);

    auto end = std::chrono::steady_clock::now();
    double us = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
    double budget = static_cast<double>(numFrames) / stream->getSampleRate() * 1e6;
    mCpuLoad.store(us / budget);

    return oboe::DataCallbackResult::Continue;
}

void OboeEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    LOGE("Stream disconnected: %s — reopening", oboe::convertToText(error));
    if (mIsRunning.load()) {
        oboe::Result result = openStream();
        if (result == oboe::Result::OK) {
            // DSP engine persists across reconnects — just restart audio
            mIsRunning.store(true);
            mStream->requestStart();
        }
    }
}

// ── C API pass-throughs ──────────────────────────────
void OboeEngine::setPort(const char* uri, const char* sym, float value) {
    if (dsp_engine_) orpheus_engine_set_port(dsp_engine_, uri, sym, value);
}
float OboeEngine::getPort(const char* uri, const char* sym) {
    return dsp_engine_ ? orpheus_engine_get_port(dsp_engine_, uri, sym) : 0.0f;
}
void OboeEngine::setVoiceGate(int index, int active) {
    if (dsp_engine_) orpheus_engine_set_voice_gate(dsp_engine_, index, active);
}
void OboeEngine::setVoiceTune(int index, float tune) {
    if (dsp_engine_) orpheus_engine_set_voice_tune(dsp_engine_, index, tune);
}
void OboeEngine::setVoiceEngine(int index, int engineIndex) {
    if (dsp_engine_) orpheus_engine_set_voice_engine(dsp_engine_, index, engineIndex);
}
void OboeEngine::setVoiceHarmonics(int index, float value) {
    if (dsp_engine_) orpheus_engine_set_voice_harmonics(dsp_engine_, index, value);
}
void OboeEngine::setVoiceTimbre(int index, float value) {
    if (dsp_engine_) orpheus_engine_set_voice_timbre(dsp_engine_, index, value);
}
void OboeEngine::setVoiceMorph(int index, float value) {
    if (dsp_engine_) orpheus_engine_set_voice_morph(dsp_engine_, index, value);
}
void OboeEngine::setVoiceDecay(int index, float value) {
    if (dsp_engine_) orpheus_engine_set_voice_decay(dsp_engine_, index, value);
}
void OboeEngine::setVoiceActive(int index, int active) {
    if (dsp_engine_) orpheus_engine_set_voice_active(dsp_engine_, index, active);
}
void OboeEngine::setVoiceHold(int index, float level) {
    if (dsp_engine_) orpheus_engine_set_voice_hold(dsp_engine_, index, level);
}
void OboeEngine::triggerDrum(int drumIndex, float accent) {
    if (dsp_engine_) orpheus_engine_trigger_drum(dsp_engine_, drumIndex, accent);
}
void OboeEngine::setMasterVolume(float v) {
    if (dsp_engine_) orpheus_engine_set_master_volume(dsp_engine_, v);
}
void OboeEngine::setDrive(float v) {
    if (dsp_engine_) orpheus_engine_set_drive(dsp_engine_, v);
}
void OboeEngine::setDelayMix(float v) {
    if (dsp_engine_) orpheus_engine_set_delay_mix(dsp_engine_, v);
}
void OboeEngine::setVibrato(float v) {
    if (dsp_engine_) orpheus_engine_set_vibrato(dsp_engine_, v);
}
void OboeEngine::setVibratoRate(float hz) {
    if (dsp_engine_) orpheus_engine_set_vibrato_rate(dsp_engine_, hz);
}
void OboeEngine::setBend(float v) {
    if (dsp_engine_) orpheus_engine_set_bend(dsp_engine_, v);
}
void OboeEngine::getMonitor(OrpheusMonitorData* out) {
    if (dsp_engine_) {
        orpheus_engine_get_monitor(dsp_engine_, out);
    } else {
        memset(out, 0, sizeof(OrpheusMonitorData));
    }
}
int OboeEngine::getViz(int channel, float* outBuf, int maxSamples, int* lastReadPos) {
    return dsp_engine_ ? orpheus_engine_get_viz(dsp_engine_, channel, outBuf, maxSamples, lastReadPos) : 0;
}
void OboeEngine::getPulsarViz(int* gatesOut, float* velocitiesOut, int* playheadsOut, int* stepCountsOut) {
    if (dsp_engine_) orpheus_engine_get_pulsar_viz(dsp_engine_, gatesOut, velocitiesOut, playheadsOut, stepCountsOut);
}
void OboeEngine::getTurntableViz(int deck, float* outBuf) {
    if (dsp_engine_) orpheus_engine_get_turntable_viz(dsp_engine_, deck, outBuf);
    else memset(outBuf, 0, 129 * sizeof(float));
}
void OboeEngine::setAutomation(int target, int voiceIndex, const float* times, const float* values, int count) {
    if (dsp_engine_) orpheus_engine_set_automation(dsp_engine_, target, voiceIndex, times, values, count);
}
void OboeEngine::clearAutomation(int target, int voiceIndex) {
    if (dsp_engine_) orpheus_engine_clear_automation(dsp_engine_, target, voiceIndex);
}
void OboeEngine::loadTtsAudio(const float* samples, int count, int sampleRate) {
    if (dsp_engine_) orpheus_engine_load_tts_audio(dsp_engine_, samples, count, sampleRate);
}
void OboeEngine::playTts() {
    if (dsp_engine_) orpheus_engine_play_tts(dsp_engine_);
}
void OboeEngine::stopTts() {
    if (dsp_engine_) orpheus_engine_stop_tts(dsp_engine_);
}
int OboeEngine::isTtsPlaying() {
    return dsp_engine_ ? orpheus_engine_is_tts_playing(dsp_engine_) : 0;
}
