package com.sarmat.perevodchik.phone

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Точный офлайн-перевод моделями Helsinki-NLP OPUS-MT (лицензия CC-BY 4.0).
 * Работает в невидимом встроенном браузере — это тот же движок, что прошёл
 * автотесты в версии для iPhone. Модели скачиваются один раз и хранятся на телефоне.
 */
@SuppressLint("SetJavaScriptEnabled")
object MtEngine {
    private var web: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val progress = ConcurrentHashMap<String, (Long, Long) -> Unit>()
    private val seq = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())

    private class Bridge {
        @JavascriptInterface
        fun onReady() {
            ready.complete(Unit)
        }

        @JavascriptInterface
        fun onResult(id: String, json: String) {
            pending.remove(id)?.complete(runCatching { JSONObject(json) }.getOrElse { JSONObject().put("error", "bad json") })
            progress.remove(id)
        }

        @JavascriptInterface
        fun onProgress(id: String, loaded: Double, total: Double) {
            progress[id]?.invoke(loaded.toLong(), total.toLong())
        }
    }

    /** Создаёт невидимый браузер (один раз за время работы приложения). */
    fun init(context: Context) {
        val app = context.applicationContext
        main.post {
            if (web != null) return@post
            // Свой обработчик: модулям JS и wasm нужны правильные типы файлов
            val assets = WebViewAssetLoader.PathHandler { path ->
                val mime = when {
                    path.endsWith(".js") || path.endsWith(".mjs") -> "text/javascript"
                    path.endsWith(".wasm") -> "application/wasm"
                    path.endsWith(".html") -> "text/html"
                    path.endsWith(".json") -> "application/json"
                    else -> "application/octet-stream"
                }
                try {
                    WebResourceResponse(mime, "utf-8", app.assets.open(path))
                } catch (e: java.io.IOException) {
                    null
                }
            }
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", assets)
                .build()
            web = WebView(app).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                addJavascriptInterface(Bridge(), "AndroidBridge")
                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)
                }
                loadUrl("https://appassets.androidplatform.net/assets/mt/mt.html")
            }
        }
    }

    private suspend fun call(
        context: Context,
        cmd: String,
        args: JSONObject,
        timeoutMs: Long,
        onProgress: ((Long, Long) -> Unit)? = null
    ): JSONObject {
        init(context)
        withTimeout(30_000) { ready.await() }
        val id = "r" + seq.incrementAndGet()
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        if (onProgress != null) progress[id] = onProgress
        withContext(Dispatchers.Main) {
            web?.evaluateJavascript(
                "window.mt(${JSONObject.quote(id)}, ${JSONObject.quote(cmd)}, $args)", null
            )
        }
        try {
            return withTimeout(timeoutMs) { d.await() }
        } finally {
            pending.remove(id)
            progress.remove(id)
        }
    }

    /** Перевод. null — для этой пары движок не подходит (тогда переводит ML Kit). */
    suspend fun translate(context: Context, text: String, src: String, tgt: String): String? {
        val r = call(context, "translate", JSONObject().put("text", text).put("src", src).put("tgt", tgt), 120_000)
        if (r.optBoolean("fallback")) return null
        val err = r.optString("error")
        if (err.isNotEmpty()) throw IllegalStateException(err)
        return r.optString("text")
    }

    /** supported — поддерживается ли пара; ready — скачаны ли модели. */
    suspend fun status(context: Context, a: String, b: String): Pair<Boolean, Boolean> {
        val r = call(context, "status", JSONObject().put("a", a).put("b", b), 30_000)
        return r.optBoolean("supported") to r.optBoolean("ready")
    }

    suspend fun prepare(context: Context, a: String, b: String, onProgress: (Long, Long) -> Unit) {
        val r = call(context, "prepare", JSONObject().put("a", a).put("b", b), 30 * 60_000L, onProgress)
        val err = r.optString("error")
        if (err.isNotEmpty()) throw IllegalStateException(err)
    }
}
