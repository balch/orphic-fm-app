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

extern "C" {

JNIEXPORT void JNICALL
JNI_FN(nativeSetup)(JNIEnv *env, jclass clazz, jobject callback) {
    env->GetJavaVM(&sJvm);
    if (sCallbackRef) {
        env->DeleteGlobalRef(sCallbackRef);
    }
    sCallbackRef = env->NewGlobalRef(callback);

    MPRemoteCommandCenter *cc = [MPRemoteCommandCenter sharedCommandCenter];

    [cc.playCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
        JNIEnv *e = getEnv();
        if (e && sCallbackRef) {
            jclass cbClass = e->GetObjectClass(sCallbackRef);
            jmethodID mid = e->GetMethodID(cbClass, "onPlay", "()V");
            e->CallVoidMethod(sCallbackRef, mid);
        }
        return MPRemoteCommandHandlerStatusSuccess;
    }];

    [cc.pauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
        JNIEnv *e = getEnv();
        if (e && sCallbackRef) {
            jclass cbClass = e->GetObjectClass(sCallbackRef);
            jmethodID mid = e->GetMethodID(cbClass, "onPause", "()V");
            e->CallVoidMethod(sCallbackRef, mid);
        }
        return MPRemoteCommandHandlerStatusSuccess;
    }];

    [cc.togglePlayPauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
        JNIEnv *e = getEnv();
        if (e && sCallbackRef) {
            jclass cbClass = e->GetObjectClass(sCallbackRef);
            jmethodID mid = e->GetMethodID(cbClass, "onTogglePlayPause", "()V");
            e->CallVoidMethod(sCallbackRef, mid);
        }
        return MPRemoteCommandHandlerStatusSuccess;
    }];

    [cc.nextTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
        JNIEnv *e = getEnv();
        if (e && sCallbackRef) {
            jclass cbClass = e->GetObjectClass(sCallbackRef);
            jmethodID mid = e->GetMethodID(cbClass, "onNext", "()V");
            e->CallVoidMethod(sCallbackRef, mid);
        }
        return MPRemoteCommandHandlerStatusSuccess;
    }];

    [cc.previousTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
        JNIEnv *e = getEnv();
        if (e && sCallbackRef) {
            jclass cbClass = e->GetObjectClass(sCallbackRef);
            jmethodID mid = e->GetMethodID(cbClass, "onPrevious", "()V");
            e->CallVoidMethod(sCallbackRef, mid);
        }
        return MPRemoteCommandHandlerStatusSuccess;
    }];

    cc.playCommand.enabled = YES;
    cc.pauseCommand.enabled = YES;
    cc.togglePlayPauseCommand.enabled = YES;
    cc.nextTrackCommand.enabled = YES;
    cc.previousTrackCommand.enabled = YES;
}

JNIEXPORT void JNICALL
JNI_FN(nativeUpdateMetadata)(JNIEnv *env, jclass clazz, jstring jTitle, jstring jArtist) {
    const char *titleChars = env->GetStringUTFChars(jTitle, NULL);
    const char *artistChars = env->GetStringUTFChars(jArtist, NULL);

    NSString *title = [NSString stringWithUTF8String:titleChars];
    NSString *artist = [NSString stringWithUTF8String:artistChars];

    env->ReleaseStringUTFChars(jTitle, titleChars);
    env->ReleaseStringUTFChars(jArtist, artistChars);

    NSMutableDictionary *info = [NSMutableDictionary dictionary];
    info[MPMediaItemPropertyTitle] = title;
    info[MPMediaItemPropertyArtist] = artist;
    [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = info;
}

JNIEXPORT void JNICALL
JNI_FN(nativeUpdatePlaybackState)(JNIEnv *env, jclass clazz, jboolean isPlaying) {
    MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
    center.playbackState = isPlaying ? MPNowPlayingPlaybackStatePlaying : MPNowPlayingPlaybackStatePaused;
}

JNIEXPORT void JNICALL
JNI_FN(nativeTeardown)(JNIEnv *env, jclass clazz) {
    MPRemoteCommandCenter *cc = [MPRemoteCommandCenter sharedCommandCenter];
    [cc.playCommand removeTarget:nil];
    [cc.pauseCommand removeTarget:nil];
    [cc.togglePlayPauseCommand removeTarget:nil];
    [cc.nextTrackCommand removeTarget:nil];
    [cc.previousTrackCommand removeTarget:nil];

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
