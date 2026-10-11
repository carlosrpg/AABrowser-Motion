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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
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
    private val onStateChanged: (BrowserMediaState) -> Unit
) {
    internal val applicationContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val appIcon = createAppIcon()
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private val mediaSession = BrowserMediaSessionRegistry.attach(applicationContext, this)
    private var currentWebView: WebView? = null
    private var mediaState = BrowserMediaState.None
    private var isDestroyed = false
    private var isYouTubePlaylist = false
    private var youtubeNextUrl: String? = null
    private var youtubePreviousUrl: String? = null
    private var lastMetadataKey: String? = null
    private var artworkUrl: String? = null
    private var artworkBitmap: Bitmap? = null
    private var artworkRequestId = 0L

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

    internal fun playFromMediaSession() {
        execute(PLAY_SCRIPT)
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
        seekToPosition((mediaState.positionMs + deltaMs).coerceIn(0L, mediaState.durationMs))
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
            isYouTubePage() && isYouTubePlaylist ->
                youtubeNextUrl?.let(::navigateToPlaylistUrl) ?: execute(YOUTUBE_PLAYLIST_NEXT_SCRIPT)
            isYouTubePage() -> execute(YOUTUBE_NEXT_SCRIPT)
            else -> execute(NEXT_SCRIPT)
        }
    }

    fun skipPrevious() {
        when {
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
        updateState(data)
    }

    private fun updateState(data: JSONObject?) {
        val found = data?.optBoolean("found") == true
        isYouTubePlaylist = found && data?.optBoolean("isPlaylist") == true
        youtubeNextUrl = validatedYouTubePlaylistUrl(data?.optString("nextUrl"))
        youtubePreviousUrl = validatedYouTubePlaylistUrl(data?.optString("previousUrl"))
        if (!found) {
            artworkRequestId++
            artworkUrl = null
            artworkBitmap = null
            lastMetadataKey = null
            publishState(BrowserMediaState.None)
            publishSessionState(BrowserMediaState.None, updateMetadata = true)
            return
        }

        val mediaData = data ?: return
        val pageUrl = getWebView()?.url ?: getPageUrl()
        val fallbackTitle = getPageTitle().ifBlank { pageUrl }
        val newState = BrowserMediaState(
            isAvailable = true,
            isPlaying = mediaData.optBoolean("playing"),
            canSkipPrevious = mediaData.optBoolean("hasPrevious"),
            canSkipNext = mediaData.optBoolean("hasNext"),
            title = mediaData.optString("title").ifBlank { fallbackTitle },
            artist = mediaData.optString("artist")
                .ifBlank { Uri.parse(pageUrl).host.orEmpty().ifBlank { applicationContext.packageName } },
            album = mediaData.optString("album").ifBlank { Uri.parse(pageUrl).host.orEmpty() },
            mediaId = mediaData.optString("mediaId").ifBlank { pageUrl },
            mediaUri = mediaData.optString("mediaUri").ifBlank { pageUrl },
            artworkUrl = mediaData.optString("artwork").takeIf(::isSupportedArtworkUrl),
            positionMs = (mediaData.optDouble("position", 0.0) * 1_000.0).toLong().coerceAtLeast(0L),
            durationMs = (mediaData.optDouble("duration", 0.0) * 1_000.0).toLong().coerceAtLeast(0L),
            canSeek = mediaData.optBoolean("canSeek")
        )
        publishState(newState)

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
            state.canSkipNext != mediaState.canSkipNext
        mediaState = state
        if (uiStateChanged) onStateChanged(state)
    }

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
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
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
            host.endsWith(".youtube.com", ignoreCase = true)

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
        const val BROWSER_URL_PLACEHOLDER = "__AA_BROWSER_CURRENT_URL__"
        const val TAG = "BrowserWebMedia"
        const val ARTWORK_TIMEOUT_MS = 5_000
        const val MAX_ARTWORK_BYTES = 5 * 1024 * 1024
        const val MAX_ARTWORK_DIMENSION = 512
        const val APP_ICON_SIZE_PX = 256
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
              const controls = Array.from(
                document.querySelectorAll(".player-middle-controls-prev-next-button")
              );
              const isEnabled = button =>
                !!button && !button.disabled && button.getAttribute("aria-disabled") !== "true";
              return JSON.stringify({
                found: players.length > 0 || !!youtubeVideoId,
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
                hasNext: isPlaylist ? isEnabled(controls[1]) :
                  typeof bridge.next === "function" ||
                    !!(youtubePlayer && typeof youtubePlayer.nextVideo === "function"),
                hasPrevious: isPlaylist ? isEnabled(controls[0]) :
                  typeof bridge.previous === "function" ||
                    !!(youtubePlayer && typeof youtubePlayer.previousVideo === "function")
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
            document.getElementById("movie_player")?.previousVideo();
        """.trimIndent()

        val YOUTUBE_NEXT_SCRIPT = """
            document.getElementById("movie_player")?.nextVideo();
        """.trimIndent()

        val YOUTUBE_PLAYLIST_NEXT_SCRIPT = """
            (function() {
              const controls = document.querySelectorAll(".player-middle-controls-prev-next-button");
              if (controls.length > 1) controls[1].click();
            })();
        """.trimIndent()

        val YOUTUBE_PLAYLIST_PREVIOUS_SCRIPT = """
            (function() {
              const controls = document.querySelectorAll(".player-middle-controls-prev-next-button");
              if (controls.length > 0) controls[0].click();
            })();
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
