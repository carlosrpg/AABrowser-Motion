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

internal class ProjectedTextBuffer(
    initialSelectionStart: Int = 0,
    initialSelectionEnd: Int = initialSelectionStart
) {
    private var value: StringBuilder? = null

    var selectionStart = initialSelectionStart.coerceAtLeast(0)
        private set
    var selectionEnd = initialSelectionEnd.coerceAtLeast(selectionStart)
        private set

    val text: String?
        get() = value?.toString()

    fun setSnapshot(text: String, start: Int, end: Int) {
        value = StringBuilder(text)
        selectionStart = start.coerceIn(0, text.length)
        selectionEnd = end.coerceIn(selectionStart, text.length)
    }

    fun setCursorPosition(position: Int): Int {
        val cursor = position.coerceIn(0, value?.length ?: 0)
        selectionStart = cursor
        selectionEnd = cursor
        return cursor
    }

    fun insertText(text: String) {
        val currentValue = value
        if (currentValue == null) {
            value = StringBuilder(text)
            selectionStart = text.length
            selectionEnd = text.length
            return
        }

        val start = selectionStart.coerceIn(0, currentValue.length)
        val end = selectionEnd.coerceIn(start, currentValue.length)
        currentValue.replace(start, end, text)
        selectionStart = start + text.length
        selectionEnd = selectionStart
    }

    fun deleteBackward(): Boolean {
        val currentValue = value ?: return false
        val start = selectionStart.coerceIn(0, currentValue.length)
        val end = selectionEnd.coerceIn(start, currentValue.length)
        if (start != end) {
            currentValue.delete(start, end)
            selectionStart = start
            selectionEnd = start
            return true
        }
        if (start == 0) return false

        val deleteStart = start - Character.charCount(Character.codePointBefore(currentValue, start))
        currentValue.delete(deleteStart, start)
        selectionStart = deleteStart
        selectionEnd = deleteStart
        return true
    }

    fun clear() {
        value = StringBuilder()
        selectionStart = 0
        selectionEnd = 0
    }
}
