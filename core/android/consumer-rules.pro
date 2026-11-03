# The native library finds AlmucantarNative and its methods by name (JNI). R8 cannot see
# these references, so it must not rename or remove them.
-keep class at.stefl.almucantar.core.AlmucantarNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
