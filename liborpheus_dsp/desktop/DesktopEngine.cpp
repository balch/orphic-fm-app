#include "DesktopEngine.h"
#include <cstring>
#include <chrono>

// miniaudio calls this from the OS audio thread (CoreAudio/WASAPI/ALSA).
// No JNI, no Java, no float-to-int16 conversion — just fill the float buffer.
static void ma_audio_callback(ma_device* pDevice, void* pOutput, const void* /*pInput*/, ma_uint32 frameCount) {
    auto* engine = static_cast<DesktopEngine*>(pDevice->pUserData);
    engine->process(static_cast<float*>(pOutput), static_cast<int>(frameCount));
}

DesktopEngine::DesktopEngine() = default;

DesktopEngine::~DesktopEngine() {
    close();
}

void DesktopEngine::open(float sampleRate) {
    // Close any existing engine first
    close();

    sample_rate_ = sampleRate;
    dsp_engine_ = orpheus_engine_create(sampleRate);
    is_running_.store(dsp_engine_ != nullptr);
}

int DesktopEngine::loadGraph(const uint8_t* data, size_t length) {
    if (!dsp_engine_) return -100;
    return orpheus_engine_load_patch(dsp_engine_, data, length);
}

void DesktopEngine::process(float* outputBuffer, int numFrames) {
    if (!is_running_.load() || !dsp_engine_) {
        memset(outputBuffer, 0, numFrames * 2 * sizeof(float));
        return;
    }

    auto start = std::chrono::steady_clock::now();

    orpheus_engine_process(dsp_engine_, outputBuffer, numFrames);

    auto end = std::chrono::steady_clock::now();
    double us = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
    double budget = static_cast<double>(numFrames) / sample_rate_ * 1e6;
    cpu_load_.store(us / budget);
}

bool DesktopEngine::startAudio() {
    if (audio_device_initialized_.load()) return true;
    if (!dsp_engine_) return false;

    ma_device_config config = ma_device_config_init(ma_device_type_playback);
    config.playback.format    = ma_format_f32;
    config.playback.channels  = 2;
    config.sampleRate         = static_cast<ma_uint32>(sample_rate_);
    config.periodSizeInFrames = 512;
    config.dataCallback       = ma_audio_callback;
    config.pUserData          = this;

    if (ma_device_init(nullptr, &config, &audio_device_) != MA_SUCCESS) {
        return false;
    }
    if (ma_device_start(&audio_device_) != MA_SUCCESS) {
        ma_device_uninit(&audio_device_);
        return false;
    }
    audio_device_initialized_.store(true);
    return true;
}

void DesktopEngine::stopAudio() {
    if (audio_device_initialized_.load()) {
        ma_device_uninit(&audio_device_);
        audio_device_initialized_.store(false);
    }
}

void DesktopEngine::close() {
    stopAudio();
    is_running_.store(false);

    if (dsp_engine_) {
        orpheus_engine_destroy(dsp_engine_);
        dsp_engine_ = nullptr;
    }

    sample_rate_ = 0.0f;
}

bool DesktopEngine::isRunning() const { return is_running_.load(); }
float DesktopEngine::getSampleRate() const { return sample_rate_; }
double DesktopEngine::getCpuLoad() const { return cpu_load_.load(); }

// -- C API pass-throughs ------------------------------------------------

