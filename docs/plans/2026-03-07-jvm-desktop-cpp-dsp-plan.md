# JVM Desktop C++ DSP Engine — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Run liborpheus_dsp on JVM desktop via JNI, with a runtime toggle to switch between JSyn and C++ engines for A/B testing.

**Architecture:** A shared library (`.dylib`) wraps liborpheus_dsp with JNI functions. A Kotlin `NativeDspAudioEngine` opens a `javax.sound.sampled.SourceDataLine` and drives audio via `nativeProcess()`. DI toggle via `-Dorpheus.engine=cpp` system property.

**Tech Stack:** C++17, CMake, JNI, javax.sound.sampled, Metro DI, Kotlin/JVM

---

### Task 1: C++ Desktop Engine Wrapper

**Files:**
- Create: `liborpheus_dsp/desktop/DesktopEngine.h`
- Create: `liborpheus_dsp/desktop/DesktopEngine.cpp`

**Step 1: Create DesktopEngine.h**

This is a simplified version of `apps/androidApp/src/main/cpp/OboeEngine.h` — no Oboe, no audio callbacks.

```cpp
// liborpheus_dsp/desktop/DesktopEngine.h
#ifndef ORPHEUS_DESKTOP_ENGINE_H
#define ORPHEUS_DESKTOP_ENGINE_H

#include "orpheus_dsp.h"
#include <atomic>
#include <chrono>

class DesktopEngine {
public:
    DesktopEngine();
    ~DesktopEngine();

    int open(float sampleRate);
    int loadGraph(const uint8_t* data, size_t length);
    void process(float* outputBuffer, int numFrames);
    void close();

    bool isRunning() const;
    int getSampleRate() const;
    double getCpuLoad() const;

    // C API pass-throughs (same as OboeEngine)
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
    void setDrive(float v);
    void setDelayMix(float v);
    void setVibrato(float v);
    void setBend(float v);
    void getMonitor(OrpheusMonitorData* out);

private:
    OrpheusEngine* dsp_engine_ = nullptr;
    std::atomic<bool> mIsRunning{false};
    std::atomic<double> mCpuLoad{0.0};
    float mSampleRate = 0.0f;
};

#endif
```

**Step 2: Create DesktopEngine.cpp**

Mirror `apps/androidApp/src/main/cpp/OboeEngine.cpp` but strip all Oboe code. The `process()` method is called by Kotlin (pull model).

```cpp
// liborpheus_dsp/desktop/DesktopEngine.cpp
#include "DesktopEngine.h"
#include <cstring>
#include <chrono>

DesktopEngine::DesktopEngine() = default;

DesktopEngine::~DesktopEngine() { close(); }

int DesktopEngine::open(float sampleRate) {
    mSampleRate = sampleRate;
    dsp_engine_ = orpheus_engine_create(sampleRate);
    if (!dsp_engine_) return -1;
    mIsRunning.store(true);
    return 0;
}

int DesktopEngine::loadGraph(const uint8_t* data, size_t length) {
    if (!dsp_engine_) return -100;
    return orpheus_engine_load_patch(dsp_engine_, data, length);
}

void DesktopEngine::process(float* outputBuffer, int numFrames) {
    if (!dsp_engine_ || !mIsRunning.load()) {
        memset(outputBuffer, 0, numFrames * 2 * sizeof(float));
        return;
    }
    auto start = std::chrono::steady_clock::now();
    orpheus_engine_process(dsp_engine_, outputBuffer, numFrames);
    auto end = std::chrono::steady_clock::now();
    double us = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
    double budget = static_cast<double>(numFrames) / mSampleRate * 1e6;
    mCpuLoad.store(us / budget);
}

void DesktopEngine::close() {
    mIsRunning.store(false);
    if (dsp_engine_) {
        orpheus_engine_destroy(dsp_engine_);
        dsp_engine_ = nullptr;
    }
}

bool DesktopEngine::isRunning() const { return mIsRunning.load(); }
int DesktopEngine::getSampleRate() const { return static_cast<int>(mSampleRate); }
double DesktopEngine::getCpuLoad() const { return mCpuLoad.load(); }

// All pass-throughs identical to OboeEngine.cpp
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
void DesktopEngine::setDrive(float v) {
    if (dsp_engine_) orpheus_engine_set_drive(dsp_engine_, v);
}
void DesktopEngine::setDelayMix(float v) {
    if (dsp_engine_) orpheus_engine_set_delay_mix(dsp_engine_, v);
}
void DesktopEngine::setVibrato(float v) {
    if (dsp_engine_) orpheus_engine_set_vibrato(dsp_engine_, v);
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
```

