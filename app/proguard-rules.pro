-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class top.hasiy.rawviewer.raw.RawNativeException { *; }

# ExifMetadataReader enumerates AndroidX's public TAG_* constants at runtime.
-keepclassmembers class androidx.exifinterface.media.ExifInterface {
    public static java.lang.String TAG_*;
}
