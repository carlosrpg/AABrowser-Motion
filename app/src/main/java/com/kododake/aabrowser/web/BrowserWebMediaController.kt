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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.webkit.WebView
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.model.BrowserMediaState
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal class BrowserWebMediaController(
    context: Context,
    private val getWebView: () -> WebView?,
    private val getPageTitle: () -> String,
    private val getPageUrl: () -> String,
    private val onStateChanged: (BrowserMediaState) -> Unit,
    private val onMediaPositionRestoreAttempted: () -> Unit = {}
) {
    internal val applicationContext = context.applicationContext
    private var pendingMediaRestore = BrowserPreferences.getMediaCheckpoint(applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private val appIcon = createAppIcon()
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private val mediaSession = BrowserMediaSessionRegistry.attach(applicationContext, this)
    private var currentWebView: WebView? = null
    private var mediaState = BrowserMediaState.None
    private var isDestroyed = false
    private var mediaPositionRestoreAttempts = 0
    private var mediaPositionRestoreSeekIssued = false
    private var isYouTubePlaylist = false
    private var youtubeNextUrl: String? = null
    private var youtubePreviousUrl: String? = null
    private var lastMetadataKey: String? = null
    private var artworkUrl: String? = null
    private var artworkBitmap: Bitmap? = null
    private var artworkRequestId = 0L
    private var startupYouTubePlaybackChecked = false
    private var youtubeSearchRequestId = 0L
    private var pendingYoutubeSearchRequestId: Long? = null
    private var pendingYoutubeSearchTerms: String? = null
    private var youtubeSearchAttempts = 0
    private var pendingYoutubeAutoplayVideoId: String? = null
    private var youtubeAutoplayAttempts = 0

    private val youtubeSearchAttempt = object : Runnable {
        override fun run() {
            val requestId = pendingYoutubeSearchRequestId ?: return
            val webView = getWebView()
            if (isDestroyed || webView == null) {
                pendingYoutubeSearchRequestId = null
                pendingYoutubeSearchTerms = null
                Log.w(TAG, "Unable to select a YouTube result without an active WebView")
                return
            }

            if (youtubeSearchAttempts >= MAX_YOUTUBE_SEARCH_ATTEMPTS) {
                pendingYoutubeSearchRequestId = null
                pendingYoutubeSearchTerms = null
                Log.w(TAG, "No playable YouTube result appeared for media voice search")
                return
            }
            youtubeSearchAttempts++

            val searchUri = Uri.parse(webView.url.orEmpty())
            if (!isYouTubeHost(searchUri.host.orEmpty()) ||
                searchUri.path != "/results" ||
                searchUri.getQueryParameter("search_query") != pendingYoutubeSearchTerms
            ) {
                handler.postDelayed(this, YOUTUBE_SEARCH_RETRY_INTERVAL_MS)
                return
            }

            webView.evaluateJavascript(FIRST_YOUTUBE_VIDEO_RESULT_SCRIPT) { result ->
                if (isDestroyed || pendingYoutubeSearchRequestId != requestId) return@evaluateJavascript

                val candidateUrl = runCatching {
                    JSONTokener(result ?: "null").nextValue() as? String
                }.getOrNull()
                val videoId = candidateUrl?.let(::youtubeVideoIdFromWatchUrl)
                if (videoId != null) {
                    pendingYoutubeSearchRequestId = null
                    pendingYoutubeSearchTerms = null
                    val watchUrl = youtubeWatchUrl(videoId)
                    webView.loadUrl(watchUrl)
                    scheduleYoutubeAutoplay(videoId)
                } else {
                    handler.postDelayed(this, YOUTUBE_SEARCH_RETRY_INTERVAL_MS)
                }
            }
        }
    }

    private val youtubeAutoplayAttempt = object : Runnable {
        override fun run() {
            val videoId = pendingYoutubeAutoplayVideoId ?: return
            val webView = getWebView()
            if (isDestroyed || webView == null) {
                pendingYoutubeAutoplayVideoId = null
                return
            }

            val currentVideoId = youtubeVideoIdFromWatchUrl(webView.url.orEmpty())
            if (currentVideoId != videoId) {
                val currentUri = Uri.parse(webView.url.orEmpty())
                if (currentUri.path != "/results" && currentUri.path != "/watch") {
                    pendingYoutubeAutoplayVideoId = null
                    return
                }
                if (youtubeAutoplayAttempts < MAX_YOUTUBE_AUTOPLAY_ATTEMPTS) {
                    youtubeAutoplayAttempts++
                    handler.postDelayed(this, YOUTUBE_AUTOPLAY_RETRY_INTERVAL_MS)
                } else {
                    pendingYoutubeAutoplayVideoId = null
                }
                return
            }

            if (mediaState.isPlaying && mediaState.mediaId == videoId) {
                pendingYoutubeAutoplayVideoId = null
                return
            }

            if (youtubeAutoplayAttempts >= MAX_YOUTUBE_AUTOPLAY_ATTEMPTS) {
                pendingYoutubeAutoplayVideoId = null
                Log.w(TAG, "YouTube did not start playing after selecting the first search result")
                return
            }

            youtubeAutoplayAttempts++
            webView.evaluateJavascript(PLAY_SCRIPT, null)
            handler.postDelayed(this, YOUTUBE_AUTOPLAY_RETRY_INTERVAL_MS)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            val webView = getWebView()
            if (webView == null) {
                currentWebView = null
                updateState(null)
            } else {
                currentWebView = webView
                webView.evaluateJavascript(createMonitorScript()) { result ->
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

    fun togglePlayback() {
        execute(if (mediaState.isPlaying) PAUSE_SCRIPT else PLAY_SCRIPT)
    }

    fun isPlaying(): Boolean = mediaState.isPlaying

    fun captureCurrentPosition() {
        val webView = getWebView() ?: return
        if (mediaState.isAvailable) {
            BrowserPreferences.persistMediaCheckpoint(
                applicationContext,
                webView.url ?: getPageUrl(),
                mediaState.mediaId,
                mediaState.positionMs,
                mediaState.durationMs
            )
        }
        webView.evaluateJavascript(createMonitorScript()) { result ->
            onMonitorResult(webView, result)
        }
    }

    internal fun playFromMediaSession() {
        execute(PLAY_SCRIPT)
    }

    internal fun searchYouTubeFromMediaSession(query: String?, extras: Bundle? = null) {
        youtubeSearchRequestId++
        pendingYoutubeSearchRequestId = null
        pendingYoutubeSearchTerms = null
        pendingYoutubeAutoplayVideoId = null
        handler.removeCallbacks(youtubeSearchAttempt)
        handler.removeCallbacks(youtubeAutoplayAttempt)

        val searchTerms = resolveMediaSearchTerms(query, extras)
            ?.trim()
            ?.take(MAX_MEDIA_SEARCH_QUERY_LENGTH)
        if (searchTerms.isNullOrBlank()) {
            Log.w(TAG, "Ignoring empty YouTube media search query")
            return
        }

        val webView = getWebView()
        if (webView == null) {
            Log.w(TAG, "Cannot search YouTube because there is no active WebView")
            return
        }

        pendingYoutubeSearchRequestId = youtubeSearchRequestId
        pendingYoutubeSearchTerms = searchTerms
        youtubeSearchAttempts = 0
        youtubeAutoplayAttempts = 0
        isYouTubePlaylist = false
        youtubeNextUrl = null
        youtubePreviousUrl = null
        val searchUrl = Uri.parse(YOUTUBE_SEARCH_URL)
            .buildUpon()
            .appendQueryParameter("search_query", searchTerms)
            .appendQueryParameter("sp", YOUTUBE_VIDEO_FILTER)
            .build()
            .toString()
        webView.loadUrl(searchUrl)
        handler.postDelayed(youtubeSearchAttempt, YOUTUBE_SEARCH_RETRY_INTERVAL_MS)
    }

    private fun resolveMediaSearchTerms(query: String?, extras: Bundle?): String? {
        val focusedTerms = when (extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)) {
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE ->
                extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE ->
                listOf(
                    extras.getString(MediaStore.EXTRA_MEDIA_ALBUM),
                    extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)
                ).filterNotNull().filter(String::isNotBlank).joinToString(" ")
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE ->
                listOf(
                    extras.getString(MediaStore.EXTRA_MEDIA_TITLE),
                    extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)
                ).filterNotNull().filter(String::isNotBlank).joinToString(" ")
            MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE ->
                extras.getString(MediaStore.EXTRA_MEDIA_GENRE)
            MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE ->
                extras.getString(MediaStore.EXTRA_MEDIA_PLAYLIST)
            else -> null
        }
        return focusedTerms?.takeIf(String::isNotBlank) ?: query
    }

    internal fun pauseFromMediaSession() {
        execute(PAUSE_SCRIPT)
    }

    internal fun seekTo(positionMs: Long) {
        if (!mediaState.canSeek) return
        seekToPosition(positionMs.coerceIn(0L, mediaState.durationMs))
    }

    internal fun seekBy(deltaMs: Long) {
        if (!mediaState.canSeek) return
        // Relative to the live position: mediaState.positionMs is up to one poll stale, which breaks repeated seeks.
        execute(SEEK_BY_SCRIPT.replace("__AA_BROWSER_SEEK_DELTA__", (deltaMs / 1_000.0).toString()))
    }

    private fun seekToPosition(positionMs: Long) {
        val seconds = positionMs / 1_000.0
        execute(
            """
                (function() {
                  const player = document.getElementById("movie_player");
                  if (player && typeof player.seekTo === "function") {
                    player.seekTo($seconds, true);
                    return;
                  }
                  const media = Array.from(document.querySelectorAll("video,audio"))
                    .find(item => !item.error && Number.isFinite(item.duration));
                  if (media) media.currentTime = Math.min($seconds, media.duration);
                })();
            """.trimIndent()
        )
    }

    fun skipNext() {
        when {
            isYouTubePage() && mediaState.canSkipNext -> execute(YOUTUBE_NEXT_SCRIPT)
            isYouTubePage() && isYouTubePlaylist ->
                youtubeNextUrl?.let(::navigateToPlaylistUrl) ?: execute(YOUTUBE_PLAYLIST_NEXT_SCRIPT)
            isYouTubePage() -> execute(YOUTUBE_NEXT_SCRIPT)
            else -> execute(NEXT_SCRIPT)
        }
    }

    // On-screen previous/next go through the session like car keys, so tap/hold behave identically.
    fun dispatchMediaKey(event: KeyEvent) {
        if (!isDestroyed) mediaSession.controller.dispatchMediaButtonEvent(event)
    }

    fun toggleFullscreen() {
        execute(FULLSCREEN_SCRIPT)
    }

    fun skipPrevious() {
        val webView = getWebView()
        if (isDestroyed || webView == null || !mediaState.canSeek) {
            skipToPreviousTrack()
            return
        }
        webView.evaluateJavascript(CURRENT_POSITION_SCRIPT) { result ->
            val positionSeconds = result?.toDoubleOrNull() ?: 0.0
            if (positionSeconds > RESTART_INSTEAD_OF_PREVIOUS_SECONDS) seekToPosition(0L) else skipToPreviousTrack()
        }
    }

    private fun skipToPreviousTrack() {
        when {
            isYouTubePage() && mediaState.canSkipPrevious -> execute(YOUTUBE_PREVIOUS_SCRIPT)
            isYouTubePage() && isYouTubePlaylist ->
                youtubePreviousUrl?.let(::navigateToPlaylistUrl) ?: execute(YOUTUBE_PLAYLIST_PREVIOUS_SCRIPT)
            isYouTubePage() -> execute(YOUTUBE_PREVIOUS_SCRIPT)
            else -> execute(PREVIOUS_SCRIPT)
        }
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        artworkRequestId++
        artworkExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        BrowserMediaSessionRegistry.detach(this)
    }

    private fun onMonitorResult(webView: WebView, result: String?) {
        if (isDestroyed || webView !== currentWebView || webView !== getWebView()) return

        if (result.isNullOrBlank() || result == "null") return
        val data = try {
            when (val parsed = JSONTokener(result).nextValue()) {
                is String -> JSONObject(parsed)
                is JSONObject -> parsed
                else -> {
                    Log.w(TAG, "Unexpected media monitor result: $parsed")
                    return
                }
            }
        } catch (error: JSONException) {
            Log.w(TAG, "Could not parse media monitor result", error)
            return
        }
        unmuteMutedYouTubeOnStartup(webView, data)
        updateState(data)
    }

    private fun unmuteMutedYouTubeOnStartup(webView: WebView, data: JSONObject) {
        if (startupYouTubePlaybackChecked ||
            !data.optBoolean("isYouTube") ||
            !data.optBoolean("found") ||
            !data.optBoolean("playing")
        ) {
            return
        }

        startupYouTubePlaybackChecked = true
        webView.evaluateJavascript(UNMUTE_YOUTUBE_SCRIPT, null)
    }

    private fun updateState(data: JSONObject?) {
        if (data == null || !data.optBoolean("found")) {
            isYouTubePlaylist = false
            youtubeNextUrl = null
            youtubePreviousUrl = null
            artworkRequestId++
            artworkUrl = null
            artworkBitmap = null
            lastMetadataKey = null
            publishState(BrowserMediaState.None)
            publishSessionState(BrowserMediaState.None, updateMetadata = true)
            return
        }

        isYouTubePlaylist = data.optBoolean("isPlaylist")
        youtubeNextUrl = validatedYouTubePlaylistUrl(data.optString("nextUrl"))
        youtubePreviousUrl = validatedYouTubePlaylistUrl(data.optString("previousUrl"))
        val pageUrl = getWebView()?.url ?: getPageUrl()
        val fallbackTitle = getPageTitle().ifBlank { pageUrl }
        val newState = BrowserMediaState(
            isAvailable = true,
            isPlaying = data.optBoolean("playing"),
            canSkipPrevious = data.optBoolean("hasPrevious"),
            canSkipNext = data.optBoolean("hasNext"),
            hasFullscreenButton = data.optBoolean("hasFullscreenButton"),
            title = data.optString("title").ifBlank { fallbackTitle },
            artist = data.optString("artist")
                .ifBlank { Uri.parse(pageUrl).host.orEmpty().ifBlank { applicationContext.packageName } },
            album = data.optString("album").ifBlank { Uri.parse(pageUrl).host.orEmpty() },
            mediaId = data.optString("mediaId").ifBlank { pageUrl },
            mediaUri = data.optString("mediaUri").ifBlank { pageUrl },
            artworkUrl = data.optString("artwork").takeIf(::isSupportedArtworkUrl),
            positionMs = (data.optDouble("position", 0.0) * 1_000.0).toLong().coerceAtLeast(0L),
            durationMs = (data.optDouble("duration", 0.0) * 1_000.0).toLong().coerceAtLeast(0L),
            canSeek = data.optBoolean("canSeek")
        )
        val restoringPosition = getWebView()?.let { restoreMediaPosition(it, pageUrl, newState) } == true
        if (!restoringPosition) {
            BrowserPreferences.persistMediaCheckpoint(
                applicationContext,
                pageUrl,
                newState.mediaId,
                newState.positionMs,
                newState.durationMs
            )
        }
        publishState(newState)
        if (newState.isPlaying && newState.mediaId == pendingYoutubeAutoplayVideoId) {
            pendingYoutubeAutoplayVideoId = null
            handler.removeCallbacks(youtubeAutoplayAttempt)
        }

        val metadataKey = listOf(
            newState.mediaId,
            newState.title,
            newState.artist,
            newState.album,
            newState.artworkUrl.orEmpty(),
            newState.durationMs
        ).joinToString("\u0000")
        val updateMetadata = metadataKey != lastMetadataKey
        if (updateMetadata) {
            lastMetadataKey = metadataKey
            updateArtwork(newState.artworkUrl)
        }
        publishSessionState(newState, updateMetadata)
    }

    private fun publishState(state: BrowserMediaState) {
        val uiStateChanged = state.isAvailable != mediaState.isAvailable ||
            state.isPlaying != mediaState.isPlaying ||
            state.canSkipPrevious != mediaState.canSkipPrevious ||
            state.canSkipNext != mediaState.canSkipNext ||
            state.hasFullscreenButton != mediaState.hasFullscreenButton
        mediaState = state
        if (uiStateChanged) onStateChanged(state)
    }

    private fun restoreMediaPosition(
        webView: WebView,
        pageUrl: String,
        state: BrowserMediaState
    ): Boolean {
        val checkpoint = pendingMediaRestore ?: return false
        val checkpointIsYouTubeVideo = YOUTUBE_VIDEO_ID_REGEX.matches(checkpoint.mediaId)
        if (checkpoint.mediaId != state.mediaId) {
            val waitingForYouTubeVideoId = checkpointIsYouTubeVideo &&
                isYouTubeHost(Uri.parse(pageUrl).host.orEmpty()) &&
                !YOUTUBE_VIDEO_ID_REGEX.matches(state.mediaId)
            if (waitingForYouTubeVideoId) return true
            clearMediaPositionRestore()
            return false
        }
        if (checkpoint.pageUrl != pageUrl && !checkpointIsYouTubeVideo) {
            clearMediaPositionRestore()
            return false
        }
        if (!state.canSeek || state.durationMs <= 0L) {
            return waitForMediaPositionRestore()
        }

        val targetPositionMs = checkpoint.positionMs.coerceAtMost(state.durationMs - 1_000L)
        if (targetPositionMs <= 0L) {
            clearMediaPositionRestore()
            return false
        }
        if (kotlin.math.abs(state.positionMs - targetPositionMs) <= MEDIA_POSITION_RESTORE_TOLERANCE_MS) {
            clearMediaPositionRestore()
            return false
        }
        if (mediaPositionRestoreAttempts >= MAX_MEDIA_POSITION_RESTORE_ATTEMPTS) {
            Log.w(TAG, "Could not restore the saved media position after multiple seek attempts")
            clearMediaPositionRestore()
            return false
        }

        mediaPositionRestoreAttempts++
        if (!mediaPositionRestoreSeekIssued) {
            mediaPositionRestoreSeekIssued = true
            onMediaPositionRestoreAttempted()
            val targetSeconds = targetPositionMs / 1_000.0
            webView.evaluateJavascript(createSeekScript(targetSeconds), null)
        }
        return true
    }

    private fun waitForMediaPositionRestore(): Boolean {
        if (mediaPositionRestoreAttempts >= MAX_MEDIA_POSITION_RESTORE_ATTEMPTS) {
            Log.w(TAG, "Media did not become seekable while restoring its saved position")
            clearMediaPositionRestore()
            return false
        }
        mediaPositionRestoreAttempts++
        return true
    }

    private fun clearMediaPositionRestore() {
        pendingMediaRestore = null
        mediaPositionRestoreAttempts = 0
        mediaPositionRestoreSeekIssued = false
        BrowserPreferences.clearMediaCheckpoint(applicationContext)
    }

    private fun createSeekScript(positionSeconds: Double): String =
        SEEK_TO_POSITION_SCRIPT.replace("__AA_BROWSER_SEEK_SECONDS__", positionSeconds.toString())

    private fun publishSessionState(state: BrowserMediaState, updateMetadata: Boolean) {
        val metadata = if (updateMetadata && state.isAvailable) buildMetadata(state) else null
        BrowserMediaSessionRegistry.updateState(
            controller = this,
            state = state,
            metadata = metadata,
            playbackState = buildPlaybackState(state),
            updateMetadata = updateMetadata
        )
    }

    private fun buildMetadata(state: BrowserMediaState): MediaMetadataCompat =
        MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, state.mediaId)
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, state.mediaUri)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, state.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, state.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, state.album)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, state.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
            .apply {
                state.artworkUrl?.let { artwork ->
                    putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artwork)
                    putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artwork)
                    putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artwork)
                }
                (artworkBitmap ?: appIcon)?.let { bitmap ->
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                    putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
                }
            }
            .build()

    private fun buildPlaybackState(state: BrowserMediaState): PlaybackStateCompat {
        var actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
        if (state.canSkipPrevious) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (state.canSkipNext) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (state.canSeek) {
            actions = actions or PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND
        }
        return PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(
                when {
                    !state.isAvailable -> PlaybackStateCompat.STATE_NONE
                    state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
                    else -> PlaybackStateCompat.STATE_PAUSED
                },
                if (state.canSeek) state.positionMs else PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                if (state.isPlaying) 1f else 0f,
                SystemClock.elapsedRealtime()
            )
            .build()
    }

    private fun updateArtwork(url: String?) {
        if (url == artworkUrl) return
        artworkUrl = url
        artworkBitmap = null
        val requestId = ++artworkRequestId
        if (url.isNullOrBlank()) return

        artworkExecutor.execute {
            val bitmap = downloadArtwork(url)
            handler.post {
                if (isDestroyed || requestId != artworkRequestId || url != artworkUrl) return@post
                artworkBitmap = bitmap ?: appIcon
                publishSessionState(mediaState, updateMetadata = true)
            }
        }
    }

    private fun downloadArtwork(url: String): Bitmap? {
        val connection = try {
            URL(url).openConnection() as? HttpURLConnection ?: return null
        } catch (error: IOException) {
            Log.w(TAG, "Could not open media artwork URL", error)
            return null
        }
        return try {
            connection.connectTimeout = ARTWORK_TIMEOUT_MS
            connection.readTimeout = ARTWORK_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            if (connection.contentLengthLong > MAX_ARTWORK_BYTES) return null
            val bytes = connection.inputStream.use { it.readNBytes(MAX_ARTWORK_BYTES + 1) }
            if (bytes.size > MAX_ARTWORK_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / MAX_ARTWORK_DIMENSION)
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            )
        } catch (error: IOException) {
            Log.w(TAG, "Could not download media artwork", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun createAppIcon(): Bitmap? {
        val drawable = applicationContext.packageManager
            .getApplicationIcon(applicationContext.applicationInfo)
        val size = APP_ICON_SIZE_PX
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).also { canvas ->
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
            }
        }
    }

    private fun isYouTubePage(): Boolean {
        val host = Uri.parse(getWebView()?.url ?: getPageUrl()).host ?: return false
        return isYouTubeHost(host)
    }

    private fun isYouTubeHost(host: String): Boolean =
        host.equals("youtube.com", ignoreCase = true) ||
            host.endsWith(".youtube.com", ignoreCase = true) ||
            host.equals("youtube-nocookie.com", ignoreCase = true) ||
            host.endsWith(".youtube-nocookie.com", ignoreCase = true)

    private fun youtubeVideoIdFromWatchUrl(candidate: String): String? {
        val uri = Uri.parse(candidate)
        if (uri.scheme != "https" || !isYouTubeHost(uri.host.orEmpty()) || uri.path != "/watch") return null
        return uri.getQueryParameter("v")
            ?.takeIf { YOUTUBE_VIDEO_ID_REGEX.matches(it) }
    }

    private fun youtubeWatchUrl(videoId: String): String =
        Uri.parse(YOUTUBE_WATCH_URL)
            .buildUpon()
            .appendQueryParameter("v", videoId)
            .build()
            .toString()

    private fun scheduleYoutubeAutoplay(videoId: String) {
        pendingYoutubeAutoplayVideoId = videoId
        youtubeAutoplayAttempts = 0
        handler.removeCallbacks(youtubeAutoplayAttempt)
        handler.postDelayed(youtubeAutoplayAttempt, YOUTUBE_AUTOPLAY_RETRY_INTERVAL_MS)
    }

    private fun isSupportedArtworkUrl(candidate: String): Boolean {
        val uri = Uri.parse(candidate)
        return uri.scheme == "https" && !uri.host.isNullOrBlank()
    }

    private fun validatedYouTubePlaylistUrl(candidate: String?): String? {
        if (candidate.isNullOrBlank()) return null
        val target = Uri.parse(candidate)
        val targetHost = target.host ?: return null
        if (target.scheme != "https" || !isYouTubeHost(targetHost)) return null

        val currentUri = Uri.parse(getWebView()?.url ?: getPageUrl())
        val currentListId = currentUri.getQueryParameter("list")
        val targetListId = target.getQueryParameter("list")
        if (currentListId != null && targetListId != null && targetListId != currentListId) return null
        if (currentListId != null && targetListId == null) {
            return target.buildUpon().appendQueryParameter("list", currentListId).build().toString()
        }
        return target.toString()
    }

    private fun navigateToPlaylistUrl(url: String) {
        if (isDestroyed) return
        val webView = getWebView() ?: return
        webView.evaluateJavascript("location.assign(${JSONObject.quote(url)});", null)
        youtubeNextUrl = null
        youtubePreviousUrl = null
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 500L)
    }

    private fun execute(script: String) {
        if (isDestroyed) return
        val webView = getWebView() ?: return
        val source = script.replace(BROWSER_URL_PLACEHOLDER, JSONObject.quote(getPageUrl()))
        webView.evaluateJavascript(source, null)
    }

    private fun createMonitorScript(): String =
        MONITOR_SCRIPT.replace(BROWSER_URL_PLACEHOLDER, JSONObject.quote(getPageUrl()))

    private companion object {
        const val MONITOR_INTERVAL_MS = 3_000L
        const val RESTART_INSTEAD_OF_PREVIOUS_SECONDS = 10.0
        const val MAX_MEDIA_POSITION_RESTORE_ATTEMPTS = 8
        const val MEDIA_POSITION_RESTORE_TOLERANCE_MS = 5_000L
        const val BROWSER_URL_PLACEHOLDER = "__AA_BROWSER_CURRENT_URL__"
        const val TAG = "BrowserWebMedia"
        const val ARTWORK_TIMEOUT_MS = 5_000
        const val MAX_ARTWORK_BYTES = 5 * 1024 * 1024
        const val MAX_ARTWORK_DIMENSION = 512
        const val APP_ICON_SIZE_PX = 256
        const val MAX_MEDIA_SEARCH_QUERY_LENGTH = 200
        const val MAX_YOUTUBE_SEARCH_ATTEMPTS = 15
        const val MAX_YOUTUBE_AUTOPLAY_ATTEMPTS = 20
        const val YOUTUBE_SEARCH_RETRY_INTERVAL_MS = 1_000L
        const val YOUTUBE_AUTOPLAY_RETRY_INTERVAL_MS = 1_000L
        const val YOUTUBE_SEARCH_URL = "https://www.youtube.com/results"
        const val YOUTUBE_WATCH_URL = "https://www.youtube.com/watch"
        const val YOUTUBE_VIDEO_FILTER = "EgIQAQ=="
        val YOUTUBE_VIDEO_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")
        val FIRST_YOUTUBE_VIDEO_RESULT_SCRIPT = """
            (function() {
              const selectors = [
                "ytd-video-renderer a#video-title[href*='/watch?v=']",
                "ytd-rich-item-renderer a#video-title[href*='/watch?v=']",
                "ytm-video-with-context-renderer a[href*='/watch?v=']",
                "ytm-compact-video-renderer a[href*='/watch?v=']",
                "ytm-video-renderer a[href*='/watch?v=']",
                "a[href^='/watch?v=']"
              ];
              const anchors = document.querySelectorAll(selectors.join(","));
              const adSelector = [
                "ytd-ad-slot-renderer",
                "ytd-promoted-sparkles-web-renderer",
                "ytm-promoted-sparkles-web-renderer",
                "[data-ad-preview]"
              ].join(",");
              for (const anchor of anchors) {
                if (anchor.closest(adSelector)) continue;
                let url;
                try {
                  url = new URL(anchor.href, location.href);
                } catch (_) {
                  continue;
                }
                if (url.pathname !== "/watch") continue;
                const videoId = url.searchParams.get("v");
                if (videoId && /^[A-Za-z0-9_-]{11}$/.test(videoId)) {
                  return "https://www.youtube.com/watch?v=" + encodeURIComponent(videoId);
                }
              }
              return null;
            })()
        """.trimIndent()
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
              const mediaPlayer = active || players[0] || null;
              const metadata = navigator.mediaSession && navigator.mediaSession.metadata;
              const youtubePlayer = document.getElementById("movie_player") ||
                document.querySelector(".html5-video-player");
              let youtubeVideoData = null;
              let youtubePlayerState = -1;
              try {
                if (youtubePlayer && typeof youtubePlayer.getVideoData === "function") {
                  youtubeVideoData = youtubePlayer.getVideoData();
                }
                if (youtubePlayer && typeof youtubePlayer.getPlayerState === "function") {
                  youtubePlayerState = youtubePlayer.getPlayerState();
                }
              } catch (_) {}
              let pageUrl = new URL(location.href);
              try {
                const browserUrl = new URL(__AA_BROWSER_CURRENT_URL__);
                if (!pageUrl.searchParams.has("list") && browserUrl.searchParams.has("list")) {
                  pageUrl = browserUrl;
                } else if (!pageUrl.searchParams.has("v") && browserUrl.searchParams.has("v")) {
                  pageUrl = browserUrl;
                }
              } catch (_) {}
              const isYouTube = /(^|\.)(youtube|youtube-nocookie)\.com$/i.test(location.hostname) ||
                /(^|\.)(youtube|youtube-nocookie)\.com$/i.test(pageUrl.hostname);
              const pageVideoId = pageUrl.searchParams.get("v") ||
                (pageUrl.pathname.indexOf("/shorts/") === 0
                  ? pageUrl.pathname.substring("/shorts/".length).split("/")[0] : "");
              const playerVideoId = youtubeVideoData && youtubeVideoData.video_id || "";
              const youtubeVideoId = pageVideoId || playerVideoId;
              const youtubeDataMatchesPage =
                !pageVideoId || !playerVideoId || pageVideoId === playerVideoId;
              let currentTime = mediaPlayer && Number.isFinite(mediaPlayer.currentTime)
                ? mediaPlayer.currentTime : 0;
              let duration = mediaPlayer && Number.isFinite(mediaPlayer.duration)
                ? mediaPlayer.duration : 0;
              let canSeek = !!(mediaPlayer && mediaPlayer.seekable && mediaPlayer.seekable.length > 0);
              try {
                if (youtubePlayer && typeof youtubePlayer.getCurrentTime === "function") {
                  const youtubeTime = youtubePlayer.getCurrentTime();
                  if (Number.isFinite(youtubeTime)) currentTime = youtubeTime;
                }
                if (youtubePlayer && typeof youtubePlayer.getDuration === "function") {
                  const youtubeDuration = youtubePlayer.getDuration();
                  if (Number.isFinite(youtubeDuration) && youtubeDuration > 0) {
                    duration = youtubeDuration;
                    canSeek = typeof youtubePlayer.seekTo === "function";
                  }
                }
              } catch (_) {}
              const pageTitle = document.title || "";
              const youtubeTitle = youtubeDataMatchesPage && youtubeVideoData && youtubeVideoData.title;
              const title = (youtubePlayer ? youtubeTitle : metadata && metadata.title) ||
                (mediaPlayer && mediaPlayer.getAttribute("title")) || pageTitle;
              const artist = (youtubePlayer
                ? youtubeDataMatchesPage && youtubeVideoData && youtubeVideoData.author
                : metadata && metadata.artist) || location.hostname;
              const album = (youtubePlayer ? pageTitle : metadata && metadata.album) ||
                pageTitle || location.hostname;
              const metadataArtwork = metadata && metadata.artwork && metadata.artwork.length
                ? metadata.artwork[0].src : "";
              const openGraphArtwork = document.querySelector('meta[property="og:image"]');
              const youtubeArtwork = youtubeVideoId
                ? "https://i.ytimg.com/vi/" + encodeURIComponent(youtubeVideoId) + "/hqdefault.jpg"
                : "";
              const artwork = youtubeArtwork ||
                metadataArtwork ||
                (mediaPlayer && mediaPlayer.poster) ||
                (openGraphArtwork && openGraphArtwork.content);
              const playlistId = pageUrl.searchParams.get("list");
              const playlistPanel = document.querySelector(
                "ytm-playlist-panel-renderer, ytd-playlist-panel-renderer, ytmusic-player-queue"
              );
              const isPlaylist = !!playlistId || !!playlistPanel;
              const fullscreenButton = document.querySelector(".ytp-fullscreen-button") ||
                document.querySelector(".icon-button.fullscreen-icon");
              const youtubeNextButton = document.querySelector(".ytp-next-button");
              const youtubePreviousButton = document.querySelector(".ytp-prev-button");
              const controls = Array.from(
                document.querySelectorAll(".player-middle-controls-prev-next-button")
              );
              const isEnabled = button =>
                !!button && !button.disabled && button.getAttribute("aria-disabled") !== "true";
              return JSON.stringify({
                found: players.length > 0 || !!youtubeVideoId || !!fullscreenButton,
                isYouTube: isYouTube,
                playing: !!active || youtubePlayerState === 1,
                title: title,
                artist: artist,
                album: album,
                mediaId: youtubeVideoId ||
                  (mediaPlayer && (mediaPlayer.currentSrc || mediaPlayer.src)) || location.href,
                mediaUri: location.href,
                artwork: artwork,
                position: currentTime,
                duration: duration,
                canSeek: canSeek && Number.isFinite(duration) && duration > 0,
                isPlaylist: isPlaylist,
                hasNext: isYouTube
                  ? !!youtubeNextButton ||
                    (isPlaylist && isEnabled(controls[1]))
                  : isPlaylist ? isEnabled(controls[1]) :
                    typeof bridge.next === "function",
                hasPrevious: isYouTube
                  ? !!youtubePreviousButton ||
                    (isPlaylist && isEnabled(controls[0]))
                  : isPlaylist ? isEnabled(controls[0]) :
                    typeof bridge.previous === "function",
                hasFullscreenButton: !!fullscreenButton
              });
            })()
        """.trimIndent()

        val PLAY_SCRIPT = """
            (function() {
              const player = document.getElementById("movie_player");
              if (player && typeof player.playVideo === "function") {
                player.playVideo();
                return;
              }
              const media = Array.from(document.querySelectorAll("video,audio"))
                .find(item => !item.error && !item.ended);
              if (media && media.paused) media.play().catch(() => {});
            })();
        """.trimIndent()

        val UNMUTE_YOUTUBE_SCRIPT = """
            (function() {
              const player = document.getElementById("movie_player") ||
                document.querySelector(".html5-video-player");
              if (player && typeof player.unMute === "function") {
                player.unMute();
                if (typeof player.getVolume === "function" &&
                    typeof player.setVolume === "function" &&
                    player.getVolume() === 0) {
                  player.setVolume(100);
                }
                return;
              }
              const media = document.querySelector("video");
              if (media) {
                media.muted = false;
                if (media.volume === 0) media.volume = 1;
              }
            })();
        """.trimIndent()

        val PAUSE_SCRIPT = """
            (function() {
              const player = document.getElementById("movie_player");
              if (player && typeof player.pauseVideo === "function") {
                player.pauseVideo();
                return;
              }
              const media = Array.from(document.querySelectorAll("video,audio"))
                .find(item => !item.error && !item.paused);
              if (media) media.pause();
            })();
        """.trimIndent()

        val YOUTUBE_PREVIOUS_SCRIPT = """
            (function() {
              const button = document.querySelector(".ytp-prev-button");
              if (button) {
                button.click();
                return;
              }
              const controls = document.querySelectorAll(".player-middle-controls-prev-next-button");
              if (controls[0]) controls[0].click();
            })();
        """.trimIndent()

        val YOUTUBE_NEXT_SCRIPT = """
            (function() {
              const button = document.querySelector(".ytp-next-button");
              if (button) {
                button.click();
                return;
              }
              const controls = document.querySelectorAll(".player-middle-controls-prev-next-button");
              if (controls[1]) controls[1].click();
            })();
        """.trimIndent()

        val YOUTUBE_PLAYLIST_NEXT_SCRIPT = """
            (function() {
              const controls = document.querySelectorAll(".player-middle-controls-prev-next-button");
              const button = controls[1];
              if (button && !button.disabled &&
                  button.getAttribute("aria-disabled") !== "true" &&
                  !button.classList.contains("ytp-button-disabled")) {
                button.click();
              }
            })();
        """.trimIndent()

        val YOUTUBE_PLAYLIST_PREVIOUS_SCRIPT = """
            (function() {
              const controls = document.querySelectorAll(".player-middle-controls-prev-next-button");
              const button = controls[0];
              if (button && !button.disabled &&
                  button.getAttribute("aria-disabled") !== "true" &&
                  !button.classList.contains("ytp-button-disabled")) {
                button.click();
              }
            })();
        """.trimIndent()

        val FULLSCREEN_SCRIPT = """
            (function() {
              const youtubeButton = document.querySelector(".ytp-fullscreen-button");
              const button = youtubeButton ||
                document.querySelector(".icon-button.fullscreen-icon");
              if (button) button.click();
            })();
        """.trimIndent()

        const val SEEK_TO_POSITION_SCRIPT = """
            (function() {
              const seconds = __AA_BROWSER_SEEK_SECONDS__;
              const player = document.getElementById("movie_player");
              if (player && typeof player.seekTo === "function") {
                player.seekTo(seconds, true);
                return true;
              }
              const media = Array.from(document.querySelectorAll("video,audio"))
                .find(item => !item.error && Number.isFinite(item.duration));
              if (!media) return false;
              media.currentTime = Math.min(seconds, media.duration);
              return true;
            })();
        """

        val CURRENT_POSITION_SCRIPT = """
            (function() {
              const player = document.getElementById("movie_player");
              if (player && typeof player.getCurrentTime === "function") return player.getCurrentTime();
              const media = Array.from(document.querySelectorAll("video,audio"))
                .find(item => !item.error && !item.paused) ||
                document.querySelector("video,audio");
              return media ? media.currentTime : 0;
            })();
        """.trimIndent()

        const val SEEK_BY_SCRIPT = """
            (function() {
              const delta = __AA_BROWSER_SEEK_DELTA__;
              const player = document.getElementById("movie_player");
              if (player && typeof player.seekTo === "function" &&
                  typeof player.getCurrentTime === "function") {
                player.seekTo(Math.max(0, player.getCurrentTime() + delta), true);
                return;
              }
              const media = Array.from(document.querySelectorAll("video,audio"))
                .find(item => !item.error && Number.isFinite(item.duration));
              if (media) media.currentTime = Math.min(Math.max(0, media.currentTime + delta), media.duration);
            })();
        """

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
