#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <cstdint>

#include "mediapipe/tasks/c/vision/hand_landmarker/hand_landmarker.h"
#include "mediapipe/tasks/c/vision/gesture_recognizer/gesture_recognizer.h"
#include "mediapipe/tasks/c/vision/core/image.h"
#include "mediapipe/tasks/c/vision/core/image_processing_options.h"
#include "mediapipe/tasks/c/core/mp_status.h"

/*
 * Combined JNI shim for MediaPipe HandLandmarker + GestureRecognizer.
 * All MediaPipe symbols are resolved at link time (no dlopen).
 * Both APIs share a single dylib and JVM reference.
 *
 * HandLandmarker callback format (packed float array):
 *   [numHands, per-hand(handedness, 21*xyz)]
 *   Per hand: 1 + 63 = 64 floats
 *
 * GestureRecognizer callback format:
 *   Float array: [numHands, per-hand(handedness, gestureScore, 21*xyz)]
 *   Per hand: 1 + 1 + 63 = 65 floats
 *   Plus a separate String[] of gesture names (one per hand).
 */

static JavaVM* g_jvm = nullptr;

/* --- Hand Landmarker state --- */
static jobject g_hl_callback = nullptr;
static jmethodID g_hl_onResult = nullptr;

/* --- Gesture Recognizer state --- */
static jobject g_gr_callback = nullptr;
static jmethodID g_gr_onResult = nullptr;  /* onResult(float[], String[], long) */

/* Helper: pack a GestureRecognizerResult into the JNI float array format and
 * call the Java callback.  Used by the VIDEO-mode synchronous path. */
static void gr_deliver_result(JNIEnv* env, const GestureRecognizerResult* result,
                              int64_t timestamp_ms);

static void throw_exception(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(cls, msg);
}

/* Helper: attach to JVM if needed, returns env and sets needs_detach flag */
static JNIEnv* attach_jvm(int* needs_detach) {
    *needs_detach = 0;
    JNIEnv* env = nullptr;
    jint result = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != 0) {
            return nullptr;
        }
        *needs_detach = 1;
    } else if (result != JNI_OK) {
        return nullptr;
    }
    return env;
}

/* ========================================================================
 * Hand Landmarker
 * ======================================================================== */

static void hl_on_result(MpStatus status, const HandLandmarkerResult* result,
                         MpImagePtr image, int64_t timestamp_ms) {
    if (g_jvm == nullptr || g_hl_callback == nullptr) return;

    int needs_detach = 0;
    JNIEnv* env = attach_jvm(&needs_detach);
    if (!env) return;

    jfloatArray jResult = nullptr;

    if (status == kMpOk && result != nullptr && result->hand_landmarks_count > 0) {
        int numHands = (int)result->hand_landmarks_count;
        if (numHands > 2) numHands = 2;

        int arraySize = 1 + numHands * 64;
        jResult = env->NewFloatArray(arraySize);
        jfloat* buf = env->GetFloatArrayElements(jResult, nullptr);

        buf[0] = (float)numHands;

        for (int h = 0; h < numHands; h++) {
            int base = 1 + h * 64;

            float handednessValue = 0.0f;
            if (h < (int)result->handedness_count &&
                result->handedness[h].categories_count > 0) {
                const char* name = result->handedness[h].categories[0].category_name;
                if (name && name[0] == 'R') {
                    handednessValue = 1.0f;
                }
            }
            buf[base] = handednessValue;

            struct NormalizedLandmarks* lms = &result->hand_landmarks[h];
            for (unsigned int i = 0; i < lms->landmarks_count && i < 21; i++) {
                buf[base + 1 + i * 3]     = lms->landmarks[i].x;
                buf[base + 1 + i * 3 + 1] = lms->landmarks[i].y;
                buf[base + 1 + i * 3 + 2] = lms->landmarks[i].z;
            }
        }

        env->ReleaseFloatArrayElements(jResult, buf, 0);
    }

    env->CallVoidMethod(g_hl_callback, g_hl_onResult,
                        jResult, static_cast<jlong>(timestamp_ms));

    if (jResult != nullptr) env->DeleteLocalRef(jResult);
    if (needs_detach) g_jvm->DetachCurrentThread();
}

