-optimizationpasses 4

# Strip debug and verbose logging in release builds
-assumenosideeffects class com.diamondedge.logging.** {
    public void verbose(...);
    public void debug(...);
}

# Suppress warnings for missing desktop-only classes
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn javax.sound.sampled.**
-dontwarn javax.sound.midi.**
-dontwarn io.micrometer.context.ContextAccessor
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn javax.enterprise.inject.spi.Extension
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn reactor.blockhound.integration.BlockHoundIntegration

# Google Play in-app review (review-ktx) generates a GMS OnSuccessListener SAM
# adapter that references @com.google.android.gms.common.annotation.NoNullnessRewrite.
# That annotation lives in a newer play-services-base than the review libs pull in,
# so R8 sees a dangling reference. It's a build-time annotation with no runtime
# behavior and the review API is direct-call (no reflection), so suppressing the
# warning is sufficient — no -keep rules needed.
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite

# Oboe JNI bridge
-keep class org.balch.orpheus.core.audio.dsp.OboeAudioBridge {
    void renderAudio(float[], int);
}

# Glance (home-screen widget) transitively pulls in WorkManager, whose
# Room-backed WorkDatabase is instantiated reflectively (Room loads the
# generated "<Database>_Impl" via Class.forName + no-arg constructor). R8
# optimization here (proguard-android-optimize + 4 passes) strips that
# generated impl / its constructor, crashing at app launch with:
#   "Failed to create an instance of androidx.work.impl.WorkDatabase".
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**
# WorkManager instantiates InputMergers (no-arg) and Workers
# (Context, WorkerParameters) reflectively — including Glance's render worker.
# R8 strips those constructors without these rules, so the widget's render
# worker never runs and the widget is stuck on its loading spinner.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Glance instantiates ActionCallback implementations reflectively (by class
# name) when a widget button is tapped. Without keeping them (and their no-arg
# constructors) R8 strips the constructors and taps silently do nothing.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
# Keep the widget's own classes (receiver, widget, actions) intact.
-keep class org.balch.djapp.widget.** { *; }

# Koog / kotlin-reflect rules MOVED (2026-07-12) to core/ai/consumer-rules.pro — consumer
# rules shipped by the module that owns the Koog dependency, so every Android app pulling
# :core:ai (this app's ai flavor, the Orpheus app) gets them automatically and the og flavor
# stays maximally shrunk. Full war story (Function2 rename bug 2026-07-01; typeOf classifier
# strip, JetBrains/koog#1068, 2026-07-12) lives in that file.
#
# Kept app-level on purpose: the attribute set below predates the move and also serves the
# og flavor (Room/WorkManager/Glance reflection), whose classpath never includes core/ai.
-keepattributes InnerClasses,Signature,RuntimeVisible*Annotations,EnclosingMethod
