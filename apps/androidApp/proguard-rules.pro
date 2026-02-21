# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/balch/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-optimizationpasses 4

# Strip debug and verbose logging in release builds
-assumenosideeffects class com.diamondedge.logging.** {
    public void verbose(...);
    public void debug(...);
}

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# JSyn audio synthesis library
-keep class com.jsyn.** { *; }
-keepclassmembers class com.jsyn.** { *; }
-keep class com.softsynth.** { *; }

# Suppress warnings from JSyn's desktop-only features (AWT, Swing, JavaSound)
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn javax.sound.sampled.**
-dontwarn javax.sound.midi.**

# Keep javax.sound.midi SPI if present (JSyn might use it)
-keep class javax.sound.midi.** { *; }
-keep class javax.sound.midi.spi.** { *; }
-keep class * extends javax.sound.midi.spi.MidiDeviceProvider { *; }

# Suppress warnings for missing classes in Android release builds
# These are JVM/Desktop-only classes used by Ktor, Netty, and reactive libraries
-dontwarn io.micrometer.context.ContextAccessor
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn javax.enterprise.inject.spi.Extension
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper
-dontwarn reactor.blockhound.integration.BlockHoundIntegration

# MediaPipe hand tracking
# Graph.<clinit> uses Flogger which walks the stack by class name;
# R8 must preserve class names for both MediaPipe and Flogger.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.mediapipe.proto.**
