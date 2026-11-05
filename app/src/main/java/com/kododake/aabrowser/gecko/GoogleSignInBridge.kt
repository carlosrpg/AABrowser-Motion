/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.gecko

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Google refuses to sign in from an embedded/outdated WebView ("this browser or app may not be secure").
 * Head units ship a system WebView that cannot be updated, so sign-in pages are handed to GeckoView instead
 * (GeckoSignInActivity). When Google redirects back, Gecko's cookies are copied into the WebView's
 * CookieManager and the original tab continues at the post-login URL, keeping the rest of the app on WebView.
 */
object GoogleSignInBridge {
    private const val TAG = "GoogleSignInBridge"

    /** After a hand-off, let the WebView load Google freely for a while so a rejected session cannot loop. */
    private const val LOOP_GUARD_MS = 30_000L

    const val YOUTUBE_SIGN_IN_URL =
        "https://accounts.google.com/ServiceLogin?service=youtube&continue=" +
            "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3D%252F"

    private val SIGN_IN_PATHS = listOf(
        "/servicelogin", "/signin", "/v3/signin", "/accountchooser", "/interactivelogin",
        "/addsession", "/o/oauth2", "/signup"
    )

    private var requester: WeakReference<WebView>? = null
    private var lastCompletedAt = 0L

    fun isSignInUrl(uri: Uri?): Boolean {
        if (uri == null || uri.scheme?.lowercase() != "https") return false
        if (uri.host?.lowercase() != "accounts.google.com") return false
        val path = uri.path?.lowercase().orEmpty()
        // Prefix match: covers /ServiceLoginAuth, /signin/v2/..., /v3/signin/identifier, /o/oauth2/v2/auth, ...
        // SetSID/CheckCookie/Logout are not listed, so session plumbing still runs in the WebView.
        return SIGN_IN_PATHS.any { path.startsWith(it) }
    }

    /**
     * Returns true when the navigation was handed to Gecko and the WebView must not load it.
     * Call from WebViewClient for main-frame navigations.
     */
    fun interceptIfNeeded(webView: WebView, uri: Uri?): Boolean {
        if (!isSignInUrl(uri)) return false
        if (SystemClock.elapsedRealtime() - lastCompletedAt < LOOP_GUARD_MS) {
            Log.w(TAG, "Sign-in requested again right after a hand-off; letting the WebView handle it")
            return false
        }
        start(webView.context, uri.toString(), webView)
        return true
    }

    fun start(context: Context, url: String, webView: WebView? = null) {
        requester = webView?.let { WeakReference(it) }
        val intent = Intent(context, GeckoSignInActivity::class.java)
            .putExtra(GeckoSignInActivity.EXTRA_URL, url)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Copies Gecko's cookies into the WebView, then resumes the requesting tab at [continueUrl]. */
    fun complete(cookies: JSONArray, continueUrl: String?, onDone: (copied: Int) -> Unit) {
        lastCompletedAt = SystemClock.elapsedRealtime()
        val manager = CookieManager.getInstance()
        var copied = 0
        for (i in 0 until cookies.length()) {
            val cookie = cookies.optJSONObject(i) ?: continue
            val (url, header) = toSetCookie(cookie) ?: continue
            manager.setCookie(url, header)
            copied++
        }
        manager.flush()
        Log.i(TAG, "Copied $copied cookies from Gecko into the WebView")

        val webView = requester?.get()
        requester = null
        if (webView != null && !continueUrl.isNullOrBlank()) {
            webView.loadUrl(continueUrl)
        }
        onDone(copied)
    }

    fun cancel() {
        requester = null
    }

    /** Converts a WebExtension cookies.Cookie into (url, Set-Cookie header) for CookieManager. */
    private fun toSetCookie(cookie: JSONObject): Pair<String, String>? {
        val name = cookie.optString("name")
        val domain = cookie.optString("domain").takeIf { it.isNotBlank() } ?: return null
        val value = cookie.optString("value")
        val path = cookie.optString("path").ifBlank { "/" }
        val hostOnly = cookie.optBoolean("hostOnly", false)
        val secure = cookie.optBoolean("secure", false)
        val host = domain.trimStart('.')

        val header = StringBuilder()
        header.append(if (name.isEmpty()) value else "$name=$value")
        header.append("; Path=").append(path)
        // __Host- cookies must not carry a Domain attribute; host-only cookies likewise.
        if (!hostOnly && !name.startsWith("__Host-")) header.append("; Domain=").append(domain)
        if (secure) header.append("; Secure")
        if (cookie.optBoolean("httpOnly", false)) header.append("; HttpOnly")
        when (cookie.optString("sameSite")) {
            "no_restriction" -> if (secure) header.append("; SameSite=None")
            "lax" -> header.append("; SameSite=Lax")
            "strict" -> header.append("; SameSite=Strict")
        }
        if (cookie.has("expirationDate") && !cookie.optBoolean("session", false)) {
            val millis = (cookie.optDouble("expirationDate") * 1000).toLong()
            header.append("; Expires=").append(httpDate(millis))
        }
        val scheme = if (secure) "https" else "http"
        return "$scheme://$host$path" to header.toString()
    }

    private fun httpDate(millis: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date(millis))
}