void DesktopEngine::setPort(const char* uri, const char* sym, float value) {
    if (dsp_engine_) orpheus_engine_set_port(dsp_engine_, uri, sym, value);
}
float DesktopEngine::getPort(const char* uri, const char* sym) {
    return dsp_engine_ ? orpheus_engine_get_port(dsp_engine_, uri, sym) : 0.0f;
}
void DesktopEngine::setVoiceGate(int index, int active) {
    if (dsp_engine_) orpheus_engine_set_voice_gate(dsp_engine_, index, active);
}
void DesktopEngine::setVoiceTune(int index, float tune) {
    if (dsp_engine_) orpheus_engine_set_voice_tune(dsp_engine_, index, tune);
}
void DesktopEngine::setVoiceEngine(int index, int engineIndex) {
    if (dsp_engine_) orpheus_engine_set_voice_engine(dsp_engine_, index, engineIndex);
}
void DesktopEngine::setVoiceHarmonics(int index, float value) {
    if (dsp_engine_) orpheus_engine_set_voice_harmonics(dsp_engine_, index, value);
}
void DesktopEngine::setVoiceTimbre(int index, float value) {
    if (dsp_engine_) orpheus_engine_set_voice_timbre(dsp_engine_, index, value);
}
void DesktopEngine::setVoiceMorph(int index, float value) {
    if (dsp_engine_) orpheus_engine_set_voice_morph(dsp_engine_, index, value);
}
void DesktopEngine::setVoiceDecay(int index, float value) {
    if (dsp_engine_) orpheus_engine_set_voice_decay(dsp_engine_, index, value);
}
void DesktopEngine::setVoiceActive(int index, int active) {
    if (dsp_engine_) orpheus_engine_set_voice_active(dsp_engine_, index, active);
}
void DesktopEngine::setVoiceHold(int index, float level) {
    if (dsp_engine_) orpheus_engine_set_voice_hold(dsp_engine_, index, level);
}
void DesktopEngine::triggerDrum(int drumIndex, float accent) {
    if (dsp_engine_) orpheus_engine_trigger_drum(dsp_engine_, drumIndex, accent);
}
void DesktopEngine::setMasterVolume(float v) {
    if (dsp_engine_) orpheus_engine_set_master_volume(dsp_engine_, v);
}
void DesktopEngine::masterFade(float target, int samples, int curve) {
    if (dsp_engine_) orpheus_engine_master_fade(dsp_engine_, target, samples, curve);
}
void DesktopEngine::masterTapeStop(int samples) {
    if (dsp_engine_) orpheus_engine_master_tape_stop(dsp_engine_, samples);
}
void DesktopEngine::masterScratch(int samples) {
    if (dsp_engine_) orpheus_engine_master_scratch(dsp_engine_, samples);
}
void DesktopEngine::masterFilter(int samples) {
    if (dsp_engine_) orpheus_engine_master_filter(dsp_engine_, samples);
}
float DesktopEngine::masterVolumeNow() {
    return dsp_engine_ ? orpheus_engine_master_volume_now(dsp_engine_) : 0.0f;
}
void DesktopEngine::setDrive(float v) {
    if (dsp_engine_) orpheus_engine_set_drive(dsp_engine_, v);
}
void DesktopEngine::setDelayMix(float v) {
    if (dsp_engine_) orpheus_engine_set_delay_mix(dsp_engine_, v);
}
void DesktopEngine::setVibrato(float v) {
    if (dsp_engine_) orpheus_engine_set_vibrato(dsp_engine_, v);
}
void DesktopEngine::setVibratoRate(float hz) {
    if (dsp_engine_) orpheus_engine_set_vibrato_rate(dsp_engine_, hz);
}
void DesktopEngine::setBend(float v) {
    if (dsp_engine_) orpheus_engine_set_bend(dsp_engine_, v);
}
void DesktopEngine::getMonitor(OrpheusMonitorData* out) {
    if (dsp_engine_) {
        orpheus_engine_get_monitor(dsp_engine_, out);
    } else {
        memset(out, 0, sizeof(OrpheusMonitorData));
    }
}
int DesktopEngine::getViz(int channel, float* outBuf, int maxSamples, int* lastReadPos) {
    return dsp_engine_ ? orpheus_engine_get_viz(dsp_engine_, channel, outBuf, maxSamples, lastReadPos) : 0;
}
int DesktopEngine::getSpectrum(float* bands, int numBands) {
    return dsp_engine_ ? orpheus_engine_get_spectrum(dsp_engine_, bands, numBands) : 0;
}
void DesktopEngine::getTurntableViz(int deck, float* outBuf) {
    if (dsp_engine_) orpheus_engine_get_turntable_viz(dsp_engine_, deck, outBuf);
    else memset(outBuf, 0, 129 * sizeof(float));
}
void DesktopEngine::setAutomation(int target, int voiceIndex, const float* times, const float* values, int count) {
    if (dsp_engine_) orpheus_engine_set_automation(dsp_engine_, target, voiceIndex, times, values, count);
}
void DesktopEngine::clearAutomation(int target, int voiceIndex) {
    if (dsp_engine_) orpheus_engine_clear_automation(dsp_engine_, target, voiceIndex);
}
void DesktopEngine::loadTtsAudio(const float* samples, int count, int sampleRate) {
    if (dsp_engine_) orpheus_engine_load_tts_audio(dsp_engine_, samples, count, sampleRate);
}
void DesktopEngine::playTts() {
    if (dsp_engine_) orpheus_engine_play_tts(dsp_engine_);
}
void DesktopEngine::stopTts() {
    if (dsp_engine_) orpheus_engine_stop_tts(dsp_engine_);
}
int DesktopEngine::isTtsPlaying() {
    return dsp_engine_ ? orpheus_engine_is_tts_playing(dsp_engine_) : 0;
}
void DesktopEngine::getPulsarViz(int* gatesOut, float* velocitiesOut, int* playheadsOut, int* stepCountsOut) {
    if (dsp_engine_) orpheus_engine_get_pulsar_viz(dsp_engine_, gatesOut, velocitiesOut, playheadsOut, stepCountsOut);
}
void DesktopEngine::getPulsarActiveEngines(int* out) {
    if (dsp_engine_) orpheus_engine_get_pulsar_active_engines(dsp_engine_, out);
    else for (int t = 0; t < 8; t++) out[t] = -1;
}
void DesktopEngine::getPulsarArrangement(int* out) {
    if (dsp_engine_) orpheus_engine_get_pulsar_arrangement(dsp_engine_, out);
    else out[0] = -1;
}
