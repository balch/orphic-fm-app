#ifndef ORPHEUS_OBOE_ENGINE_H
#define ORPHEUS_OBOE_ENGINE_H

#include <oboe/Oboe.h>
#include "orpheus_dsp.h"
#include <atomic>
#include <chrono>
#include <functional>

class OboeEngine : public oboe::AudioStreamDataCallback,
                   public oboe::AudioStreamErrorCallback {
public:
    OboeEngine();
    ~OboeEngine();

    oboe::Result openStream();
    oboe::Result open();
    int loadGraph(const uint8_t* data, size_t length);
    oboe::Result requestStart();
    oboe::Result stop();

    bool isRunning() const;
    int32_t getSampleRate() const;
    int32_t getFramesPerBuffer() const;
    double getCpuLoad() const;

    // Direct C++ DSP engine access
    OrpheusEngine* getDspEngine() { return dsp_engine_.load(std::memory_order_acquire); }

    // C API pass-through (called from JNI bridge for parameter control)
    void setPort(const char* uri, const char* sym, float value);
    float getPort(const char* uri, const char* sym);
    void setVoiceGate(int index, int active);
    void setVoiceTune(int index, float tune);
    void setVoiceEngine(int index, int engineIndex);
    void setVoiceHarmonics(int index, float value);
    void setVoiceTimbre(int index, float value);
    void setVoiceMorph(int index, float value);
    void setVoiceDecay(int index, float value);
    void setVoiceActive(int index, int active);
    void setVoiceHold(int index, float level);
    void triggerDrum(int drumIndex, float accent);
    void setMasterVolume(float v);
    void masterFade(float target, int samples, int curve);
    void masterTapeStop(int samples);
    void masterScratch(int samples);
    void masterDjSweep(int samples);
    float masterVolumeNow();
    void setDrive(float v);
    void setDelayMix(float v);
    void setVibrato(float v);
    void setVibratoRate(float hz);
    void setBend(float v);
    void getMonitor(OrpheusMonitorData* out);
    int  getViz(int channel, float* outBuf, int maxSamples, int* lastReadPos);
    void getPulsarViz(int* gatesOut, float* velocitiesOut, int* playheadsOut, int* stepCountsOut);
    void getPulsarArrangement(int* out);
    void getTurntableViz(int deck, float* outBuf);
    void setAutomation(int target, int voiceIndex, const float* times, const float* values, int count);
    void clearAutomation(int target, int voiceIndex);
    void loadTtsAudio(const float* samples, int count, int sampleRate);
    void playTts();
    void stopTts();
    int  isTtsPlaying();

    // Oboe callbacks
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(
        oboe::AudioStream* stream, oboe::Result error) override;

    // Register a callback that fires after the C++ DSP engine is recreated
    // (e.g. on a route-change with a different sample rate). Pass nullptr to
    // clear. Called from Oboe's error thread; the implementation must marshal
    // off-thread before doing real work.
    void setEngineRecreatedCallback(std::function<void()> cb) {
        mEngineRecreatedCallback = std::move(cb);
    }

private:
    std::shared_ptr<oboe::AudioStream> mStream;
    std::atomic<OrpheusEngine*> dsp_engine_{nullptr};
    int32_t mCreatedSampleRate = 0;
    std::atomic<bool> mIsRunning{false};
    std::atomic<double> mCpuLoad{0.0};
    std::function<void()> mEngineRecreatedCallback;
};

#endif