**Step 3: Commit**

```bash
git add liborpheus_dsp/desktop/
git commit -m "feat(dsp): Add DesktopEngine C++ wrapper (no audio I/O)"
```

---

### Task 2: JNI Bridge for Desktop

**Files:**
- Create: `liborpheus_dsp/desktop/jni_bridge_desktop.cpp`

**Step 1: Create the JNI bridge**

Mirror `apps/androidApp/src/main/cpp/jni_bridge.cpp` with these differences:
- JNI class: `org_balch_orpheus_core_audio_dsp_DesktopDspBridge`
- No Android log — use stderr or no logging
- Add `nativeProcess(float[])` for pull-model audio
- `nativeOpen` takes `sampleRate` parameter (desktop sets this, unlike Oboe which negotiates)

```cpp
// liborpheus_dsp/desktop/jni_bridge_desktop.cpp
#include <jni.h>
#include "DesktopEngine.h"
#include <cstring>

static DesktopEngine sEngine;

#define JNI_FN(name) Java_org_balch_orpheus_core_audio_dsp_DesktopDspBridge_##name

extern "C" {

JNIEXPORT jint JNICALL JNI_FN(nativeOpen)(JNIEnv*, jobject, jint sampleRate) {
    return sEngine.open(static_cast<float>(sampleRate));
}

JNIEXPORT void JNICALL JNI_FN(nativeClose)(JNIEnv*, jobject) {
    sEngine.close();
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsRunning)(JNIEnv*, jobject) {
    return sEngine.isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_FN(nativeGetSampleRate)(JNIEnv*, jobject) {
    return sEngine.getSampleRate();
}

JNIEXPORT jdouble JNICALL JNI_FN(nativeGetCpuLoad)(JNIEnv*, jobject) {
    return sEngine.getCpuLoad();
}

JNIEXPORT jint JNICALL JNI_FN(nativeLoadGraph)(JNIEnv* env, jobject, jbyteArray serialized) {
    jbyte* data = env->GetByteArrayElements(serialized, nullptr);
    jsize len = env->GetArrayLength(serialized);
    jint result = sEngine.loadGraph(
        reinterpret_cast<const uint8_t*>(data), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(serialized, data, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL JNI_FN(nativeProcess)(JNIEnv* env, jobject, jfloatArray buffer) {
    jsize len = env->GetArrayLength(buffer);
    int numFrames = len / 2;  // stereo interleaved
    jfloat* data = env->GetFloatArrayElements(buffer, nullptr);
    sEngine.process(data, numFrames);
    env->ReleaseFloatArrayElements(buffer, data, 0);  // 0 = copy back
}

// ── Parameter control ──────────────────────────

JNIEXPORT void JNICALL JNI_FN(nativeSetPort)(
        JNIEnv* env, jobject, jstring uri, jstring sym, jfloat value) {
    const char* c_uri = env->GetStringUTFChars(uri, nullptr);
    const char* c_sym = env->GetStringUTFChars(sym, nullptr);
    sEngine.setPort(c_uri, c_sym, value);
    env->ReleaseStringUTFChars(uri, c_uri);
    env->ReleaseStringUTFChars(sym, c_sym);
}

JNIEXPORT jfloat JNICALL JNI_FN(nativeGetPort)(
        JNIEnv* env, jobject, jstring uri, jstring sym) {
    const char* c_uri = env->GetStringUTFChars(uri, nullptr);
    const char* c_sym = env->GetStringUTFChars(sym, nullptr);
    float result = sEngine.getPort(c_uri, c_sym);
    env->ReleaseStringUTFChars(uri, c_uri);
    env->ReleaseStringUTFChars(sym, c_sym);
    return result;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetVoiceGate)(JNIEnv*, jobject, jint i, jboolean a) {
    sEngine.setVoiceGate(i, a ? 1 : 0);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetVoiceTune)(JNIEnv*, jobject, jint i, jfloat v) {
    sEngine.setVoiceTune(i, v);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetVoiceEngine)(JNIEnv*, jobject, jint i, jint e) {
    sEngine.setVoiceEngine(i, e);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetVoiceHarmonics)(JNIEnv*, jobject, jint i, jfloat v) {
    sEngine.setVoiceHarmonics(i, v);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetVoiceTimbre)(JNIEnv*, jobject, jint i, jfloat v) {
    sEngine.setVoiceTimbre(i, v);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetVoiceMorph)(JNIEnv*, jobject, jint i, jfloat v) {
    sEngine.setVoiceMorph(i, v);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetVoiceDecay)(JNIEnv*, jobject, jint i, jfloat v) {
    sEngine.setVoiceDecay(i, v);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetVoiceActive)(JNIEnv*, jobject, jint i, jboolean a) {
    sEngine.setVoiceActive(i, a ? 1 : 0);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetVoiceHold)(JNIEnv*, jobject, jint i, jfloat v) {
    sEngine.setVoiceHold(i, v);
}
JNIEXPORT void JNICALL JNI_FN(nativeTriggerDrum)(JNIEnv*, jobject, jint i, jfloat a) {
    sEngine.triggerDrum(i, a);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetMasterVolume)(JNIEnv*, jobject, jfloat v) {
    sEngine.setMasterVolume(v);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetDrive)(JNIEnv*, jobject, jfloat v) {
    sEngine.setDrive(v);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetDelayMix)(JNIEnv*, jobject, jfloat v) {
    sEngine.setDelayMix(v);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetVibrato)(JNIEnv*, jobject, jfloat v) {
    sEngine.setVibrato(v);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetBend)(JNIEnv*, jobject, jfloat v) {
    sEngine.setBend(v);
}
JNIEXPORT void JNICALL JNI_FN(nativeGetMonitor)(JNIEnv* env, jobject, jfloatArray out) {
    OrpheusMonitorData mon;
    sEngine.getMonitor(&mon);
    env->SetFloatArrayRegion(out, 0,
        static_cast<jsize>(sizeof(mon) / sizeof(float)),
        reinterpret_cast<float*>(&mon));
}

} // extern "C"
```

