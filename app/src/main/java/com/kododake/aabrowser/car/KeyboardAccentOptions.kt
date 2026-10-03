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

package com.kododake.aabrowser.car

internal object KeyboardAccentOptions {
    fun forKey(key: Char, language: String): String? =
        when (language) {
            "pt" -> when (key) {
                'a' -> "aáàâãäæ"
                'e' -> "eéèêë"
                'i' -> "iíìîï"
                'o' -> "oóòôõö"
                'u' -> "uúùûü"
                'c' -> "cç"
                else -> null
            }
            "es" -> when (key) {
                'a' -> "aáà"
                'e' -> "eéè"
                'i' -> "ií"
                'o' -> "oó"
                'u' -> "uúü"
                'n' -> "nñ"
                else -> null
            }
            "fr" -> when (key) {
                'a' -> "aàâäæ"
                'c' -> "cç"
                'e' -> "eéèêë"
                'i' -> "iîï"
                'o' -> "oôœ"
                'u' -> "uùûü"
                else -> null
            }
            "de" -> when (key) {
                'a' -> "aäáà"
                'o' -> "oöóò"
                'u' -> "uüúù"
                's' -> "sß"
                else -> null
            }
            else -> when (key) {
                'a' -> "aáàâäãå"
                'e' -> "eéèêë"
                'i' -> "iíìîï"
                'o' -> "oóòôöõ"
                'u' -> "uúùûü"
                'n' -> "nñ"
                else -> null
            }
        }
}
