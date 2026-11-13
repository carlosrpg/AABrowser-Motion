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

package com.kododake.aabrowser.gecko

import android.app.Activity
import android.content.Context
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate

/**
 * Dialogs for alert/confirm/prompt and <select>, which Gecko does not render on its own. Popups are allowed, as
 * the WebView build allows them. Without an Activity (no window to show a dialog in) prompts are dismissed.
 */
internal class GeckoDialogPrompts(private val context: Context) : PromptDelegate {
    private fun canShow(): Boolean = (context as? Activity)?.let { !it.isFinishing && !it.isDestroyed } == true

    override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptDelegate.PromptResponse> {
        if (!canShow()) return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        AlertDialog.Builder(context)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.dismiss()) }
            .setOnCancelListener { result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptDelegate.PromptResponse> {
        if (!canShow()) return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        AlertDialog.Builder(context)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
            .setOnCancelListener { result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptDelegate.PromptResponse> {
        if (!canShow()) return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        val input = EditText(context).apply { setText(prompt.defaultValue) }
        AlertDialog.Builder(context)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.confirm(input.text.toString())) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
            .setOnCancelListener { result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptDelegate.PromptResponse> {
        val flat = ArrayList<PromptDelegate.ChoicePrompt.Choice>()
        fun collect(choices: Array<PromptDelegate.ChoicePrompt.Choice>) {
            for (choice in choices) {
                val items = choice.items
                if (items != null) collect(items) else if (!choice.separator && !choice.disabled) flat.add(choice)
            }
        }
        collect(prompt.choices)
        if (flat.isEmpty() || !canShow()) return GeckoResult.fromValue(prompt.dismiss())

        val result = GeckoResult<PromptDelegate.PromptResponse>()
        AlertDialog.Builder(context)
            .setTitle(prompt.title)
            .setSingleChoiceItems(flat.map { it.label }.toTypedArray(), flat.indexOfFirst { it.selected }) { dialog, which ->
                result.complete(prompt.confirm(flat[which]))
                dialog.dismiss()
            }
            .setOnCancelListener { result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptDelegate.PromptResponse> =
        GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: PromptDelegate.BeforeUnloadPrompt): GeckoResult<PromptDelegate.PromptResponse> =
        GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
}
