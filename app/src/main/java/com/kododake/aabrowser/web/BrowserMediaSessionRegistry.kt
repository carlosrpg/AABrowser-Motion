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

package com.kododake.aabrowser.web

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.kododake.aabrowser.MainActivity
import com.kododake.aabrowser.model.BrowserMediaState

internal object BrowserMediaSessionRegistry {
    private val callbackHandler = Handler(Looper.getMainLooper())
    private var mediaSession: MediaSessionCompat? = null
    private var activeController: BrowserWebMediaController? = null
    private var browserService: BrowserMediaBrowserService? = null
    private var currentState = BrowserMediaState.None
    private var currentMetadata: MediaMetadataCompat? = null
    private var serviceStartRequested = false
    private var mediaKeyHeld = false
    private var lastMediaKeySeekTime = 0L

    @Synchronized
    fun getSession(context: Context): MediaSessionCompat {
        mediaSession?.let { return it }
        return MediaSessionCompat(context.applicationContext, SESSION_TAG).apply {
            setSessionActivity(
                PendingIntent.getActivity(
                    context.applicationContext,
                    SESSION_ACTIVITY_REQUEST,
                    Intent(context.applicationContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    activeController?.playFromMediaSession()
                }

                override fun onPause() {
                    activeController?.pauseFromMediaSession()
                }

                override fun onStop() {
                    activeController?.pauseFromMediaSession()
                }

                // Car next/previous keys: tap skips (on release), hold seeks repeatedly.
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val event = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    val direction = when (event?.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT -> 1L
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -1L
                        else -> return super.onMediaButtonEvent(mediaButtonEvent)
                    }
                    val controller = activeController ?: return true
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                            mediaKeyHeld = false
                            lastMediaKeySeekTime = 0L
                        } else if (event.eventTime - lastMediaKeySeekTime >= HELD_KEY_SEEK_INTERVAL_MS) {
                            mediaKeyHeld = true
                            lastMediaKeySeekTime = event.eventTime
                            controller.seekBy(direction * FAST_FORWARD_INTERVAL_MS)
                        }
                        KeyEvent.ACTION_UP -> when {
                            mediaKeyHeld || event.isCanceled -> Unit
                            // Head units that send no repeat events: a long DOWN→UP gap is still a hold.
                            event.eventTime - event.downTime >= LONG_PRESS_MS ->
                                controller.seekBy(direction * FAST_FORWARD_INTERVAL_MS)
                            direction > 0 -> controller.skipNext()
                            else -> controller.skipPrevious()
                        }
                    }
                    return true
                }

                override fun onSkipToNext() {
                    activeController?.skipNext()
                }

                override fun onSkipToPrevious() {
                    activeController?.skipPrevious()
                }

                override fun onSeekTo(pos: Long) {
                    activeController?.seekTo(pos)
                }

                override fun onFastForward() {
                    activeController?.seekBy(FAST_FORWARD_INTERVAL_MS)
                }

                override fun onRewind() {
                    activeController?.seekBy(-FAST_FORWARD_INTERVAL_MS)
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {
                    if (mediaId == null || mediaId == currentState.mediaId) {
                        activeController?.playFromMediaSession()
                    }
                }

                override fun onPlayFromSearch(query: String?, extras: android.os.Bundle?) {
                    activeController?.searchYouTubeFromMediaSession(query, extras)
                }
            }, callbackHandler)
        }.also { mediaSession = it }
    }

    @Synchronized
    fun attach(context: Context, controller: BrowserWebMediaController): MediaSessionCompat {
        activeController = controller
        currentState = BrowserMediaState.None
        return getSession(context)
    }

    fun updateState(
        controller: BrowserWebMediaController,
        state: BrowserMediaState,
        metadata: MediaMetadataCompat?,
        playbackState: PlaybackStateCompat,
        updateMetadata: Boolean
    ) {
        val session: MediaSessionCompat
        val service: BrowserMediaBrowserService?
        synchronized(this) {
            if (activeController !== controller) return
            currentState = state
            if (updateMetadata) currentMetadata = metadata
            session = getSession(controller.applicationContext)
            service = browserService
        }

        session.isActive = state.isAvailable
        if (updateMetadata) session.setMetadata(metadata)
        session.setPlaybackState(playbackState)
        service?.updateForState(state)
        if (state.isPlaying && service == null) {
            startBrowserService(controller.applicationContext)
        }
    }

    fun detach(controller: BrowserWebMediaController) {
        val session: MediaSessionCompat
        val service: BrowserMediaBrowserService?
        synchronized(this) {
            if (activeController !== controller) return
            activeController = null
            currentState = BrowserMediaState.None
            currentMetadata = null
            session = mediaSession ?: return
            service = browserService
        }

        session.isActive = false
        session.setMetadata(null)
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    PlaybackStateCompat.STATE_NONE,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    0f
                )
                .build()
        )
        service?.updateForState(BrowserMediaState.None)
    }

    @Synchronized
    fun registerService(service: BrowserMediaBrowserService): BrowserMediaState {
        browserService = service
        serviceStartRequested = false
        return currentState
    }

    @Synchronized
    fun unregisterService(service: BrowserMediaBrowserService) {
        if (browserService !== service) return
        browserService = null
        serviceStartRequested = false
    }

    @Synchronized
    fun currentState(): BrowserMediaState = currentState

    @Synchronized
    fun currentMetadata(): MediaMetadataCompat? = currentMetadata

    private fun startBrowserService(context: Context) {
        synchronized(this) {
            if (serviceStartRequested || browserService != null) return
            serviceStartRequested = true
        }
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BrowserMediaBrowserService::class.java)
                    .setAction(BrowserMediaBrowserService.ACTION_UPDATE)
            )
        } catch (error: IllegalStateException) {
            synchronized(this) { serviceStartRequested = false }
            Log.e(TAG, "Unable to start the media playback service", error)
        }
    }

    private const val SESSION_TAG = "AABrowserMotion.WebMedia"
    private const val FAST_FORWARD_INTERVAL_MS = 15_000L
    private const val HELD_KEY_SEEK_INTERVAL_MS = 500L
    private const val LONG_PRESS_MS = 500L
    private const val SESSION_ACTIVITY_REQUEST = 2039
    private const val TAG = "BrowserMediaSession"
}
