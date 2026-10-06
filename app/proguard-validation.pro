# Instrumentation calls app APIs which ordinary production callers do not retain.
# This rule belongs only to the validation variant; Release still prunes/renames these APIs.
# Gson payloads and JNI contracts use the same precise production rules in both variants.
-keep,allowoptimization class com.pickaudio.** { public *; }
# The runner uses this shared dependency after the app shrinker would otherwise inline it.
-keep class androidx.tracing.Trace { *; }
# Shared libraries are excluded from the test APK. Retain their public ABI for
# the runner/Compose/Room/MediaController/MockWebServer callers in that APK.
# Material icon classes remain eligible for pruning, as in the production build.
-keep class kotlin.** { public *; }
-keep class kotlinx.coroutines.** { public *; }
-keep class !androidx.compose.material.icons.**,androidx.** { public *; }
-keep class okhttp3.** { public *; }
-keep class okio.** { public *; }
-keep class com.google.gson.** { public *; }
