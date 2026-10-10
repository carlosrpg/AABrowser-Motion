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

import android.app.Presentation
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
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
import com.kododake.aabrowser.R
import com.kododake.aabrowser.databinding.ActivityMainBinding
import com.kododake.aabrowser.main.BrowserHostContext
import com.kododake.aabrowser.main.BrowserShellController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal class BrowserPresentation(
    private val presentationContext: Context,
    display: Display,
    private val onExternalInputRequested: (SystemInputSession) -> Unit
) : Presentation(presentationContext, display, R.style.Theme_AABrowser),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    BrowserHostContext {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val hostViewModelStore = ViewModelStore()
    private val handler = Handler(Looper.getMainLooper())
    override val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val hostContext: Context get() = presentationContext
    override val hostActivity: AppCompatActivity? = null
    override val hostWindow: Window? get() = window

    private var browserShell: BrowserShellController? = null
    private var projectedKeyboardController: ProjectedKeyboardController? = null
    private var contentRoot: View? = null
    private var isDestroyed = false

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = hostViewModelStore

    override fun launchPickBackground(onPicked: (Uri?) -> Unit) {
        onPicked(null)
    }

    override fun onAddressInputFocusChanged(hasFocus: Boolean) {
        projectedKeyboardController?.onAddressInputFocusChanged(hasFocus)
    }

    fun requestInput(x: Float, y: Float) {
        projectedKeyboardController?.requestInput(x, y)
    }

    fun submitExternalInput(session: SystemInputSession, text: String): Boolean =
        projectedKeyboardController?.submitExternalInput(session, text) == true

    fun dismissExternalInput(session: SystemInputSession) {
        projectedKeyboardController?.dismissExternalInput(session)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        savedStateController.performAttach()
        savedStateController.performRestore(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onCreate(savedInstanceState)

        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        window?.decorView?.apply {
            setViewTreeLifecycleOwner(this@BrowserPresentation)
            setViewTreeSavedStateRegistryOwner(this@BrowserPresentation)
            setViewTreeViewModelStoreOwner(this@BrowserPresentation)
        }

        val binding = ActivityMainBinding.inflate(layoutInflater)
        binding.root.setViewTreeLifecycleOwner(this)
        binding.root.setViewTreeSavedStateRegistryOwner(this)
        binding.root.setViewTreeViewModelStoreOwner(this)

        val keyboardController = ProjectedKeyboardController(
            context = presentationContext,
            findFocusedView = {
                window?.currentFocus ?: window?.decorView?.findFocus()
            },
            onExternalInputRequested = onExternalInputRequested
        )
        projectedKeyboardController = keyboardController
        val browserRoot = ProjectedBrowserHostView(
            presentationContext,
            binding.root,
            keyboardController.view
        ).apply {
            setViewTreeLifecycleOwner(this@BrowserPresentation)
            setViewTreeSavedStateRegistryOwner(this@BrowserPresentation)
            setViewTreeViewModelStoreOwner(this@BrowserPresentation)
        }
        setContentView(browserRoot)
        contentRoot = browserRoot

        browserShell = BrowserShellController(
            hostContext = this,
            binding = binding
        ).also {
            it.initialize(intent = null, shouldForceSessionRestore = true)
        }
    }

    fun updateVisibleArea(
        surfaceWidth: Int,
        surfaceHeight: Int,
        visibleArea: Rect?
    ) {
        val root = contentRoot ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || visibleArea == null) {
            root.setPadding(0, 0, 0, 0)
            return
        }

        val horizontalThreshold = (surfaceWidth * MIN_OCCLUSION_RATIO).toInt()
        val verticalThreshold = (surfaceHeight * MIN_OCCLUSION_RATIO).toInt()
        val rawLeft = visibleArea.left.coerceIn(0, surfaceWidth)
        val rawTop = visibleArea.top.coerceIn(0, surfaceHeight)
        val rawRight = (surfaceWidth - visibleArea.right).coerceIn(0, surfaceWidth)
        val rawBottom = (surfaceHeight - visibleArea.bottom).coerceIn(0, surfaceHeight)
        root.setPadding(
            rawLeft.takeIf { it > horizontalThreshold } ?: 0,
            rawTop.takeIf { it > verticalThreshold } ?: 0,
            rawRight.takeIf { it > horizontalThreshold } ?: 0,
            rawBottom.takeIf { it > verticalThreshold } ?: 0
        )
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        browserShell?.onResume()
    }

    override fun onStop() {
        if (!isDestroyed) {
            browserShell?.onPause()
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
        super.onStop()
    }

    override fun onSaveInstanceState(): Bundle {
        val outState = super.onSaveInstanceState()
        savedStateController.performSave(outState)
        return outState
    }

    override fun dismiss() {
        if (isDestroyed) return
        isDestroyed = true

        projectedKeyboardController?.close()
        projectedKeyboardController = null
        browserShell?.onDestroy()
        browserShell = null
        contentRoot = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        coroutineScope.cancel()
        hostViewModelStore.clear()
        super.dismiss()
    }

    private companion object {
        const val MIN_OCCLUSION_RATIO = 0.05f
    }
}
