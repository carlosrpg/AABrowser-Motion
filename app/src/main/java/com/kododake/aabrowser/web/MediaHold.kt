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

/**
 * Decides what to report while the page gives no clear answer. A video change (next in a mix, an ad
 * boundary, a page navigation) reads for a few seconds as "ended", "loading" or "nothing here"; car media
 * cards drop a session the moment it stops saying PLAYING, so those readings keep the last state: up to
 * [holdMs] while the media is still on the page, [missHoldMs] once the page shows none or stops answering.
 * Only a deliberate pause, or the hold running out, ends playback.
 */
internal class MediaHold(private val holdMs: Long, private val missHoldMs: Long) {
    private var lastPlayingAt: Long? = null
    private var lastFoundAt: Long? = null

    /** True while the last answer was a held one: poll faster until the page settles. */
    var isHolding = false
        private set

    /** Media is on the page: should it be reported as playing? [paused] is a deliberate pause. */
    fun playing(playing: Boolean, paused: Boolean, now: Long): Boolean {
        lastFoundAt = now
        if (playing) lastPlayingAt = now
        if (paused) lastPlayingAt = null
        isHolding = !playing && within(holdMs, lastPlayingAt, now)
        return playing || isHolding
    }

    /** The page showed no media or did not answer: keep the last reported state? */
    fun keepOnMiss(now: Long): Boolean {
        isHolding = within(missHoldMs, lastFoundAt, now)
        if (!isHolding) reset()
        return isHolding
    }

    /** A pause we sent ourselves: the next unclear reading must not be held as playing. */
    fun pauseRequested() {
        lastPlayingAt = null
    }

    fun reset() {
        lastPlayingAt = null
        lastFoundAt = null
        isHolding = false
    }

    private fun within(limitMs: Long, since: Long?, now: Long) = since != null && now - since <= limitMs
}
