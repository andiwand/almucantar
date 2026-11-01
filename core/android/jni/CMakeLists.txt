# The JNI binding (libsunradar_jni.so) for the AAR in core/android. The core includes
# this directory if SUNRADAR_JNI is ON. android/build_native.py builds it for each ABI.

add_library(sunradar_jni SHARED sunradar_jni.cpp)
target_link_libraries(sunradar_jni PRIVATE sunradar::sunradar sunradar::options)

if(ANDROID)
  # The NDK has jni.h in its sysroot. Export only the JNI functions, and drop unused code.
  target_compile_options(sunradar_jni PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
  target_link_options(sunradar_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else()
  # A host build (for clang-tidy) needs only the JNI headers. FindJNI also requires AWT and
  # the JVM library, so search the headers directly: in a JDK (JAVA_HOME), or else in the NDK
  # (ANDROID_NDK_HOME). The jni.h of the NDK needs no jni_md.h, but its sysroot must not be on
  # the host include path, so the build copies only jni.h.
  find_path(SUNRADAR_JDK_JNI_INCLUDE_DIR jni.h HINTS "$ENV{JAVA_HOME}/include" NO_DEFAULT_PATH)
  if(SUNRADAR_JDK_JNI_INCLUDE_DIR)
    target_include_directories(sunradar_jni PRIVATE "${SUNRADAR_JDK_JNI_INCLUDE_DIR}"
      "${SUNRADAR_JDK_JNI_INCLUDE_DIR}/darwin" "${SUNRADAR_JDK_JNI_INCLUDE_DIR}/linux")
  else()
    file(GLOB ndk_jni_h "$ENV{ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/*/sysroot/usr/include/jni.h")
    if(NOT ndk_jni_h)
      message(FATAL_ERROR "jni.h not found: set JAVA_HOME to a JDK with headers, or ANDROID_NDK_HOME")
    endif()
    list(GET ndk_jni_h 0 ndk_jni_h)
    configure_file("${ndk_jni_h}" "${CMAKE_CURRENT_BINARY_DIR}/jni_include/jni.h" COPYONLY)
    target_include_directories(sunradar_jni PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/jni_include")
  endif()
endif()
