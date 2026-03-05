#include <jni.h>
#include "OboeEngine.h"

static OboeEngine sEngine;

extern "C" {

JNIEXPORT jint JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeOpen(
        JNIEnv *env, jobject thiz) {
    return static_cast<jint>(sEngine.open(env, thiz));
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

JNIEXPORT jdouble JNICALL
Java_org_balch_orpheus_core_audio_dsp_OboeAudioBridge_nativeGetCpuLoad(
        JNIEnv *env, jobject thiz) {
    return sEngine.getCpuLoad();
}

} // extern "C"
