# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.pickaudio.source.QuickJsNativeBridge { *; }
-keep interface com.pickaudio.source.QuickJsHostCallback { *; }
-keepclassmembers class * implements com.pickaudio.source.QuickJsHostCallback {
    public void onConsoleLog(java.lang.String, java.lang.String);
    public void onLxSend(java.lang.String, java.lang.String);
    public void onLxRequest(long, java.lang.String, java.lang.String);
    public java.lang.String md5(byte[]);
}

# Gson payloads persisted across versions; field names are part of the disk/wire format.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class com.pickaudio.backup.BackupManifest { <fields>; <init>(...); }
-keep class com.pickaudio.backup.BackupPlaylist { <fields>; <init>(...); }
-keep class com.pickaudio.backup.BackupTrack { <fields>; <init>(...); }
-keep class com.pickaudio.backup.BackupFileHint { <fields>; <init>(...); }
-keep class com.pickaudio.backup.BackupFavorite { <fields>; <init>(...); }
-keep class com.pickaudio.backup.BackupLyric { <fields>; <init>(...); }
-keep class com.pickaudio.backup.BackupSourceDescriptor { <fields>; <init>(...); }
-keep class com.pickaudio.backup.RestoreReport { <fields>; <init>(...); }
-keep class com.pickaudio.source.SourcePlatformCapability { <fields>; <init>(...); }
-keep class com.pickaudio.data.repository.LyricPayload { <fields>; <init>(...); }
-keep class com.pickaudio.update.GitHubRelease { <fields>; <init>(...); }
-keep class com.pickaudio.update.GitHubAsset { <fields>; <init>(...); }
-keep class com.pickaudio.update.VersionManifest { <fields>; <init>(...); }
