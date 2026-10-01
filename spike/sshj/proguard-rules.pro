# Spike only: measure size with the library rooted, tolerate optional references.
-keep class io.github.tuthan.paddock.spike.ProbeActivity
-dontwarn **
-keep class io.github.tuthan.paddock.spike.SshjClient { *; }
