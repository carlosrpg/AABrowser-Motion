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
import android.view.View
import com.kododake.aabrowser.databinding.ActivityMainBinding

/**
 * Resets the active tab session by completely destroying the existing page
 * and replacing it with a fresh, isolated one to prevent malicious injection,
 * cross-page script leaks, history pollution, and residual background execution.
 */
object TabSessionResetter {

    fun resetActiveTab(
        context: Context,
        binding: ActivityMainBinding,
        browserTabs: MutableList<BrowserTab>,
        activeTabId: Long?,
        callbacks: TabCallbacks,
        newUrl: String
    ): BrowserTab? {
        val targetId = activeTabId ?: return null
        val tabIndex = browserTabs.indexOfFirst { it.id == targetId }
        if (tabIndex < 0) return null

        val oldTab = browserTabs[tabIndex]
        oldTab.page.release()

        val newTab = BrowserTabFactory.createTab(
            context = context,
            tabId = targetId,
            initialUrl = newUrl,
            initialTitle = "",
            activate = true,
            createBrowserCallbacks = callbacks::buildBrowserCallbacks,
            onRequestSpeechMicrophone = callbacks::requestSpeechRecognitionMicrophoneAccess,
            onSanitizeJsExternalUrl = callbacks::sanitizeJsExternalUrl,
            onOpenUriExternally = callbacks::openUriExternally,
            onShowMenuButtonTemporarily = callbacks::showMenuButtonTemporarily,
            onPageDragChanged = callbacks::onPageDragChanged
        )

        binding.webViewContainer.addView(newTab.page.view)
        browserTabs[tabIndex] = newTab

        newTab.page.view.visibility = View.VISIBLE
        newTab.page.view.bringToFront()
        newTab.page.view.requestFocus()
        newTab.page.view.invalidate()
        newTab.page.onResume()

        callbacks.onTabChanged(newTab)
        return newTab
    }
}
