# readymade-android

Android port of the readymade Meander shell. It embeds the shared mobile web app
in an Android `WebView`, exposes the same `hkp://` bridge used by the iOS app,
stores boards locally, proxies REST calls to a runtime, and delegates Auth0 login
to the native browser with Authorization Code + PKCE.

## Building

From the repo root:

```bash
./build-android.sh Debug
```

The script builds `meander/frontend` with the Android Vite target and copies the
result into `readymade-android/app/src/main/assets/WebApp/`, then runs Gradle.

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

## Current parity note

The iOS app starts an embedded `hkp-rt` server and registers Objective-C++ native
services for device motion, location, microphone, and speaker. This Android port
keeps that behind `RuntimeHost`, currently backed by a configured HTTP runtime
URL (`@string/hkp_runtime_base_url`). Porting the embedded runtime requires JNI
bindings plus Android-native implementations of those services.
