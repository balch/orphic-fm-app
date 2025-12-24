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

# Keep javax.sound.midi SPI - required for MIDI device provider discovery
-keep class javax.sound.midi.** { *; }
-keep class javax.sound.midi.spi.** { *; }
# Keep all MidiDeviceProvider implementations
-keep class * extends javax.sound.midi.spi.MidiDeviceProvider { *; }