**Step 2: Commit**

```bash
git add liborpheus_dsp/desktop/jni_bridge_desktop.cpp
git commit -m "feat(dsp): Add JNI bridge for desktop (pull-model audio)"
```

---

### Task 3: Desktop CMake Build

**Files:**
- Create: `liborpheus_dsp/desktop/CMakeLists.txt`

**Step 1: Create the CMakeLists**

Builds a shared library linking liborpheus_dsp (static) + JNI headers.

```cmake
# liborpheus_dsp/desktop/CMakeLists.txt
cmake_minimum_required(VERSION 3.22)
project(orpheus_desktop CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find JNI headers from JAVA_HOME
find_package(JNI REQUIRED)

# liborpheus_dsp (static library from parent)
if(NOT DEFINED EURORACK_DIR)
    if(DEFINED ENV{EURORACK_DIR})
        set(EURORACK_DIR $ENV{EURORACK_DIR})
    else()
        set(EURORACK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../eurorack")
    endif()
endif()
add_subdirectory(
    "${CMAKE_CURRENT_SOURCE_DIR}/.."
    "${CMAKE_CURRENT_BINARY_DIR}/liborpheus_dsp"
)

# Desktop JNI shared library
add_library(orpheus_desktop SHARED
    DesktopEngine.cpp
    jni_bridge_desktop.cpp
)

target_include_directories(orpheus_desktop PRIVATE
    ${JNI_INCLUDE_DIRS}
    "${CMAKE_CURRENT_SOURCE_DIR}/../include"
)

target_link_libraries(orpheus_desktop orpheus_dsp)

target_compile_options(orpheus_desktop PRIVATE
    -Wall
    "$<$<CONFIG:Release>:-O3>"
    "$<$<CONFIG:Release>:-ffast-math>"
)

# macOS: set install name for dylib loading
if(APPLE)
    set_target_properties(orpheus_desktop PROPERTIES
        INSTALL_RPATH "@loader_path"
        BUILD_WITH_INSTALL_RPATH TRUE
    )
endif()
```

