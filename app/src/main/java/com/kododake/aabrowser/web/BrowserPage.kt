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

import android.view.View
import com.kododake.aabrowser.model.UserAgentProfile

/**
 * One tab's page, whichever engine draws it: the system WebView ([WebViewPage]), or GeckoView on the
 * automotive build (head units ship a WebView too old for current sites). The shell only talks to this.
 */
interface BrowserPage {
    val view: View
    val url: String?

    /**
     * True when the page draws its own fullscreen video in place (GeckoView): the shell then gets an empty view
     * for fullscreen and must keep the page shown. WebView hands over a separate video view instead.
     */
    val drawsOwnFullscreen: Boolean get() = false

    /** Wires the page's events to the shell. Called once, right after the tab is created. */
    fun attach(callbacks: BrowserCallbacks)

    fun loadUrl(url: String, replaceCurrentEntry: Boolean = false)

    /** As WebView's: [callback] gets the JSON of the script's value, "null" when there is none or no page. */
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?)

    fun canGoBack(): Boolean
    fun canGoForward(): Boolean
    fun goBack()
    fun goForward()
    fun reload()

    /** A hidden tab, or the app in the background with nothing playing. */
    fun onPause()
    fun onResume()

    /** The next load of [url] skips the cleartext (http) prompt: the user just allowed it. */
    fun allowCleartextOnce(url: String)

    fun isBrowserIdentityApplied(profile: UserAgentProfile, desktop: Boolean): Boolean

    /** Applies the user agent profile and desktop mode, and reloads. */
    fun applyBrowserIdentity(profile: UserAgentProfile, desktop: Boolean)

    fun setDrmL3Enforcer(enabled: Boolean)
    fun setYouTubeAdBlock(enabled: Boolean)

    /** Answer to a page's Web Speech microphone request. */
    fun onMicrophonePermissionResult(granted: Boolean)

    fun release()
}
