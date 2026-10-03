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
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.compose.ui.platform.ComposeView
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

internal class ProjectedKeyboardController(
    context: Context,
    private val findFocusedView: () -> View?
) {
    private var activeInputSession: SystemInputSession? = null
    private var activeInputView: View? = null
    private var isAddressInputFocused = false
    private var ignoreHiddenKeyboardClickUntil = 0L

    val view = ProjectedKeyboardView(
        context,
        object : ProjectedKeyboardView.Listener {
            override fun onText(text: String) {
                activeInputSession?.insertText(text)
                refreshPreview()
            }

            override fun onBackspace() {
                activeInputSession?.deleteBackward()
                refreshPreview()
            }

            override fun onEditorAction() {
                val keepKeyboardOpen = activeInputSession?.performEditorAction() == true
                if (!keepKeyboardOpen) {
                    suppressHiddenKeyboardClick()
                    hide()
                }
            }

            override fun onClearText() {
                activeInputSession?.clearText()
                refreshPreview()
            }

            override fun onDismiss() {
                suppressHiddenKeyboardClick()
                hide()
            }

            override fun onLanguageChanged(locale: Locale) = Unit

            override fun onPreviewCursorChanged(position: Int) {
                if (activeInputSession?.setCursorPosition(position) == true) {
                    refreshPreview()
                }
            }
        }
    ).apply {
        visibility = View.GONE
    }

    fun onAddressInputFocusChanged(hasFocus: Boolean) {
        isAddressInputFocused = hasFocus
        if (hasFocus) {
            view.post {
                if (isAddressInputFocused) {
                    val focusedView = findFocusedView()
                    if (focusedView is ComposeView) activateInput(focusedView)
                }
            }
        } else {
            hide()
        }
    }

    fun requestInput(surfaceX: Float, surfaceY: Float): Boolean {
        if (view.containsPoint(surfaceX, surfaceY)) return true
        if (SystemClock.uptimeMillis() < ignoreHiddenKeyboardClickUntil) {
            ignoreHiddenKeyboardClickUntil = 0L
            if (view.containsPointInBounds(surfaceX, surfaceY)) return true
        } else {
            ignoreHiddenKeyboardClickUntil = 0L
        }

        val focusedView = findFocusedView()
        if (focusedView is ComposeView) {
            if (!isAddressInputFocused) {
                hide()
                return false
            }
            if (activeInputView === focusedView && view.visibility == View.VISIBLE) return true
        }
        return activateInput(focusedView)
    }

    private fun activateInput(focusedView: View?): Boolean {
        val inputSession = SystemInputSession.from(focusedView)
        if (focusedView == null || inputSession == null) {
            hide()
            return false
        }

        activeInputSession?.endEditing()
        activeInputSession = inputSession
        activeInputView = focusedView
        view.configure(inputSession.editorInfo)
        view.setInputSession(inputSession)

        val snapshotScript = inputSession.createWebViewSnapshotScript()
        if (focusedView is WebView && snapshotScript != null) {
            view.visibility = View.GONE
            val snapshotRevision = inputSession.editRevision
            focusedView.evaluateJavascript(snapshotScript) { result ->
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
                view.visibility = View.VISIBLE
                refreshPreview()
            }
        } else {
            view.visibility = View.VISIBLE
            try {
                inputSession.readExtractedText()
            } catch (error: AssertionError) {
                Log.w(TAG, "Focused editor does not expose text to the inline keyboard", error)
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Focused editor text is temporarily unavailable", error)
            }
            refreshPreview()
        }
        return true
    }

    fun close() {
        hide()
    }

    fun hideIfVisible(): Boolean {
        if (view.visibility != View.VISIBLE) return false
        hide()
        return true
    }

    private fun refreshPreview() {
        if (activeInputSession == null) return
        view.refreshPreview()
    }

    private fun hide() {
        activeInputSession?.endEditing()
        activeInputSession = null
        activeInputView = null
        view.visibility = View.GONE
        view.clearInputSession()
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

    private data class WebInputSnapshot(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    private companion object {
        const val HIDDEN_KEYBOARD_CLICK_SUPPRESSION_MS = 500L
        const val TAG = "ProjectedKeyboard"
    }
}
