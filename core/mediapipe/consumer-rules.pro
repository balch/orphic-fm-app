# Consumer R8 rules for :core:mediapipe — the module that owns the MediaPipe dependency.
# AGP merges this into the R8 config of every Android app on this module's classpath, so a
# consumer no longer has to know MediaPipe needs keep rules. Orpheus carried an identical
# block in its own app-level proguard-rules.pro; another app did not, and the symptom was quiet:
# GestureRecognizer threw "Field platform_ for gn1 not found" and AndroidHandTracker fell
# back to HandLandmarker, so skeleton tracking still worked and every gesture silently
# stopped being recognised.
#
# MediaPipe resolves protobuf fields reflectively by name, and Graph.<clinit> uses Flogger,
# which walks the stack by class name. Both break under renaming.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.mediapipe.proto.**
