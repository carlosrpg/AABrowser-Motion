package com.kododake.aabrowser.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkDefaultsTest {

    private val legacy = listOf(
        "https://www.google.com",
        "https://youtube.com",
        "https://duckduckgo.com",
        "https://keepandroidopen.org"
    )

    @Test
    fun defaultBookmarksMatchStartPageQuickLinks() {
        assertEquals(StartPagePreferences.DEFAULT_START_PAGE_SITES, BookmarkPreferences.DEFAULT_BOOKMARKS)
    }

    @Test
    fun untouchedLegacyDefaultsAreReplaced() {
        assertTrue(BookmarkPreferences.isLegacyDefaultBookmarks(legacy))
        assertTrue(BookmarkPreferences.isLegacyDefaultBookmarks(legacy + BookmarkPreferences.GAME_BOOKMARK_URL))
        assertTrue(BookmarkPreferences.isLegacyDefaultBookmarks(legacy.reversed().map { "$it/" }))
    }

    @Test
    fun customizedBookmarksAreKept() {
        assertFalse(BookmarkPreferences.isLegacyDefaultBookmarks(legacy + "https://example.com"))
        assertFalse(BookmarkPreferences.isLegacyDefaultBookmarks(legacy.drop(1)))
        assertFalse(BookmarkPreferences.isLegacyDefaultBookmarks(legacy + legacy.first()))
        assertFalse(BookmarkPreferences.isLegacyDefaultBookmarks(BookmarkPreferences.DEFAULT_BOOKMARKS))
        assertFalse(BookmarkPreferences.isLegacyDefaultBookmarks(emptyList()))
    }
}