/* ========================================================================
 * Gesture Recognizer
 * ======================================================================== */

/* Pack result into JNI float array + gesture name strings and call Java callback.
 * env must already be attached.
 *
 * Float array format (changed from original):
 *   [numHands, per-hand(handedness, gestureScore, 21*xyz)]
 *   Per hand: 1 + 1 + 63 = 65 floats
 *
 * Gesture names are passed as a separate String[] (one per hand), read
 * directly from category_name each frame. No name-table indirection. */
static void gr_deliver_result(JNIEnv* env, const GestureRecognizerResult* result,
                              int64_t timestamp_ms) {
    jfloatArray jResult = nullptr;
    jobjectArray jNames = nullptr;

    if (result != nullptr && result->hand_landmarks_count > 0) {
        int numHands = (int)result->hand_landmarks_count;
        if (numHands > 2) numHands = 2;

        int perHand = 65;
        int arraySize = 1 + numHands * perHand;

        jResult = env->NewFloatArray(arraySize);
        jfloat* buf = env->GetFloatArrayElements(jResult, nullptr);

        jclass stringClass = env->FindClass("java/lang/String");
        jNames = env->NewObjectArray((jsize)numHands, stringClass, nullptr);

        buf[0] = (float)numHands;

        for (int h = 0; h < numHands; h++) {
            int base = 1 + h * perHand;

            float handednessValue = 0.0f;
            if (h < (int)result->handedness_count &&
                result->handedness[h].categories_count > 0) {
                const char* name = result->handedness[h].categories[0].category_name;
                if (name && name[0] == 'R') {
                    handednessValue = 1.0f;
                }
            }
            buf[base] = handednessValue;

            float gestureScore = 0.0f;
            const char* gestureName = nullptr;
            if (h < (int)result->gestures_count &&
                result->gestures[h].categories_count > 0) {
                gestureName = result->gestures[h].categories[0].category_name;
                gestureScore = result->gestures[h].categories[0].score;
            }
            buf[base + 1] = gestureScore;

            // Pass gesture name string directly — no index indirection
            if (gestureName != nullptr) {
                jstring jname = env->NewStringUTF(gestureName);
                env->SetObjectArrayElement(jNames, (jsize)h, jname);
                env->DeleteLocalRef(jname);
            }

            struct NormalizedLandmarks* lms = &result->hand_landmarks[h];
            for (unsigned int i = 0; i < lms->landmarks_count && i < 21; i++) {
                buf[base + 2 + i * 3]     = lms->landmarks[i].x;
                buf[base + 2 + i * 3 + 1] = lms->landmarks[i].y;
                buf[base + 2 + i * 3 + 2] = lms->landmarks[i].z;
            }
        }

        env->ReleaseFloatArrayElements(jResult, buf, 0);
    }

    env->CallVoidMethod(g_gr_callback, g_gr_onResult,
                        jResult, jNames, static_cast<jlong>(timestamp_ms));

    if (jResult != nullptr) env->DeleteLocalRef(jResult);
    if (jNames != nullptr) env->DeleteLocalRef(jNames);
}

/* ========================================================================
 * JNI exports
 * ======================================================================== */

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

/* --- Hand Landmarker --- */

