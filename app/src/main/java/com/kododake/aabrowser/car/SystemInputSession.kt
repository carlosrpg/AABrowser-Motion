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

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

internal class SystemInputSession private constructor(
    private val inputConnection: InputConnection,
    val editorInfo: EditorInfo
) {
    private var currentText: StringBuilder? = null
    private var selectionStart = editorInfo.initialSelStart.coerceAtLeast(0)
    private var selectionEnd = editorInfo.initialSelEnd.coerceAtLeast(selectionStart)
    var editRevision: Int = 0
        private set

    val initialText: String
        get() = currentText?.toString().orEmpty()

    val hint: String
        get() = editorInfo.hintText?.toString()?.takeIf(String::isNotBlank).orEmpty()

    val isSecureInput: Boolean
        get() {
            val inputClass = editorInfo.inputType and InputType.TYPE_MASK_CLASS
            val variation = editorInfo.inputType and InputType.TYPE_MASK_VARIATION
            return when (inputClass) {
                InputType.TYPE_CLASS_TEXT ->
                    variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                InputType.TYPE_CLASS_NUMBER ->
                    variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }

    val previewText: String?
        get() = currentText?.toString()

    val cursorStart: Int
        get() = selectionStart

    val cursorEnd: Int
        get() = selectionEnd

    fun setTextSnapshot(text: String, start: Int, end: Int) {
        currentText = StringBuilder(text)
        selectionStart = start.coerceIn(0, text.length)
        selectionEnd = end.coerceIn(selectionStart, text.length)
    }

    fun setCursorPosition(position: Int): Boolean {
        val length = currentText?.length ?: 0
        val cursor = position.coerceIn(0, length)
        if (!inputConnection.setSelection(cursor, cursor)) return false
        selectionStart = cursor
        selectionEnd = cursor
        return true
    }

    fun readExtractedText(): Boolean {
        val extractedText = inputConnection.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        val text = extractedText.text?.toString() ?: return false
        setTextSnapshot(
            text,
            extractedText.selectionStart,
            extractedText.selectionEnd
        )
        return true
    }

    fun insertText(text: String) {
        inputConnection.commitText(text, 1)
        editRevision++
        val existingText = currentText
        if (existingText == null) {
            currentText = StringBuilder(text)
            selectionStart = text.length
            selectionEnd = text.length
            return
        }
        val start = selectionStart.coerceIn(0, existingText.length)
        val end = selectionEnd.coerceIn(start, existingText.length)
        existingText.replace(start, end, text)
        selectionStart = start + text.length
        selectionEnd = selectionStart
    }

    fun deleteBackward() {
        val text = currentText
        val start = selectionStart
        val end = selectionEnd
        if (text != null && start != end) {
            inputConnection.commitText("", 1)
            editRevision++
            text.delete(start, end)
            selectionStart = start
            selectionEnd = start
            return
        }

        val deleteLength = text
            ?.takeIf { start > 0 }
            ?.let { Character.charCount(Character.codePointBefore(it, start)) }
            ?: 1
        if (!inputConnection.deleteSurroundingText(deleteLength, 0)) {
            sendDeleteKey()
        }
        editRevision++

        if (text != null && start > 0) {
            val deleteStart = (start - deleteLength).coerceAtLeast(0)
            text.delete(deleteStart, start)
            selectionStart = deleteStart
            selectionEnd = selectionStart
        }
    }

    fun clearText() {
        val text = currentText
        if (text != null) {
            inputConnection.setSelection(0, text.length)
            inputConnection.commitText("", 1)
        } else {
            if (!inputConnection.deleteSurroundingText(MAX_CLEAR_TEXT_LENGTH, MAX_CLEAR_TEXT_LENGTH)) {
                repeat(32) { sendDeleteKey() }
            }
        }
        editRevision++
        currentText = StringBuilder()
        selectionStart = 0
        selectionEnd = 0
    }

    fun submit(text: String) {
        insertText(text)
        performEditorAction()
    }

    fun performEditorAction(): Boolean {
        val isMultiline =
            editorInfo.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (isMultiline) {
            insertText("\n")
            return true
        }

        val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        inputConnection.performEditorAction(
            if (action == EditorInfo.IME_ACTION_NONE ||
                action == EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                EditorInfo.IME_ACTION_DONE
            } else {
                action
            }
        )
        return false
    }

    private fun sendDeleteKey() {
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }

    companion object {
        private const val MAX_CLEAR_TEXT_LENGTH = 16_384

        fun from(focusedView: View?): SystemInputSession? {
            focusedView ?: return null

            val editorInfo = EditorInfo()
            val inputConnection = focusedView.onCreateInputConnection(editorInfo) ?: return null
            if (editorInfo.inputType == InputType.TYPE_NULL) return null

            return SystemInputSession(
                inputConnection = inputConnection,
                editorInfo = editorInfo
            )
        }
    }
}
