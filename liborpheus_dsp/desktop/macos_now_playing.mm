#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <MediaPlayer/MediaPlayer.h>
#import <jni.h>

#define JNI_FN(name) Java_org_balch_orpheus_core_media_MacOsNowPlaying_##name

// Store JVM reference for callbacks from native to Kotlin
static JavaVM *sJvm = nullptr;
static jobject sCallbackRef = nullptr;

// Helper to get JNIEnv on any thread (remote command handlers run on main thread)
static JNIEnv* getEnv() {
    JNIEnv *env = nullptr;
    if (sJvm) {
        int status = sJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            sJvm->AttachCurrentThread((void**)&env, nullptr);
        }
    }
    return env;
}

// Build a remote-command target block that calls the named no-arg Kotlin
// callback method. Used for play/pause/toggle/next/previous/skipForward/etc.
static MPRemoteCommandHandlerStatus (^makeCallbackBlock(const char *methodName))(MPRemoteCommandEvent *) {
    return [^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
        JNIEnv *e = getEnv();
        if (e && sCallbackRef) {
            jclass cbClass = e->GetObjectClass(sCallbackRef);
            jmethodID mid = e->GetMethodID(cbClass, methodName, "()V");
            if (mid) e->CallVoidMethod(sCallbackRef, mid);
        }
        return MPRemoteCommandHandlerStatusSuccess;
    } copy];
}

// Every write to nowPlayingInfo is a read-modify-write of one shared
// dictionary, and pushes arrive on several JVM threads (metadata and progress
// are separate collectors), so they are serialized on the main queue. macOS
// also measures elapsed time from the moment the dictionary was last set, so
// each write first carries the elapsed time forward at the playback rate; a
// metadata or rate push mid loop-cycle then leaves the seek bar where it is.
static CFAbsoluteTime sLastSet = 0;

static void updateNowPlaying(void (^mutate)(NSMutableDictionary *info)) {
    dispatch_async(dispatch_get_main_queue(), ^{
        MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
        NSMutableDictionary *info = [(center.nowPlayingInfo ?: @{}) mutableCopy];
        CFAbsoluteTime now = CFAbsoluteTimeGetCurrent();
        NSNumber *elapsed = info[MPNowPlayingInfoPropertyElapsedPlaybackTime];
        NSNumber *rate = info[MPNowPlayingInfoPropertyPlaybackRate];
        if (elapsed != nil && sLastSet > 0 && rate.doubleValue > 0) {
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] =
                @(elapsed.doubleValue + (now - sLastSet) * rate.doubleValue);
        }
        mutate(info);
        center.nowPlayingInfo = info;
        sLastSet = now;
    });
}

