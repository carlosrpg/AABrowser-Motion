package com.kododake.aabrowser.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaHoldTest {
    private val hold = MediaHold(holdMs = 10_000, missHoldMs = 5_000)

    @Test
    fun videoChangeKeepsPlayingUntilTheHoldRunsOut() {
        assertTrue(hold.playing(playing = true, paused = false, now = 0))
        // ended / loading the next video: neither playing nor a deliberate pause
        assertTrue(hold.playing(playing = false, paused = false, now = 3_000))
        assertTrue(hold.isHolding)
        assertTrue(hold.playing(playing = false, paused = false, now = 10_000))
        assertFalse(hold.playing(playing = false, paused = false, now = 10_001))
        assertFalse(hold.isHolding)
    }

    @Test
    fun deliberatePauseIsReportedAtOnce() {
        hold.playing(playing = true, paused = false, now = 0)
        assertFalse(hold.playing(playing = false, paused = true, now = 1_000))
        // and a later unclear reading does not bring "playing" back
        assertFalse(hold.playing(playing = false, paused = false, now = 2_000))
    }

    @Test
    fun ourOwnPauseCommandEndsTheHold() {
        hold.playing(playing = true, paused = false, now = 0)
        hold.pauseRequested()
        assertFalse(hold.playing(playing = false, paused = false, now = 300))
    }

    @Test
    fun unclearReadingWithoutEarlierPlaybackIsNotPlaying() {
        assertFalse(hold.playing(playing = false, paused = false, now = 0))
    }

    @Test
    fun missedPollsKeepTheStateOnlyForTheHold() {
        assertFalse(hold.keepOnMiss(now = 0)) // nothing was ever found
        hold.playing(playing = true, paused = false, now = 0)
        assertTrue(hold.keepOnMiss(now = 5_000))
        assertFalse(hold.keepOnMiss(now = 5_001))
        // forgotten: media has to be found again before anything is held
        assertFalse(hold.keepOnMiss(now = 5_002))
    }

    @Test
    fun playbackResumingAfterAHoldRestartsTheClock() {
        hold.playing(playing = true, paused = false, now = 0)
        hold.playing(playing = false, paused = false, now = 8_000)
        assertTrue(hold.playing(playing = true, paused = false, now = 9_000))
        assertFalse(hold.isHolding)
        assertTrue(hold.playing(playing = false, paused = false, now = 18_000))
    }
}
