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

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.util.Log
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kododake.aabrowser.BuildConfig

internal class ProjectedBrowserHostView(
    context: Context,
    contentView: View,
    private val keyboardView: View
) : FrameLayout(context) {
    private var appliedSystemBottomInset: Int? = null
    private var lastDebugMetrics = ""
    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        updateKeyboardPosition(
            appliedSystemBottomInset
                ?: windowInsetsBottom(ViewCompat.getRootWindowInsets(this))
        )
    }

    init {
        addView(
            contentView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        addView(
            keyboardView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            appliedSystemBottomInset = windowInsetsBottom(insets)
            updateKeyboardPosition(appliedSystemBottomInset ?: 0)
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        ViewCompat.requestApplyInsets(this)
        post {
            updateKeyboardPosition(
                appliedSystemBottomInset
                    ?: windowInsetsBottom(ViewCompat.getRootWindowInsets(this))
            )
        }
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateKeyboardPosition(
            appliedSystemBottomInset
                ?: windowInsetsBottom(ViewCompat.getRootWindowInsets(this))
        )
    }

    private fun windowInsetsBottom(insets: WindowInsetsCompat?): Int {
        val insetTypes = WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or
            WindowInsetsCompat.Type.systemOverlays() or
            WindowInsetsCompat.Type.ime()
        return insets?.getInsets(insetTypes)?.bottom ?: 0
    }

    private fun updateKeyboardPosition(systemBottom: Int) {
        val visibleFrame = Rect()
        getWindowVisibleDisplayFrame(visibleFrame)
        val location = IntArray(2)
        getLocationOnScreen(location)
        val frameBottom = if (visibleFrame.isEmpty) {
            0
        } else {
            (location[1] + height - visibleFrame.bottom).coerceAtLeast(0)
        }
        val bottomInset = maxOf(systemBottom, frameBottom)
        if (BuildConfig.DEBUG) {
            val metrics = "host=${width}x$height systemBottom=$systemBottom frameBottom=$frameBottom " +
                "visible=${visibleFrame.toShortString()} rootY=${location[1]}"
            if (metrics != lastDebugMetrics) {
                Log.d(TAG, metrics)
                lastDebugMetrics = metrics
            }
        }
        val params = keyboardView.layoutParams as? LayoutParams ?: return
        if (params.bottomMargin == bottomInset) return
        params.bottomMargin = bottomInset
        keyboardView.layoutParams = params
    }

    private companion object {
        const val TAG = "ProjectedBrowserHost"
    }
}
