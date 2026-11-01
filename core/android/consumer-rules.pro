# The native library finds SunradarNative and its methods by name (JNI). R8 cannot see
# these references, so it must not rename or remove them.
-keep class at.stefl.sunradar.core.SunradarNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
