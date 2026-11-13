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

package com.kododake.aabrowser.tabs

import android.content.Context
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.kododake.aabrowser.gecko.GeckoSupport
import com.kododake.aabrowser.web.BrowserCallbacks
import com.kododake.aabrowser.web.WebViewPage

/**
 * Creates a tab and its page: GeckoView on the automotive build, the system WebView otherwise.
 */
object BrowserTabFactory {

    fun createTab(
        context: Context,
        tabId: Long,
        initialUrl: String?,
        initialTitle: String,
        activate: Boolean,
        createBrowserCallbacks: (BrowserTab) -> BrowserCallbacks,
        onRequestSpeechMicrophone: (tabId: Long, pageUrl: String?) -> Unit,
        onSanitizeJsExternalUrl: (sourceWebView: android.webkit.WebView, rawUrl: String?) -> Uri?,
        onOpenUriExternally: (Uri) -> Unit,
        onShowMenuButtonTemporarily: () -> Unit,
        onPageDragChanged: (Boolean) -> Unit = {}
    ): BrowserTab {
        val page = GeckoSupport.createPage(context) ?: WebViewPage(
            context,
            onRequestSpeechMicrophone = { pageUrl -> onRequestSpeechMicrophone(tabId, pageUrl) },
            onSanitizeJsExternalUrl = onSanitizeJsExternalUrl,
            onOpenUriExternally = onOpenUriExternally
        )
        page.view.apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            visibility = View.GONE
        }

        val tab = BrowserTab(
            id = tabId,
            page = page,
            currentUrl = initialUrl.orEmpty(),
            currentTitle = initialTitle,
            isActive = activate
        )
        page.attach(createBrowserCallbacks(tab))

        page.view.setOnTouchListener { _, event ->
            onShowMenuButtonTemporarily()
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> onPageDragChanged(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onPageDragChanged(false)
            }
            false
        }
        page.onPause()

        return tab
    }
}
