# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/balch/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep rules here:

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
