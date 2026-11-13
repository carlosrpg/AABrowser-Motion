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

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import androidx.core.content.ContextCompat
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.model.UserAgentProfile
import com.kododake.aabrowser.web.BrowserCallbacks
import com.kododake.aabrowser.web.BrowserPage
import com.kododake.aabrowser.web.UserAgentManager
import com.kododake.aabrowser.web.WebScripts
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/**
 * A tab on GeckoView. Page scripts run through the page bridge extension (see GeckoEngine and
 * assets/extensions/page_bridge), so the shell's WebView-style scripts work unchanged.
 */
internal class GeckoPage(private val context: Context) : BrowserPage {
    // A TextureView draws like any view: shown, hidden and stacked with the other tabs. With the default
    // SurfaceView a tab switched to stayed blank behind another tab's surface.
    private val geckoView = GeckoView(context).apply { setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW) }
    private val session = GeckoSession(
        GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            // Keep playing when the tab or the app goes to the background, as the WebView build does.
            .suspendMediaWhenInactive(false)
            .build()
    )
    private lateinit var callbacks: BrowserCallbacks

    override val view: View get() = geckoView
    override val drawsOwnFullscreen: Boolean get() = true
    override var url: String? = null
        private set

    private var canGoBack = false
    private var canGoForward = false
    private var cleartextAllowedOnce: String? = null
    private var identity: Pair<UserAgentProfile, Boolean>? = null
    private var released = false

    // Page bridge: one port per document; the page loads only once the bridge listens, so it can connect.
    private var port: WebExtension.Port? = null
    private var bridgeReady = false
    private var queuedLoad: Pair<String, Boolean>? = null
    private var nextScriptId = 0
    private val pendingScripts = HashMap<Int, (String?) -> Unit>()

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var media: MediaSession? = null
    private var resumeOnFocusGain = false

    // Unlike Chromium, Gecko leaves audio focus to the app: without it the car keeps its own source playing.
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> media?.pause()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> media?.let {
                    resumeOnFocusGain = true
                    it.pause()
                }
                AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    media?.play()
                }
            }
        }
        .build()

    override fun attach(callbacks: BrowserCallbacks) {
        this.callbacks = callbacks
        session.navigationDelegate = navigationDelegate
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, progress: Int) = callbacks.onProgressChange(progress)
        }
        session.contentDelegate = contentDelegate
        session.permissionDelegate = permissionDelegate
        session.promptDelegate = GeckoDialogPrompts(context)
        session.mediaSessionDelegate = mediaDelegate
        applyIdentity(BrowserPreferences.getUserAgentProfile(context), BrowserPreferences.shouldUseDesktopMode(context))
        session.open(GeckoEngine.runtime(context))
        geckoView.setSession(session)
        if (BrowserPreferences.isYouTubeAdBlockEnabled(context)) updateYouTubeAdBlock(true)

        GeckoEngine.withPageBridge(context) { extension ->
            if (released) return@withPageBridge
            session.webExtensionController.setMessageDelegate(extension, bridgeDelegate, GeckoEngine.PAGE_APP)
            bridgeReady = true
            queuedLoad?.let { (url, replace) -> loadUrl(url, replace) }
            queuedLoad = null
        }
    }

    override fun loadUrl(url: String, replaceCurrentEntry: Boolean) {
        if (released) return
        if (!bridgeReady) {
            queuedLoad = url to replaceCurrentEntry
            return
        }
        val loader = GeckoSession.Loader().uri(url)
        if (replaceCurrentEntry) loader.flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY)
        session.load(loader)
    }

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        val target = port
        if (released || target == null) {
            callback?.invoke(null)
            return
        }
        val id = nextScriptId++
        if (callback != null) pendingScripts[id] = callback
        target.postMessage(
            JSONObject().put("type", "eval").put("id", id).put("code", script).put("reply", callback != null)
        )
    }

    private fun failPendingScripts() {
        val waiting = pendingScripts.values.toList()
        pendingScripts.clear()
        waiting.forEach { it(null) }
    }

    private val bridgeDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(newPort: WebExtension.Port) {
            failPendingScripts() // asked of the previous document
            port = newPort
            newPort.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val reply = message as? JSONObject ?: return
                    val callback = pendingScripts.remove(reply.optInt("id", -1)) ?: return
                    val error = reply.optString("error").takeIf { !reply.isNull("error") && it.isNotEmpty() }
                    if (error != null) Log.w(TAG, "page script failed: $error")
                    callback(if (error != null) "null" else reply.optString("result", "null"))
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    if (this@GeckoPage.port === port) {
                        this@GeckoPage.port = null
                        failPendingScripts()
                    }
                }
            })
        }
    }

    override fun canGoBack() = canGoBack
    override fun canGoForward() = canGoForward
    override fun goBack() = session.goBack()
    override fun goForward() = session.goForward()
    override fun reload() = session.reload()

    // GeckoSession.setActive mirrors WebView.onPause/onResume; media keeps playing (suspendMediaWhenInactive).
    override fun onPause() = session.setActive(false)
    override fun onResume() = session.setActive(true)

    override fun allowCleartextOnce(url: String) {
        cleartextAllowedOnce = url
    }

    override fun isBrowserIdentityApplied(profile: UserAgentProfile, desktop: Boolean) = identity == (profile to desktop)

    override fun applyBrowserIdentity(profile: UserAgentProfile, desktop: Boolean) {
        applyIdentity(profile, desktop)
        session.reload()
    }

    private fun applyIdentity(profile: UserAgentProfile, desktop: Boolean) {
        identity = profile to desktop
        // The default profile keeps Gecko's own user agent: a Chrome one on a Gecko engine makes sites send code
        // meant for Chrome, and Google's sign-in refuses it. The other profiles are explicit choices.
        session.settings.userAgentOverride =
            if (profile == UserAgentProfile.ANDROID_CHROME) null else UserAgentManager.buildUserAgent(context, profile, desktop)
        session.settings.userAgentMode =
            if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.viewportMode =
            if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
    }

    // Forces Widevine L3 in Chromium; Gecko's own Widevine path plays DRM without it (Prime Video verified).
    override fun setDrmL3Enforcer(enabled: Boolean) = Unit

    override fun setYouTubeAdBlock(enabled: Boolean) {
        updateYouTubeAdBlock(enabled)
        if (Uri.parse(url.orEmpty()).host.orEmpty().let { it == "youtube.com" || it.endsWith(".youtube.com") }) reload()
    }

    private fun updateYouTubeAdBlock(enabled: Boolean) = GeckoEngine.setStartupScript(
        context,
        AD_BLOCK_SCRIPT_KEY,
        WebScripts.YOUTUBE_AD_BLOCK_JS.takeIf { enabled },
        WebScripts.YOUTUBE_AD_BLOCK_ORIGINS.map { "$it/*" }
    )

    // The Web Speech polyfill is WebView-only; Gecko pages never ask.
    override fun onMicrophonePermissionResult(granted: Boolean) = Unit

    override fun release() {
        if (released) return
        released = true
        audioManager.abandonAudioFocusRequest(focusRequest)
        failPendingScripts()
        port = null
        (geckoView.parent as? ViewGroup)?.removeView(geckoView)
        geckoView.releaseSession()
        session.close()
    }

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean
        ) {
            // Gecko reports about:blank for a session that has loaded nothing yet; WebView reports nothing, and the
            // shell takes any URL as "this tab has a page" (no start page).
            if (url == null || url == "about:blank") return
            this@GeckoPage.url = url
            callbacks.onUrlChange(url)
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@GeckoPage.canGoBack = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            this@GeckoPage.canGoForward = canGoForward
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny>? {
            val uri = Uri.parse(request.uri)
            val scheme = uri.scheme?.lowercase()
            // Same as the WebView build: other schemes (intent:, market:, tel:) are not opened.
            if (scheme != null && scheme !in PAGE_SCHEMES) return GeckoResult.fromValue(AllowOrDeny.DENY)
            if (scheme != "http") return null
            if (cleartextAllowedOnce == request.uri) {
                cleartextAllowedOnce = null
                return null
            }
            val host = uri.host?.lowercase()
            if (BrowserPreferences.isHostAllowedCleartext(context, host)) return null
            val target = request.uri
            callbacks.onCleartextNavigationRequested(
                uri,
                {
                    allowCleartextOnce(target)
                    loadUrl(target)
                },
                {
                    if (host != null) BrowserPreferences.addAllowedCleartextHost(context, host)
                    allowCleartextOnce(target)
                    loadUrl(target)
                },
                {}
            )
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

        // shortcut: target=_blank and window.open open as a new tab without an opener, so pages that talk to
        // their popup (some OAuth logins) do not get an answer; return a GeckoSession here if that is needed.
        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            callbacks.onCreateNewWindow()?.loadUrl(uri)
            return null
        }

        // GeckoView has no error page of its own (a failed load stays blank): show the app's, as the WebView build does.
        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String> {
            callbacks.onError(error.code, uri)
            val message = when {
                error.code == WebRequestError.ERROR_UNKNOWN_HOST -> "Server not found"
                error.code == WebRequestError.ERROR_NET_TIMEOUT -> "Connection timed out"
                error.code == WebRequestError.ERROR_CONNECTION_REFUSED -> "Connection refused"
                error.code == WebRequestError.ERROR_OFFLINE -> "No internet connection"
                error.category == WebRequestError.ERROR_CATEGORY_SECURITY -> "Secure connection failed"
                else -> ""
            }
            return GeckoResult.fromValue(
                "resource://android/assets/error.html?failedUrl=${Uri.encode(uri.orEmpty())}" +
                    "&code=${error.code}&message=${Uri.encode(message)}"
            )
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) = callbacks.onTitleChange(title)

        // The page stays where it is and fills itself with the video; the shell gets an empty, see-through view so it
        // hides its controls and the system bars. Moving the GeckoView instead takes focus from the page, and Gecko
        // then leaves fullscreen at once.
        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            if (fullScreen) {
                callbacks.onEnterFullscreen(View(context), WebChromeClient.CustomViewCallback {
                    // The shell left fullscreen (back key): take the page out of it too.
                    this@GeckoPage.session.exitFullScreen()
                })
            } else {
                callbacks.onExitFullscreen()
            }
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) =
            callbacks.onShowDownloadPrompt(Uri.parse(response.uri))

        // The content process died (crash or killed for memory): only the page went down, so bring it back.
        override fun onCrash(session: GeckoSession) = recover("crashed")

        override fun onKill(session: GeckoSession) = recover("was killed")
    }

    private fun recover(what: String) {
        if (released) return
        Log.w(TAG, "content process $what, reloading $url")
        port = null
        failPendingScripts()
        if (!session.isOpen) session.open(GeckoEngine.runtime(context))
        url?.let { session.loadUri(it) }
    }

    private val permissionDelegate = object : PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: PermissionDelegate.ContentPermission
        ): GeckoResult<Int> = when (perm.permission) {
            // As the WebView build: protected media (DRM) and autoplay are always allowed.
            PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS,
            PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
            PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
            PermissionDelegate.PERMISSION_STORAGE_ACCESS -> GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_ALLOW)
            PermissionDelegate.PERMISSION_GEOLOCATION -> {
                val result = GeckoResult<Int>()
                callbacks.onGeolocationPermissionRequest(
                    Uri.parse(perm.uri).let { "${it.scheme}://${it.authority}" },
                    GeolocationPermissions.Callback { _, allow, _ ->
                        result.complete(
                            if (allow) PermissionDelegate.ContentPermission.VALUE_ALLOW else PermissionDelegate.ContentPermission.VALUE_DENY
                        )
                    }
                )
                result
            }
            else -> GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_DENY)
        }

        // Asked after a site permission was granted (location): the shell already asked Android for it.
        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: PermissionDelegate.Callback
        ) {
            val granted = permissions.orEmpty().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            if (granted) callback.grant() else callback.reject()
        }

        // shortcut: no camera/microphone for pages (getUserMedia); the WebView build only allows it for Web Speech.
        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out PermissionDelegate.MediaSource>?,
            audio: Array<out PermissionDelegate.MediaSource>?,
            callback: PermissionDelegate.MediaCallback
        ) = callback.reject()
    }

    private val mediaDelegate = object : MediaSession.Delegate {
        override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
            media = mediaSession
        }

        override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
            media = mediaSession
            audioManager.requestAudioFocus(focusRequest)
        }

        override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
            if (media === mediaSession) media = null
            resumeOnFocusGain = false
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    private companion object {
        const val TAG = "GeckoPage"
        const val AD_BLOCK_SCRIPT_KEY = "youtube-ad-block"
        val PAGE_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "file", "resource")
    }
}
