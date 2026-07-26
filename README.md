# meander-android

Android port of the readymade Meander shell. It embeds the shared mobile web app
in an Android `WebView`, exposes the same `hkp://` bridge used by the iOS app,
stores boards locally, proxies REST calls to a runtime, and delegates Auth0 login
to the native browser with Authorization Code + PKCE.

## Building

See [README-android.md](../README-android.md) in the repo root — prerequisites
(NDK/CMake versions, the prebuilt vcpkg trees), the `./build-android.sh` CLI build,
the Android Studio route, and how to refresh the bundled web app.

## Architecture

`MainActivity` hosts a full-screen WebView. The bundled app is loaded from
`hkp://app/index.html`. Android WebView cannot reliably run `fetch()` directly
against custom schemes, so the page receives a tiny injected fetch bridge:
existing web code still calls `fetch("hkp://boards/…")`, while native Kotlin
handles the request and returns a normal `Response`.

Routes:

| Route | Purpose |
| --- | --- |
| `hkp://app/...` | Bundled frontend assets |
| `hkp://boards/...` | Local board list / load / save / delete |
| `hkp://remotes/` | Lists the built-in runtime remote |
| `hkp://remotes/meander-android/...` | Proxies REST calls to the configured runtime |
| `hkp://settings` | Runtime exposure settings persisted in app preferences |

## Embedded runtime

Like iOS, the app starts an embedded `hkp-rt` server in-process. `RuntimeHost` loads
`libhkprt_jni.so` (built from `app/src/main/cpp/` by the Gradle `externalNativeBuild`)
and drives it over JNI — `nativeStart` / `nativeStop` / `nativePort` /
`nativeSetAllowedUsers` — with `RuntimeService` keeping it alive as a foreground service.

Parity gap: iOS also registers Objective-C++ native services for device motion, location,
microphone, and speaker. Those do not have Android-native implementations yet.
