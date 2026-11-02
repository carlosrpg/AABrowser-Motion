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

package com.kododake.aabrowser.data.prefs

import android.content.Context
import com.kododake.aabrowser.model.QuickActionButtonMode
import com.kododake.aabrowser.model.QuickActionButtonPosition
import com.kododake.aabrowser.model.UserAgentProfile

object UIPreferences {
    const val PREFS_NAME = "browser_prefs"
    private const val KEY_QUICK_ACTION_BUTTON_MODE = "quick_action_button_mode"
    private const val KEY_QUICK_ACTION_BUTTON_ALWAYS_VISIBLE = "quick_action_button_always_visible"
    private const val KEY_QUICK_ACTION_BUTTON_POSITION = "quick_action_button_position"
    private const val KEY_HIDE_SPONSORS = "hide_sponsors"
    private const val KEY_USER_AGENT_PROFILE = "user_agent_profile"
    private const val KEY_CUSTOM_USER_AGENT = "custom_user_agent"
    private const val KEY_DESKTOP_MODE = "desktop_mode"
    private const val KEY_FULLSCREEN_MODE = "fullscreen_mode"
    private const val KEY_SHEET_DRAG_ALPHA_PERCENT = "sheet_drag_alpha_percent"

    const val MIN_SHEET_DRAG_ALPHA_PERCENT = 10
    const val MAX_SHEET_DRAG_ALPHA_PERCENT = 100
    const val DEFAULT_SHEET_DRAG_ALPHA_PERCENT = 80

    /** Opacity (percent) of a bottom sheet while the user is dragging it. */
    fun getSheetDragAlphaPercent(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SHEET_DRAG_ALPHA_PERCENT, DEFAULT_SHEET_DRAG_ALPHA_PERCENT)
            .coerceIn(MIN_SHEET_DRAG_ALPHA_PERCENT, MAX_SHEET_DRAG_ALPHA_PERCENT)
    }

    fun setSheetDragAlphaPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SHEET_DRAG_ALPHA_PERCENT, percent.coerceIn(MIN_SHEET_DRAG_ALPHA_PERCENT, MAX_SHEET_DRAG_ALPHA_PERCENT))
            .apply()
    }

    fun getQuickActionButtonMode(context: Context): QuickActionButtonMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_QUICK_ACTION_BUTTON_MODE, QuickActionButtonMode.MENU.storageKey)
        return QuickActionButtonMode.fromKey(raw)
    }

    fun setQuickActionButtonMode(context: Context, mode: QuickActionButtonMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUICK_ACTION_BUTTON_MODE, mode.storageKey)
            .apply()
    }

    fun isQuickActionButtonAlwaysVisible(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_QUICK_ACTION_BUTTON_ALWAYS_VISIBLE, false)
    }

    fun setQuickActionButtonAlwaysVisible(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_QUICK_ACTION_BUTTON_ALWAYS_VISIBLE, enabled)
            .apply()
    }

    fun getQuickActionButtonPosition(context: Context): QuickActionButtonPosition {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_QUICK_ACTION_BUTTON_POSITION, QuickActionButtonPosition.BOTTOM_LEFT.storageKey)
        return QuickActionButtonPosition.fromKey(raw)
    }

    fun setQuickActionButtonPosition(context: Context, position: QuickActionButtonPosition) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUICK_ACTION_BUTTON_POSITION, position.storageKey)
            .apply()
    }

    fun shouldHideSponsors(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_SPONSORS, false)
    }

    fun setHideSponsors(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_SPONSORS, hide)
            .apply()
    }

    fun getUserAgentProfile(context: Context): UserAgentProfile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_USER_AGENT_PROFILE, UserAgentProfile.ANDROID_CHROME.storageKey)
        return UserAgentProfile.fromKey(raw)
    }

    fun setUserAgentProfile(context: Context, profile: UserAgentProfile) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USER_AGENT_PROFILE, profile.storageKey)
            .apply()
    }

    fun getCustomUserAgent(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_USER_AGENT, "") ?: ""
    }

    fun setCustomUserAgent(context: Context, userAgent: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_USER_AGENT, userAgent.filterNot { it.isISOControl() }.trim())
            .apply()
    }

    fun shouldUseDesktopMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DESKTOP_MODE, false)
    }

    fun toggleDesktopMode(context: Context): Boolean {
        val next = !shouldUseDesktopMode(context)
        setDesktopMode(context, next)
        return next
    }

    fun setDesktopMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DESKTOP_MODE, enabled)
            .apply()
    }

    fun shouldUseFullscreenMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FULLSCREEN_MODE, false)
    }

    fun setFullscreenMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FULLSCREEN_MODE, enabled)
            .apply()
    }
}
