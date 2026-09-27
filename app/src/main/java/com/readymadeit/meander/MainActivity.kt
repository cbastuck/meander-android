package com.readymadeit.meander

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.readymadeit.meander.auth.AuthSessionBridge
import com.readymadeit.meander.bridge.HkpJavascriptBridge
import com.readymadeit.meander.bridge.HkpSchemeRouter
import com.readymadeit.meander.bridge.NativeAuthJavascriptBridge
import com.readymadeit.meander.bridge.RuntimeAuthJavascriptBridge
import com.readymadeit.meander.runtime.RuntimeHost
import com.readymadeit.meander.runtime.RuntimeService
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.net.URL

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var authBridge: AuthSessionBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val runtimeHost = RuntimeHost(applicationContext)
        val router = HkpSchemeRouter(applicationContext, runtimeHost)
        authBridge = AuthSessionBridge(this, packageName)

        // Bring up the embedded hkp-rt runtime in a foreground service so it (and
        // LAN discovery) keeps working while the app is backgrounded. The router
        // resolves the runtime's port lazily, absorbing the brief startup race.
        RuntimeService.start(applicationContext)

        webView = WebView(this)
        setContentView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        configureWebView(webView, router, runtimeHost)
        handleIntent(intent)
        requestRuntimePermissions()

        val devUrl = getString(R.string.dev_webapp_url)
        loadInitialUrl(devUrl)
    }

    // Runtime permissions needed on newer Android: NEARBY_WIFI_DEVICES (API 33+)
    // gates LAN discovery; POST_NOTIFICATIONS (API 33+) lets the foreground
    // service show its notification. Both are best-effort — discovery still fails
    // gracefully if denied.
    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val needed = listOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQUEST_RUNTIME_PERMISSIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (authBridge.handleRedirect(uri)) {
            Log.i(TAG, "Handled auth redirect")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView, router: HkpSchemeRouter, runtimeHost: RuntimeHost) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        webView.addJavascriptInterface(HkpJavascriptBridge(router), "AndroidHkpBridge")
        webView.addJavascriptInterface(NativeAuthJavascriptBridge(authBridge), "AndroidAuthBridge")
        webView.addJavascriptInterface(RuntimeAuthJavascriptBridge(runtimeHost), "AndroidRuntimeAuthBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(TAG, "${consoleMessage.messageLevel()}: ${consoleMessage.message()}")
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                // The bundled app must be served from an http(s) origin: Chromium
                // refuses CORS fetches (ES module scripts) on custom schemes, which
                // leaves the page blank.
                if (uri.scheme != "https" || uri.host != BUNDLED_APP_HOST) {
                    return null
                }
                return try {
                    serveAppAsset(uri)
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to serve ${uri}", error)
                    WebResourceResponse(
                        "application/json",
                        "utf-8",
                        404,
                        "Not Found",
                        emptyMap(),
                        ByteArrayInputStream("""{"error":"${error.message ?: "Not found"}"}""".toByteArray()),
                    )
                }
            }
        }
    }

    private fun serveAppAsset(uri: Uri): WebResourceResponse {
        val path = normalizedAssetPath(uri.path ?: "")
        val relativePath = when {
            path.isEmpty() || path == "index.html" -> "index.html"
            path.startsWith("assets/") -> path
            else -> "index.html"
        }
        if (relativePath.contains("..")) {
            throw FileNotFoundException("Forbidden path")
        }

        val data = assets.open("WebApp/$relativePath").use { it.readBytes() }
        val responseData = if (relativePath == "index.html") injectBridge(data) else data
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to if (BuildConfig.DEBUG) "no-store" else "public, max-age=31536000",
        )
        return WebResourceResponse(
            mimeType(relativePath),
            "utf-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(responseData),
        )
    }

    private fun loadInitialUrl(devUrl: String) {
        if (devUrl.isBlank()) {
            webView.loadUrl(BUNDLED_APP_URL)
            return
        }

        Thread {
            try {
                val html = URL(devUrl).openStream().use { it.readBytes() }
                val injected = String(injectBridge(html), Charsets.UTF_8)
                runOnUiThread {
                    webView.loadDataWithBaseURL(devUrl, injected, "text/html", "utf-8", null)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to load dev web app, falling back to bundled app", error)
                runOnUiThread { webView.loadUrl(BUNDLED_APP_URL) }
            }
        }.start()
    }

    private fun injectBridge(htmlBytes: ByteArray): ByteArray {
        val html = String(htmlBytes, Charsets.UTF_8)
        val marker = "</head>"
        val injected = "<script>${bridgeScript()}</script>"
        return if (html.contains(marker, ignoreCase = true)) {
            html.replace(marker, "$injected$marker", ignoreCase = true)
        } else {
            "$injected$html"
        }.toByteArray(Charsets.UTF_8)
    }

    private fun bridgeScript(): String {
        val remoteName = jsLiteral(getString(R.string.hkp_runtime_remote_name).ifBlank { "meander-android" })
        return """
            (() => {
              window.__MEANDER_ANDROID__ = true;
              window.__MEANDER_CONFIG__ = Object.assign(window.__MEANDER_CONFIG__ || {}, {
                runtimeBaseURL: "hkp://remotes/" + $remoteName
              });
              const originalFetch = window.fetch.bind(window);
              const toBase64 = async (body) => {
                if (body == null) return "";
                let bytes;
                if (typeof body === "string") {
                  bytes = new TextEncoder().encode(body);
                } else if (body instanceof ArrayBuffer) {
                  bytes = new Uint8Array(body);
                } else if (ArrayBuffer.isView(body)) {
                  bytes = new Uint8Array(body.buffer, body.byteOffset, body.byteLength);
                } else if (body instanceof Blob) {
                  bytes = new Uint8Array(await body.arrayBuffer());
                } else {
                  bytes = new TextEncoder().encode(String(body));
                }
                let binary = "";
                for (let i = 0; i < bytes.length; i += 0x8000) {
                  binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
                }
                return btoa(binary);
              };
              const fromBase64 = (value) => {
                const binary = atob(value || "");
                const bytes = new Uint8Array(binary.length);
                for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                return bytes;
              };
              const headersObject = (headers) => {
                const out = {};
                new Headers(headers || {}).forEach((value, key) => { out[key] = value; });
                return out;
              };
              window.fetch = async (input, init = {}) => {
                const url = typeof input === "string" ? input : input.url;
                if (!String(url).startsWith("hkp://")) {
                  return originalFetch(input, init);
                }
                const request = input instanceof Request ? input : null;
                const method = (init.method || request?.method || "GET").toUpperCase();
                const headers = headersObject(init.headers || request?.headers);
                const body = init.body ?? (request && method !== "GET" && method !== "HEAD" ? await request.arrayBuffer() : null);
                const raw = AndroidHkpBridge.fetch(JSON.stringify({
                  url: String(url),
                  method,
                  headers,
                  bodyBase64: await toBase64(body)
                }));
                const reply = JSON.parse(raw);
                return new Response(fromBase64(reply.bodyBase64), {
                  status: reply.status,
                  statusText: reply.statusText || "",
                  headers: reply.headers || {}
                });
              };
              window.hkpAuth = {
                postMessage: async (body) => JSON.parse(AndroidAuthBridge.postMessage(JSON.stringify(body || {})))
              };
              window.hkpRuntimeAuth = {
                postMessage: (body) => AndroidRuntimeAuthBridge.postMessage(JSON.stringify(body || {}))
              };
              console.log("Android HKP bridge installed @ " + location.href);
            })();
        """.trimIndent()
    }

    private fun normalizedAssetPath(path: String): String = path.trim('/')

    private fun mimeType(path: String): String =
        when (path.substringAfterLast('.', "")) {
            "css" -> "text/css"
            "html" -> "text/html"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "ttf" -> "font/ttf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            // WebAssembly compiles while it streams in only when served as
            // what it is; otherwise the page falls back to a slower copy.
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }

    private fun jsLiteral(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun String.trim(char: Char): String = trim { it == char }

    companion object {
        private const val TAG = "ReadymadeAndroid"
        private const val REQUEST_RUNTIME_PERMISSIONS = 42

        // Reserved Android domain for locally served app assets; requests never hit
        // the network, they are all answered in shouldInterceptRequest.
        private const val BUNDLED_APP_HOST = "appassets.androidplatform.net"
        private const val BUNDLED_APP_URL = "https://$BUNDLED_APP_HOST/index.html"
    }
}
