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

package com.kododake.aabrowser.main

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.kododake.aabrowser.AppConstants.REQUEST_CODE_RECORD_AUDIO
import com.kododake.aabrowser.R
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.data.SiteIconCache
import com.kododake.aabrowser.databinding.ActivityMainBinding
import com.kododake.aabrowser.tabs.BrowserTab
import com.kododake.aabrowser.web.BrowserCallbacks

class WebBrowserCallbackFactory(
    private val context: Context,
    private val activity: AppCompatActivity?,
    private val binding: ActivityMainBinding,
    private val provider: BrowserManagersProvider,
    private val isDebugBuild: Boolean,
    private val onUrlChanged: (String) -> Unit,
    private val onTitleChanged: (String) -> Unit,
    private val onProgressChanged: (Int) -> Unit,
    private val onNavigationButtonsUpdateNeeded: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun runOnUiThread(action: () -> Unit) {
        activity?.runOnUiThread(action) ?: mainHandler.post(action)
    }

    fun build(tab: BrowserTab): BrowserCallbacks {
        return BrowserCallbacks(
            onUrlChange = { url ->
                runOnUiThread {
                    provider.tabManager.updateTabUrl(tab.id, url)
                    BrowserPreferences.persistUrl(context, url)
                    provider.bookmarkManager.prefetchSiteIcon(url)
                    if (tab.id == provider.tabManager.activeTabId) {
                        onUrlChanged(url)
                        onNavigationButtonsUpdateNeeded()
                        if (!provider.startPageManager.isShowingStartPage) {
                            provider.uiManager.menuHelper.updatePage(
                                url = url,
                                title = provider.tabManager.activeTab?.currentTitle.orEmpty()
                            )
                        }
                        provider.startPageManager.refreshStartPage()
                        provider.tabManager.refreshTabs()
                    }
                }
            },
            onTitleChange = { title ->
                runOnUiThread {
                    val finalTitle = title.orEmpty()
                    provider.tabManager.updateTabTitle(tab.id, finalTitle)
                    if (tab.id == provider.tabManager.activeTabId) {
                        onTitleChanged(finalTitle)
                        if (!provider.startPageManager.isShowingStartPage) {
                            val activeTab = provider.tabManager.activeTab
                            val displayTitle = finalTitle.ifBlank {
                                if (activeTab != null) provider.tabManager.displayTitleForTab(activeTab) else ""
                            }
                            provider.uiManager.menuHelper.updatePage(
                                url = activeTab?.currentUrl.orEmpty(),
                                title = displayTitle
                            )
                        }
                    }
                    provider.tabManager.refreshTabs()
                }
            },
            onFaviconReceived = { url, icon ->
                runOnUiThread {
                    SiteIconCache.cacheIcon(context, url, icon)
                    if (provider.startPageManager.isShowingStartPage) provider.startPageManager.refreshStartPage()
                    if (binding.bookmarkComposeView.isVisible) provider.bookmarkManager.refreshBookmarks()
                    if (binding.tabComposeView.isVisible) provider.tabManager.refreshTabs()
                }
            },
            onProgressChange = { p ->
                if (tab.id == provider.tabManager.activeTabId) {
                    runOnUiThread {
                        onProgressChanged(p)
                    }
                }
            },
            onShowDownloadPrompt = { uri ->
                runOnUiThread {
                    provider.uiManager.openUriExternally(uri)
                }
            },
            onCleartextNavigationRequested = { uri, once, host, cancel ->
                runOnUiThread {
                    provider.permissionManager.showCleartextNavigationDialog(uri, once, host, cancel)
                }
            },
            onError = { _, d ->
                runOnUiThread {
                    if (isDebugBuild && tab.id == provider.tabManager.activeTabId) {
                        Toast.makeText(
                            context,
                            d ?: context.getString(R.string.error_generic_message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onEnterFullscreen = { v, c ->
                runOnUiThread {
                    provider.uiManager.enterFullscreen(v, c)
                }
            },
            onExitFullscreen = {
                runOnUiThread {
                    provider.uiManager.exitFullscreen(true)
                }
            },
            onPermissionRequest = { r ->
                runOnUiThread {
                    provider.permissionManager.handleWebPermissionRequest(r, REQUEST_CODE_RECORD_AUDIO)
                }
            },
            onGeolocationPermissionRequest = { origin, callback ->
                runOnUiThread {
                    provider.permissionManager.handleGeolocationPermissionRequest(origin, callback)
                }
            },
            onCreateNewWindow = {
                val newTab = provider.tabManager.createBrowserTab(
                    initialUrl = null,
                    initialTitle = "",
                    activate = true
                )
                if (newTab != null) {
                    if (provider.startPageManager.isShowingStartPage) {
                        provider.startPageManager.hideStartPage("", "")
                    }
                    newTab.webView
                } else {
                    null
                }
            },
            onRenderProcessGone = { didCrash ->
                runOnUiThread {
                    Toast.makeText(
                        context,
                        context.getString(R.string.error_generic_message),
                        Toast.LENGTH_SHORT
                    ).show()
                    provider.tabManager.closeTab(tab.id) {}
                }
            }
        )
    }
}
