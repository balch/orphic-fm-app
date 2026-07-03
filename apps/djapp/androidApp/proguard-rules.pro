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

# Koog builds its agent graph from delegated DSL builder functions (e.g.
#   val node by nodeLLMRequestStreaming()
# in OrpheusAgentConfig) that kotlin-reflect resolves at runtime by reading the builder
# function's @kotlin.Metadata. THE ACTUAL R8 BUG (root-caused 2026-07-01 from mapping.txt):
# R8 full mode renamed kotlin.jvm.functions.Function2 -> a synthetic name, because nothing
# kept kotlin.jvm.functions.**. A -keep on the koog method does NOT pin its PARAMETER types,
# so R8 rewrote the real method's bytecode descriptor to take the renamed Function2 — but R8
# does NOT rewrite the JVM-signature string frozen inside @kotlin.Metadata, which still names
# Function2. kotlin-reflect reads the frozen metadata, can't map it to the renamed method, and
# throws "nodeLLMRequestStreaming ... not resolved in file class AIAgentNodesKt: no members
# found", killing the whole agent run (empty Activity/Thinking feeds, generation stuck).
# THE FIX is to pin the NAMES of Kotlin's functional-interface types so the live method
# descriptor keeps matching the frozen metadata string kotlin-reflect reads. -keepnames
# (not -keep) only blocks renaming; it does not disable shrinking/optimization. This is why
# the earlier -keep class ai.koog.**/-dontoptimize attempts failed — none kept the type that
# was actually renamed out from under the metadata.
-keepnames class kotlin.jvm.functions.** { *; }
-keepnames class kotlin.Function** { *; }

# Freeze koog's own builder facades + metadata so their names/metadata stay in lockstep
# with the reflection that reads them.
-keep class ai.koog.** { *; }
-keepclassmembers class ai.koog.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes InnerClasses,Signature,RuntimeVisible*Annotations,EnclosingMethod
-dontwarn ai.koog.**
-dontwarn kotlin.reflect.jvm.internal.**
# Keeping ai.koog.** makes R8 trace into Koog's bundled OpenTelemetry integration, which
# references AutoValue (a compile-time-only annotation processor, never on the runtime
# classpath) and a few optional/incubator OpenTelemetry metrics APIs this OpenTelemetry
# version doesn't ship. Neither is reachable at runtime — Koog's tracing isn't configured
# with any exporter in this app — so warning suppression is correct, not a keep rule.
-dontwarn com.google.auto.value.**
-dontwarn io.opentelemetry.api.incubator.**
# Jackson (transitive via Koog) references java.beans.* which does not exist on Android.
# These are optional Java SE reflection hooks Jackson probes for and skips at runtime; the
# standard Android suppression. (Surfaced once R8 full mode was disabled — full mode had
# been silently suppressing them.)
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
