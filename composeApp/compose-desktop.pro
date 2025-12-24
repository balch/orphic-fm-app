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
