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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

internal class ProjectedWebMediaSession(
    private val context: Context,
    private val getWebView: () -> WebView?,
    private val getPageTitle: () -> String,
    private val getPageUrl: () -> String
) {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val appIcon = createAppIcon()
    private val mediaSession = MediaSession(context, SESSION_TAG).apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (requestAudioFocus()) {
                    execute(PLAY_SCRIPT)
                } else {
                    execute(PAUSE_SCRIPT)
                }
            }

            override fun onPause() {
                execute(PAUSE_SCRIPT)
                resumeAfterFocusGain = false
                abandonAudioFocus()
            }

            override fun onSkipToNext() {
                execute(NEXT_SCRIPT)
            }

            override fun onSkipToPrevious() {
                execute(PREVIOUS_SCRIPT)
            }
        }, handler)
    }
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterFocusGain = false
                execute(PAUSE_SCRIPT)
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeAfterFocusGain = isPlaying
                execute(PAUSE_SCRIPT)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeAfterFocusGain) {
                    resumeAfterFocusGain = false
                    execute(PLAY_SCRIPT)
                }
            }
        }
    }
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        .setOnAudioFocusChangeListener(focusChangeListener, handler)
        .setWillPauseWhenDucked(true)
        .build()

    private var currentWebView: WebView? = null
    private var isPlaying = false
    private var isPlaybackStatePlaying = false
    private var resumeAfterFocusGain = false
    private var isDestroyed = false
    private var lastMetadata: String? = null
    private var lastActions = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            val webView = getWebView()
            if (webView == null) {
                currentWebView = null
                updateMedia(null)
            } else if (webView !== currentWebView) {
                currentWebView = webView
                webView.evaluateJavascript(MONITOR_SCRIPT) { result ->
                    onMonitorResult(webView, result)
                }
            } else {
                webView.evaluateJavascript(MONITOR_SCRIPT) { result ->
                    onMonitorResult(webView, result)
                }
            }
            handler.postDelayed(this, MONITOR_INTERVAL_MS)
        }
    }

    fun startMonitoring() {
        if (!isDestroyed) {
            handler.removeCallbacks(poll)
            handler.post(poll)
        }
    }

    fun stopMonitoring() {
        handler.removeCallbacks(poll)
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        handler.removeCallbacksAndMessages(null)
        abandonAudioFocus()
        mediaSession.isActive = false
        mediaSession.release()
    }

    private fun onMonitorResult(webView: WebView, result: String?) {
        if (isDestroyed) return
        if (webView !== currentWebView || webView !== getWebView()) return

        val data = runCatching {
            val json = JSONTokener(result ?: return).nextValue() as? String ?: return
            JSONObject(json)
        }.getOrNull() ?: return
        updateMedia(data)
    }

    private fun updateMedia(data: JSONObject?) {
        val found = data?.optBoolean("found") == true
        var nowPlaying = found && data.optBoolean("playing")
        if (!found) {
            isPlaying = false
            isPlaybackStatePlaying = false
            resumeAfterFocusGain = false
            abandonAudioFocus()
            mediaSession.isActive = false
            mediaSession.setMetadata(null)
            mediaSession.setPlaybackState(
                PlaybackState.Builder().setState(
                    PlaybackState.STATE_NONE,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    0f
                ).build()
            )
            lastMetadata = null
            lastActions = 0L
            return
        }

        if (nowPlaying && !isPlaying && !requestAudioFocus()) {
            execute(PAUSE_SCRIPT)
            nowPlaying = false
        }
        isPlaying = nowPlaying

        val title = data.optString("title")
            .takeIf { it.isNotBlank() }
            ?: getPageTitle().ifBlank { getPageUrl() }
        val artist = data.optString("artist")
            .takeIf { it.isNotBlank() }
            ?: getPageUrl().substringAfter("://").substringBefore('/').ifBlank { context.packageName }
        val album = data.optString("album")
            .takeIf { it.isNotBlank() }
            ?: getPageTitle()
        val metadataKey = "$title\u0000$artist\u0000$album"
        if (metadataKey != lastMetadata) {
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
            appIcon?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
            mediaSession.setMetadata(metadata.build())
            lastMetadata = metadataKey
        }

        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            (if (data.optBoolean("hasNext")) PlaybackState.ACTION_SKIP_TO_NEXT else 0L) or
            (if (data.optBoolean("hasPrevious")) PlaybackState.ACTION_SKIP_TO_PREVIOUS else 0L)
        if (actions != lastActions || !mediaSession.isActive || nowPlaying != isPlaybackStatePlaying) {
            mediaSession.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(
                        if (nowPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        if (nowPlaying) 1f else 0f,
                        SystemClock.elapsedRealtime()
                    )
                    .build()
            )
            lastActions = actions
            isPlaybackStatePlaying = nowPlaying
        }
        mediaSession.isActive = true
    }

    private fun requestAudioFocus(): Boolean {
        val granted = audioManager?.requestAudioFocus(audioFocusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            resumeAfterFocusGain = false
        }
        return granted
    }

    private fun abandonAudioFocus() {
        audioManager?.abandonAudioFocusRequest(audioFocusRequest)
    }

    private fun execute(script: String) {
        if (!isDestroyed) getWebView()?.evaluateJavascript(script, null)
    }

    private fun createAppIcon(): Bitmap? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(context.applicationInfo)
        val size = ICON_SIZE_PX
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
    }.getOrNull()

    private companion object {
        const val SESSION_TAG = "AABrowserMotion.WebMedia"
        const val MONITOR_INTERVAL_MS = 500L
        const val ICON_SIZE_PX = 256

        val MONITOR_SCRIPT = """
            (function() {
              const key = "__aaBrowserMotionMedia";
              let bridge = window[key];
              if (!bridge) {
                bridge = { next: null, previous: null };
                window[key] = bridge;
                const mediaSession = navigator.mediaSession;
                if (mediaSession && typeof mediaSession.setActionHandler === "function") {
                  const original = mediaSession.setActionHandler.bind(mediaSession);
                  mediaSession.setActionHandler = function(action, callback) {
                    if (action === "nexttrack") bridge.next = typeof callback === "function" ? callback : null;
                    if (action === "previoustrack") bridge.previous = typeof callback === "function" ? callback : null;
                    return original(action, callback);
                  };
                }
              }
              const players = Array.from(document.querySelectorAll("audio,video"))
                .filter(player => !player.error && (player.currentSrc || player.src || player.querySelector("source[src]")));
              const active = players.find(player => !player.paused && !player.ended);
              const metadata = navigator.mediaSession && navigator.mediaSession.metadata;
              const artwork = metadata && metadata.artwork && metadata.artwork.length
                ? metadata.artwork[0].src : "";
              return JSON.stringify({
                found: players.length > 0,
                playing: !!active,
                title: metadata && metadata.title || (active && active.getAttribute("title")) || document.title || "",
                artist: metadata && metadata.artist || "",
                album: metadata && metadata.album || "",
                artwork: artwork,
                hasNext: typeof bridge.next === "function",
                hasPrevious: typeof bridge.previous === "function"
              });
            })()
        """.trimIndent()

        val PLAY_SCRIPT = """
            Array.from(document.querySelectorAll("audio,video")).forEach(player => {
              if (player.paused || player.ended) player.play().catch(() => {});
            });
        """.trimIndent()

        val PAUSE_SCRIPT = """
            Array.from(document.querySelectorAll("audio,video")).forEach(player => {
              if (!player.paused) player.pause();
            });
        """.trimIndent()

        val NEXT_SCRIPT = """
            (function() {
              const bridge = window.__aaBrowserMotionMedia;
              if (bridge && typeof bridge.next === "function") {
                try { bridge.next({ action: "nexttrack" }); } catch (_) {}
              }
            })();
        """.trimIndent()

        val PREVIOUS_SCRIPT = """
            (function() {
              const bridge = window.__aaBrowserMotionMedia;
              if (bridge && typeof bridge.previous === "function") {
                try { bridge.previous({ action: "previoustrack" }); } catch (_) {}
              }
            })();
        """.trimIndent()
    }
}