**Step 2: Test the build**

```bash
cd liborpheus_dsp/desktop
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build
# Verify: build/liborpheus_desktop.dylib exists
ls -la build/liborpheus_desktop.dylib
```

**Step 3: Commit**

```bash
git add liborpheus_dsp/desktop/CMakeLists.txt
git commit -m "build: Add CMake for desktop JNI shared library"
```

---

### Task 4: Kotlin DesktopDspBridge

**Files:**
- Create: `core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/DesktopDspBridge.kt`

**Step 1: Create the bridge class**

Same shape as `OboeAudioBridge.kt` but loads `orpheus_desktop` and adds `nativeProcess`.
Library loading: extract from JAR resource or load from a known path.

```kotlin
// core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/DesktopDspBridge.kt
package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging

/**
 * JNI bridge to liborpheus_dsp for JVM desktop.
 * Pull-model: Kotlin audio thread calls nativeProcess() to fill buffers.
 */
class DesktopDspBridge {
    companion object {
        private val log = logging("DesktopDspBridge")

        init {
            val libName = System.mapLibraryName("orpheus_desktop")
            // Try loading from system path first (development),
            // fall back to extracting from JAR resources (packaged)
            try {
                System.loadLibrary("orpheus_desktop")
                log.info { "Loaded orpheus_desktop from system library path" }
            } catch (e: UnsatisfiedLinkError) {
                val tempDir = java.nio.file.Files.createTempDirectory("orpheus-native").toFile()
                tempDir.deleteOnExit()
                val tempLib = java.io.File(tempDir, libName)
                val arch = System.getProperty("os.arch").let {
                    if (it == "aarch64" || it == "arm64") "aarch64" else "x86_64"
                }
                val os = System.getProperty("os.name").lowercase().let {
                    when {
                        "mac" in it || "darwin" in it -> "darwin"
                        "linux" in it -> "linux"
                        "win" in it -> "windows"
                        else -> it
                    }
                }
                val resourcePath = "/native/$os-$arch/$libName"
                val stream = DesktopDspBridge::class.java.getResourceAsStream(resourcePath)
                    ?: throw UnsatisfiedLinkError(
                        "Cannot find native library at $resourcePath")
                stream.use { input ->
                    tempLib.outputStream().use { output -> input.copyTo(output) }
                }
                tempLib.deleteOnExit()
                System.load(tempLib.absolutePath)
                log.info { "Loaded orpheus_desktop from JAR resource: $resourcePath" }
            }
        }
    }

    // Lifecycle
    external fun nativeOpen(sampleRate: Int): Int
    external fun nativeClose()
    external fun nativeIsRunning(): Boolean
    external fun nativeGetSampleRate(): Int
    external fun nativeGetCpuLoad(): Double

    // Audio render (pull model — Kotlin audio thread calls this)
    external fun nativeProcess(buffer: FloatArray)

    // Graph
    external fun nativeLoadGraph(serialized: ByteArray): Int

    // Parameter control
    external fun nativeSetPort(uri: String, symbol: String, value: Float)
    external fun nativeGetPort(uri: String, symbol: String): Float
    external fun nativeSetVoiceGate(index: Int, active: Boolean)
    external fun nativeSetVoiceTune(index: Int, tune: Float)
    external fun nativeSetVoiceEngine(index: Int, engineIndex: Int)
    external fun nativeSetVoiceHarmonics(index: Int, value: Float)
    external fun nativeSetVoiceTimbre(index: Int, value: Float)
    external fun nativeSetVoiceMorph(index: Int, value: Float)
    external fun nativeSetVoiceDecay(index: Int, value: Float)
    external fun nativeSetVoiceActive(index: Int, active: Boolean)
    external fun nativeSetVoiceHold(index: Int, level: Float)
    external fun nativeTriggerDrum(drumIndex: Int, accent: Float)
    external fun nativeSetMasterVolume(value: Float)
    external fun nativeSetDrive(value: Float)
    external fun nativeSetDelayMix(value: Float)
    external fun nativeSetVibrato(value: Float)
    external fun nativeSetBend(value: Float)
    external fun nativeGetMonitor(out: FloatArray)
}
```

