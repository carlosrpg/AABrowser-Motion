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

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * A black screen on the car's instrument cluster that the page's fullscreen video is moved into.
 * AAOS exposes the cluster panel as a public presentation display (on the emulator: "HDMI Screen"); the cluster's
 * navigation area is a private display that apps cannot use.
 */
class ClusterPresentation(
    private val activity: Activity,
    private val onClosed: () -> Unit
) {
    private var presentation: Presentation? = null
    private var container: FrameLayout? = null

    /** Shows the cluster screen if needed and returns the container for the video, or null when there is no cluster. */
    fun show(): ViewGroup? {
        container?.let { return it }
        val display = findDisplay(activity) ?: return null
        val shown = Presentation(activity, display)
        val host = FrameLayout(shown.context).apply { setBackgroundColor(Color.BLACK) }
        shown.setContentView(host)
        // The cluster can disappear (display removed); the presentation then dismisses itself.
        shown.setOnDismissListener {
            if (presentation === shown) {
                presentation = null
                container = null
                onClosed()
            }
        }
        try {
            shown.show()
        } catch (error: WindowManager.InvalidDisplayException) {
            Log.w(TAG, "Cluster display ${display.displayId} is not usable", error)
            return null
        }
        Log.i(TAG, "Showing on cluster display ${display.displayId} (${display.name})")
        presentation = shown
        container = host
        return host
    }

    fun dismiss() {
        val shown = presentation ?: return
        presentation = null
        container = null
        shown.dismiss()
    }

    companion object {
        private const val TAG = "ClusterPresentation"

        fun isAvailable(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) && findDisplay(context) != null

        private fun findDisplay(context: Context): Display? =
            context.getSystemService(DisplayManager::class.java)
                .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }
}
