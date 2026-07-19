#import <AVFoundation/AVFoundation.h>
#include <atomic>
#include <cstring>
#include "orpheus_ios_audio.h"
#include "orpheus_dsp.h"

// ObjC++ struct: ObjC pointer members get correct ARC retain/release via
// generated special members. Allocated with new/delete from the C API.
struct OrpheusIosAudio {
    AVAudioEngine* avEngine = nil;
    AVAudioSourceNode* sourceNode = nil;
    std::atomic<OrpheusEngine*> engine{nullptr};
    // Gate read by the render thread. Cleared by stop() BEFORE the
    // AVAudioEngine stops so a callback racing the stop renders silence
    // instead of touching an engine that is about to be destroyed.
    std::atomic<bool> rendering{false};
    double sampleRate = 48000.0;
};

extern "C" {

OrpheusIosAudio* orpheus_ios_audio_create(OrpheusEngine* engine,
                                          double sample_rate) {
    auto* host = new OrpheusIosAudio();
    host->engine.store(engine, std::memory_order_release);
    host->sampleRate = sample_rate;
    host->avEngine = [[AVAudioEngine alloc] init];

    // The render block runs on the CoreAudio real-time thread. It must not
    // allocate, lock, take ObjC message sends, or touch any managed
    // runtime. Plain pointer math + the DSP call only.
    AVAudioSourceNodeRenderBlock render =
        ^OSStatus(BOOL* isSilence, const AudioTimeStamp* timestamp,
                  AVAudioFrameCount frameCount, AudioBufferList* outputData) {
        // State the flag on every path: the early-outs below override to YES.
        *isSilence = NO;
        OrpheusEngine* eng = host->engine.load(std::memory_order_acquire);
        bool active = host->rendering.load(std::memory_order_acquire);
        if (!eng || !active || outputData->mNumberBuffers < 2 ||
            !outputData->mBuffers[0].mData || !outputData->mBuffers[1].mData) {
            // CoreAudio buffers are not guaranteed zeroed. Without an
            // explicit clear, stale data replays as a buzz during
            // teardown/startup windows.
            for (UInt32 i = 0; i < outputData->mNumberBuffers; i++) {
                if (outputData->mBuffers[i].mData) {
                    memset(outputData->mBuffers[i].mData, 0,
                           outputData->mBuffers[i].mDataByteSize);
                }
            }
            *isSilence = YES;
            return noErr;
        }
        float* left  = (float*)outputData->mBuffers[0].mData;
        float* right = (float*)outputData->mBuffers[1].mData;
        orpheus_engine_process_deinterleaved(eng, left, right, (int)frameCount);
        return noErr;
    };

    host->sourceNode = [[AVAudioSourceNode alloc] initWithRenderBlock:render];

    AVAudioFormat* fmt =
        [[AVAudioFormat alloc] initStandardFormatWithSampleRate:sample_rate
                                                       channels:2];
    [host->avEngine attachNode:host->sourceNode];
    [host->avEngine connect:host->sourceNode
                         to:host->avEngine.mainMixerNode
                     format:fmt];
    return host;
}

int orpheus_ios_audio_start(OrpheusIosAudio* host) {
    if (!host || !host->avEngine) return -1;
    host->rendering.store(true, std::memory_order_release);
    NSError* err = nil;
    if (![host->avEngine startAndReturnError:&err]) {
        host->rendering.store(false, std::memory_order_release);
        return err ? (int)err.code : -1;
    }
    return 0;
}

void orpheus_ios_audio_stop(OrpheusIosAudio* host) {
    if (!host || !host->avEngine) return;
    host->rendering.store(false, std::memory_order_release);
    // -[AVAudioEngine stop] synchronizes with the render thread through the
    // underlying AudioOutputUnitStop: when it returns, no render block
    // invocation is in flight.
    [host->avEngine stop];
}

void orpheus_ios_audio_destroy(OrpheusIosAudio* host) {
    if (!host) return;
    orpheus_ios_audio_stop(host);
    host->engine.store(nullptr, std::memory_order_release);
    if (host->avEngine && host->sourceNode) {
        [host->avEngine detachNode:host->sourceNode];
    }
    // ARC releases the node and, with it, the render block that captured
    // the raw host pointer — must happen before `delete host`.
    host->sourceNode = nil;
    host->avEngine = nil;
    delete host;
}

int orpheus_ios_audio_is_running(OrpheusIosAudio* host) {
    if (!host || !host->avEngine) return 0;
    return host->avEngine.isRunning ? 1 : 0;
}

}  // extern "C"