extern "C" {

JNIEXPORT void JNICALL
JNI_FN(nativeSetup)(JNIEnv *env, jclass clazz, jobject callback) {
    env->GetJavaVM(&sJvm);
    if (sCallbackRef) {
        env->DeleteGlobalRef(sCallbackRef);
    }
    sCallbackRef = env->NewGlobalRef(callback);

    // Called from the AWT thread; the command center wants the AppKit main
    // thread, and enabled flags set elsewhere left ⏮/⏭ drawn dimmed.
    dispatch_async(dispatch_get_main_queue(), ^{
    MPRemoteCommandCenter *cc = [MPRemoteCommandCenter sharedCommandCenter];

    [cc.playCommand addTargetWithHandler:makeCallbackBlock("onPlay")];
    [cc.pauseCommand addTargetWithHandler:makeCallbackBlock("onPause")];
    [cc.togglePlayPauseCommand addTargetWithHandler:makeCallbackBlock("onTogglePlayPause")];

    // Forward: route both nextTrack and skipForward to onNext. macOS picks
    // which button to render (⏭ vs ⏩+15) based on metadata heuristics, and
    // we want both to land on the same handler so the user-visible button —
    // whichever it is — actually advances vibes.
    [cc.nextTrackCommand addTargetWithHandler:makeCallbackBlock("onNext")];
    [cc.skipForwardCommand addTargetWithHandler:makeCallbackBlock("onNext")];

    // Backward: same story for previousTrack + skipBackward. The user
    // reported that the back chevron in their Now Playing widget did
    // nothing, which means macOS was sending skipBackwardCommand instead
    // of previousTrackCommand for that vibe-set. Wiring both fixes it.
    [cc.previousTrackCommand addTargetWithHandler:makeCallbackBlock("onPrevious")];
    [cc.skipBackwardCommand addTargetWithHandler:makeCallbackBlock("onPrevious")];

    // skipForward/Backward use a single tap; declare a token interval so
    // macOS picks an icon. The number doesn't drive any actual seeking on
    // our side — Pulsar has no concept of a track position to skip within.
    cc.skipForwardCommand.preferredIntervals = @[@15];
    cc.skipBackwardCommand.preferredIntervals = @[@15];

    cc.playCommand.enabled = YES;
    cc.pauseCommand.enabled = YES;
    cc.togglePlayPauseCommand.enabled = YES;
    cc.nextTrackCommand.enabled = YES;
    cc.previousTrackCommand.enabled = YES;
    // Left disabled: with a duration published, macOS prefers the seek-style
    // skip buttons when both are enabled and renders them, not next/previous.
    cc.skipForwardCommand.enabled = NO;
    cc.skipBackwardCommand.enabled = NO;
    // Same for the seek and scrub commands, which are enabled by default and
    // put a "10" seek icon in place of next track once a duration exists.
    cc.seekForwardCommand.enabled = NO;
    cc.seekBackwardCommand.enabled = NO;
    cc.changePlaybackPositionCommand.enabled = NO;

    });
    // Music, not a podcast: the media type also steers which buttons show.
    updateNowPlaying(^(NSMutableDictionary *info) {
        info[MPNowPlayingInfoPropertyMediaType] = @(MPNowPlayingInfoMediaTypeAudio);
        info[MPMediaItemPropertyMediaType] = @(MPMediaTypeMusic);
        // A vibe always has a previous and a next: a queue with room on both sides.
        info[MPNowPlayingInfoPropertyPlaybackQueueCount] = @(3);
        info[MPNowPlayingInfoPropertyPlaybackQueueIndex] = @(1);
    });
}

JNIEXPORT void JNICALL
JNI_FN(nativeUpdateMetadata)(JNIEnv *env, jclass clazz, jstring jTitle, jstring jArtist) {
    const char *titleChars = env->GetStringUTFChars(jTitle, NULL);
    const char *artistChars = env->GetStringUTFChars(jArtist, NULL);

    NSString *title = [NSString stringWithUTF8String:titleChars];
    NSString *artist = [NSString stringWithUTF8String:artistChars];

    env->ReleaseStringUTFChars(jTitle, titleChars);
    env->ReleaseStringUTFChars(jArtist, artistChars);

    updateNowPlaying(^(NSMutableDictionary *info) {
        info[MPMediaItemPropertyTitle] = title;
        info[MPMediaItemPropertyArtist] = artist;
    });
}

// Seek-bar progress. macOS extrapolates elapsed time from this anchor at the
// playback rate, so one push per loop-cycle keeps the bar moving. A duration
// of 0 or less clears both keys (no arrangement: no seek bar).
JNIEXPORT void JNICALL
JNI_FN(nativeUpdateProgress)(JNIEnv *env, jclass clazz, jlong positionMs, jlong durationMs) {
    updateNowPlaying(^(NSMutableDictionary *info) {
        if (durationMs <= 0) {
            [info removeObjectForKey:MPMediaItemPropertyPlaybackDuration];
            [info removeObjectForKey:MPNowPlayingInfoPropertyElapsedPlaybackTime];
        } else {
            info[MPMediaItemPropertyPlaybackDuration] = @(durationMs / 1000.0);
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(positionMs / 1000.0);
        }
    });
}

JNIEXPORT void JNICALL
JNI_FN(nativeUpdatePlaybackState)(JNIEnv *env, jclass clazz, jboolean isPlaying) {
    updateNowPlaying(^(NSMutableDictionary *info) {
        MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
        center.playbackState = isPlaying ? MPNowPlayingPlaybackStatePlaying : MPNowPlayingPlaybackStatePaused;
        // ALSO write playbackRate into the nowPlayingInfo dict. macOS Sonoma+
        // drives the play/pause icon in Control Center off this field, not the
        // separate playbackState property — without it, the icon stays stuck
        // showing whatever it was on the first metadata push.
        info[MPNowPlayingInfoPropertyPlaybackRate] = @(isPlaying ? 1.0 : 0.0);
    });
}

JNIEXPORT void JNICALL
JNI_FN(nativeUpdateArtwork)(JNIEnv *env, jclass clazz, jbyteArray jBytes) {
    // The JNIEnv is bound to this thread, so the bytes are copied out before the hop.
    NSData *data = nil;
    if (jBytes != nullptr) {
        jsize len = env->GetArrayLength(jBytes);
        jbyte *bytes = env->GetByteArrayElements(jBytes, NULL);
        data = [NSData dataWithBytes:bytes length:(NSUInteger)len];
        env->ReleaseByteArrayElements(jBytes, bytes, JNI_ABORT);
    }

    updateNowPlaying(^(NSMutableDictionary *info) {
        NSImage *image = data ? [[NSImage alloc] initWithData:data] : nil;
        if (image == nil) {
            [info removeObjectForKey:MPMediaItemPropertyArtwork];
            return;
        }
        // Use the bitmap's intrinsic size if available (handles HiDPI), else NSImage.size.
        NSSize size = image.size;
        MPMediaItemArtwork *artwork = [[MPMediaItemArtwork alloc]
            initWithBoundsSize:size
                  requestHandler:^NSImage * _Nonnull(CGSize requested) {
            return image;
        }];
        info[MPMediaItemPropertyArtwork] = artwork;
    });
}

JNIEXPORT void JNICALL
JNI_FN(nativeTeardown)(JNIEnv *env, jclass clazz) {
    MPRemoteCommandCenter *cc = [MPRemoteCommandCenter sharedCommandCenter];
    [cc.playCommand removeTarget:nil];
    [cc.pauseCommand removeTarget:nil];
    [cc.togglePlayPauseCommand removeTarget:nil];
    [cc.nextTrackCommand removeTarget:nil];
    [cc.previousTrackCommand removeTarget:nil];
    [cc.skipForwardCommand removeTarget:nil];
    [cc.skipBackwardCommand removeTarget:nil];

    // Same queue as the writers, so a push already queued cannot land after the clear.
    dispatch_async(dispatch_get_main_queue(), ^{
        [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil;
        sLastSet = 0;
    });

    if (sCallbackRef && sJvm) {
        JNIEnv *e = getEnv();
        if (e) {
            e->DeleteGlobalRef(sCallbackRef);
        }
        sCallbackRef = nullptr;
    }
}

} // extern "C"