**Step 2: Commit**

```bash
git add core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/DesktopDspBridge.kt
git commit -m "feat(audio): Add DesktopDspBridge JNI declarations for JVM desktop"
```

---

### Task 5: NativeDspAudioEngine (Kotlin audio thread)

**Files:**
- Create: `core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/NativeDspAudioEngine.kt`

**Step 1: Create the engine class**

Implements `AudioEngine` + `NativeDspBridge`. Opens a `javax.sound.sampled.SourceDataLine`,
runs an audio thread that pulls from C++ via `nativeProcess()`.

```kotlin
// core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/NativeDspAudioEngine.kt
package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * JVM desktop AudioEngine backed by C++ liborpheus_dsp.
 * Audio output via javax.sound.sampled, DSP via JNI pull-model.
 */
class NativeDspAudioEngine : AudioEngine, NativeDspBridge {
    private val bridge = DesktopDspBridge()
    private var audioThread: Thread? = null
    private var sourceLine: SourceDataLine? = null
    @Volatile private var running = false

    private companion object {
        private val log = logging("NativeDspAudioEngine")
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val BUFFER_FRAMES = 512 // ~10ms at 48kHz
    }

    override fun start() {
        if (running) return
        log.info { "start() — opening C++ engine at ${SAMPLE_RATE}Hz" }

        val result = bridge.nativeOpen(SAMPLE_RATE)
        if (result != 0) {
            log.error { "nativeOpen failed: $result" }
            return
        }

        // Open Java Sound output line
        val format = AudioFormat(
            AudioFormat.Encoding.PCM_FLOAT,
            SAMPLE_RATE.toFloat(),
            32,       // bits per sample (float32)
            CHANNELS,
            CHANNELS * 4, // frame size in bytes
            SAMPLE_RATE.toFloat(),
            false     // little-endian
        )
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format, BUFFER_FRAMES * CHANNELS * 4 * 2) // 2x buffer for headroom
        line.start()
        sourceLine = line
        running = true

        log.info { "Audio line opened: ${line.format}" }

        // Audio thread: pull from C++, write to Java Sound
        audioThread = Thread({
            val floatBuf = FloatArray(BUFFER_FRAMES * CHANNELS)
            val byteBuf = ByteArray(BUFFER_FRAMES * CHANNELS * 4)
            val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN)

            while (running) {
                bridge.nativeProcess(floatBuf)
                bb.clear()
                bb.asFloatBuffer().put(floatBuf)
                line.write(byteBuf, 0, byteBuf.size)
            }
        }, "OrpheusAudio").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
        audioThread!!.start()
        log.info { "Audio thread started" }
    }

    override fun stop() {
        running = false
        audioThread?.join(1000)
        audioThread = null
        sourceLine?.stop()
        sourceLine?.close()
        sourceLine = null
        bridge.nativeClose()
        log.info { "Stopped" }
    }

    override val isRunning: Boolean get() = running
    override val sampleRate: Int get() = SAMPLE_RATE
    override fun addUnit(unit: AudioUnit) {} // C++ manages graph
    override fun setUnitEnabled(unit: AudioUnit, enabled: Boolean) {}
    override val lineOutLeft: AudioInput get() = NoOpAudioInput
    override val lineOutRight: AudioInput get() = NoOpAudioInput
    override fun getCpuLoad(): Float = (bridge.nativeGetCpuLoad() * 100f).toFloat()
    override fun getCurrentTime(): Double = System.nanoTime() / 1_000_000_000.0

    // ── NativeDspBridge ─────────────────────────────────
    override fun nativeSetVoiceGate(index: Int, active: Boolean) = bridge.nativeSetVoiceGate(index, active)
    override fun nativeSetVoiceTune(index: Int, tune: Float) = bridge.nativeSetVoiceTune(index, tune)
    override fun nativeSetVoiceEngine(index: Int, engineIndex: Int) = bridge.nativeSetVoiceEngine(index, engineIndex)
    override fun nativeSetVoiceHarmonics(index: Int, value: Float) = bridge.nativeSetVoiceHarmonics(index, value)
    override fun nativeSetVoiceTimbre(index: Int, value: Float) = bridge.nativeSetVoiceTimbre(index, value)
    override fun nativeSetVoiceMorph(index: Int, value: Float) = bridge.nativeSetVoiceMorph(index, value)
    override fun nativeSetVoiceDecay(index: Int, value: Float) = bridge.nativeSetVoiceDecay(index, value)
    override fun nativeSetVoiceActive(index: Int, active: Boolean) = bridge.nativeSetVoiceActive(index, active)
    override fun nativeSetVoiceHold(index: Int, level: Float) = bridge.nativeSetVoiceHold(index, level)
    override fun nativeSetMasterVolume(value: Float) = bridge.nativeSetMasterVolume(value)
    override fun nativeSetDrive(value: Float) = bridge.nativeSetDrive(value)
    override fun nativeSetDelayMix(value: Float) = bridge.nativeSetDelayMix(value)
    override fun nativeSetVibrato(value: Float) = bridge.nativeSetVibrato(value)
    override fun nativeSetBend(value: Float) = bridge.nativeSetBend(value)
    override fun nativeSetPort(uri: String, symbol: String, value: Float) = bridge.nativeSetPort(uri, symbol, value)
    override fun nativeGetPort(uri: String, symbol: String): Float = bridge.nativeGetPort(uri, symbol)
    override fun nativeGetMonitor(out: FloatArray) = bridge.nativeGetMonitor(out)
    override fun nativeTriggerDrum(drumIndex: Int, accent: Float) = bridge.nativeTriggerDrum(drumIndex, accent)
    override fun nativeLoadGraph(data: ByteArray): Int = bridge.nativeLoadGraph(data)
}

/** No-op AudioInput for C++ engine (routing handled internally). */
private object NoOpAudioInput : AudioInput {
    override fun set(value: Double) {}
    override fun disconnectAll() {}
}
```

