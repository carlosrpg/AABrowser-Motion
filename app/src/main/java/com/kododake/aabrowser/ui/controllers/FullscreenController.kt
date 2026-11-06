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

package com.kododake.aabrowser.ui.controllers

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowManager
import android.view.Window
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kododake.aabrowser.databinding.ActivityMainBinding

class FullscreenController(
    private val context: Context,
    private val window: Window?,
    private val binding: ActivityMainBinding,
    private val onFullscreenChanged: (Boolean) -> Unit,
    private val onFullscreenInteraction: () -> Unit = {}
) {
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var touchHost: View? = null
    // Set while "Show video on cluster" is on: the page's fullscreen video goes to the instrument cluster instead.
    private var clusterHost: ViewGroup? = null
    private var isImmersiveMode: Boolean = false

    /** Fullscreen shown on this screen (UI, back key, menu button). False while the video is on the cluster. */
    fun isInFullscreen(): Boolean = customView != null && clusterHost == null

    /** The page itself is fullscreen, wherever its video is shown. */
    fun isPageFullscreen(): Boolean = customView != null

    fun isClusterMode(): Boolean = clusterHost != null

    fun setClusterHost(host: ViewGroup?) {
        if (clusterHost === host) return
        clusterHost = host
        touchHost?.let(::showFullscreenView)
        updateSystemBars()
    }

    fun isImmersiveMode(): Boolean = isImmersiveMode

    fun setImmersiveMode(enabled: Boolean) {
        if (isImmersiveMode == enabled) return
        isImmersiveMode = enabled
        updateSystemBars()
    }

    fun updateSystemBars() {
        val shouldHide = isInFullscreen() || isImmersiveMode
        val hostWindow = window ?: return
        val controller = WindowInsetsControllerCompat(hostWindow, binding.root)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (shouldHide) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun enterFullscreen(
        view: View,
        callback: WebChromeClient.CustomViewCallback,
        onPrepareEnter: () -> Unit
    ) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }

        Log.i(TAG, "enterFullscreen")
        (view.parent as? ViewGroup)?.removeView(view)
        customView = view
        customViewCallback = callback

        onPrepareEnter()

        val host = TouchAwareFullscreenContainer(context, onFullscreenInteraction).apply {
            addView(view, FrameLayout.LayoutParams(-1, -1))
        }
        touchHost = host
        showFullscreenView(host)

        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateSystemBars()
        onFullscreenChanged(true)
    }

    private fun showFullscreenView(host: View) {
        (host.parent as? ViewGroup)?.removeView(host)
        val cluster = clusterHost
        binding.fullscreenContainer.apply {
            removeAllViews()
            visibility = if (cluster == null) View.VISIBLE else View.GONE
        }
        (cluster ?: binding.fullscreenContainer).addView(host, FrameLayout.LayoutParams(-1, -1))
        if (cluster == null) binding.fullscreenContainer.bringToFront()
    }

    fun exitFullscreen(
        fromWebChrome: Boolean = false,
        onRestoreView: () -> Unit
    ) {
        if (customView == null) return
        Log.i(TAG, "exitFullscreen fromWebChrome=$fromWebChrome")

        touchHost?.let { (it.parent as? ViewGroup)?.removeView(it) }
        touchHost = null
        binding.fullscreenContainer.apply {
            removeAllViews()
            visibility = View.GONE
        }

        onRestoreView()

        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val callback = customViewCallback
        customView = null
        customViewCallback = null

        if (!fromWebChrome) {
            callback?.onCustomViewHidden()
        }
        updateSystemBars()
        onFullscreenChanged(false)
    }

    private companion object {
        const val TAG = "FullscreenController"
    }

    private class TouchAwareFullscreenContainer(
        context: Context,
        private val onFullscreenInteraction: () -> Unit
    ) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onFullscreenInteraction()
            }
            return super.dispatchTouchEvent(event)
        }

        // Chromium's fullscreen view pauses the page when its window hides (car tile left for the map, app in
        // the background); keep it "visible" exactly like the WebView subclass does. Seen on the DHU: the
        // split-screen host's onStop hid the window, the page paused a second later and Chromium then exited
        // fullscreen on its own. Leaving fullscreen in onPause and re-maximizing on resume was tried first and
        // rejected: the synthesized fullscreen click is ignored by the player more often than not.
        override fun dispatchWindowVisibilityChanged(visibility: Int) {
            if (visibility != View.GONE) super.dispatchWindowVisibilityChanged(View.VISIBLE)
        }
    }
}
