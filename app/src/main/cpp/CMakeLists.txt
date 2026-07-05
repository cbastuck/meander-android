# ── Android NDK build for the embedded hkp-rt runtime ─────────────────────────
# Invoked by the Android Gradle Plugin's externalNativeBuild. Builds hkp-rt-lib
# (the portable C++ runtime) plus a small JNI shim (libhkprt_jni.so) that the
# Kotlin layer loads to start/stop the server — the Android analogue of iOS's
# HKPRuntimeHost.mm / meander-ios/CMakeLists.txt.
#
# Gradle already sets CMAKE_TOOLCHAIN_FILE to the NDK's android.toolchain.cmake
# and defines ANDROID_ABI. We select the matching classic-mode vcpkg triplet
# from that ABI so find_package(Boost/OpenSSL) resolves from the prebuilt
# 3rdparty/vcpkg/installed/<triplet> tree (install with e.g.
#   ANDROID_NDK_HOME=... ./3rdparty/vcpkg/vcpkg install boost-asio ... openssl \
#       --triplet arm64-android).

cmake_minimum_required(VERSION 3.22)

# Repo root is five levels up: cpp → main → src → app → meander-android → hkp.
get_filename_component(HKP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)

# ── Select the vcpkg triplet for this ABI (must be set before 3rdparty) ────────
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(VCPKG_TARGET_TRIPLET "arm64-android" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(VCPKG_TARGET_TRIPLET "x64-android" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(VCPKG_TARGET_TRIPLET "arm-neon-android" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86")
    set(VCPKG_TARGET_TRIPLET "x86-android" CACHE STRING "" FORCE)
else()
    message(FATAL_ERROR "Unsupported ANDROID_ABI for hkp-rt: ${ANDROID_ABI}")
endif()

project(hkprt_android LANGUAGES CXX)

# ── Disable components that don't apply to Android (mirror the iOS build) ──────
set(BUILD_HKP_SAUCER OFF CACHE BOOL "Disable Saucer GUI for Android" FORCE)
set(ENABLE_FFMPEG    OFF CACHE BOOL "Disable FFmpeg for Android"      FORCE)
set(BUILD_TESTING    OFF CACHE BOOL ""                               FORCE)

# ── Dependencies: 3rdparty (CPM + vcpkg) then hkp-rt-lib ───────────────────────
# 3rdparty must be added before hkp-rt/lib; it defines the hkp-rt-deps target and
# runs CPM before including vcpkg (ordering required to avoid ALIAS conflicts).
if(NOT TARGET hkp-rt-deps)
    add_subdirectory(${HKP_ROOT}/3rdparty ${CMAKE_CURRENT_BINARY_DIR}/3rdparty)
endif()

if(NOT TARGET hkp-rt-lib)
    add_subdirectory(${HKP_ROOT}/hkp-rt/lib ${CMAKE_CURRENT_BINARY_DIR}/hkp-rt-lib)
endif()

# ── JNI shim ───────────────────────────────────────────────────────────────────
add_library(hkprt_jni SHARED hkp_runtime_jni.cpp)

target_compile_features(hkprt_jni PRIVATE cxx_std_20)
set_target_properties(hkprt_jni PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON)

# hkp-rt-lib propagates its public include dirs; `log` for __android_log_print.
find_library(ANDROID_LOG_LIB log)
target_link_libraries(hkprt_jni PRIVATE hkp-rt-lib ${ANDROID_LOG_LIB})

# 16 KB page-size support: align ELF LOAD segments to 16 KB so the library loads
# on 16 KB-page devices/emulators (Android 15+, e.g. the sdk_gphone16k images).
# NDK r27 still defaults to 4 KB (16 KB is the r28 default), so opt in explicitly.
target_link_options(hkprt_jni PRIVATE "-Wl,-z,max-page-size=16384")
