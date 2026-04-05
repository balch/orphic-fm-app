# Suppress all warnings to allow build to proceed
-ignorewarnings

-dontnote kotlin.**
-dontnote io.ktor.**
-dontnote io.netty.**
-dontnote io.lettuce.**
-dontnote io.opentelemetry.**
-dontnote org.bytedeco.**
-dontnote ch.qos.logback.**
-dontnote org.apache.**
-dontnote org.slf4j.**
-dontnote reactor.**
-dontnote ai.koog.**
-dontnote aws.**
-dontnote com.typesafe.**
-dontnote okhttp3.**
-dontnote okio.**
-dontnote javax.sound.midi.**
-dontwarn kotlin.**

-optimizationpasses 2

-dontobfuscate

# Keep all Compose UI classes - ProGuard optimization breaks Compose's internal bytecode
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }
-keep class org.jetbrains.skiko.** { *; }
-keep class org.jetbrains.skia.** { *; }

# Strip debug and verbose logging in release builds
# This removes the method invocations entirely, so string arguments are never evaluated
# Strip debug and verbose logging in release builds
-assumenosideeffects class com.diamondedge.logging.** {
    public void verbose(...);
    public void debug(...);
}

# Suppress warnings from JavaCPP about missing Maven classes
-dontwarn org.apache.maven.**
-dontwarn org.bytedeco.javacpp.tools.**
-dontwarn org.osgi.**
-dontwarn org.slf4j.**

# Keep JavaCPP classes as they might be used via reflection
-keep class org.bytedeco.javacpp.** { *; }

# Suppress warnings from LibreMidi Panama (Foreign Function API)
-dontwarn dev.atsushieno.panama.libremidi.**
-keep class dev.atsushieno.panama.libremidi.** { *; }

# Keep JSyn audio synthesis library - required for audio device manager initialization
-keep class com.jsyn.** { *; }
-keepclassmembers class com.jsyn.** { *; }
-keep class com.softsynth.** { *; }

# Keep CoreMIDI4J library - required for MIDI device detection on macOS
# Uses JNI and Java Service Provider Interface (SPI)
-keep class uk.co.xfactorylibrarians.coremidi4j.** { *; }
-keepclassmembers class uk.co.xfactorylibrarians.coremidi4j.** { *; }

# Keep MidiDeviceProvider implementations for SPI discovery
# (javax.sound.midi.** are JDK library classes — ProGuard preserves them automatically)
-keep class * extends javax.sound.midi.spi.MidiDeviceProvider { *; }

# Suppress warnings from common libraries that have optional dependencies
-dontwarn io.lettuce.**
-dontwarn org.apache.commons.pool2.**
-dontwarn io.opentelemetry.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn io.netty.**
-dontwarn kotlin.internal.**

# Keep Netty logging classes to prevent IncompleteClassHierarchyException
# ProGuard fails when it can't resolve Log4J2 parent classes during optimization
-keep class io.netty.util.internal.logging.** { *; }
-dontwarn org.apache.logging.log4j.**

# Prevent optimization of Netty's Log4J2 logger factory which references missing log4j classes
-keep,allowshrinking class io.netty.util.internal.logging.Log4J2LoggerFactory { *; }
-keep,allowshrinking class io.netty.util.internal.logging.Log4J2Logger { *; }

# Logback optional dependencies (conditional evaluation, XZ compression)
-dontwarn ch.qos.logback.**
-dontwarn org.codehaus.janino.**
-dontwarn org.codehaus.commons.**
-dontwarn org.tukaani.xz.**

# Ktor server websocket (pulled transitively, not used in client)
-dontwarn io.ktor.server.**

# Additional suppressions for transitive dependencies (Micrometer, Reactor, BouncyCastle, etc.)
-dontwarn reactor.**
-dontwarn io.micrometer.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.brotli.dec.**
-dontwarn aws.sdk.kotlin.**
-dontwarn org.apache.hc.client5.**
-dontwarn com.oracle.svm.**
-dontwarn org.graalvm.**
-dontwarn org.openjsse.**
-dontwarn jakarta.servlet.**
-dontwarn jakarta.mail.**

# Ktor HTTP client - keep engine discovery via ServiceLoader
-keep class io.ktor.client.** { *; }
-keep class io.ktor.client.engine.** { *; }
-keep class io.ktor.client.engine.apache5.** { *; }
-keep class io.ktor.client.engine.cio.** { *; }
# Keep all HttpClientEngineContainer implementations for ServiceLoader discovery
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }
-keepclassmembers class * implements io.ktor.client.HttpClientEngineContainer {
    <init>(...);
}

# Keep ServiceLoader metadata files
-adaptresourcefilenames META-INF/services/**
-keepnames class * implements io.ktor.client.HttpClientEngineContainer

# Ktor serialization and content negotiation
-keep class io.ktor.serialization.** { *; }
-keep class io.ktor.client.plugins.** { *; }

# Apache HTTP Client 5 (used by Ktor Apache5 engine)
-keep class org.apache.hc.** { *; }
-dontwarn org.apache.hc.**