JNIEXPORT jlong JNICALL
Java_org_balch_orpheus_core_mediapipe_MediaPipeJni_nativeCreateLandmarker(
    JNIEnv* env, jclass cls, jstring modelPath, jobject callback) {

    if (g_hl_callback != nullptr) {
        env->DeleteGlobalRef(g_hl_callback);
    }
    g_hl_callback = env->NewGlobalRef(callback);

    jclass callbackClass = env->GetObjectClass(callback);
    g_hl_onResult = env->GetMethodID(callbackClass, "onResult", "([FJ)V");
    if (g_hl_onResult == nullptr) {
        throw_exception(env, "ResultCallback.onResult([FJ)V method not found");
        return 0;
    }

    const char* model = env->GetStringUTFChars(modelPath, nullptr);

    struct HandLandmarkerOptions options;
    memset(&options, 0, sizeof(options));
    options.base_options.model_asset_path = model;
    options.running_mode = LIVE_STREAM;
    options.num_hands = 2;
    options.min_hand_detection_confidence = 0.5f;
    options.min_hand_presence_confidence = 0.5f;
    options.min_tracking_confidence = 0.5f;
    options.result_callback = hl_on_result;

    MpHandLandmarkerPtr landmarker = nullptr;
    char* error_msg = nullptr;
    MpStatus status = MpHandLandmarkerCreate(&options, &landmarker, &error_msg);

    env->ReleaseStringUTFChars(modelPath, model);

    if (status != kMpOk) {
        char buf[512];
        snprintf(buf, sizeof(buf), "MpHandLandmarkerCreate failed: %s",
                 error_msg ? error_msg : "unknown error");
        if (error_msg) free(error_msg);
        throw_exception(env, buf);
        return 0;
    }

    return reinterpret_cast<jlong>(landmarker);
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_mediapipe_MediaPipeJni_nativeDetectAsync(
    JNIEnv* env, jclass cls, jlong landmarkerPtr,
    jbyteArray pixelData, jint width, jint height, jlong timestampMs) {

    auto landmarker = reinterpret_cast<MpHandLandmarkerPtr>(landmarkerPtr);

    jbyte* pixels = env->GetByteArrayElements(pixelData, nullptr);
    int dataSize = width * height * 3;

    MpImagePtr image = nullptr;
    char* error_msg = nullptr;
    MpStatus status = MpImageCreateFromUint8Data(
        kMpImageFormatSrgb, width, height,
        reinterpret_cast<const uint8_t*>(pixels), dataSize,
        &image, &error_msg);

    env->ReleaseByteArrayElements(pixelData, pixels, JNI_ABORT);

    if (status != kMpOk) {
        if (error_msg) free(error_msg);
        return;
    }

    status = MpHandLandmarkerDetectAsync(landmarker, image, nullptr,
                                          timestampMs, &error_msg);

    if (status != kMpOk) {
        if (error_msg) free(error_msg);
        MpImageFree(image);
    }
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_mediapipe_MediaPipeJni_nativeCloseLandmarker(
    JNIEnv* env, jclass cls, jlong landmarkerPtr) {

    auto landmarker = reinterpret_cast<MpHandLandmarkerPtr>(landmarkerPtr);
    char* error_msg = nullptr;
    MpHandLandmarkerClose(landmarker, &error_msg);
    if (error_msg) free(error_msg);

    if (g_hl_callback != nullptr) {
        env->DeleteGlobalRef(g_hl_callback);
        g_hl_callback = nullptr;
        g_hl_onResult = nullptr;
    }
}

/* --- Gesture Recognizer --- */

JNIEXPORT jlong JNICALL
Java_org_balch_orpheus_core_mediapipe_MediaPipeJni_nativeCreateGestureRecognizer(
    JNIEnv* env, jclass cls, jstring modelPath, jint numHands, jobject callback) {

    if (g_gr_callback != nullptr) {
        env->DeleteGlobalRef(g_gr_callback);
    }
    g_gr_callback = env->NewGlobalRef(callback);

    jclass callbackClass = env->GetObjectClass(callback);
    /* New signature: onResult(float[], String[], long) */
    g_gr_onResult = env->GetMethodID(callbackClass, "onResult",
                                     "([F[Ljava/lang/String;J)V");
    if (g_gr_onResult == nullptr) {
        throw_exception(env, "GestureCallback.onResult([F[Ljava/lang/String;J)V not found");
        return 0;
    }

    const char* model = env->GetStringUTFChars(modelPath, nullptr);

    struct GestureRecognizerOptions options;
    memset(&options, 0, sizeof(options));
    options.base_options.model_asset_path = model;
    // Use VIDEO mode (synchronous) instead of LIVE_STREAM (async) to avoid
    // a crash in Holder<Eigen::Matrix>::~Holder() during ClearCurrentInputs.
    // The GestureRecognizer graph uses LandmarksToMatrixCalculator which
    // creates intermediate Matrix packets that get double-freed in the async
    // callback flow. VIDEO mode processes synchronously, sidestepping this.
    options.running_mode = VIDEO;
    options.num_hands = (int)numHands;
    options.min_hand_detection_confidence = 0.5f;
    options.min_hand_presence_confidence = 0.5f;
    options.min_tracking_confidence = 0.5f;
    // No result_callback — VIDEO mode returns results synchronously.

    MpGestureRecognizerPtr recognizer = nullptr;
    char* error_msg = nullptr;
    MpStatus status = MpGestureRecognizerCreate(&options, &recognizer, &error_msg);

    env->ReleaseStringUTFChars(modelPath, model);

    if (status != kMpOk) {
        char buf[512];
        snprintf(buf, sizeof(buf), "MpGestureRecognizerCreate failed: %s",
                 error_msg ? error_msg : "unknown error");
        if (error_msg) free(error_msg);
        throw_exception(env, buf);
        return 0;
    }

    return reinterpret_cast<jlong>(recognizer);
}

JNIEXPORT jboolean JNICALL
Java_org_balch_orpheus_core_mediapipe_MediaPipeJni_nativeRecognizeGestureForVideo(
    JNIEnv* env, jclass cls, jlong recognizerPtr,
    jbyteArray pixelData, jint width, jint height, jlong timestampMs) {

    auto recognizer = reinterpret_cast<MpGestureRecognizerPtr>(recognizerPtr);

    jbyte* pixels = env->GetByteArrayElements(pixelData, nullptr);
    int dataSize = width * height * 3;

    MpImagePtr image = nullptr;
    char* error_msg = nullptr;
    MpStatus status = MpImageCreateFromUint8Data(
        kMpImageFormatSrgb, width, height,
        reinterpret_cast<const uint8_t*>(pixels), dataSize,
        &image, &error_msg);

    env->ReleaseByteArrayElements(pixelData, pixels, JNI_ABORT);

    if (status != kMpOk) {
        fprintf(stderr, "[MediaPipe JNI] GR image create failed: %s\n",
                error_msg ? error_msg : "unknown");
        if (error_msg) free(error_msg);
        return JNI_FALSE;
    }

    // Synchronous recognition — blocks until result is available.
    GestureRecognizerResult result;
    memset(&result, 0, sizeof(result));
    status = MpGestureRecognizerRecognizeForVideo(recognizer, image, nullptr,
                                                   timestampMs, &result, &error_msg);

    if (status != kMpOk) {
        fprintf(stderr, "[JNI] GR err: %s\n", error_msg ? error_msg : "?");
        if (error_msg) free(error_msg);
        return JNI_FALSE;
    } else {
        gr_deliver_result(env, &result, timestampMs);
        MpGestureRecognizerCloseResult(&result);
        return JNI_TRUE;
    }
}

JNIEXPORT void JNICALL
Java_org_balch_orpheus_core_mediapipe_MediaPipeJni_nativeCloseGestureRecognizer(
    JNIEnv* env, jclass cls, jlong recognizerPtr) {

    auto recognizer = reinterpret_cast<MpGestureRecognizerPtr>(recognizerPtr);
    char* error_msg = nullptr;
    MpGestureRecognizerClose(recognizer, &error_msg);
    if (error_msg) free(error_msg);

    if (g_gr_callback != nullptr) {
        env->DeleteGlobalRef(g_gr_callback);
        g_gr_callback = nullptr;
        g_gr_onResult = nullptr;
    }
}

}  /* extern "C" */
