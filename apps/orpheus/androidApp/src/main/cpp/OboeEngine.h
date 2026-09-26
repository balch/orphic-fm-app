#ifndef ORPHEUS_OBOE_ENGINE_H
#define ORPHEUS_OBOE_ENGINE_H

#include <oboe/Oboe.h>
#include "orpheus_dsp.h"
#include "orpheus_engine_slot.h"
#include <atomic>
#include <chrono>
#include <mutex>

class OboeEngine : public oboe::AudioStreamDataCallback,
                   public oboe::AudioStreamErrorCallback {
public:
    OboeEngine();
    ~OboeEngine();

    oboe::Result open();
    int loadGraph(const uint8_t* data, size_t length);
    oboe::Result requestStart();
    oboe::Result stop();
    // Reopens a started host whose route-change reopen failed; a no-op otherwise.
    void ensureRunning();

    bool isRunning() const;
    int32_t getSampleRate() const;
    int32_t getFramesPerBuffer() const;
    double getCpuLoad() const;
    int32_t getXRunCount() const;

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
    void masterFilter(int samples);
    float masterVolumeNow();
    void setDrive(float v);
    void setDelayMix(float v);
    void setVibrato(float v);
    void setVibratoRate(float hz);
    void setBend(float v);
    void getMonitor(OrpheusMonitorData* out);
    int  getViz(int channel, float* outBuf, int maxSamples, int* lastReadPos);
    int  getSpectrum(float* bands, int numBands);
    int  getScope(float* out, int numPoints, float windowMs);
    void getPulsarViz(int* gatesOut, float* velocitiesOut, int* playheadsOut, int* stepCountsOut);
    void getPulsarActiveEngines(int* out);
    void getPulsarArrangement(int* out);
    void getTurntableViz(int deck, float* outBuf);
    void setAutomation(int target, int voiceIndex, const float* times, const float* values, int count);
    void clearAutomation(int target, int voiceIndex);
    void loadTtsAudio(const float* samples, int count, int sampleRate);
    void loadPulsarClip(int slot, const float* samples, int count, int sampleRate);
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
    // clear. Called from Oboe's error thread or the repair thread, holding no
    // lock; the implementation must marshal off-thread before doing real work.
    // A plain function pointer, so swapping it can't tear a call in flight.
    void setEngineRecreatedCallback(void (*cb)()) {
        mEngineRecreatedCallback.store(cb);
    }

private:
    // Both: caller holds mLifecycleMutex.
    oboe::Result openStream();
    void reopenLocked(bool* engineRecreated);

    // Held by everything that touches mStream or swaps the engine, so a stop() and the error
    // thread's reopen can't interleave. The audio callback never takes it.
    mutable std::mutex mLifecycleMutex;
    std::shared_ptr<oboe::AudioStream> mStream;
    // JNI threads borrow the engine per call, so a route-change rebuild can't free it under them.
    OrpheusEngineSlot engine_;
    int32_t mCreatedSampleRate = 0;
    // Set by a successful start, cleared by stop(); guarded by mLifecycleMutex. Unlike mIsRunning
    // it survives a failed reopen, which is what tells ensureRunning() there is a host to repair.
    bool mWantRunning = false;
    std::atomic<bool> mIsRunning{false};
    std::atomic<double> mCpuLoad{0.0};
    // Cumulative underrun count for the current stream, mirrored out of the
    // audio callback. Exclusive MMAP underruns are only visible to the client
    // (AudioFlinger never sees them), so this is the sole source of truth.
    std::atomic<int32_t> mXRunCount{0};
    std::atomic<void (*)()> mEngineRecreatedCallback{nullptr};
};

#endif
