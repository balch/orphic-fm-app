#include <jni.h>
#include "OboeEngine.h"
#include <cstring>
#include <mutex>

static OboeEngine sEngine;

// JVM + global ref for the engine-recreated callback. Stored in C++ so the
// callback survives the JNI call that registered it. The Runnable.run()
// method is invoked from Oboe's error thread when the DSP engine is rebuilt.
static JavaVM* sCallbackJvm = nullptr;
static jobject sEngineRecreatedRunnable = nullptr;
// Cached at registration on a real JNI thread. Resolving the method id from the
// Oboe error thread would leak the jclass local ref (that thread is attached
// manually, so its local refs are not reclaimed until DetachCurrentThread).
static jmethodID sEngineRecreatedRun = nullptr;
// Guards the three globals above. Registration (main thread) can delete the
// global ref at the same moment Oboe's error thread is mid-upcall; without this
// the error thread would call through a freed ref. The lock is held across the
// upcall so the ref stays alive for its whole use. Oboe invokes the callback
// without holding any of its own locks (atomics only), so there is no
// lock-ordering hazard, and the Kotlin Runnable does not re-register the
// callback, so a non-recursive mutex cannot self-deadlock.
static std::mutex sCallbackMutex;

// Attach the calling (Oboe error) thread to the JVM if it isn't already.
// Sets *needsDetach so the caller can balance a fresh attach with a detach —
// leaving a thread we attached permanently attached leaks its thread-local JNI
// state and can crash the JVM if the thread later exits without detaching.
static JNIEnv* attachToJvm(bool* needsDetach) {
    *needsDetach = false;
    if (!sCallbackJvm) return nullptr;
    JNIEnv* env = nullptr;
    int status = sCallbackJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (sCallbackJvm->AttachCurrentThread(&env, nullptr) != 0) return nullptr;
        *needsDetach = true;
    } else if (status != JNI_OK) {
        return nullptr;
    }
    return env;
}

