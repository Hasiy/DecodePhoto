# Keep JNI entry-point names and the exception class resolved from native code.
-keepclasseswithmembernames,includedescriptorclasses class top.hasiy.libheif.** {
    native <methods>;
}
-keep class top.hasiy.libheif.LibHeifException { *; }
