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

oboe::Result OboeEngine::open(JNIEnv *env, jobject kotlinCallback) {
    env->GetJavaVM(&mJvm);
    mKotlinCallback = env->NewGlobalRef(kotlinCallback);

    jclass cls = env->GetObjectClass(kotlinCallback);
    mRenderMethod = env->GetMethodID(cls, "renderAudio", "([FI)V");
    if (!mRenderMethod) {
        LOGE("Could not find renderAudio method");
        return oboe::Result::ErrorInternal;
    }

    oboe::Result result = openStream();
    if (result != oboe::Result::OK) {
        LOGE("Failed to open stream: %s", oboe::convertToText(result));
        return result;
    }

    int32_t bufferCapacity = mStream->getBufferCapacityInFrames();
    jfloatArray localBuffer = env->NewFloatArray(bufferCapacity * 2);
    mJniOutputBuffer = (jfloatArray)env->NewGlobalRef(localBuffer);
    env->DeleteLocalRef(localBuffer);

    LOGI("Stream opened: sampleRate=%d, framesPerBurst=%d, bufferCapacity=%d",
         mStream->getSampleRate(), mStream->getFramesPerBurst(), bufferCapacity);

    return oboe::Result::OK;
}

oboe::Result OboeEngine::requestStart() {
    // Set running BEFORE requestStart — the callback can fire immediately
    // on another thread, and if mIsRunning is false it returns Stop.
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

    // Audio thread detaches itself: once the stream is stopped/closed above,
    // the callback won't fire again. The audio thread's JNI attachment is
    // cleaned up by the system when the thread exits.
    mAudioThreadAttached.store(false);
    mAudioThreadEnv = nullptr;

    if (mJvm && mKotlinCallback) {
        JNIEnv *env;
        mJvm->AttachCurrentThread(&env, nullptr);
        env->DeleteGlobalRef(mKotlinCallback);
        env->DeleteGlobalRef(mJniOutputBuffer);
        mJvm->DetachCurrentThread();
        mKotlinCallback = nullptr;
        mJniOutputBuffer = nullptr;
    }

    return result;
}

bool OboeEngine::isRunning() const { return mIsRunning.load(); }
int32_t OboeEngine::getSampleRate() const { return mStream ? mStream->getSampleRate() : 0; }
int32_t OboeEngine::getFramesPerBuffer() const { return mStream ? mStream->getFramesPerBurst() : 0; }
double OboeEngine::getCpuLoad() const { return mCpuLoad.load(); }

oboe::DataCallbackResult OboeEngine::onAudioReady(
        oboe::AudioStream *stream, void *audioData, int32_t numFrames) {

    if (!mIsRunning.load()) {
        memset(audioData, 0, numFrames * 2 * sizeof(float));
        return oboe::DataCallbackResult::Stop;
    }

    auto startTime = std::chrono::steady_clock::now();

    if (!mAudioThreadAttached.load()) {
        JNIEnv *env;
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_6;
        args.name = "OboeAudioThread";
        args.group = nullptr;
        mJvm->AttachCurrentThread(&env, &args);
        mAudioThreadEnv = env;
        mAudioThreadAttached.store(true);
        setpriority(PRIO_PROCESS, 0, -19);
    }

    mAudioThreadEnv->CallVoidMethod(mKotlinCallback, mRenderMethod,
                                     mJniOutputBuffer, numFrames);

    mAudioThreadEnv->GetFloatArrayRegion(mJniOutputBuffer, 0,
                                          numFrames * 2, (float *)audioData);

    auto endTime = std::chrono::steady_clock::now();
    double processingUs = std::chrono::duration_cast<std::chrono::microseconds>(
        endTime - startTime).count();
    double bufferUs = (double)numFrames / stream->getSampleRate() * 1e6;
    mCpuLoad.store(processingUs / bufferUs);

    return oboe::DataCallbackResult::Continue;
}

void OboeEngine::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) {
    LOGE("Stream disconnected: %s — reopening", oboe::convertToText(error));
    if (mIsRunning.load()) {
        // onErrorAfterClose runs on Oboe's error notification thread, NOT the
        // audio thread — we must attach this thread to get a valid JNIEnv.
        JNIEnv *env;
        mJvm->AttachCurrentThread(&env, nullptr);

        oboe::Result result = openStream();
        if (result == oboe::Result::OK) {
            // Reallocate JNI buffer — new stream may have different capacity
            if (mJniOutputBuffer) {
                int32_t newCapacity = mStream->getBufferCapacityInFrames();
                env->DeleteGlobalRef(mJniOutputBuffer);
                jfloatArray localBuffer = env->NewFloatArray(newCapacity * 2);
                mJniOutputBuffer = (jfloatArray)env->NewGlobalRef(localBuffer);
                env->DeleteLocalRef(localBuffer);
                LOGI("Reallocated JNI buffer: capacity=%d", newCapacity);
            }
            // Audio thread will re-attach itself on next onAudioReady
            mAudioThreadAttached.store(false);
            mAudioThreadEnv = nullptr;
            mIsRunning.store(true);
            mStream->requestStart();
        }

        mJvm->DetachCurrentThread();
    }
}
