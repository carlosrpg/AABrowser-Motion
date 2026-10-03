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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

internal class ProjectedKeyboardView(
    context: Context,
    private val listener: Listener
) : LinearLayout(context) {
    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onSubmit()
        fun onDismiss()
    }

    private var shiftEnabled = false
    private var symbolsEnabled = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))
        setBackgroundColor(Color.rgb(20, 20, 20))
        isFocusable = false
        renderKeys()
    }

    private fun renderKeys() {
        removeAllViews()
        if (symbolsEnabled) {
            addTextRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
            addTextRow(listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/"))
            addTextRow(listOf("*", "\"", "'", ":", ";", "!", "?", "_", "="))
        } else {
            addTextRow(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"))
            addTextRow(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"))
            addLetterActionRow()
        }
        addBottomRow()
    }

    private fun addTextRow(keys: List<String>) {
        addRow().also { row ->
            keys.forEach { key ->
                row.addView(
                    createKey(
                        label = if (shiftEnabled) key.uppercase() else key,
                        weight = 1f
                    ) {
                        listener.onText(if (shiftEnabled) key.uppercase() else key)
                        if (shiftEnabled) {
                            shiftEnabled = false
                            renderKeys()
                        }
                    }
                )
            }
        }
    }

    private fun addLetterActionRow() {
        addRow().also { row ->
            row.addView(createKey("Shift", 1.5f, special = true) {
                shiftEnabled = !shiftEnabled
                renderKeys()
            })
            listOf("z", "x", "c", "v", "b", "n", "m").forEach { key ->
                row.addView(createKey(if (shiftEnabled) key.uppercase() else key, 1f) {
                    listener.onText(if (shiftEnabled) key.uppercase() else key)
                    if (shiftEnabled) {
                        shiftEnabled = false
                        renderKeys()
                    }
                })
            }
            row.addView(createKey("Del", 1.5f, special = true, listener::onBackspace))
        }
    }

    private fun addBottomRow() {
        addRow().also { row ->
            row.addView(createKey(if (symbolsEnabled) "ABC" else "?123", 1.4f, special = true) {
                symbolsEnabled = !symbolsEnabled
                shiftEnabled = false
                renderKeys()
            })
            row.addView(createKey(",", 0.8f) { listener.onText(",") })
            row.addView(createKey("Space", 4f) { listener.onText(" ") })
            row.addView(createKey(".", 0.8f) { listener.onText(".") })
            row.addView(createKey("Enter", 1.4f, special = true, listener::onSubmit))
            row.addView(createKey("Hide", 1.2f, special = true, listener::onDismiss))
        }
    }

    private fun addRow(): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            isFocusable = false
            this@ProjectedKeyboardView.addView(
                this,
                LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
            )
        }

    private fun createKey(
        label: String,
        weight: Float,
        special: Boolean = false,
        onClick: () -> Unit
    ): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = if (label.length > 1) 13f else 18f
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            background = GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                setColor(
                    if (special) {
                        Color.rgb(65, 75, 72)
                    } else {
                        Color.rgb(48, 48, 48)
                    }
                )
            }
            setOnClickListener { onClick() }
            layoutParams = LayoutParams(0, dp(42), weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
