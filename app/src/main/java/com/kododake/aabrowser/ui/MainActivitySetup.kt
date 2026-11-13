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

package com.kododake.aabrowser.ui

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kododake.aabrowser.AppConstants
import com.kododake.aabrowser.R
import com.kododake.aabrowser.bookmarks.BookmarkManager
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.databinding.ActivityMainBinding
import com.kododake.aabrowser.model.YouTubeMix
import com.kododake.aabrowser.navigation.NavigationManager
import com.kododake.aabrowser.startpage.StartPageManager
import com.kododake.aabrowser.tabs.TabManager

class MainActivitySetup(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val managers: Managers,
    private val actions: Actions
) {
    constructor(
        context: Context,
        binding: ActivityMainBinding,
        browserManagers: com.kododake.aabrowser.main.BrowserManagers,
        actions: Actions
    ) : this(
        context = context,
        binding = binding,
        managers = Managers(
            bookmarkManager = browserManagers.bookmarkManager,
            startPageManager = browserManagers.startPageManager,
            tabManager = browserManagers.tabManager,
            uiManager = browserManagers.uiManager,
            navigationManager = browserManagers.navigationManager,
            overlayManager = browserManagers.overlayManager,
            overlayCoordinator = browserManagers.overlayCoordinator
        ),
        actions = actions
    )

    data class Managers(
        val bookmarkManager: BookmarkManager,
        val startPageManager: StartPageManager,
        val tabManager: TabManager,
        val uiManager: BrowserUIManager,
        val navigationManager: NavigationManager,
        val overlayManager: OverlayManager,
        val overlayCoordinator: OverlayNavigationCoordinator
    )

    data class Actions(
        val getPage: () -> com.kododake.aabrowser.web.BrowserPage?,
        val getCurrentUrl: () -> String,
        val getLatestReleaseUrl: () -> String,
        val updateNavigationButtons: () -> Unit,
        val handleQuickActionButtonPressed: () -> Unit,
        val handleHideUiPressed: () -> Unit = {},
        val handleVoiceSearchPressed: () -> Unit = {},
        val handleMixesTabPressed: () -> Unit = {},
        val onMixSelected: (YouTubeMix) -> Unit = {},
        val showStartPage: () -> Unit,
        val onDesktopModeChanged: (Boolean) -> Unit,
        val onAddressInputFocusChanged: (Boolean) -> Unit = {},
        val onMediaKey: (KeyEvent) -> Unit = {},
        val onMediaPlayPause: () -> Unit = {},
        val onMediaFullscreen: () -> Unit = {},
        val onClusterModeChanged: (Boolean) -> Unit = {}
    )

    fun initializeUi(
        intentUrl: String?,
        shouldForceSessionRestore: Boolean
    ) {
        // The root CoordinatorLayout's own fitsSystemWindows handling sets SYSTEM_UI_FLAG_LAYOUT_STABLE, so it kept the
        // system bars' space after "Request full screen" hid them (top/bottom bands). Pad only for the bars shown now.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.webViewContainer) { _, _ ->
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.progressComposeView) { _, _ ->
            WindowInsetsCompat.CONSUMED
        }
        managers.tabManager.initializeTabs(
            intentUrl,
            BrowserPreferences.getHomePageUrl(context),
            BrowserPreferences.getLastVisitedUrl(context),
            BrowserPreferences.shouldRestoreTabsOnLaunch(context),
            BrowserPreferences.shouldResumeLastPageOnLaunch(context),
            shouldForceSessionRestore
        )

        setupClickListeners()
        setupComposeMenu()
    }

    private fun setupComposeMenu() {
        val menuActions = com.kododake.aabrowser.ui.compose.screens.menu.MenuActions(
            onBack = {
                actions.getPage()?.let { if (it.canGoBack()) it.goBack() }
                actions.updateNavigationButtons()
            },
            onForward = {
                actions.getPage()?.let { if (it.canGoForward()) it.goForward() }
                actions.updateNavigationButtons()
            },
            onReload = {
                actions.getPage()?.reload()
                managers.uiManager.hideMenuOverlay()
            },
            onHome = {
                actions.showStartPage()
                managers.uiManager.hideMenuOverlay()
            },
            onDesktopToggle = { isChecked ->
                BrowserPreferences.setDesktopMode(context, isChecked)
                actions.onDesktopModeChanged(isChecked)
                managers.uiManager.menuHelper.stateHolder.isDesktopMode = isChecked
            },
            onFullscreenToggle = { isChecked ->
                BrowserPreferences.setFullscreenMode(context, isChecked)
                managers.uiManager.setImmersiveMode(isChecked)
                managers.uiManager.menuHelper.stateHolder.isFullscreenMode = isChecked
            },
            onClusterToggle = actions.onClusterModeChanged,
            onNewTab = {
                managers.tabManager.createNewTab(true)
                managers.uiManager.hideMenuOverlay()
            },
            onTabs = {
                managers.overlayCoordinator.openTabs(fromMenu = true)
            },
            onBookmarks = {
                managers.overlayCoordinator.openBookmarks(fromMenu = true)
            },
            onQrCode = {
                managers.overlayCoordinator.openQrCode(actions.getCurrentUrl(), fromMenu = true)
            },
            onSettings = {
                managers.overlayCoordinator.openSettings(fromMenu = true)
            },
            onNavigate = { url ->
                managers.navigationManager.navigateToAddress(url, true)
            },
            onAddressInputFocusChanged = actions.onAddressInputFocusChanged,
            onClose = { managers.uiManager.hideMenuOverlay() },
            onGitHub = {
                val uri = Uri.parse(AppConstants.GITHUB_REPO_URL)
                managers.uiManager.openUriExternally(uri)
            },
            onDragDelta = { deltaY ->
                com.kododake.aabrowser.ui.controllers.MenuDragGestureHelper.handleDragDelta(binding, deltaY)
            },
            onDragEnd = { totalDeltaY ->
                com.kododake.aabrowser.ui.controllers.MenuDragGestureHelper.handleDragEnd(binding, totalDeltaY) {
                    managers.uiManager.hideMenuOverlay()
                }
            },
            onProgress = { progress ->
                managers.overlayCoordinator.onScreenProgress(OverlayNavigationCoordinator.OverlayScreen.MENU, progress)
            },
            onDismissFinished = {
                managers.uiManager.onMenuDismissFinished()
            }
        )
        managers.uiManager.menuHelper.setup(binding.menuComposeView, menuActions)
        managers.uiManager.menuHelper.setupFab(
            composeView = binding.fabComposeView,
            onClick = actions.handleQuickActionButtonPressed
        )
        managers.uiManager.menuHelper.setupHideUiButton(
            composeView = binding.hideUiComposeView,
            onClick = actions.handleHideUiPressed
        )
        managers.uiManager.menuHelper.setupVoiceSearchButton(
            composeView = binding.voiceSearchComposeView,
            onClick = actions.handleVoiceSearchPressed
        )
        managers.uiManager.menuHelper.setupMixes(
            tabComposeView = binding.mixesTabComposeView,
            panelComposeView = binding.mixesPanelComposeView,
            onOpen = actions.handleMixesTabPressed,
            onMixSelected = actions.onMixSelected
        )
        managers.uiManager.menuHelper.setupMediaControls(
            composeView = binding.mediaControlsComposeView,
            onMediaKey = actions.onMediaKey,
            onPlayPause = actions.onMediaPlayPause
        )
        managers.uiManager.menuHelper.setupFullscreenControl(
            composeView = binding.fullscreenControlComposeView,
            onClick = actions.onMediaFullscreen
        )
        managers.uiManager.menuHelper.setupMediaRestoreCue(binding.mediaRestoreCueComposeView)
        managers.uiManager.menuHelper.updateVersion("v${com.kododake.aabrowser.BuildConfig.VERSION_NAME}")
        val isFullscreen = BrowserPreferences.shouldUseFullscreenMode(context)
        managers.uiManager.menuHelper.stateHolder.isFullscreenMode = isFullscreen
        if (isFullscreen) {
            managers.uiManager.setImmersiveMode(true)
        }
    }

    private fun setupClickListeners() {
        binding.commonScrimView.setOnClickListener {
            managers.overlayCoordinator.hideAll()
        }

        binding.startPageRoot.setOnClickListener {
            if (managers.startPageManager.isStartPagePhotoOnlyMode) {
                managers.startPageManager.isStartPagePhotoOnlyMode = false
                managers.startPageManager.applyStartPagePhotoOnlyMode()
            }
        }
    }
}
