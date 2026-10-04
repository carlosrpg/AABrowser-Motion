package com.kododake.aabrowser.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardAccentOptionsTest {
    @Test
    fun portugueseLongPressIncludesPlainAndCommonAccentedLetters() {
        assertEquals("aáàâãäæ", KeyboardAccentOptions.forKey('a', "pt"))
        assertEquals("oóòôõö", KeyboardAccentOptions.forKey('o', "pt"))
        assertEquals("cç", KeyboardAccentOptions.forKey('c', "pt"))
    }

    @Test
    fun spanishLongPressIncludesEñe() {
        assertEquals("nñ", KeyboardAccentOptions.forKey('n', "es"))
    }

    @Test
    fun lettersWithoutAccentVariantsHaveNoPopup() {
        assertNull(KeyboardAccentOptions.forKey('z', "pt"))
    }
}
