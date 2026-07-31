# JNI entry points are resolved by name from native code.
-keepclasseswithmembernames class com.marathonrecomp.launcher.NativeBridge {
    native <methods>;
}
-keep class com.marathonrecomp.launcher.NativeBridge { *; }