**Step 2: Commit**

```bash
git add core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/NativeDspAudioEngine.kt
git commit -m "feat(audio): Add NativeDspAudioEngine with javax.sound.sampled output"
```

---

### Task 6: DI Toggle — AudioEngine Provider

**Files:**
- Modify: `core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/OrpheusAudioEngine.kt`
- Create: `core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/AudioEngineProvider.kt`

**Step 1: Remove @ContributesBinding from OrpheusAudioEngine**

In `OrpheusAudioEngine.kt`, change the class annotations. Remove the auto-binding
so the provider can choose which engine to create.

```kotlin
// BEFORE:
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OrpheusAudioEngine @Inject constructor() : AudioEngine {

// AFTER:
@SingleIn(AppScope::class)
class OrpheusAudioEngine @Inject constructor() : AudioEngine {
```

**Step 2: Create AudioEngineProvider**

A Metro provider that checks the system property and returns the right engine.

```kotlin
// core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/AudioEngineProvider.kt
package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
interface AudioEngineModule {
    companion object {
        private val log = logging("AudioEngineProvider")

        @Provides
        @SingleIn(AppScope::class)
        fun provideAudioEngine(): AudioEngine {
            val engine = System.getProperty("orpheus.engine", "jsyn")
            log.info { "Audio engine selection: $engine" }
            return if (engine == "cpp") {
                log.info { "Using NativeDspAudioEngine (C++ DSP)" }
                NativeDspAudioEngine()
            } else {
                log.info { "Using OrpheusAudioEngine (JSyn)" }
                OrpheusAudioEngine()
            }
        }
    }
}
```

