package eu.kanade.tachiyomi.animeextension.en.animepahe

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Automatic Cloudflare bypass for animepahe.
 *
 * The site fronts everything with Cloudflare, so plain requests can get a 403 challenge.
 * When that happens the interceptor opens an invisible WebView with the device's real
 * WebView user agent, waits for the challenge to set cf_clearance, then retries the
 * request with that cookie and the user agent that solved it, so the clearance is
 * accepted instead of looping. If the challenge cannot be solved automatically, the
 * request is retried against the site's official mirrors (animepahe.com/.org), whose
 * Cloudflare screening is often less strict; a mirror that answers becomes the active
 * one for later requests.
 */
class CloudflareInterceptor(
    private val context: () -> Application,
    private val userAgent: () -> String,
    private val mirrors: List<String>,
) : Interceptor {

    private val bypassLock = Any()

    @Volatile
    private var activeMirror: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (request.url.host in mirrors) {
            activeMirror?.takeIf { it != request.url.host }?.let { mirror ->
                request = request.newBuilder()
                    .url(request.url.newBuilder().host(mirror).build())
                    .build()
            }
        }

        val response = chain.proceed(request)
        if (response.code !in ERROR_CODES || response.header("cf-ray") == null) {
            return response
        }
        response.close()

        // One WebView bypass at a time; concurrent requests serialize behind it.
        val bypass = synchronized(bypassLock) {
            CloudflareBypass(context()).getCookies(request.url.toString(), userAgent())
        }
        if (bypass != null) {
            return chain.proceed(
                request.newBuilder()
                    .header("Cookie", bypass.cookies)
                    .header("User-Agent", bypass.userAgent)
                    .build(),
            )
        }

        // Auto-solve failed (interactive challenge): try the official mirrors.
        for (mirror in mirrors - request.url.host) {
            val mirrored = request.newBuilder()
                .url(request.url.newBuilder().host(mirror).build())
                .build()
            val mirrorResponse = chain.proceed(mirrored)
            if (mirrorResponse.code !in ERROR_CODES) {
                activeMirror = mirror
                return mirrorResponse
            }
            mirrorResponse.close()
        }

        return chain.proceed(request)
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
    }
}

private data class BypassResult(
    val cookies: String,
    val userAgent: String,
)

private class CloudflareBypass(private val context: Application) {

    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    fun getCookies(pageUrl: String, userAgent: String): BypassResult? {
        clearCookiesForUrl(pageUrl)

        val latch = CountDownLatch(1)
        var result: BypassResult? = null
        var webView: WebView? = null
        val cancelled = AtomicBoolean(false)

        Handler(Looper.getMainLooper()).post {
            webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = userAgent
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    pollForClearance(pageUrl, userAgent, cancelled) {
                        result = it
                        latch.countDown()
                    }
                }
            }
            webView.loadUrl(pageUrl)
        }

        try {
            latch.await(30, TimeUnit.SECONDS)
        } finally {
            cancelled.set(true)
            Handler(Looper.getMainLooper()).post { webView?.destroy() }
        }
        return result
    }

    private fun pollForClearance(
        url: String,
        userAgent: String,
        cancelled: AtomicBoolean,
        onComplete: (BypassResult) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val startTime = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                if (cancelled.get()) return
                if (System.currentTimeMillis() - startTime >= 30_000L) return
                val cookies = CookieManager.getInstance().getCookie(url)
                if (cookies?.contains("cf_clearance=") == true) {
                    onComplete(BypassResult(cookies, userAgent))
                } else {
                    handler.postDelayed(this, 500L)
                }
            }
        }
        handler.post(runnable)
    }

    private fun clearCookiesForUrl(pageUrl: String) {
        val domain = Uri.parse(pageUrl).host ?: return
        val cookieManager = CookieManager.getInstance()
        listOf("https://$domain", "https://www.$domain").forEach { url ->
            cookieManager.getCookie(url)?.split(";")?.forEach { cookieStr ->
                val cookieName = cookieStr.substringBefore("=").trim()
                if (cookieName.isNotEmpty()) {
                    cookieManager.setCookie(url, "$cookieName=; Max-Age=0; path=/")
                    cookieManager.setCookie(url, "$cookieName=; Max-Age=0; path=/; domain=.$domain")
                }
            }
        }
        cookieManager.flush()
    }
}
