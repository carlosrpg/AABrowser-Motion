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
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.view.View
import android.view.Window
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.kododake.aabrowser.AppConstants.REQUEST_CODE_POST_NOTIFICATIONS
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.databinding.ActivityMainBinding
import com.kododake.aabrowser.model.QuickActionButtonMode
import com.kododake.aabrowser.tabs.BrowserTab
import com.kododake.aabrowser.ui.MainActivitySetup
import com.kododake.aabrowser.ui.controllers.NavigationButtonUpdater
import com.kododake.aabrowser.ui.controllers.ProgressIndicatorController
import kotlinx.coroutines.CoroutineScope

class BrowserShellController(
    private val context: Context,
    private val activity: AppCompatActivity?,
    private val window: Window?,
    private val coroutineScope: CoroutineScope,
    private val binding: ActivityMainBinding,
    private val onPickBackgroundRequested: () -> Unit,
    private val onRecreateRequested: () -> Unit
) : MainActivityCallbackFactory.CallbackHost {
    private val isDebugBuild =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val managers = BrowserManagers(
        context = context,
        activity = activity,
        window = window,
        coroutineScope = coroutineScope,
        binding = binding,
        host = this,
        isDebugBuild = isDebugBuild,
        onUrlChanged = ::onUrlChanged,
        onTitleChanged = ::onTitleChanged,
        onProgressChanged = ::updateProgress,
        onNavigationButtonsUpdateNeeded = ::updateNavigationButtons
    )

    private val menuFabController = MenuFabController(
        context = context,
        binding = binding,
        menuHelper = managers.uiManager.menuHelper,
        isShowingStartPage = { managers.startPageManager.isShowingStartPage },
        isInFullscreen = { managers.uiManager.isInFullscreen() }
    )

    private val progressController = ProgressIndicatorController(binding.progressComposeView)

    var webView: WebView? = null
        private set

    override var currentUrl: String = ""
        private set

    override var currentPageTitle: String = ""
        private set

    var latestReleaseUrl: String = "https://github.com/kododake/AABrowser/releases"
        private set

    fun initialize(intent: Intent?, shouldForceSessionRestore: Boolean) {
        BrowserPreferences.ensureGameBookmarkMigrated(context)
        managers.umamiTracker.trackEvent("app_open")

        val setup = MainActivitySetup(
            context = context,
            binding = binding,
            browserManagers = managers,
            actions = MainActivitySetup.Actions(
                getWebView = { webView },
                getCurrentUrl = { currentUrl },
                getLatestReleaseUrl = { latestReleaseUrl },
                updateNavigationButtons = ::updateNavigationButtons,
                handleQuickActionButtonPressed = ::onQuickActionButtonPressed,
                showStartPage = ::showStartPage,
                onDesktopModeChanged = { isChecked ->
                    managers.tabManager.updateDesktopMode(
                        isChecked,
                        BrowserPreferences.getUserAgentProfile(context)
                    )
                }
            )
        )
        setup.initializeUi(
            intentUrl = managers.navigationManager.extractBrowsableUrl(intent),
            shouldForceSessionRestore = shouldForceSessionRestore
        )

        updateNavigationButtons()
        managers.tabManager.refreshTabs()
        managers.startPageManager.refreshStartPage()
        menuFabController.showMenuButtonTemporarily()
        managers.bookmarkManager.refreshBookmarks()
        managers.uiManager.applyQuickActionButtonPreferences()
        managers.permissionManager.ensureNotificationPermissionIfNeeded(
            REQUEST_CODE_POST_NOTIFICATIONS
        )
        activity?.let { hostActivity ->
            MainActivityBackPressHandler.register(
                activity = hostActivity,
                overlayCoordinator = managers.overlayCoordinator,
                uiManager = managers.uiManager,
                tabManager = managers.tabManager,
                startPageManager = managers.startPageManager,
                getCurrentUrl = { currentUrl },
                onHideStartPage = ::hideStartPage,
                onNavigationButtonsUpdateNeeded = ::updateNavigationButtons
            )
            FreeDroidWarnHelper.checkAndShow(
                hostActivity,
                managers.themeManager.resolveThemeColor(
                    androidx.appcompat.R.attr.colorError
                )
            ) { url ->
                managers.navigationManager.loadUrlFromIntent(url)
            }
        }
    }

    fun onNewIntent(intent: Intent) {
        managers.navigationManager.extractBrowsableUrl(intent)?.let {
            managers.navigationManager.loadUrlFromIntent(it)
        }
    }

    fun onResume() {
        webView?.onResume()
        managers.themeManager.applyMenuHeaderColors()
        managers.bookmarkManager.refreshBookmarks()
        managers.tabManager.refreshTabs()
        managers.startPageManager.refreshStartPage()
        managers.tabManager.updateUserAgentProfile(
            BrowserPreferences.getUserAgentProfile(context),
            BrowserPreferences.shouldUseDesktopMode(context)
        )
        managers.uiManager.applyQuickActionButtonPreferences()
    }

    fun onPause() {
        managers.uiManager.exitFullscreen()
        webView?.onPause()
        managers.tabManager.persistTabSession()
    }

    fun onDestroy() {
        menuFabController.cleanup()
        managers.uiManager.exitFullscreen()
        managers.startPageManager.onDestroy()
        managers.tabManager.destroy()
        webView = null
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        managers.permissionManager.handleRequestPermissionsResult(requestCode, grantResults) { granted ->
            val speechTab = managers.tabManager.browserTabs.firstOrNull {
                it.id == managers.permissionManager.pendingSpeechBridgeTabId
            }

            speechTab?.speechBridge?.onPermissionResult(granted)
        }
    }

    fun onStartPageBackgroundPicked(uri: Uri?) {
        managers.startPageManager.handleStartPageBackgroundPicked(uri)
        onRebuildSettingsContent()
    }

    fun handleBackPressed(): Boolean {
        val handled = when {
            managers.uiManager.isInFullscreen() -> {
                managers.uiManager.exitFullscreen()
                true
            }
            managers.overlayCoordinator.handleBackPressed() -> true
            managers.startPageManager.isNavigating -> {
                managers.startPageManager.cancelNavigationLoading()
                true
            }
            managers.startPageManager.isShowingStartPage && currentUrl.isNotBlank() -> {
                hideStartPage()
                true
            }
            managers.tabManager.activeTab?.webView?.canGoBack() == true -> {
                managers.tabManager.activeTab?.webView?.goBack()
                true
            }
            else -> false
        }
        if (handled) {
            updateNavigationButtons()
        }
        return handled
    }

    private fun showStartPage() {
        managers.startPageManager.showStartPage()
        webView?.visibility = View.INVISIBLE
    }

    private fun hideStartPage() {
        managers.startPageManager.hideStartPage(currentPageTitle, currentUrl)
        webView?.visibility = View.VISIBLE
    }

    private fun updateNavigationButtons() {
        NavigationButtonUpdater.update(
            binding = binding,
            isShowingStartPage = managers.startPageManager.isShowingStartPage,
            currentUrl = currentUrl,
            webView = webView,
            menuHelper = managers.uiManager.menuHelper
        )
    }

    private fun updateProgress(progress: Int) = progressController.updateProgress(progress)

    override fun onShowMenuButtonTemporarily() =
        menuFabController.showMenuButtonTemporarily()

    override fun onHomePagePreferenceChanged() {
        managers.bookmarkManager.refreshBookmarks()
        managers.startPageManager.refreshStartPage()
        onRebuildSettingsContent()
        val url = BrowserPreferences.getHomePageUrl(context)
        if (!url.isNullOrBlank() && managers.startPageManager.isShowingStartPage) {
            managers.navigationManager.loadUrlFromIntent(url)
        } else {
            updateNavigationButtons()
        }
    }

    override fun onRebuildSettingsContent() {
        if (binding.settingsComposeView.isVisible) {
            managers.overlayManager.showSettingsView()
        }
    }

    override fun onQuickActionButtonPressed() {
        val mode = BrowserPreferences.getQuickActionButtonMode(context)
        managers.uiManager.showMenuOverlay(
            focusAddressBar = mode != QuickActionButtonMode.MENU
        )
    }

    override fun onUrlChanged(url: String) {
        currentUrl = url
    }

    override fun onTitleChanged(title: String) {
        currentPageTitle = title
        managers.bookmarkManager.updateBookmarkTitleIfBetter(currentUrl, title)
    }

    override fun onTabChanged(tab: BrowserTab) {
        webView = tab.webView
        currentUrl = tab.currentUrl
        currentPageTitle = tab.currentTitle
        managers.bookmarkManager.updateBookmarkTitleIfBetter(tab.currentUrl, tab.currentTitle)
        managers.uiManager.menuHelper.updatePage(tab.currentUrl, tab.currentTitle)
    }

    override fun onShowStartPage() = showStartPage()
    override fun onHideStartPage() = hideStartPage()
    override fun onUpdateNavigationButtons() = updateNavigationButtons()
    override fun onPickBackgroundRequested() = onPickBackgroundRequested.invoke()
    override fun onVersionInfoReceived(latestUrl: String, tagName: String) {
        latestReleaseUrl = latestUrl
    }
    override fun onRecreateRequested() = onRecreateRequested.invoke()
}
