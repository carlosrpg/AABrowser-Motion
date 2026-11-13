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

package com.kododake.aabrowser.web

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.kododake.aabrowser.R
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.model.UserAgentProfile

/** A tab drawn by the system WebView: the standard build, and the car hosts. */
class WebViewPage(
    private val context: Context,
    onRequestSpeechMicrophone: (pageUrl: String?) -> Unit,
    private val onSanitizeJsExternalUrl: (WebView, String?) -> Uri?,
    private val onOpenUriExternally: (Uri) -> Unit
) : BrowserPage {
    val webView: WebView = object : WebView(context) {
        // Chromium pauses video once the window is hidden; keep it "visible" so media plays in the background.
        // Idle pages are still paused via WebView.onPause() in BrowserShellController.onPause().
        override fun onWindowVisibilityChanged(visibility: Int) {
            if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE)
        }

        // Detaching hides the page for Chromium (pausing video); logged to diagnose car-host teardowns.
        override fun onDetachedFromWindow() {
            android.util.Log.i("BrowserTab", "WebView detached from window: $url")
            super.onDetachedFromWindow()
        }
    }.apply { overScrollMode = View.OVER_SCROLL_NEVER }

    private val speechBridge = SpeechRecognitionBridge(webView, onRequestSpeechMicrophone)

    override val view: View get() = webView
    override val url: String? get() = webView.url

    override fun attach(callbacks: BrowserCallbacks) {
        configureWebView(
            webView,
            callbacks,
            BrowserPreferences.shouldUseDesktopMode(context),
            BrowserPreferences.getUserAgentProfile(context),
            BrowserPreferences.isDrmL3EnforcerEnabled(context),
            BrowserPreferences.isYouTubeAdBlockEnabled(context)
        )
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                SpeechRecognitionBridge.BRIDGE_OBJECT_NAME,
                setOf("*")
            ) { webViewInstance, message, sourceOrigin, isMainFrame, _ ->
                speechBridge.handleWebMessage(message, sourceOrigin, isMainFrame, webViewInstance.url)
            }
        }
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun openExternal(url: String) {
                (context as? Activity)?.runOnUiThread {
                    onSanitizeJsExternalUrl(webView, url)?.let(onOpenUriExternally)
                }
            }
        }, "Android")
    }

    override fun loadUrl(url: String, replaceCurrentEntry: Boolean) {
        if (replaceCurrentEntry) WebViewNavigationHelper.navigate(webView, url, replaceCurrentEntry = true) else webView.loadUrl(url)
    }

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        webView.evaluateJavascript(script, callback?.let { ValueCallback<String?> { value -> it(value) } })
    }

    override fun canGoBack() = webView.canGoBack()
    override fun canGoForward() = webView.canGoForward()
    override fun goBack() = webView.goBack()
    override fun goForward() = webView.goForward()
    override fun reload() = webView.reload()
    override fun onPause() = webView.onPause()
    override fun onResume() = webView.onResume()

    override fun allowCleartextOnce(url: String) {
        webView.setTag(R.id.webview_allow_once_uri_tag, url)
    }

    override fun isBrowserIdentityApplied(profile: UserAgentProfile, desktop: Boolean) =
        UserAgentManager.isBrowserIdentityApplied(webView, profile, desktop)

    override fun applyBrowserIdentity(profile: UserAgentProfile, desktop: Boolean) =
        webView.updateUserAgentProfile(profile, desktop)

    override fun setDrmL3Enforcer(enabled: Boolean) = webView.updateDrmL3Enforcer(enabled)

    override fun setYouTubeAdBlock(enabled: Boolean) = webView.updateYouTubeAdBlock(enabled)

    override fun onMicrophonePermissionResult(granted: Boolean) = speechBridge.onPermissionResult(granted)

    override fun release() {
        speechBridge.destroy()
        webView.releaseCompletely()
    }
}