**Step 3: Verify JVM compile**

```bash
./gradlew compileKotlinJvm
```

**Step 4: Commit**

```bash
git add core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/OrpheusAudioEngine.kt
git add core/audio/src/jvmMain/kotlin/org/balch/orpheus/core/audio/dsp/AudioEngineProvider.kt
git commit -m "feat(di): Add AudioEngine provider with JSyn/C++ toggle via -Dorpheus.engine"
```

---

### Task 7: Gradle Build Integration

**Files:**
- Modify: `apps/composeApp/build.gradle.kts`

**Step 1: Add buildDesktopNative task and jvmArgs forwarding**

Add a Gradle task that builds the dylib and a resource copy step.
Also forward the `orpheus.engine` system property to the desktop JVM.

Add after the existing `compose.desktop` block (around line 170):

```kotlin
// In apps/composeApp/build.gradle.kts, inside compose.desktop.application block,
// update the jvmArgs line:

// BEFORE (line 168):
jvmArgs += listOf("-Dorpheus.debug.gc=${System.getProperty("orpheus.debug.gc", "false")}")

// AFTER:
jvmArgs += listOf(
    "-Dorpheus.debug.gc=${System.getProperty("orpheus.debug.gc", "false")}",
    "-Dorpheus.engine=${System.getProperty("orpheus.engine", "jsyn")}",
    "-Djava.library.path=${System.getProperty("orpheus.native.path", "")}"
)
```

Add a top-level task for building the native library:

```kotlin
// After the compose.desktop block, add:

tasks.register<Exec>("buildDesktopNative") {
    group = "build"
    description = "Build liborpheus_desktop native library for JVM desktop"

    val desktopDir = rootProject.file("liborpheus_dsp/desktop")
    val buildDir = desktopDir.resolve("build")

    workingDir = desktopDir
    commandLine("bash", "-c", """
        cmake -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build --config Release
    """.trimIndent())

    doLast {
        val arch = System.getProperty("os.arch").let {
            if (it == "aarch64" || it == "arm64") "aarch64" else "x86_64"
        }
        val libName = "liborpheus_desktop.dylib" // TODO: handle .so/.dll
        val targetDir = project.file("src/jvmMain/resources/native/darwin-$arch")
        targetDir.mkdirs()
        buildDir.resolve(libName).copyTo(targetDir.resolve(libName), overwrite = true)
        logger.lifecycle("Copied $libName to $targetDir")
    }
}
```

**Step 2: Build and verify**

```bash
./gradlew buildDesktopNative
ls -la apps/composeApp/src/jvmMain/resources/native/darwin-aarch64/liborpheus_desktop.dylib
```

**Step 3: Commit**

```bash
git add apps/composeApp/build.gradle.kts
git commit -m "build: Add buildDesktopNative task and engine toggle JVM args"
```

---

### Task 8: End-to-End Test — Run Desktop with C++ Engine

**Step 1: Build the native library**

```bash
./gradlew buildDesktopNative
```

**Step 2: Run with JSyn (baseline — should work as before)**

```bash
./gradlew :apps:composeApp:run
```

Verify: app launches, audio works with JSyn.

**Step 3: Run with C++ engine**

```bash
./gradlew :apps:composeApp:run -Dorpheus.engine=cpp
```

Verify:
- Console shows `"Using NativeDspAudioEngine (C++ DSP)"`
- Audio plays through javax.sound.sampled
- Load a preset, play notes
- Check that voice levels appear in the monitor

**Step 4: If issues, check:**
- `java.library.path` includes the dylib location
- JNI function names match exactly (case-sensitive package path)
- Audio format (PCM_FLOAT 32-bit) is supported by the system

**Step 5: Commit (tag milestone)**

```bash
git add -A
git commit -m "feat: JVM desktop C++ DSP engine — end-to-end working"
```
