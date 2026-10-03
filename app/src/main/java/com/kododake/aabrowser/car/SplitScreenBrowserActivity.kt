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

import android.os.Bundle
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
import com.kododake.aabrowser.main.BrowserShellController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class SplitScreenBrowserActivity : CarActivity(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val hostViewModelStore = ViewModelStore()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var browserShell: BrowserShellController? = null

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = hostViewModelStore

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
        hostWindow.decorView.setViewTreeLifecycleOwner(this)
        hostWindow.decorView.setViewTreeSavedStateRegistryOwner(this)
        hostWindow.decorView.setViewTreeViewModelStoreOwner(this)

        val binding = ActivityMainBinding.inflate(layoutInflater)
        binding.root.setViewTreeLifecycleOwner(this)
        binding.root.setViewTreeSavedStateRegistryOwner(this)
        binding.root.setViewTreeViewModelStoreOwner(this)
        setContentView(binding.root)

        browserShell = BrowserShellController(
            context = this,
            activity = null,
            window = hostWindow,
            coroutineScope = coroutineScope,
            binding = binding,
            onPickBackgroundRequested = {},
            onRecreateRequested = {}
        ).also {
            it.initialize(intent, shouldForceSessionRestore = true)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        browserShell?.onResume()
    }

    override fun onStart() {
        super.onStart()
        ProjectionPerspectiveCoordinator.activateLegacy(this)
    }

    override fun onPause() {
        browserShell?.onPause()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        super.onPause()
    }

    override fun onStop() {
        ProjectionPerspectiveCoordinator.deactivateLegacy(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onStop()
    }

    override fun onBackPressed() {
        if (browserShell?.handleBackPressed() != true) {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        savedStateController.performSave(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        browserShell?.onDestroy()
        browserShell = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        coroutineScope.cancel()
        hostViewModelStore.clear()
        super.onDestroy()
    }

    fun finishProjection() {
        LegacyProjectionFinisher.finish(this)
    }
}
