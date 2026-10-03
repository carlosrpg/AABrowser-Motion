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

package com.kododake.aabrowser.car

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

internal object ProjectionPerspectiveCoordinator {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var legacyActivity = WeakReference<SplitScreenBrowserActivity>(null)
    private var fullscreenOwner: Any? = null
    private var closeFullscreen: (() -> Unit)? = null

    fun activateLegacy(activity: SplitScreenBrowserActivity) {
        val fullscreenCloser = synchronized(this) {
            legacyActivity = WeakReference(activity)
            val closer = closeFullscreen
            fullscreenOwner = null
            closeFullscreen = null
            closer
        }
        fullscreenCloser?.let { closer ->
            mainHandler.post { closer() }
        }
    }

    fun deactivateLegacy(activity: SplitScreenBrowserActivity) {
        synchronized(this) {
            if (legacyActivity.get() === activity) {
                legacyActivity.clear()
            }
        }
    }

    fun activateFullscreen(owner: Any, fullscreenCloser: () -> Unit) {
        val activity = synchronized(this) {
            fullscreenOwner = owner
            closeFullscreen = fullscreenCloser
            legacyActivity.get().also { legacyActivity.clear() }
        }
        activity?.let { legacyActivity ->
            mainHandler.post { legacyActivity.finishProjection() }
        }
    }

    fun deactivateFullscreen(owner: Any) {
        synchronized(this) {
            if (fullscreenOwner === owner) {
                fullscreenOwner = null
                closeFullscreen = null
            }
        }
    }
}
