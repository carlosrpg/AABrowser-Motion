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

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.kododake.aabrowser.model.BrowserMediaState
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal class BrowserWebMediaController(
    private val getWebView: () -> WebView?,
    private val getPageUrl: () -> String,
    private val onStateChanged: (BrowserMediaState) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var currentWebView: WebView? = null
    private var mediaState = BrowserMediaState.None
    private var isDestroyed = false
    private var isYouTubePlaylist = false
    private var youtubeNextUrl: String? = null
    private var youtubePreviousUrl: String? = null

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
        handler.removeCallbacksAndMessages(null)
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
            publishState(BrowserMediaState.None)
            return
        }

        val newState = BrowserMediaState(
            isAvailable = true,
            isPlaying = data?.optBoolean("playing") == true,
            canSkipPrevious = data?.optBoolean("hasPrevious") == true,
            canSkipNext = data?.optBoolean("hasNext") == true
        )
        publishState(newState)
    }

    private fun publishState(state: BrowserMediaState) {
        if (state != mediaState) {
            mediaState = state
            onStateChanged(state)
        }
    }

    private fun isYouTubePage(): Boolean {
        val host = Uri.parse(getWebView()?.url ?: getPageUrl()).host ?: return false
        return isYouTubeHost(host)
    }

    private fun isYouTubeHost(host: String): Boolean =
        host.equals("youtube.com", ignoreCase = true) ||
            host.endsWith(".youtube.com", ignoreCase = true)

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
                }
              } catch (_) {}
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
                found: players.length > 0 || !!(youtubeVideoData && youtubeVideoData.video_id),
                playing: !!active || youtubePlayerState === 1,
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
