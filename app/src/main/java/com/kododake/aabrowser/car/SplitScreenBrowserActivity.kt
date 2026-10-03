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
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.apps.auto.sdk.CarActivity
import com.kododake.aabrowser.R
import com.kododake.aabrowser.databinding.ActivityMainBinding
import com.kododake.aabrowser.main.BrowserHostContext
import com.kododake.aabrowser.main.BrowserShellController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

open class SplitScreenBrowserActivity : CarActivity(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    BrowserHostContext {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val hostViewModelStore = ViewModelStore()
    private val handler = Handler(Looper.getMainLooper())
    override val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val hostContext: Context get() = this
    override val hostActivity: AppCompatActivity? = null

    private var internalWindow: Window? = null
    override val hostWindow: Window? get() = internalWindow

    private var browserShell: BrowserShellController? = null
    private var projectedKeyboardController: ProjectedKeyboardController? = null
    private var originalWindowCallback: Window.Callback? = null
    private var inputWindowCallback: Window.Callback? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val showProjectedKeyboardAfterInteraction = Runnable {
        projectedKeyboardController?.requestInput(lastTouchX, lastTouchY)
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = hostViewModelStore

    override fun launchPickBackground(onPicked: (Uri?) -> Unit) {
        onPicked(null)
    }

    override fun finishHost() {
        finishProjection()
    }

    override fun onAddressInputFocusChanged(hasFocus: Boolean) {
        projectedKeyboardController?.onAddressInputFocusChanged(hasFocus)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_AABrowser)
        savedStateController.performAttach()
        savedStateController.performRestore(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onCreate(savedInstanceState)

        setIgnoreConfigChanges(0xFFFFFFFF.toInt())
        getCarUiController().statusBarController.hideAppHeader()
        getCarUiController().menuController.hideMenuButton()

        val hostWindow = c()
        internalWindow = hostWindow
        hostWindow.decorView.setViewTreeLifecycleOwner(this)
        hostWindow.decorView.setViewTreeSavedStateRegistryOwner(this)
        hostWindow.decorView.setViewTreeViewModelStoreOwner(this)

        val binding = ActivityMainBinding.inflate(layoutInflater)
        binding.root.setViewTreeLifecycleOwner(this)
        binding.root.setViewTreeSavedStateRegistryOwner(this)
        binding.root.setViewTreeViewModelStoreOwner(this)
        val keyboardController = ProjectedKeyboardController(this) {
            internalWindow?.currentFocus ?: internalWindow?.decorView?.findFocus()
        }
        projectedKeyboardController = keyboardController
        val browserRoot = ProjectedBrowserHostView(
            this,
            binding.root,
            keyboardController.view
        ).apply {
            setViewTreeLifecycleOwner(this@SplitScreenBrowserActivity)
            setViewTreeSavedStateRegistryOwner(this@SplitScreenBrowserActivity)
            setViewTreeViewModelStoreOwner(this@SplitScreenBrowserActivity)
        }
        setContentView(browserRoot)
        installInputWindowCallback(hostWindow)

        browserShell = BrowserShellController(
            hostContext = this,
            binding = binding
        ).also {
            it.initialize(intent, shouldForceSessionRestore = true)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        browserShell?.onResume()
    }

    override fun onPause() {
        projectedKeyboardController?.close()
        browserShell?.onPause()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        super.onPause()
    }

    override fun onStop() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onStop()
    }

    override fun onBackPressed() {
        if (projectedKeyboardController?.hideIfVisible() == true) return
        if (browserShell?.handleBackPressed() != true) {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        savedStateController.performSave(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(showProjectedKeyboardAfterInteraction)
        internalWindow?.let { window ->
            if (window.callback === inputWindowCallback) {
                window.callback = originalWindowCallback
            }
        }
        projectedKeyboardController?.close()
        projectedKeyboardController = null
        browserShell?.onDestroy()
        browserShell = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        coroutineScope.cancel()
        hostViewModelStore.clear()
        super.onDestroy()
    }

    private companion object {
        const val TEXT_INPUT_FOCUS_DELAY_MS = 75L
    }

    private fun installInputWindowCallback(window: Window) {
        val originalCallback = window.callback ?: return
        originalWindowCallback = originalCallback
        val inputCallback = object : Window.Callback by originalCallback {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val handled = originalCallback.dispatchTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    handler.removeCallbacks(showProjectedKeyboardAfterInteraction)
                    handler.postDelayed(
                        showProjectedKeyboardAfterInteraction,
                        TEXT_INPUT_FOCUS_DELAY_MS
                    )
                }
                return handled
            }
        }
        inputWindowCallback = inputCallback
        window.callback = inputCallback
    }

    fun finishProjection() {
        LegacyProjectionFinisher.finish(this)
    }
}
