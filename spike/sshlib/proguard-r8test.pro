# The instrumentation APK links against the shared spike classes, so keep them under R8.
-keep class io.github.tuthan.paddock.spike.** { *; }