extern "C" {

JNIEXPORT jint JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeOpen(
        JNIEnv *env, jobject thiz) {
    return static_cast<jint>(sEngine.open());
}

JNIEXPORT jint JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeRequestStart(
        JNIEnv *env, jobject thiz) {
    return static_cast<jint>(sEngine.requestStart());
}

JNIEXPORT jint JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeStop(
        JNIEnv *env, jobject thiz) {
    return static_cast<jint>(sEngine.stop());
}

JNIEXPORT jboolean JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeIsRunning(
        JNIEnv *env, jobject thiz) {
    return sEngine.isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetSampleRate(
        JNIEnv *env, jobject thiz) {
    return sEngine.getSampleRate();
}

JNIEXPORT jint JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetFramesPerBuffer(
        JNIEnv *env, jobject thiz) {
    return sEngine.getFramesPerBuffer();
}

JNIEXPORT jint JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeLoadGraph(
        JNIEnv *env, jobject thiz, jbyteArray serialized) {
    jbyte* data = env->GetByteArrayElements(serialized, nullptr);
    if (data == nullptr) return -200;  // OOM pinning the array; leave the graph untouched
    jsize len = env->GetArrayLength(serialized);
    jint result = sEngine.loadGraph(
        reinterpret_cast<const uint8_t*>(data),
        static_cast<size_t>(len));
    env->ReleaseByteArrayElements(serialized, data, JNI_ABORT);
    return result;
}

JNIEXPORT jdouble JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetCpuLoad(
        JNIEnv *env, jobject thiz) {
    return sEngine.getCpuLoad();
}

JNIEXPORT jint JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetXRunCount(
        JNIEnv *env, jobject thiz) {
    return sEngine.getXRunCount();
}

// ── Parameter control ──────────────────────────

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetPort(
        JNIEnv *env, jobject thiz, jstring uri, jstring sym, jfloat value) {
    const char* c_uri = env->GetStringUTFChars(uri, nullptr);
    const char* c_sym = env->GetStringUTFChars(sym, nullptr);
    sEngine.setPort(c_uri, c_sym, value);
    env->ReleaseStringUTFChars(uri, c_uri);
    env->ReleaseStringUTFChars(sym, c_sym);
}

JNIEXPORT jfloat JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetPort(
        JNIEnv *env, jobject thiz, jstring uri, jstring sym) {
    const char* c_uri = env->GetStringUTFChars(uri, nullptr);
    const char* c_sym = env->GetStringUTFChars(sym, nullptr);
    float result = sEngine.getPort(c_uri, c_sym);
    env->ReleaseStringUTFChars(uri, c_uri);
    env->ReleaseStringUTFChars(sym, c_sym);
    return result;
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetVoiceGate(
        JNIEnv *env, jobject thiz, jint index, jboolean active) {
    sEngine.setVoiceGate(index, active ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetVoiceTune(
        JNIEnv *env, jobject thiz, jint index, jfloat tune) {
    sEngine.setVoiceTune(index, tune);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetVoiceEngine(
        JNIEnv *env, jobject thiz, jint index, jint engineIndex) {
    sEngine.setVoiceEngine(index, engineIndex);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetVoiceHarmonics(
        JNIEnv *env, jobject thiz, jint index, jfloat value) {
    sEngine.setVoiceHarmonics(index, value);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetVoiceTimbre(
        JNIEnv *env, jobject thiz, jint index, jfloat value) {
    sEngine.setVoiceTimbre(index, value);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetVoiceMorph(
        JNIEnv *env, jobject thiz, jint index, jfloat value) {
    sEngine.setVoiceMorph(index, value);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetVoiceDecay(
        JNIEnv *env, jobject thiz, jint index, jfloat value) {
    sEngine.setVoiceDecay(index, value);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetVoiceActive(
        JNIEnv *env, jobject thiz, jint index, jboolean active) {
    sEngine.setVoiceActive(index, active ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetVoiceHold(
        JNIEnv *env, jobject thiz, jint index, jfloat level) {
    sEngine.setVoiceHold(index, level);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeTriggerDrum(
        JNIEnv *env, jobject thiz, jint drumIndex, jfloat accent) {
    sEngine.triggerDrum(drumIndex, accent);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetMasterVolume(
        JNIEnv *env, jobject thiz, jfloat value) {
    sEngine.setMasterVolume(value);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeMasterFade(
        JNIEnv *env, jobject thiz, jfloat target, jint samples, jint curve) {
    sEngine.masterFade(target, samples, curve);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeMasterTapeStop(
        JNIEnv *env, jobject thiz, jint samples) {
    sEngine.masterTapeStop(samples);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeMasterScratch(
        JNIEnv *env, jobject thiz, jint samples) {
    sEngine.masterScratch(samples);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeMasterFilter(
        JNIEnv *env, jobject thiz, jint samples) {
    sEngine.masterFilter(samples);
}

JNIEXPORT jfloat JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeMasterVolumeNow(
        JNIEnv *env, jobject thiz) {
    return sEngine.masterVolumeNow();
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetDrive(
        JNIEnv *env, jobject thiz, jfloat value) {
    sEngine.setDrive(value);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetDelayMix(
        JNIEnv *env, jobject thiz, jfloat value) {
    sEngine.setDelayMix(value);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetVibrato(
        JNIEnv *env, jobject thiz, jfloat value) {
    sEngine.setVibrato(value);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetVibratoRate(
        JNIEnv *env, jobject thiz, jfloat value) {
    sEngine.setVibratoRate(value);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetBend(
        JNIEnv *env, jobject thiz, jfloat value) {
    sEngine.setBend(value);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetMonitor(
        JNIEnv *env, jobject thiz, jfloatArray out) {
    OrpheusMonitorData mon;
    sEngine.getMonitor(&mon);
    // Copy struct as flat float array (all fields are float)
    env->SetFloatArrayRegion(out, 0, sizeof(mon) / sizeof(float),
                             reinterpret_cast<float*>(&mon));
}

// -- Visualization ------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetViz(
        JNIEnv *env, jobject thiz,
        jint channel, jfloatArray outBuf, jintArray lastReadPos) {
    jint* rp = env->GetIntArrayElements(lastReadPos, nullptr);
    if (rp == nullptr) return 0;
    jfloat* buf = env->GetFloatArrayElements(outBuf, nullptr);
    if (buf == nullptr) {
        // Release the array we already pinned before bailing, else it leaks.
        env->ReleaseIntArrayElements(lastReadPos, rp, JNI_ABORT);
        return 0;
    }
    int count = sEngine.getViz(channel, buf, env->GetArrayLength(outBuf), rp);
    env->ReleaseFloatArrayElements(outBuf, buf, 0);
    env->ReleaseIntArrayElements(lastReadPos, rp, 0);
    return count;
}

JNIEXPORT jint JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetSpectrum(
        JNIEnv *env, jobject thiz, jfloatArray bands) {
    jfloat* buf = env->GetFloatArrayElements(bands, nullptr);
    int count = sEngine.getSpectrum(buf, env->GetArrayLength(bands));
    env->ReleaseFloatArrayElements(bands, buf, 0);
    return count;
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetPulsarViz(
        JNIEnv *env, jobject thiz,
        jbooleanArray gatesOut,
        jfloatArray velocitiesOut,
        jintArray playheadsOut,
        jintArray stepCountsOut) {
    // Must match kNumPulsarTracks and the viz EXPORT width kPulsarVizSteps (orpheus_engine.h),
    // decoupled from the sequencer cap kMaxPulsarSteps (now 64). The producer clamps its
    // export to kPulsarVizSteps; keep kSteps == it or these buffers overrun.
    constexpr int kTracks = 8;
    constexpr int kSteps = 32;   // == kPulsarVizSteps
    constexpr int kTotal = kTracks * kSteps;

    int gatesInt[kTotal] = {};
    float velocities[kTotal] = {};
    int playheads[kTracks] = {};
    int stepCounts[kTracks] = {};
    sEngine.getPulsarViz(gatesInt, velocities, playheads, stepCounts);

    jboolean gates[kTotal];
    for (int i = 0; i < kTotal; i++)
        gates[i] = gatesInt[i] ? JNI_TRUE : JNI_FALSE;
    env->SetBooleanArrayRegion(gatesOut, 0, kTotal, gates);
    env->SetFloatArrayRegion(velocitiesOut, 0, kTotal, velocities);
    env->SetIntArrayRegion(playheadsOut, 0, kTracks, reinterpret_cast<jint*>(playheads));
    env->SetIntArrayRegion(stepCountsOut, 0, kTracks, reinterpret_cast<jint*>(stepCounts));
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetPulsarActiveEngines(
        JNIEnv *env, jobject thiz, jintArray out) {
    int buf[8] = {};
    sEngine.getPulsarActiveEngines(buf);
    env->SetIntArrayRegion(out, 0, 8, reinterpret_cast<jint*>(buf));
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetPulsarArrangement(
        JNIEnv *env, jobject thiz, jintArray out) {
    int data[6] = {-1, 0, 0, 0, -1, 0};
    sEngine.getPulsarArrangement(data);
    env->SetIntArrayRegion(out, 0, 6, reinterpret_cast<jint*>(data));
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetTurntableViz(
        JNIEnv *env, jobject thiz,
        jint deck, jfloatArray outBuf) {
    jfloat* buf = env->GetFloatArrayElements(outBuf, nullptr);
    sEngine.getTurntableViz(deck, buf);
    env->ReleaseFloatArrayElements(outBuf, buf, 0);
}

// -- Automation ---------------------------------------------------------------

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetAutomation(
        JNIEnv *env, jobject thiz,
        jint target, jint voiceIndex,
        jfloatArray jtimes, jfloatArray jvalues,
        jint count) {
    jfloat* times = env->GetFloatArrayElements(jtimes, nullptr);
    if (times == nullptr) return;
    jfloat* values = env->GetFloatArrayElements(jvalues, nullptr);
    if (values == nullptr) {
        env->ReleaseFloatArrayElements(jtimes, times, JNI_ABORT);
        return;
    }
    sEngine.setAutomation(target, voiceIndex, times, values, count);
    env->ReleaseFloatArrayElements(jtimes, times, JNI_ABORT);
    env->ReleaseFloatArrayElements(jvalues, values, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeClearAutomation(
        JNIEnv *env, jobject thiz,
        jint target, jint voiceIndex) {
    sEngine.clearAutomation(target, voiceIndex);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeLoadTtsAudio(
        JNIEnv *env, jobject thiz,
        jfloatArray jsamples, jint sampleRate) {
    jint count = env->GetArrayLength(jsamples);
    jfloat* samples = env->GetFloatArrayElements(jsamples, nullptr);
    sEngine.loadTtsAudio(samples, count, sampleRate);
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativePlayTts(
        JNIEnv *env, jobject thiz) {
    sEngine.playTts();
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeStopTts(
        JNIEnv *env, jobject thiz) {
    sEngine.stopTts();
}

JNIEXPORT jint JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeIsTtsPlaying(
        JNIEnv *env, jobject thiz) {
    return sEngine.isTtsPlaying();
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeSetEngineRecreatedCallback(
        JNIEnv *env, jobject thiz, jobject runnable) {
    // Resolve Runnable.run() here, on a valid JNI thread, before taking the lock
    // (these lookups touch only local state).
    jclass cls = (runnable != nullptr) ? env->GetObjectClass(runnable) : nullptr;
    jmethodID run = (cls != nullptr) ? env->GetMethodID(cls, "run", "()V") : nullptr;
    if (cls != nullptr) env->DeleteLocalRef(cls);

    std::lock_guard<std::mutex> lock(sCallbackMutex);
    env->GetJavaVM(&sCallbackJvm);
    if (sEngineRecreatedRunnable) {
        env->DeleteGlobalRef(sEngineRecreatedRunnable);
        sEngineRecreatedRunnable = nullptr;
        sEngineRecreatedRun = nullptr;
    }
    if (runnable == nullptr || run == nullptr) {
        sEngine.setEngineRecreatedCallback(nullptr);
        return;
    }
    sEngineRecreatedRunnable = env->NewGlobalRef(runnable);
    sEngineRecreatedRun = run;

    sEngine.setEngineRecreatedCallback([]() {
        bool needsDetach = false;
        JNIEnv* e = attachToJvm(&needsDetach);
        if (e != nullptr) {
            // Hold the lock across the upcall so registration can't delete the
            // global ref out from under us while run() executes.
            std::lock_guard<std::mutex> lock(sCallbackMutex);
            if (sEngineRecreatedRunnable != nullptr && sEngineRecreatedRun != nullptr) {
                e->CallVoidMethod(sEngineRecreatedRunnable, sEngineRecreatedRun);
                // Never let an exception from the Runnable leak back into the JVM
                // on a native thread — that would abort at the next JNI call.
                if (e->ExceptionCheck()) e->ExceptionClear();
            }
        }
        if (needsDetach) sCallbackJvm->DetachCurrentThread();
    });
}

} // extern "C"
