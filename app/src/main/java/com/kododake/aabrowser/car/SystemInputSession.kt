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
import android.webkit.WebView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

internal class SystemInputSession private constructor(
    private val inputConnection: InputConnection,
    val editorInfo: EditorInfo,
    private val webView: WebView?
) {
    private val webViewInputToken = nextWebViewInputToken.incrementAndGet()
    private val textBuffer = ProjectedTextBuffer(
        editorInfo.initialSelStart,
        editorInfo.initialSelEnd
    )
    private var hasWebViewSnapshot = false
    var editRevision: Int = 0
        private set

    val initialText: String
        get() = textBuffer.text.orEmpty()

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
        get() = textBuffer.text

    val cursorStart: Int
        get() = textBuffer.selectionStart

    val cursorEnd: Int
        get() = textBuffer.selectionEnd

    fun createWebViewSnapshotScript(): String? {
        if (webView == null) return null

        return """
            (function() {
                var element = document.activeElement;
                if (!element) return null;
                if (element.isContentEditable && element.closest) {
                    element = element.closest('[contenteditable="true"],[contenteditable=""]') ||
                        element;
                }
                var value = typeof element.value === "string"
                    ? element.value
                    : (element.isContentEditable ? element.innerText : "");
                if (typeof element.value !== "string" && !element.isContentEditable &&
                    !(element.getAttribute &&
                        /^(textbox|searchbox)$/i.test(element.getAttribute("role") || ""))) {
                    return null;
                }
                window.__aabrowserProjectedTextTarget = element;
                window.__aabrowserProjectedTextToken = $webViewInputToken;
                var start = value.length;
                var end = value.length;
                try {
                    if (typeof element.selectionStart === "number") {
                        start = element.selectionStart;
                        end = element.selectionEnd;
                    }
                } catch (ignored) {
                    var selection = element.ownerDocument.getSelection();
                    if (selection && selection.rangeCount > 0) {
                        start = end = selection.getRangeAt(0).startOffset;
                    }
                }
                return { text: value, selectionStart: start, selectionEnd: end };
            })()
        """.trimIndent()
    }

    fun setTextSnapshot(text: String, start: Int, end: Int) {
        textBuffer.setSnapshot(text, start, end)
        hasWebViewSnapshot = webView != null
    }

    fun setCursorPosition(position: Int): Boolean {
        val length = textBuffer.text?.length ?: 0
        val cursor = position.coerceIn(0, length)
        if (webView == null || !hasWebViewSnapshot) {
            if (!inputConnection.setSelection(cursor, cursor)) return false
        }
        textBuffer.setCursorPosition(cursor)
        updateWebViewText()
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
        if (webView == null || !hasWebViewSnapshot) {
            inputConnection.commitText(text, 1)
        }
        editRevision++
        textBuffer.insertText(text)
        updateWebViewText()
    }

    fun deleteBackward() {
        val text = textBuffer.text
        val start = textBuffer.selectionStart
        val end = textBuffer.selectionEnd
        if (text != null && start != end) {
            if (webView == null || !hasWebViewSnapshot) {
                inputConnection.commitText("", 1)
            }
            editRevision++
            textBuffer.deleteBackward()
            updateWebViewText()
            return
        }

        if (text != null && start == 0) return
        val deleteLength = text
            ?.takeIf { start > 0 }
            ?.let { Character.charCount(Character.codePointBefore(it, start)) }
            ?: 1
        if (webView == null || !hasWebViewSnapshot) {
            if (!inputConnection.deleteSurroundingText(deleteLength, 0)) {
                sendDeleteKey()
            }
        }
        editRevision++
        textBuffer.deleteBackward()
        updateWebViewText()
    }

    fun clearText() {
        val text = textBuffer.text
        if (webView == null || !hasWebViewSnapshot) {
            if (text != null) {
                inputConnection.setSelection(0, text.length)
                inputConnection.commitText("", 1)
            } else {
                if (!inputConnection.deleteSurroundingText(
                        MAX_CLEAR_TEXT_LENGTH,
                        MAX_CLEAR_TEXT_LENGTH
                    )
                ) {
                    repeat(32) { sendDeleteKey() }
                }
            }
        }
        editRevision++
        textBuffer.clear()
        updateWebViewText()
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

        if (webView != null && hasWebViewSnapshot) {
            webView.evaluateJavascript(createWebViewSubmitScript(), null)
            return false
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

    fun endEditing() {
        if (webView == null) return

        webView.evaluateJavascript(
            """
                (function() {
                    if (window.__aabrowserProjectedTextToken === $webViewInputToken) {
                        window.__aabrowserProjectedTextTarget = null;
                        window.__aabrowserProjectedTextToken = null;
                    }
                })()
            """.trimIndent(),
            null
        )
        hasWebViewSnapshot = false
    }

    private fun updateWebViewText() {
        if (webView == null || !hasWebViewSnapshot) return

        val value = JSONObject.quote(textBuffer.text.orEmpty())
        val start = textBuffer.selectionStart
        val end = textBuffer.selectionEnd
        webView.evaluateJavascript(
            """
                (function() {
                    if (window.__aabrowserProjectedTextToken !== $webViewInputToken) {
                        return false;
                    }
                    var element = window.__aabrowserProjectedTextTarget;
                    if (!element || !element.ownerDocument ||
                        !element.ownerDocument.contains(element)) return false;
                    var value = $value;
                    var tag = (element.tagName || "").toLowerCase();
                    if (element.isContentEditable) {
                        element.innerText = value;
                    } else if ("value" in element) {
                        var windowObject = element.ownerDocument.defaultView || window;
                        var prototype = tag === "textarea"
                            ? windowObject.HTMLTextAreaElement &&
                                windowObject.HTMLTextAreaElement.prototype
                            : tag === "input"
                                ? windowObject.HTMLInputElement &&
                                    windowObject.HTMLInputElement.prototype
                                : null;
                        var descriptor = prototype &&
                            Object.getOwnPropertyDescriptor(prototype, "value");
                        if (descriptor && descriptor.set) descriptor.set.call(element, value);
                        else element.value = value;
                    } else if (element.getAttribute &&
                        /^(textbox|searchbox)$/i.test(element.getAttribute("role") || "")) {
                        element.textContent = value;
                    } else {
                        return false;
                    }
                    try {
                        if (typeof element.setSelectionRange === "function") {
                            element.setSelectionRange($start, $end);
                        } else if (element.isContentEditable) {
                            var textNode = element.firstChild;
                            if (textNode) {
                                var range = element.ownerDocument.createRange();
                                range.setStart(textNode, Math.min($start, textNode.length));
                                range.collapse(true);
                                var selection = element.ownerDocument.getSelection();
                                selection.removeAllRanges();
                                selection.addRange(range);
                            }
                        }
                    } catch (ignored) {
                    }
                    try {
                        element.dispatchEvent(new InputEvent("input", {
                            bubbles: true,
                            data: null,
                            inputType: "insertText"
                        }));
                    } catch (ignored) {
                        element.dispatchEvent(new Event("input", { bubbles: true }));
                    }
                    element.dispatchEvent(new Event("change", { bubbles: true }));
                    return true;
                })()
            """.trimIndent(),
            null
        )
    }

    private fun createWebViewSubmitScript(): String = """
        (function() {
            if (window.__aabrowserProjectedTextToken !== $webViewInputToken) {
                return false;
            }
            var element = window.__aabrowserProjectedTextTarget;
            if (!element) return false;
            var form = element.form ||
                (element.closest && element.closest("form"));
            if (form) {
                if (form.requestSubmit) form.requestSubmit();
                else form.submit();
            } else {
                var event = {
                    code: "Enter",
                    key: "Enter",
                    keyCode: 13,
                    which: 13,
                    bubbles: true
                };
                element.dispatchEvent(new KeyboardEvent("keydown", event));
                element.dispatchEvent(new KeyboardEvent("keypress", event));
                element.dispatchEvent(new KeyboardEvent("keyup", event));
            }
            window.__aabrowserProjectedTextTarget = null;
            window.__aabrowserProjectedTextToken = null;
            return true;
        })()
    """.trimIndent()

    private fun sendDeleteKey() {
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }

    companion object {
        private const val MAX_CLEAR_TEXT_LENGTH = 16_384
        private val nextWebViewInputToken = AtomicLong()

        fun from(focusedView: View?): SystemInputSession? {
            focusedView ?: return null

            val editorInfo = EditorInfo()
            val inputConnection = focusedView.onCreateInputConnection(editorInfo) ?: return null
            if (editorInfo.inputType == InputType.TYPE_NULL) return null

            return SystemInputSession(
                inputConnection = inputConnection,
                editorInfo = editorInfo,
                webView = focusedView as? WebView
            )
        }
    }
}
