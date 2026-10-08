# Keep JNI entry-point names and the exception class resolved from native code.
-keepclasseswithmembernames,includedescriptorclasses class top.hasiy.libraw.** {
    native <methods>;
}
-keep class top.hasiy.libraw.LibRawException { *; }
