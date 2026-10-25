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
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.kododake.aabrowser.MainActivity
import com.kododake.aabrowser.R
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.model.BrowserMediaState

internal object BrowserMediaSessionRegistry {
    private val callbackHandler = Handler(Looper.getMainLooper())
    private var mediaSession: MediaSessionCompat? = null
    private var activeController: BrowserWebMediaController? = null
    private var browserService: BrowserMediaBrowserService? = null
    private var currentState = BrowserMediaState.None
    private var currentMetadata: MediaMetadataCompat? = null
    private var serviceStartRequested = false
    private var appContext: Context? = null
    private var resumePlaybackRequested = false

    private val resumeTimeout = Runnable {
        if (!resumePlaybackRequested) return@Runnable
        resumePlaybackRequested = false
        // The browser never came up (e.g. background activity start blocked): tell the user instead of failing silently.
        if (activeController == null) {
            val context = appContext ?: return@Runnable
            mediaSession?.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(RESUMABLE_ACTIONS)
                    .setErrorMessage(
                        PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                        context.getString(R.string.media_resume_open_app)
                    )
                    .setState(PlaybackStateCompat.STATE_ERROR, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
                    .build()
            )
        }
    }

    @Synchronized
    fun getSession(context: Context): MediaSessionCompat {
        mediaSession?.let { return it }
        appContext = context.applicationContext
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
                    activeController?.playFromMediaSession() ?: resumeLastMedia()
                }

                override fun onPause() {
                    activeController?.pauseFromMediaSession()
                }

                override fun onStop() {
                    activeController?.pauseFromMediaSession()
                }

                // Car next/previous keys: tap skips, hold seeks (see BrowserWebMediaController.handleMediaKey).
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val event = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    val direction = when (event?.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT -> 1L
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -1L
                        else -> return super.onMediaButtonEvent(mediaButtonEvent)
                    }
                    Log.d(TAG, "Media key $event")
                    activeController?.handleMediaKey(event, direction)
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
                    if (activeController == null) {
                        resumeLastMedia()
                    } else if (mediaId == null || mediaId == currentState.mediaId) {
                        activeController?.playFromMediaSession()
                    }
                }

                override fun onPlayFromSearch(query: String?, extras: android.os.Bundle?) {
                    activeController?.searchYouTubeFromMediaSession(query, extras)
                }
            }, callbackHandler)
        }.also {
            mediaSession = it
            publishResumableState(it)
        }
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
        if (resumePlaybackRequested && state.isAvailable) {
            if (state.isPlaying) {
                resumePlaybackRequested = false
                callbackHandler.removeCallbacks(resumeTimeout)
            } else {
                controller.playFromMediaSession()
            }
        }
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
        publishResumableState(session)
        service?.updateForState(BrowserMediaState.None)
    }

    // With no browser open, expose the last played item as paused so a media host can offer to resume it.
    // The session stays inactive so it doesn't take media buttons from other players.
    // Note: Android Auto does not use this to choose its startup source (see BrowserMediaBrowserService.onGetRoot);
    // it is kept as standard resumption support.
    private fun publishResumableState(session: MediaSessionCompat) {
        val checkpoint = appContext?.let(BrowserPreferences::getMediaCheckpoint)
        if (checkpoint == null) {
            session.setMetadata(null)
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_NONE, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
                    .build()
            )
            return
        }
        val title = checkpoint.title.ifBlank { Uri.parse(checkpoint.pageUrl).host.orEmpty() }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, checkpoint.mediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, checkpoint.pageUrl)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, checkpoint.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, checkpoint.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, checkpoint.durationMs)
                .apply {
                    checkpoint.artworkUrl?.let {
                        putString(MediaMetadataCompat.METADATA_KEY_ART_URI, it)
                        putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, it)
                    }
                }
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(RESUMABLE_ACTIONS)
                .setState(PlaybackStateCompat.STATE_PAUSED, checkpoint.positionMs, 0f)
                .build()
        )
    }

    // Opens the browser on the last played page; playback starts once its media is detected (see updateState).
    private fun resumeLastMedia() {
        val context = appContext ?: return
        val checkpoint = BrowserPreferences.getMediaCheckpoint(context) ?: return
        resumePlaybackRequested = true
        callbackHandler.removeCallbacks(resumeTimeout)
        callbackHandler.postDelayed(resumeTimeout, RESUME_TIMEOUT_MS)
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(checkpoint.pageUrl), context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to open the browser to resume media", error)
        }
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
    const val FAST_FORWARD_INTERVAL_MS = 15_000L
    private const val SESSION_ACTIVITY_REQUEST = 2039
    private const val RESUME_TIMEOUT_MS = 20_000L
    private const val RESUMABLE_ACTIONS = PlaybackStateCompat.ACTION_PLAY or
        PlaybackStateCompat.ACTION_PLAY_PAUSE or
        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
        PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
    private const val TAG = "BrowserMediaSession"
}
