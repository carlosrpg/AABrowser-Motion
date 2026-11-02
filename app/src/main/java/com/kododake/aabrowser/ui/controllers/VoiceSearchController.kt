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

package com.kododake.aabrowser.ui.controllers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** One-shot speech capture for the mic button; the recognized text becomes a YouTube search. */
class VoiceSearchController(
    private val context: Context,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onQuery: (String) -> Unit
) : RecognitionListener {
    private var recognizer: SpeechRecognizer? = null

    val isListening: Boolean get() = recognizer != null

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        stop()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { speechRecognizer ->
            speechRecognizer.setRecognitionListener(this)
            speechRecognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
            )
        }
        onListeningChanged(true)
    }

    fun stop() {
        recognizer?.let { speechRecognizer ->
            runCatching { speechRecognizer.stopListening() }
            runCatching { speechRecognizer.destroy() }
        }
        recognizer = null
        onListeningChanged(false)
    }

    override fun onResults(results: Bundle?) {
        val query = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim()
        stop()
        if (!query.isNullOrBlank()) onQuery(query)
    }

    override fun onError(error: Int) = stop()
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
