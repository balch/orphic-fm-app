#ifndef ORPHEUS_OBOE_ENGINE_H
#define ORPHEUS_OBOE_ENGINE_H

#include <oboe/Oboe.h>
#include <jni.h>
#include <atomic>

class OboeEngine : public oboe::AudioStreamDataCallback,
                   public oboe::AudioStreamErrorCallback {
public:
    OboeEngine();
    ~OboeEngine();

    /** Open the stream and set up JNI. Does NOT start audio. */
    oboe::Result open(JNIEnv *env, jobject kotlinCallback);
    /** Begin audio playback. Call after Kotlin has allocated buffers. */
    oboe::Result requestStart();
    oboe::Result stop();
    bool isRunning() const;
    int32_t getSampleRate() const;
    int32_t getFramesPerBuffer() const;
    double getCpuLoad() const;

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    oboe::Result openStream();

    std::shared_ptr<oboe::AudioStream> mStream;
    JavaVM *mJvm = nullptr;
    // Written once from audio thread on first callback; read only from audio thread thereafter.
    JNIEnv *mAudioThreadEnv = nullptr;
    // Written once during open() before audio starts; read from audio thread.
    // Safe due to happens-before from stream start, but documented for clarity.
    jobject mKotlinCallback = nullptr;
    jmethodID mRenderMethod = nullptr;
    jfloatArray mJniOutputBuffer = nullptr;

    std::atomic<bool> mIsRunning{false};
    std::atomic<bool> mAudioThreadAttached{false};
    std::atomic<double> mCpuLoad{0.0};
};

#endif
