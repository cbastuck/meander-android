# Overlay triplet for the Android build (emulator ABI). Identical to vcpkg's
# stock x64-android except VCPKG_CMAKE_SYSTEM_VERSION is pinned to 26 to match
# the app's minSdk. See arm64-android.cmake in this directory for rationale.
set(VCPKG_TARGET_ARCHITECTURE x64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_CMAKE_SYSTEM_NAME Android)
set(VCPKG_CMAKE_SYSTEM_VERSION 26)
set(VCPKG_MAKE_BUILD_TRIPLET "--host=x86_64-linux-android")
set(VCPKG_CMAKE_CONFIGURE_OPTIONS -DANDROID_ABI=x86_64)
