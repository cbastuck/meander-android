# Overlay triplet for the Android build. Identical to vcpkg's stock
# arm64-android except VCPKG_CMAKE_SYSTEM_VERSION is pinned to 26 to match the
# app's minSdk (build.gradle.kts). Keeping the dependency API level == minSdk
# avoids linking symbols newer than the oldest supported device, which would
# make System.loadLibrary() fail at runtime on API 26/27.
set(VCPKG_TARGET_ARCHITECTURE arm64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_CMAKE_SYSTEM_NAME Android)
set(VCPKG_CMAKE_SYSTEM_VERSION 26)
set(VCPKG_MAKE_BUILD_TRIPLET "--host=aarch64-linux-android")
set(VCPKG_CMAKE_CONFIGURE_OPTIONS -DANDROID_ABI=arm64-v8a)
