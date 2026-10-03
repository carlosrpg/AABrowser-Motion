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

import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.kododake.aabrowser.R

class FullscreenCarService : CarAppService() {
    override fun onCreateSession(sessionInfo: SessionInfo): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = BrowserSurfaceScreen(carContext)
    }

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    private class BrowserSurfaceScreen(
        carContext: CarContext
    ) : Screen(carContext), SurfaceCallback {
        private val handler = Handler(Looper.getMainLooper())
        private var virtualDisplay: VirtualDisplay? = null
        private var presentation: BrowserPresentation? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var visibleArea: Rect? = null
        private var pointerX = 0f
        private var pointerY = 0f
        private var scrollDownTime = 0L
        private var lastScrollTime = 0L
        private var scrollInProgress = false
        private var lastClickX = 0f
        private var lastClickY = 0f

        private val showKeyboardAfterClick = Runnable {
            presentation?.requestSystemKeyboard(lastClickX, lastClickY)
        }

        private val finishScroll = object : Runnable {
            override fun run() {
                if (!scrollInProgress) return

                val now = SystemClock.uptimeMillis()
                val remaining = lastScrollTime + SCROLL_END_DELAY_MS - now
                if (remaining > 0) {
                    handler.postDelayed(this, remaining)
                    return
                }

                dispatchMotionEvent(
                    scrollDownTime,
                    now,
                    MotionEvent.ACTION_UP,
                    pointerX,
                    pointerY
                )
                scrollInProgress = false
            }
        }

        init {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
            ProjectionPerspectiveCoordinator.activateFullscreen(this, carContext::finishCarApp)
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    ProjectionPerspectiveCoordinator.deactivateFullscreen(this@BrowserSurfaceScreen)
                    releaseViewHost()
                }
            })
        }

        override fun onGetTemplate(): Template {
            return NavigationTemplate.Builder()
                .setActionStrip(emptyActionStrip())
                .setMapActionStrip(
                    ActionStrip.Builder()
                        .addAction(Action.PAN)
                        .build()
                )
                .build()
        }

        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            val surface = surfaceContainer.surface
            if (surface == null || !surface.isValid) return

            val width = surfaceContainer.width
            val height = surfaceContainer.height
            if (width <= 0 || height <= 0) return

            releaseViewHost()
            surfaceWidth = width
            surfaceHeight = height

            val displayManager =
                carContext.getSystemService(DISPLAY_SERVICE) as? DisplayManager ?: return
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

            virtualDisplay = displayManager.createVirtualDisplay(
                carContext.getString(R.string.app_name),
                surfaceWidth,
                surfaceHeight,
                surfaceContainer.dpi.coerceAtLeast(160),
                surface,
                flags
            )

            val display = virtualDisplay?.display ?: run {
                releaseViewHost()
                return
            }

            val displayContext = carContext.createDisplayContext(display)

            presentation = BrowserPresentation(
                displayContext = displayContext,
                display = display
            ).also {
                it.show()
                it.updateVisibleArea(surfaceWidth, surfaceHeight, visibleArea)
            }
            resetPointerPosition()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            this.visibleArea = visibleArea.takeUnless(Rect::isEmpty)?.let(::Rect)
            updatePresentationInsets()
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            releaseViewHost()
        }

        override fun onClick(x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            dispatchMotionEvent(now, now, MotionEvent.ACTION_DOWN, x, y)
            dispatchMotionEvent(now, now, MotionEvent.ACTION_UP, x, y)
            lastClickX = x
            lastClickY = y
            handler.removeCallbacks(showKeyboardAfterClick)
            handler.postDelayed(showKeyboardAfterClick, TEXT_INPUT_FOCUS_DELAY_MS)
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            val now = SystemClock.uptimeMillis()
            lastScrollTime = now
            if (!scrollInProgress) {
                scrollInProgress = true
                scrollDownTime = now
                resetPointerPosition()
                dispatchMotionEvent(now, now, MotionEvent.ACTION_DOWN, pointerX, pointerY)
            }

            pointerX = (pointerX - distanceX).coerceIn(0f, (surfaceWidth - 1).coerceAtLeast(0).toFloat())
            pointerY = (pointerY - distanceY).coerceIn(0f, (surfaceHeight - 1).coerceAtLeast(0).toFloat())
            dispatchMotionEvent(
                scrollDownTime,
                now,
                MotionEvent.ACTION_MOVE,
                pointerX,
                pointerY
            )
            handler.removeCallbacks(finishScroll)
            handler.postDelayed(finishScroll, SCROLL_END_DELAY_MS)
        }

        private fun dispatchMotionEvent(
            downTime: Long,
            eventTime: Long,
            action: Int,
            x: Float,
            y: Float
        ) {
            MotionEvent.obtain(downTime, eventTime, action, x, y, 0).also { event ->
                presentation?.dispatchTouchEvent(event)
                event.recycle()
            }
        }

        private fun resetPointerPosition() {
            pointerX = surfaceWidth / 2f
            pointerY = surfaceHeight / 2f
        }

        private fun updatePresentationInsets() {
            presentation?.updateVisibleArea(
                surfaceWidth,
                surfaceHeight,
                visibleArea
            )
        }

        private fun releaseViewHost() {
            handler.removeCallbacks(finishScroll)
            handler.removeCallbacks(showKeyboardAfterClick)
            scrollInProgress = false

            presentation?.dismiss()
            presentation = null

            virtualDisplay?.release()
            virtualDisplay = null
            surfaceWidth = 0
            surfaceHeight = 0
        }

        private companion object {
            const val SCROLL_END_DELAY_MS = 120L
            const val TEXT_INPUT_FOCUS_DELAY_MS = 200L

            fun emptyActionStrip(): ActionStrip {
                val constructor = ActionStrip::class.java.getDeclaredConstructor()
                constructor.isAccessible = true
                return constructor.newInstance()
            }
        }
    }
}
