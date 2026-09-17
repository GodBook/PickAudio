# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.pickaudio.source.QuickJsNativeBridge { *; }
-keep class com.pickaudio.source.QuickJsNativeBridge$* { *; }
-keep class com.pickaudio.data.db.** { *; }
