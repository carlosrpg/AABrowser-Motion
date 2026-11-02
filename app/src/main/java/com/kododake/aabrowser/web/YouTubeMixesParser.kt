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

import com.kododake.aabrowser.model.YouTubeMix
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Turns the mixes page script's `{done, notReady, items[]}` payload into validated [YouTubeMix] entries. */
internal object YouTubeMixesParser {
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val MIX_ID = Regex("RD[A-Za-z0-9_-]{1,80}")

    /** [pageNotReady]: YouTube's page config was not there yet, so the result says nothing; retry later. */
    data class Outcome(val mixes: List<YouTubeMix>, val pageNotReady: Boolean)

    /**
     * [result] is raw `WebView.evaluateJavascript` output; null means the page is still fetching
     * (unless [acceptPartial], which takes whatever the page has gathered so far).
     */
    fun parse(result: String?, acceptPartial: Boolean = false): Outcome? {
        val payload = runCatching {
            var element = Json.parseToJsonElement(result ?: "null")
            if (element is JsonPrimitive && element.isString) element = Json.parseToJsonElement(element.content)
            element as? JsonObject
        }.getOrNull() ?: return null
        if ((payload["done"] as? JsonPrimitive)?.booleanOrNull != true && !acceptPartial) return null
        val notReady = (payload["notReady"] as? JsonPrimitive)?.booleanOrNull == true
        val items = payload["items"] as? JsonArray ?: return Outcome(emptyList(), notReady)
        val mixes = items.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            val videoId = entry.string("videoId")
            val playlistId = entry.string("playlistId")
            val thumbnail = entry.string("thumbnail")
            if (!VIDEO_ID.matches(videoId) || !MIX_ID.matches(playlistId) || !thumbnail.startsWith("https://")) {
                return@mapNotNull null
            }
            YouTubeMix(
                title = entry.string("title").ifBlank { playlistId },
                thumbnailUrl = thumbnail,
                videoId = videoId,
                playlistId = playlistId
            )
        }.distinctBy { it.playlistId }
        return Outcome(mixes, notReady)
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}
