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
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
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
import com.kododake.aabrowser.main.BrowserShellController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal class BrowserPresentation(
    context: Context,
    display: Display
) : Presentation(context, display, R.style.Theme_AABrowser),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val hostViewModelStore = ViewModelStore()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var browserShell: BrowserShellController? = null
    private var contentRoot: View? = null
    private var projectedKeyboard: ProjectedKeyboardView? = null
    private var activeInputSession: InputSession? = null
    private var isDestroyed = false

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = hostViewModelStore

    override fun onCreate(savedInstanceState: Bundle?) {
        savedStateController.performAttach()
        savedStateController.performRestore(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onCreate(savedInstanceState)

        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val binding = ActivityMainBinding.inflate(layoutInflater)
        val presentationRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setViewTreeLifecycleOwner(this@BrowserPresentation)
            setViewTreeSavedStateRegistryOwner(this@BrowserPresentation)
            setViewTreeViewModelStoreOwner(this@BrowserPresentation)
        }
        binding.root.setViewTreeLifecycleOwner(this)
        binding.root.setViewTreeSavedStateRegistryOwner(this)
        binding.root.setViewTreeViewModelStoreOwner(this)
        presentationRoot.addView(
            binding.root,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        projectedKeyboard = ProjectedKeyboardView(
            context,
            object : ProjectedKeyboardView.Listener {
                override fun onText(text: String) {
                    activeInputSession?.inputConnection?.commitText(text, 1)
                }

                override fun onBackspace() {
                    activeInputSession?.inputConnection?.let { connection ->
                        if (!connection.deleteSurroundingText(1, 0)) {
                            connection.sendKeyEvent(
                                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                            )
                            connection.sendKeyEvent(
                                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)
                            )
                        }
                    }
                }

                override fun onSubmit() {
                    submitFocusedInput()
                }

                override fun onDismiss() {
                    hideProjectedKeyboard()
                }
            }
        ).also { keyboard ->
            keyboard.visibility = View.GONE
            presentationRoot.addView(
                keyboard,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(presentationRoot)
        contentRoot = presentationRoot

        browserShell = BrowserShellController(
            context = context,
            activity = null,
            window = window,
            coroutineScope = coroutineScope,
            binding = binding,
            onPickBackgroundRequested = {},
            onRecreateRequested = {}
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
        val left = rawLeft.takeIf { it > horizontalThreshold } ?: 0
        val top = rawTop.takeIf { it > verticalThreshold } ?: 0
        val right = rawRight.takeIf { it > horizontalThreshold } ?: 0
        val bottom = rawBottom.takeIf { it > verticalThreshold } ?: 0
        root.setPadding(left, top, right, bottom)
    }

    fun showKeyboardForFocusedInput(): Boolean {
        val focusedView = window?.currentFocus ?: contentRoot?.findFocus() ?: run {
            hideProjectedKeyboard()
            return false
        }
        val editorInfo = EditorInfo()
        val inputConnection = focusedView.onCreateInputConnection(editorInfo) ?: run {
            hideProjectedKeyboard()
            return false
        }
        if (editorInfo.inputType == InputType.TYPE_NULL) {
            hideProjectedKeyboard()
            return false
        }

        activeInputSession = InputSession(inputConnection, editorInfo)
        projectedKeyboard?.visibility = View.VISIBLE
        return true
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

    override fun dismiss() {
        if (isDestroyed) return

        super.dismiss()
        isDestroyed = true
        browserShell?.onDestroy()
        browserShell = null
        contentRoot = null
        projectedKeyboard = null
        activeInputSession = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        coroutineScope.cancel()
        hostViewModelStore.clear()
    }

    private companion object {
        const val MIN_OCCLUSION_RATIO = 0.05f
    }

    private data class InputSession(
        val inputConnection: InputConnection,
        val editorInfo: EditorInfo
    )

    private fun submitFocusedInput() {
        val session = activeInputSession ?: return
        val isMultiline =
            session.editorInfo.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (isMultiline) {
            session.inputConnection.commitText("\n", 1)
            return
        }

        val action = session.editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        session.inputConnection.performEditorAction(
            if (action == EditorInfo.IME_ACTION_NONE ||
                action == EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                EditorInfo.IME_ACTION_DONE
            } else {
                action
            }
        )
        hideProjectedKeyboard()
    }

    private fun hideProjectedKeyboard() {
        projectedKeyboard?.visibility = View.GONE
        activeInputSession = null
    }
}
