#include <jni.h>
#include "DesktopEngine.h"
#include <cstring>

static DesktopEngine sEngine;

// JNI function name macro for DesktopDspBridge
#define JNI_FN(name) Java_org_balch_orpheus_core_audio_dsp_DesktopDspBridge_##name

extern "C" {

// -- Lifecycle ------------------------------------------------------------

JNIEXPORT void JNICALL
JNI_FN(nativeOpen)(JNIEnv *env, jobject thiz, jint sampleRate) {
    sEngine.open(static_cast<float>(sampleRate));
}

JNIEXPORT jint JNICALL
JNI_FN(nativeLoadGraph)(JNIEnv *env, jobject thiz, jbyteArray serialized) {
    jbyte* data = env->GetByteArrayElements(serialized, nullptr);
    jsize len = env->GetArrayLength(serialized);
    jint result = sEngine.loadGraph(
        reinterpret_cast<const uint8_t*>(data),
        static_cast<size_t>(len));
    env->ReleaseByteArrayElements(serialized, data, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL
JNI_FN(nativeProcess)(JNIEnv *env, jobject thiz, jfloatArray buffer) {
    jsize totalFloats = env->GetArrayLength(buffer);
    // Buffer is interleaved stereo: numFrames = totalFloats / 2
    int numFrames = totalFloats / 2;
    jfloat* buf = env->GetFloatArrayElements(buffer, nullptr);
    sEngine.process(buf, numFrames);
    env->ReleaseFloatArrayElements(buffer, buf, 0); // 0 = copy back
}

JNIEXPORT void JNICALL
JNI_FN(nativeClose)(JNIEnv *env, jobject thiz) {
    sEngine.close();
}

// -- Query ----------------------------------------------------------------

JNIEXPORT jboolean JNICALL
JNI_FN(nativeIsRunning)(JNIEnv *env, jobject thiz) {
    return sEngine.isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
JNI_FN(nativeGetSampleRate)(JNIEnv *env, jobject thiz) {
    return sEngine.getSampleRate();
}

JNIEXPORT jdouble JNICALL
JNI_FN(nativeGetCpuLoad)(JNIEnv *env, jobject thiz) {
    return sEngine.getCpuLoad();
}

// -- Parameter control ----------------------------------------------------

JNIEXPORT void JNICALL
JNI_FN(nativeSetPort)(JNIEnv *env, jobject thiz, jstring uri, jstring sym, jfloat value) {
    const char* c_uri = env->GetStringUTFChars(uri, nullptr);
    const char* c_sym = env->GetStringUTFChars(sym, nullptr);
    sEngine.setPort(c_uri, c_sym, value);
    env->ReleaseStringUTFChars(uri, c_uri);
    env->ReleaseStringUTFChars(sym, c_sym);
}

JNIEXPORT jfloat JNICALL
JNI_FN(nativeGetPort)(JNIEnv *env, jobject thiz, jstring uri, jstring sym) {
    const char* c_uri = env->GetStringUTFChars(uri, nullptr);
    const char* c_sym = env->GetStringUTFChars(sym, nullptr);
    float result = sEngine.getPort(c_uri, c_sym);
    env->ReleaseStringUTFChars(uri, c_uri);
    env->ReleaseStringUTFChars(sym, c_sym);
    return result;
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetVoiceGate)(JNIEnv *env, jobject thiz, jint index, jboolean active) {
    sEngine.setVoiceGate(index, active ? 1 : 0);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetVoiceTune)(JNIEnv *env, jobject thiz, jint index, jfloat tune) {
    sEngine.setVoiceTune(index, tune);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetVoiceEngine)(JNIEnv *env, jobject thiz, jint index, jint engineIndex) {
    sEngine.setVoiceEngine(index, engineIndex);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetVoiceHarmonics)(JNIEnv *env, jobject thiz, jint index, jfloat value) {
    sEngine.setVoiceHarmonics(index, value);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetVoiceTimbre)(JNIEnv *env, jobject thiz, jint index, jfloat value) {
    sEngine.setVoiceTimbre(index, value);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetVoiceMorph)(JNIEnv *env, jobject thiz, jint index, jfloat value) {
    sEngine.setVoiceMorph(index, value);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetVoiceDecay)(JNIEnv *env, jobject thiz, jint index, jfloat value) {
    sEngine.setVoiceDecay(index, value);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetVoiceActive)(JNIEnv *env, jobject thiz, jint index, jboolean active) {
    sEngine.setVoiceActive(index, active ? 1 : 0);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetVoiceHold)(JNIEnv *env, jobject thiz, jint index, jfloat level) {
    sEngine.setVoiceHold(index, level);
}

JNIEXPORT void JNICALL
JNI_FN(nativeTriggerDrum)(JNIEnv *env, jobject thiz, jint drumIndex, jfloat accent) {
    sEngine.triggerDrum(drumIndex, accent);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetMasterVolume)(JNIEnv *env, jobject thiz, jfloat value) {
    sEngine.setMasterVolume(value);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetDrive)(JNIEnv *env, jobject thiz, jfloat value) {
    sEngine.setDrive(value);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetDelayMix)(JNIEnv *env, jobject thiz, jfloat value) {
    sEngine.setDelayMix(value);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetVibrato)(JNIEnv *env, jobject thiz, jfloat value) {
    sEngine.setVibrato(value);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetVibratoRate)(JNIEnv *env, jobject thiz, jfloat value) {
    sEngine.setVibratoRate(value);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetBend)(JNIEnv *env, jobject thiz, jfloat value) {
    sEngine.setBend(value);
}

JNIEXPORT void JNICALL
JNI_FN(nativeGetMonitor)(JNIEnv *env, jobject thiz, jfloatArray out) {
    OrpheusMonitorData mon;
    sEngine.getMonitor(&mon);
    // Copy struct as flat float array (all fields are float)
    env->SetFloatArrayRegion(out, 0, sizeof(mon) / sizeof(float),
                             reinterpret_cast<float*>(&mon));
}

} // extern "C"
