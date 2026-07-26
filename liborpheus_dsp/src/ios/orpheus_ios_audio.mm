#import <AVFoundation/AVFoundation.h>
#include <atomic>
#include <cstring>
#include <memory>
#include "orpheus_ios_audio.h"
#include "orpheus_dsp.h"

// The config-change callback pair, deliberately NOT stored inside
// OrpheusIosAudio. Do not "simplify" it back in.
//
// The observer block has to read this pair, and destroy has to null it. If the
// block held a raw `OrpheusIosAudio*`, its own null guard would live inside the
// object being freed: the block could be preempted between entry and its first
// load, destroy could run to completion including `delete host`, and the block
// would then resume and read freed heap. `queue:nil` means there is no queue to
// drain, and `removeObserver:` does not synchronize with delivery already in
// flight, so no ordering inside destroy can close that window.
//
// Separately heap-allocating the pair and having the block capture a
// shared_ptr COPY makes the lifetime structural instead of a timing argument.
// `addObserverForName:` copies the block to the heap, which runs the shared_ptr
// copy constructor, and the system retains the returned token, so the slot
// provably outlives the block. An arbitrarily delayed block reads live memory,
// sees the null `cb` destroy stored, and returns. Dropping the token in
// `removeObserver:` releases the last reference.
//
// This is not shutdown-only paranoia. The Kotlin host calls destroy from its
// route-change repair path, so destroy and config-change delivery are
// correlated events: one Bluetooth disconnect can post several config changes
// while the HAL settles onto the built-in speaker.
struct ConfigCallbackSlot {
    // Written by the client, read by the observer block on AVAudioEngine's
    // internal dispatch queue. Atomic for the same reason `engine` is: the
    // writer and the reader are unrelated threads. Published ctx-then-cb and
    // read cb-then-ctx, so a reader that sees a callback also sees its
    // context rather than a half-written pair.
    std::atomic<void (*)(void*)> cb{nullptr};
    std::atomic<void*> ctx{nullptr};
};

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
    // Opaque token for the config-change observer. Only create/destroy touch
    // it, so it needs no synchronization.
    id configObserver = nil;
    // Co-owned with the observer block. See ConfigCallbackSlot above for why
    // this is a separate allocation and not two plain members.
    std::shared_ptr<ConfigCallbackSlot> configSlot =
        std::make_shared<ConfigCallbackSlot>();
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

    // Deliberately NOT connecting mainMixerNode -> outputNode with an explicit
    // format. Left unset, the engine re-tracks that connection to whatever the
    // hardware is on every (re)start, which is what lets a restart alone
    // recover from a route change. Pinning it here would freeze the graph at
    // the old device's rate.

    // When the output hardware's channel count or sample rate changes, the
    // engine stops ITSELF and posts this notification. A Bluetooth speaker
    // powering off is exactly that, and nothing else in the system reports it,
    // so this is the only in-band notice that audio has died.
    //
    // queue:nil delivers the block synchronously on the engine's own internal
    // dispatch queue. The block therefore does one thing and nothing else:
    // hand the signal to the registered callback, which is contracted to
    // return immediately. Touching engine lifecycle from in here deadlocks
    // against the engine's synchronous teardown, so the client owns the queue
    // hop and every repair decision.
    //
    // The block captures `slot`, never `host`. A local is required: capturing
    // `host->configSlot` directly would capture `host` and reintroduce the
    // use-after-free the slot exists to prevent.
    auto slot = host->configSlot;
    host->configObserver = [[NSNotificationCenter defaultCenter]
        addObserverForName:AVAudioEngineConfigurationChangeNotification
                    object:host->avEngine
                     queue:nil
                usingBlock:^(NSNotification* note) {
        (void)note;
        auto cb = slot->cb.load(std::memory_order_acquire);
        if (cb) {
            cb(slot->ctx.load(std::memory_order_acquire));
        }
    }];
    return host;
}

void orpheus_ios_audio_set_config_change_callback(OrpheusIosAudio* host,
                                                  void (*cb)(void*),
                                                  void* ctx) {
    if (!host) return;
    // Store the context first. The observer block loads the callback and only
    // then the context, so ordering the writes this way means a non-null
    // callback is never paired with a context the writer had not yet stored.
    host->configSlot->ctx.store(ctx, std::memory_order_release);
    host->configSlot->cb.store(cb, std::memory_order_release);
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
    // Silence the callback before doing anything else, mirroring how
    // `rendering` gates the render block: a notification already in flight on
    // the engine's queue then finds a null pointer and returns, instead of
    // calling into a host that is being freed. This retraction is what makes
    // teardown safe, and it is sufficient rather than merely likely because the
    // block reads the slot, which outlives it, and not `host`. There is no
    // reliance on [avEngine stop] taking long enough. Deregistration still has
    // to precede `avEngine = nil`, since that is where ARC releases the engine
    // the observer is registered against.
    //
    // Null cb first, then ctx: the reverse of the setter's publish order. The
    // block loads cb then ctx, so retracting in this order can only ever hand
    // it a null ctx, never a stale one.
    host->configSlot->cb.store(nullptr, std::memory_order_release);
    host->configSlot->ctx.store(nullptr, std::memory_order_release);
    if (host->configObserver) {
        [[NSNotificationCenter defaultCenter]
            removeObserver:host->configObserver];
        host->configObserver = nil;
    }
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
