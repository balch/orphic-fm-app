#ifndef ORPHEUS_DESKTOP_ENGINE_H
#define ORPHEUS_DESKTOP_ENGINE_H

#include "orpheus_dsp.h"
#include "miniaudio.h"
#include <atomic>

/**
 * Simplified DSP engine wrapper for JVM desktop (no Oboe).
 * Push-model: miniaudio's OS audio thread calls process() via callback.
 */
class DesktopEngine {
public:
    DesktopEngine();
    ~DesktopEngine();

    void open(float sampleRate);
    int  loadGraph(const uint8_t* data, size_t length);
    void process(float* outputBuffer, int numFrames);
    void close();

    bool startAudio();  // Start miniaudio playback device
    void stopAudio();   // Stop miniaudio playback device

    bool   isRunning() const;
    float  getSampleRate() const;
    double getCpuLoad() const;

    // C API pass-throughs (called from JNI bridge)
    void  setPort(const char* uri, const char* sym, float value);
    float getPort(const char* uri, const char* sym);
    void  setVoiceGate(int index, int active);
    void  setVoiceTune(int index, float tune);
    void  setVoiceEngine(int index, int engineIndex);
    void  setVoiceHarmonics(int index, float value);
    void  setVoiceTimbre(int index, float value);
    void  setVoiceMorph(int index, float value);
    void  setVoiceDecay(int index, float value);
    void  setVoiceActive(int index, int active);
    void  setVoiceHold(int index, float level);
    void  triggerDrum(int drumIndex, float accent);
    void  setMasterVolume(float v);
    void  setDrive(float v);
    void  setDelayMix(float v);
    void  setVibrato(float v);
    void  setVibratoRate(float hz);
    void  setBend(float v);
    void  getMonitor(OrpheusMonitorData* out);
    int   getViz(int channel, float* outBuf, int maxSamples, int* lastReadPos);
    void  getTurntableViz(int deck, float* outBuf);
    void  setAutomation(int target, int voiceIndex, const float* times, const float* values, int count);
    void  clearAutomation(int target, int voiceIndex);
    void  loadTtsAudio(const float* samples, int count, int sampleRate);
    void  playTts();
    void  stopTts();
    int   isTtsPlaying();

    void  getPulsarViz(int* gatesOut, float* velocitiesOut, int* playheadsOut, int* stepCountsOut);

private:
    OrpheusEngine* dsp_engine_ = nullptr;
    float sample_rate_ = 0.0f;
    std::atomic<bool> is_running_{false};
    std::atomic<double> cpu_load_{0.0};
    ma_device audio_device_{};
    std::atomic<bool> audio_device_initialized_{false};
};

#endif
