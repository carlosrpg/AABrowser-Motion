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

package com.kododake.aabrowser.data.prefs

import android.content.Context

object MediaPreferences {
    private const val PREFS_NAME = "browser_prefs"
    private const val KEY_MEDIA_PAGE_URL = "media_page_url"
    private const val KEY_MEDIA_ID = "media_id"
    private const val KEY_MEDIA_POSITION_MS = "media_position_ms"
    private const val KEY_MEDIA_DURATION_MS = "media_duration_ms"
    private const val KEY_MEDIA_TITLE = "media_title"
    private const val KEY_MEDIA_ARTIST = "media_artist"
    private const val KEY_MEDIA_ARTWORK_URL = "media_artwork_url"

    data class MediaCheckpoint(
        val pageUrl: String,
        val mediaId: String,
        val positionMs: Long,
        val durationMs: Long,
        val title: String = "",
        val artist: String = "",
        val artworkUrl: String? = null
    )

    fun getMediaCheckpoint(context: Context): MediaCheckpoint? {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pageUrl = preferences.getString(KEY_MEDIA_PAGE_URL, null)
            ?.takeIf(UrlFormatter::isHttpOrHttps) ?: return null
        val mediaId = preferences.getString(KEY_MEDIA_ID, null)
            ?.takeIf(String::isNotBlank) ?: return null
        return MediaCheckpoint(
            pageUrl = pageUrl,
            mediaId = mediaId,
            positionMs = preferences.getLong(KEY_MEDIA_POSITION_MS, 0L).coerceAtLeast(0L),
            durationMs = preferences.getLong(KEY_MEDIA_DURATION_MS, 0L).coerceAtLeast(0L),
            title = preferences.getString(KEY_MEDIA_TITLE, null).orEmpty(),
            artist = preferences.getString(KEY_MEDIA_ARTIST, null).orEmpty(),
            artworkUrl = preferences.getString(KEY_MEDIA_ARTWORK_URL, null)
        )
    }

    fun persistMediaCheckpoint(
        context: Context,
        pageUrl: String,
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
        title: String,
        artist: String,
        artworkUrl: String?
    ) {
        if (!UrlFormatter.isHttpOrHttps(pageUrl) || mediaId.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MEDIA_PAGE_URL, pageUrl)
            .putString(KEY_MEDIA_ID, mediaId)
            .putLong(KEY_MEDIA_POSITION_MS, positionMs.coerceAtLeast(0L))
            .putLong(KEY_MEDIA_DURATION_MS, durationMs.coerceAtLeast(0L))
            .putString(KEY_MEDIA_TITLE, title)
            .putString(KEY_MEDIA_ARTIST, artist)
            .putString(KEY_MEDIA_ARTWORK_URL, artworkUrl)
            .apply()
    }

    fun clearMediaCheckpoint(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_MEDIA_PAGE_URL)
            .remove(KEY_MEDIA_ID)
            .remove(KEY_MEDIA_POSITION_MS)
            .remove(KEY_MEDIA_DURATION_MS)
            .remove(KEY_MEDIA_TITLE)
            .remove(KEY_MEDIA_ARTIST)
            .remove(KEY_MEDIA_ARTWORK_URL)
            .apply()
    }
}
