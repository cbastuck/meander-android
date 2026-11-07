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
import android.widget.Toast
import com.readymadeit.meander.auth.AuthSessionBridge
import com.readymadeit.meander.bridge.HkpJavascriptBridge
import com.readymadeit.meander.bridge.HkpSchemeRouter
import com.readymadeit.meander.bridge.HomeScreenPins
import com.readymadeit.meander.bridge.LaunchInbox
import com.readymadeit.meander.bridge.NativeAuthJavascriptBridge
import com.readymadeit.meander.bridge.RuntimeAuthJavascriptBridge
import com.readymadeit.meander.bridge.ShareInbox
import com.readymadeit.meander.runtime.RuntimeHost
import com.readymadeit.meander.runtime.RuntimeService
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.net.URL

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var authBridge: AuthSessionBridge
    private lateinit var router: HkpSchemeRouter
    private lateinit var shareInbox: ShareInbox

    // Set once the page has loaded, when the share sink it was given exists.
    private var webAppReady = false
    // Shares handed to the page whose delivery has not come back yet, so a
    // second drain while one runs does not deliver them twice.
    private val deliveringShares = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val runtimeHost = RuntimeHost(applicationContext)
        router = HkpSchemeRouter(applicationContext, runtimeHost)
        shareInbox = ShareInbox(applicationContext)
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
        // A recreated activity is handed the intent it was started with again;
        // a share in it was already taken into the inbox the first time.
        val relaunched = savedInstanceState != null ||
            (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (!(relaunched && ShareInbox.isShare(intent))) {
            handleIntent(intent)
        }
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
        if (ShareInbox.isShare(intent)) {
            receiveShare(intent!!)
            return
        }
        val uri = intent?.data ?: return
        // A pinned board's shortcut: kept until the web app takes it. The page
        // is told one is waiting; on a cold start it is not there yet, and it
        // takes the board as it mounts instead.
        HomeScreenPins.target(uri)?.let { (kind, id) ->
            LaunchInbox.offer(kind, id)
            webView.evaluateJavascript("window.__readymadeOnLaunch && window.__readymadeOnLaunch();", null)
            return
        }
        if (authBridge.handleRedirect(uri)) {
            Log.i(TAG, "Handled auth redirect")
        }
    }

    // Share → Readymade from another app. The bytes are copied off the main
    // thread; the share is then handed to the page, whose in-app picker asks
    // for the board.
    private fun receiveShare(intent: Intent) {
        Thread {
            val received = try {
                shareInbox.receive(intent, contentResolver)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to receive a share", error)
                ShareInbox.Received(stored = 0, tooLarge = 0)
            }
            runOnUiThread {
                if (received.tooLarge > 0) {
                    val limit = "${ShareInbox.MAX_PAYLOAD_BYTES / (1024 * 1024)} MB"
                    Toast.makeText(this, getString(R.string.share_too_large, limit), Toast.LENGTH_LONG).show()
                }
                deliverPendingShares()
            }
        }.start()
    }

    // Drains the inbox into the page. An item is removed only once the page's
    // sink took it, so one that arrives before the page has loaded waits for
    // onPageFinished.
    private fun deliverPendingShares() {
        shareInbox.sweepStalePayloads()
        if (!webAppReady) return
        for (item in shareInbox.pending()) {
            val id = item.optString("id")
            if (!deliveringShares.add(id)) continue
            val js = "window.__readymadeDeliverShare ? (window.__readymadeDeliverShare($item), true) : false"
            webView.evaluateJavascript(js) { result ->
                deliveringShares.remove(id)
                if (result == "true") {
                    shareInbox.remove(id)
                } else {
                    Log.w(TAG, "Share $id not delivered: the page has no share sink")
                }
            }
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
            override fun onPageFinished(view: WebView, url: String) {
                // The page and its share sink are live: deliver what was
                // shared before it was (a cold start from the share sheet).
                webAppReady = true
                deliverPendingShares()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                // The bundled app must be served from an http(s) origin: Chromium
                // refuses CORS fetches (ES module scripts) on custom schemes, which
                // leaves the page blank.
                if (uri.scheme != "https" || uri.host != BUNDLED_APP_HOST) {
                    return null
                }
                return try {
                    serveAppAsset(uri, request.isForMainFrame)
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

    private fun serveAppAsset(uri: Uri, forMainFrame: Boolean): WebResourceResponse {
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
        val isPage = relativePath == "index.html"
        // The vault goes only into the app's own top-level page, as the iOS
        // shell's main-frame-only script does: a frame, or a fetch from any
        // page, gets the page without it.
        val responseData = if (isPage) injectBridge(data, withSecrets = forMainFrame) else data
        val headers = buildMap {
            // The page is never readable from another origin; the assets
            // carry nothing private and stay open to CORS requests.
            if (!isPage) put("Access-Control-Allow-Origin", "*")
            // The page carries the vault, written into it as it is served: it
            // is never kept, so each load reads what is held now.
            put("Cache-Control", if (BuildConfig.DEBUG || isPage) "no-store" else "public, max-age=31536000")
        }
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
                val injected = String(injectBridge(html, withSecrets = true), Charsets.UTF_8)
                runOnUiThread {
                    webView.loadDataWithBaseURL(devUrl, injected, "text/html", "utf-8", null)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to load dev web app, falling back to bundled app", error)
                runOnUiThread { webView.loadUrl(BUNDLED_APP_URL) }
            }
        }.start()
    }

    private fun injectBridge(htmlBytes: ByteArray, withSecrets: Boolean): ByteArray {
        val html = String(htmlBytes, Charsets.UTF_8)
        val marker = "</head>"
        val injected = "<script>${bridgeScript(withSecrets)}</script>"
        return if (html.contains(marker, ignoreCase = true)) {
            html.replace(marker, "$injected$marker", ignoreCase = true)
        } else {
            "$injected$html"
        }.toByteArray(Charsets.UTF_8)
    }

    private fun bridgeScript(withSecrets: Boolean): String {
        val remoteName = jsLiteral(getString(R.string.hkp_runtime_remote_name).ifBlank { "meander-android" })
        return """
            (() => {
              window.__MEANDER_ANDROID__ = true;
              // The sink a share is delivered to (deliverPendingShares), as on
              // iOS: one that arrives before React mounts waits on the queue,
              // later ones call the app's handler directly.
              window.__READYMADE_SHARES__ = window.__READYMADE_SHARES__ || [];
              window.__readymadeDeliverShare = function (env) {
                try {
                  window.__READYMADE_SHARES__.push(env);
                  if (window.__readymadeOnShare) { window.__readymadeOnShare(env); }
                } catch (e) {}
              };
              ${if (withSecrets) router.secretsScript() else ""}
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
