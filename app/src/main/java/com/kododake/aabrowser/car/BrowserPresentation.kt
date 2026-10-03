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
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.webkit.WebView
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
import org.json.JSONException
import org.json.JSONObject

internal class BrowserPresentation(
    displayContext: Context,
    display: Display
) : Presentation(displayContext, display, R.style.Theme_AABrowser),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    BrowserHostContext {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val hostViewModelStore = ViewModelStore()
    override val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val hostActivity: AppCompatActivity? = null
    override val hostContext: Context get() = context
    override val hostWindow: Window? get() = window

    var browserShell: BrowserShellController? = null
        private set
    private var contentRoot: View? = null
    private var projectedKeyboard: ProjectedKeyboardView? = null
    private var activeInputSession: SystemInputSession? = null
    private val pendingKeyboardText = StringBuilder()
    private var keyboardTextFlushScheduled = false
    private val flushKeyboardText = Runnable { flushPendingKeyboardText() }
    private var ignoreHiddenKeyboardClickUntil = 0L
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

    override fun onCreate(savedInstanceState: Bundle?) {
        savedStateController.performAttach()
        savedStateController.performRestore(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onCreate(savedInstanceState)

        window?.let { w ->
            w.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            w.decorView.isFocusable = true
            w.decorView.isFocusableInTouchMode = true
            w.decorView.requestFocus()
        }

        val binding = ActivityMainBinding.inflate(layoutInflater)
        val presentationRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setViewTreeLifecycleOwner(this@BrowserPresentation)
            setViewTreeSavedStateRegistryOwner(this@BrowserPresentation)
            setViewTreeViewModelStoreOwner(this@BrowserPresentation)
        }
        val keyboard = ProjectedKeyboardView(
            context,
            object : ProjectedKeyboardView.Listener {
                override fun onText(text: String) {
                    enqueueKeyboardText(text)
                }

                override fun onBackspace() {
                    flushPendingKeyboardText()
                    activeInputSession?.deleteBackward()
                    refreshKeyboardPreview()
                }

                override fun onEditorAction() {
                    flushPendingKeyboardText()
                    val keepKeyboardOpen =
                        activeInputSession?.performEditorAction() == true
                    if (!keepKeyboardOpen) {
                        suppressHiddenKeyboardClick()
                        hideProjectedKeyboard()
                    }
                }

                override fun onClearText() {
                    flushPendingKeyboardText()
                    activeInputSession?.clearText()
                    refreshKeyboardPreview()
                }

                override fun onDismiss() {
                    suppressHiddenKeyboardClick()
                    hideProjectedKeyboard()
                }

                override fun onLanguageChanged(locale: java.util.Locale) {
                    refreshKeyboardPreview()
                }

                override fun onPreviewCursorChanged(position: Int) {
                    flushPendingKeyboardText()
                    if (activeInputSession?.setCursorPosition(position) == true) {
                        refreshKeyboardPreview()
                    }
                }
            }
        ).apply {
            visibility = View.GONE
        }
        projectedKeyboard = keyboard
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
        presentationRoot.addView(
            keyboard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(presentationRoot)
        contentRoot = presentationRoot

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
        val left = rawLeft.takeIf { it > horizontalThreshold } ?: 0
        val top = rawTop.takeIf { it > verticalThreshold } ?: 0
        val right = rawRight.takeIf { it > horizontalThreshold } ?: 0
        val bottom = rawBottom.takeIf { it > verticalThreshold } ?: 0
        root.setPadding(left, top, right, bottom)
    }

    fun requestSystemKeyboard(surfaceX: Float, surfaceY: Float): Boolean {
        flushPendingKeyboardText()
        val keyboard = projectedKeyboard
        if (keyboard?.containsPoint(surfaceX, surfaceY) == true) return true
        if (SystemClock.uptimeMillis() < ignoreHiddenKeyboardClickUntil) {
            ignoreHiddenKeyboardClickUntil = 0L
            if (keyboard?.containsPointInBounds(surfaceX, surfaceY) == true) return true
        } else {
            ignoreHiddenKeyboardClickUntil = 0L
        }

        val focusedView = window?.currentFocus ?: contentRoot?.findFocus()
        if (focusedView == null) {
            hideProjectedKeyboard()
            return false
        }
        val inputSession = SystemInputSession.from(focusedView)
        if (inputSession == null) {
            hideProjectedKeyboard()
            return false
        }

        activeInputSession = inputSession
        keyboard?.configure(inputSession.editorInfo)
        keyboard?.setInputSession(inputSession)
        keyboard?.visibility = View.VISIBLE

        if (focusedView is WebView) {
            val snapshotRevision = inputSession.editRevision
            focusedView.evaluateJavascript(WEB_INPUT_SNAPSHOT_SCRIPT) { result ->
                if (activeInputSession !== inputSession ||
                    inputSession.editRevision != snapshotRevision
                ) {
                    return@evaluateJavascript
                }
                readWebInputSnapshot(result)?.let { snapshot ->
                    inputSession.setTextSnapshot(
                        snapshot.text,
                        snapshot.selectionStart,
                        snapshot.selectionEnd
                    )
                }
                refreshKeyboardPreview()
            }
        } else {
            try {
                inputSession.readExtractedText()
            } catch (error: AssertionError) {
                Log.w(TAG, "Focused editor does not expose text to the inline keyboard", error)
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Focused editor text is temporarily unavailable", error)
            }
            refreshKeyboardPreview()
        }
        return true
    }

    private fun refreshKeyboardPreview() {
        if (activeInputSession == null) return
        projectedKeyboard?.refreshPreview()
    }

    private fun enqueueKeyboardText(text: String) {
        if (text.isEmpty() || activeInputSession == null) return

        pendingKeyboardText.append(text)
        if (keyboardTextFlushScheduled) return

        keyboardTextFlushScheduled = true
        projectedKeyboard?.postOnAnimation(flushKeyboardText) ?: flushPendingKeyboardText()
    }

    private fun flushPendingKeyboardText() {
        projectedKeyboard?.removeCallbacks(flushKeyboardText)
        keyboardTextFlushScheduled = false
        if (pendingKeyboardText.isEmpty()) return

        val session = activeInputSession
        val text = pendingKeyboardText.toString()
        pendingKeyboardText.setLength(0)
        session?.insertText(text)
        refreshKeyboardPreview()
    }

    private fun hideProjectedKeyboard() {
        flushPendingKeyboardText()
        projectedKeyboard?.visibility = View.GONE
        projectedKeyboard?.clearInputSession()
        activeInputSession = null
    }

    private fun suppressHiddenKeyboardClick() {
        ignoreHiddenKeyboardClickUntil =
            SystemClock.uptimeMillis() + HIDDEN_KEYBOARD_CLICK_SUPPRESSION_MS
    }

    private fun readWebInputSnapshot(result: String?): WebInputSnapshot? {
        if (result.isNullOrBlank() || result == "null") return null
        return try {
            val json = JSONObject(result)
            val text = json.optString("text")
            WebInputSnapshot(
                text = text,
                selectionStart = json.optInt("selectionStart", text.length),
                selectionEnd = json.optInt("selectionEnd", text.length)
            )
        } catch (error: JSONException) {
            Log.w(TAG, "Unable to read the focused WebView field for the inline keyboard", error)
            null
        }
    }

    private companion object {
        const val MIN_OCCLUSION_RATIO = 0.05f
        const val HIDDEN_KEYBOARD_CLICK_SUPPRESSION_MS = 500L
        const val TAG = "BrowserPresentation"
        const val WEB_INPUT_SNAPSHOT_SCRIPT = """
            (function() {
                var element = document.activeElement;
                if (!element) return null;
                var value = typeof element.value === "string"
                    ? element.value
                    : (element.isContentEditable ? element.innerText : "");
                var start = value.length;
                var end = value.length;
                try {
                    if (typeof element.selectionStart === "number") {
                        start = element.selectionStart;
                        end = element.selectionEnd;
                    }
                } catch (ignored) {
                    start = value.length;
                    end = value.length;
                }
                return { text: value, selectionStart: start, selectionEnd: end };
            })()
        """
    }

    private data class WebInputSnapshot(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int
    )

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

        flushPendingKeyboardText()
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

}
