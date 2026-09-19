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

    // Music, not a podcast: the media type also steers which buttons show.
    MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
    NSMutableDictionary *info = [(center.nowPlayingInfo ?: @{}) mutableCopy];
    info[MPNowPlayingInfoPropertyMediaType] = @(MPNowPlayingInfoMediaTypeAudio);
    info[MPMediaItemPropertyMediaType] = @(MPMediaTypeMusic);
    // A vibe always has a previous and a next: a queue with room on both sides.
    info[MPNowPlayingInfoPropertyPlaybackQueueCount] = @(3);
    info[MPNowPlayingInfoPropertyPlaybackQueueIndex] = @(1);
    center.nowPlayingInfo = info;
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

    MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
    NSMutableDictionary *info = [(center.nowPlayingInfo ?: @{}) mutableCopy];
    info[MPMediaItemPropertyTitle] = title;
    info[MPMediaItemPropertyArtist] = artist;
    center.nowPlayingInfo = info;
}

// Seek-bar progress. macOS extrapolates elapsed time from this anchor at the
// playback rate, so one push per loop-cycle keeps the bar moving. A duration
// of 0 or less clears both keys (no arrangement: no seek bar).
JNIEXPORT void JNICALL
JNI_FN(nativeUpdateProgress)(JNIEnv *env, jclass clazz, jlong positionMs, jlong durationMs) {
    MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
    NSMutableDictionary *info = [(center.nowPlayingInfo ?: @{}) mutableCopy];
    if (durationMs <= 0) {
        [info removeObjectForKey:MPMediaItemPropertyPlaybackDuration];
        [info removeObjectForKey:MPNowPlayingInfoPropertyElapsedPlaybackTime];
    } else {
        info[MPMediaItemPropertyPlaybackDuration] = @(durationMs / 1000.0);
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(positionMs / 1000.0);
    }
    center.nowPlayingInfo = info;
}

JNIEXPORT void JNICALL
JNI_FN(nativeUpdatePlaybackState)(JNIEnv *env, jclass clazz, jboolean isPlaying) {
    MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
    center.playbackState = isPlaying ? MPNowPlayingPlaybackStatePlaying : MPNowPlayingPlaybackStatePaused;

    // ALSO write playbackRate into the nowPlayingInfo dict. macOS Sonoma+
    // drives the play/pause icon in Control Center off this field, not the
    // separate playbackState property — without it, the icon stays stuck
    // showing whatever it was on the first metadata push.
    NSMutableDictionary *info = [(center.nowPlayingInfo ?: @{}) mutableCopy];
    info[MPNowPlayingInfoPropertyPlaybackRate] = @(isPlaying ? 1.0 : 0.0);
    center.nowPlayingInfo = info;
}

JNIEXPORT void JNICALL
JNI_FN(nativeUpdateArtwork)(JNIEnv *env, jclass clazz, jbyteArray jBytes) {
    MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
    NSMutableDictionary *info = [(center.nowPlayingInfo ?: @{}) mutableCopy];

    if (jBytes == nullptr) {
        [info removeObjectForKey:MPMediaItemPropertyArtwork];
        center.nowPlayingInfo = info;
        return;
    }

    jsize len = env->GetArrayLength(jBytes);
    jbyte *bytes = env->GetByteArrayElements(jBytes, NULL);
    NSData *data = [NSData dataWithBytes:bytes length:(NSUInteger)len];
    env->ReleaseByteArrayElements(jBytes, bytes, JNI_ABORT);

    NSImage *image = [[NSImage alloc] initWithData:data];
    if (image == nil) {
        [info removeObjectForKey:MPMediaItemPropertyArtwork];
        center.nowPlayingInfo = info;
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
    center.nowPlayingInfo = info;
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

    [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil;

    if (sCallbackRef && sJvm) {
        JNIEnv *e = getEnv();
        if (e) {
            e->DeleteGlobalRef(sCallbackRef);
        }
        sCallbackRef = nullptr;
    }
}

} // extern "C"
