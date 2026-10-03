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

import android.content.Context
import android.graphics.Rect
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.media.AudioManager
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.kododake.aabrowser.R
import java.util.Locale

@Suppress("DEPRECATION")
internal class ProjectedKeyboardView(
    context: Context,
    private val listener: Listener
) : LinearLayout(context) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onEditorAction()
        fun onClearText()
        fun onDismiss()
        fun onLanguageChanged(locale: Locale)
        fun onPreviewCursorChanged(position: Int)
    }

    private val previewScroller = HorizontalScrollView(context)
    private var previewCursorPosition = 0
    private var previewValueLength = 0
    private var previewHasCaret = false
    private var previewTouchStartX = 0f
    private var previewTouchLastX = 0f
    private var previewWasDragged = false
    private val previewTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var previewSession: SystemInputSession? = null
    private var previewUpdatePosted = false
    private val selectionBackgroundColor = MaterialColors.getColor(
        context,
        com.google.android.material.R.attr.colorPrimaryContainer,
        0xFFD7E3FF.toInt()
    )
    private val selectionForegroundColor = MaterialColors.getColor(
        context,
        com.google.android.material.R.attr.colorOnPrimaryContainer,
        0xFF001C46.toInt()
    )
    private val caretColor = MaterialColors.getColor(
        context,
        androidx.appcompat.R.attr.colorPrimary,
        0xFF1B6EF3.toInt()
    )
    private val scrollPreviewToCaret = Runnable {
        val layout = previewText.layout ?: return@Runnable
        val caretX = layout.getPrimaryHorizontal(previewCursorPosition).toInt()
        val scrollX = (caretX - previewScroller.width + dp(32)).coerceAtLeast(0)
        previewScroller.scrollTo(scrollX, 0)
    }
    private val renderPreview = Runnable {
        previewUpdatePosted = false
        val session = previewSession
        renderPreviewText(
            session?.previewText,
            session?.hint ?: "",
            session?.isSecureInput == true,
            session?.cursorStart ?: 0,
            session?.cursorEnd ?: 0
        )
    }
    private val previewText = TextView(context)
    private val languageButton = TextView(context)
    private val clearButton = TextView(context)
    private val keyboardView = LayoutInflater.from(context)
        .inflate(R.layout.inline_keyboard_view, this, false) as KeyboardView
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val enabledLanguages = SUPPORTED_LANGUAGES
    private var languageIndex = findInitialLanguage(context)
    private var editorInfo = EditorInfo()
    private var currentKeyboard: Keyboard? = null
    private var currentLayoutResource = 0
    private var inputType = InputType.TYPE_CLASS_TEXT
    private var symbolsVisible = false
    private var shiftEnabled = false
    private var capsLockEnabled = false
    private var lastShiftTap = 0L
    private var isSecureInput = false
    private var deleteKeyHeld = false
    private var deleteRepeatTriggered = false
    private val deleteRepeatRunnable = object : Runnable {
        override fun run() {
            if (!deleteKeyHeld) return
            deleteRepeatTriggered = true
            listener.onBackspace()
            postDelayed(this, DELETE_REPEAT_INTERVAL_MS)
        }
    }

    val currentLanguage: Locale
        get() = enabledLanguages[languageIndex]

    init {
        orientation = VERTICAL
        isFocusable = false
        setPadding(dp(6), dp(5), dp(6), dp(4))
        setBackgroundColor(
            MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorSurfaceContainer,
                0xFF101216.toInt()
            )
        )

        addView(createPreviewRow(), LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        keyboardView.apply {
            setPreviewEnabled(false)
            setProximityCorrectionEnabled(false)
            setPopupParent(this@ProjectedKeyboardView)
            setOnKeyboardActionListener(createKeyboardActionListener())
        }
        addView(
            keyboardView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        loadKeyboard(resolveKeyboardResource())
        updateLanguageButton()
    }

    fun configure(newEditorInfo: EditorInfo) {
        val newInputType = newEditorInfo.inputType
        val inputTypeChanged = newInputType != inputType
        editorInfo = newEditorInfo
        inputType = newInputType
        isSecureInput = isSecureInput(newInputType)
        if (inputTypeChanged) {
            symbolsVisible = false
            shiftEnabled = shouldStartShifted(newInputType)
            capsLockEnabled = false
            loadKeyboard(resolveKeyboardResource())
        } else {
            updateActionLabel()
        }
        updateLanguageButton()
    }

    fun setInputSession(session: SystemInputSession) {
        previewSession = session
        schedulePreviewRender()
    }

    fun refreshPreview() {
        schedulePreviewRender()
    }

    fun clearInputSession() {
        previewSession = null
        previewUpdatePosted = false
        previewText.removeCallbacks(renderPreview)
        renderPreviewText(null, "", false, 0, 0)
    }

    private fun schedulePreviewRender() {
        if (previewUpdatePosted) return
        previewUpdatePosted = true
        previewText.postOnAnimation(renderPreview)
    }

    private fun renderPreviewText(
        text: String?,
        hint: String,
        maskText: Boolean,
        selectionStart: Int,
        selectionEnd: Int
    ) {
        isSecureInput = maskText
        val value = text.orEmpty()
        previewValueLength = value.length
        val displayValue = if (isSecureInput) {
            buildString { repeat(value.length) { append('\u2022') } }
        } else value
        val start = selectionStart.coerceIn(0, displayValue.length)
        val end = selectionEnd.coerceIn(start, displayValue.length)

        if (displayValue.isEmpty()) {
            previewText.text = ""
            previewText.hint = hint
            previewCursorPosition = 0
            previewHasCaret = false
        } else {
            val preview = SpannableStringBuilder(displayValue)
            if (start != end) {
                preview.setSpan(
                    BackgroundColorSpan(selectionBackgroundColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                preview.setSpan(
                    ForegroundColorSpan(selectionForegroundColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            preview.insert(start, "\u2502")
            preview.setSpan(
                ForegroundColorSpan(caretColor),
                start,
                start + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            previewText.text = preview
            previewText.hint = null
            previewCursorPosition = start
            previewHasCaret = true
        }
        clearButton.visibility = if (value.isEmpty()) View.INVISIBLE else View.VISIBLE
        previewScroller.removeCallbacks(scrollPreviewToCaret)
        previewScroller.post(scrollPreviewToCaret)
    }

    fun containsPoint(x: Float, y: Float): Boolean {
        if (visibility != View.VISIBLE) return false
        return containsPointInBounds(x, y)
    }

    fun containsPointInBounds(x: Float, y: Float): Boolean {
        val bounds = Rect()
        getHitRect(bounds)
        return bounds.contains(x.toInt(), y.toInt())
    }

    override fun onDetachedFromWindow() {
        cancelDeleteRepeat()
        super.onDetachedFromWindow()
    }

    private fun createPreviewRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        languageButton.apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOnPrimaryContainer,
                    0xFF001C46.toInt()
                )
            )
            background = roundedBackground(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorPrimaryContainer,
                    0xFFD7E3FF.toInt()
                ),
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorPrimaryContainer,
                    0xFFD7E3FF.toInt()
                )
            )
            isClickable = true
            isFocusable = false
            setOnClickListener { cycleLanguage() }
        }
        row.addView(languageButton, LayoutParams(dp(76), dp(44)))

        previewText.apply {
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            setHorizontallyScrolling(true)
            setTextColor(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOnSurface,
                    0xFF191C20.toInt()
                )
            )
            setHintTextColor(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    0xFF43474E.toInt()
                )
            )
            setPadding(dp(10), 0, dp(10), 0)
            contentDescription = "Current field text"
            isClickable = true
            setOnClickListener { }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        previewTouchStartX = event.x
                        previewTouchLastX = event.x
                        previewWasDragged = false
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(event.x - previewTouchStartX) > previewTouchSlop) {
                            previewWasDragged = true
                            previewScroller.scrollBy(
                                (previewTouchLastX - event.x).toInt(),
                                0
                            )
                        }
                        previewTouchLastX = event.x
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!previewWasDragged) {
                            val offset = previewText.getOffsetForPosition(event.x, event.y)
                            val position = if (previewHasCaret &&
                                offset > previewCursorPosition
                            ) {
                                offset - 1
                            } else {
                                offset
                            }
                            listener.onPreviewCursorChanged(
                                position.coerceIn(0, previewValueLength)
                            )
                            performClick()
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        previewWasDragged = false
                        true
                    }
                    else -> true
                }
            }
        }
        previewScroller.apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            background = roundedBackground(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorSurfaceContainerHighest,
                    0xFFDFE3E8.toInt()
                ),
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOutlineVariant,
                    0xFFC3C7CF.toInt()
                )
            )
            addView(
                previewText,
                ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            )
        }
        row.addView(
            previewScroller,
            LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        )

        clearButton.apply {
            text = "\u00D7"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    0xFF43474E.toInt()
                )
            )
            contentDescription = "Clear field text"
            isClickable = true
            isFocusable = false
            setOnClickListener { listener.onClearText() }
        }
        row.addView(clearButton, LayoutParams(dp(44), dp(44)))
        return row
    }

    private fun cycleLanguage() {
        if (enabledLanguages.size < 2) return
        languageIndex = (languageIndex + 1) % enabledLanguages.size
        symbolsVisible = false
        shiftEnabled = false
        capsLockEnabled = false
        loadKeyboard(resolveKeyboardResource())
        updateLanguageButton()
        listener.onLanguageChanged(currentLanguage)
    }

    private fun updateLanguageButton() {
        val locale = currentLanguage
        languageButton.text = locale.toLanguageTag().uppercase(Locale.ROOT)
        languageButton.contentDescription =
            "Keyboard language: ${locale.getDisplayName(locale)}"
    }

    private fun resolveKeyboardResource(): Int {
        if (symbolsVisible) return R.xml.inline_keyboard_symbols
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass == InputType.TYPE_CLASS_PHONE) return R.xml.inline_keyboard_phone
        if (inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_DATETIME
        ) {
            return R.xml.inline_keyboard_numeric
        }

        return when (currentLanguage.language) {
            "fr" -> R.xml.inline_keyboard_azerty
            "de" -> R.xml.inline_keyboard_qwertz
            else -> R.xml.inline_keyboard_qwerty
        }
    }

    private fun loadKeyboard(resourceId: Int) {
        if (resourceId == currentLayoutResource && currentKeyboard != null) {
            updateActionLabel()
            return
        }

        val keyboard = Keyboard(context, resourceId)
        applyPopupCharacters(keyboard)
        currentKeyboard = keyboard
        currentLayoutResource = resourceId
        keyboardView.keyboard = keyboard
        keyboardView.isShifted = shiftEnabled || capsLockEnabled
        updateActionLabel()
    }

    private fun applyPopupCharacters(keyboard: Keyboard) {
        val language = currentLanguage.language
        keyboard.keys.forEach { key ->
            val code = key.codes.firstOrNull() ?: return@forEach
            if (code !in 'a'.code..'z'.code) return@forEach
            key.popupCharacters = popupCharacters(code.toChar(), language)
        }
    }

    private fun popupCharacters(key: Char, language: String): String? {
        return when (language) {
            "pt" -> when (key) {
                'a' -> "áàâãä"
                'e' -> "éèêë"
                'i' -> "íìîï"
                'o' -> "óòôõö"
                'u' -> "úùûü"
                'c' -> "ç"
                else -> null
            }
            "es" -> when (key) {
                'a' -> "áà"
                'e' -> "éè"
                'i' -> "íì"
                'o' -> "óò"
                'u' -> "úü"
                'n' -> "ñ"
                else -> null
            }
            "fr" -> when (key) {
                'a' -> "àâäæ"
                'c' -> "ç"
                'e' -> "éèêë"
                'i' -> "îï"
                'o' -> "ôœ"
                'u' -> "ùûü"
                else -> null
            }
            "de" -> when (key) {
                'a' -> "äáà"
                'o' -> "öóò"
                'u' -> "üúù"
                's' -> "ß"
                else -> null
            }
            else -> when (key) {
                'a' -> "áàâäãå"
                'e' -> "éèêë"
                'i' -> "íìîï"
                'o' -> "óòôöõ"
                'u' -> "úùûü"
                'n' -> "ñ"
                else -> null
            }
        }
    }

    private fun updateActionLabel() {
        val keyboard = currentKeyboard ?: return
        val actionLabel = editorInfo.actionLabel?.toString()?.takeIf(String::isNotBlank)
            ?: editorActionLabel()
        keyboard.keys.firstOrNull {
            it.codes.firstOrNull() == Keyboard.KEYCODE_DONE
        }?.label = actionLabel
        keyboardView.invalidateAllKeys()
    }

    private fun editorActionLabel(): String {
        val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        val portuguese = currentLanguage.language == "pt"
        return when (action) {
            EditorInfo.IME_ACTION_GO -> if (portuguese) "Ir" else "Go"
            EditorInfo.IME_ACTION_SEARCH -> if (portuguese) "Buscar" else "Search"
            EditorInfo.IME_ACTION_SEND -> if (portuguese) "Enviar" else "Send"
            EditorInfo.IME_ACTION_NEXT -> if (portuguese) "Próximo" else "Next"
            else -> if (portuguese) "OK" else "Done"
        }
    }

    private fun handleCharacter(primaryCode: Int) {
        if (primaryCode < 0) return
        val value = String(Character.toChars(primaryCode))
        listener.onText(
            if (shiftEnabled || capsLockEnabled) value.uppercase(currentLanguage) else value
        )
        if (shiftEnabled && !capsLockEnabled) {
            shiftEnabled = false
            keyboardView.isShifted = false
            keyboardView.invalidateAllKeys()
        }
    }

    private fun cancelDeleteRepeat() {
        deleteKeyHeld = false
        removeCallbacks(deleteRepeatRunnable)
    }

    private fun toggleShift() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastShiftTap < DOUBLE_TAP_SHIFT_MS) {
            capsLockEnabled = !capsLockEnabled
            shiftEnabled = capsLockEnabled
        } else {
            shiftEnabled = !shiftEnabled
        }
        lastShiftTap = now
        keyboardView.isShifted = shiftEnabled || capsLockEnabled
        keyboardView.invalidateAllKeys()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private fun createKeyboardActionListener(): KeyboardView.OnKeyboardActionListener =
        object : KeyboardView.OnKeyboardActionListener {
            override fun onPress(primaryCode: Int) {
                if (primaryCode == Keyboard.KEYCODE_DELETE) {
                    deleteKeyHeld = true
                    deleteRepeatTriggered = false
                    removeCallbacks(deleteRepeatRunnable)
                    postDelayed(deleteRepeatRunnable, DELETE_REPEAT_START_DELAY_MS)
                }
                keyboardView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            override fun onRelease(primaryCode: Int) {
                if (primaryCode == Keyboard.KEYCODE_DELETE) {
                    cancelDeleteRepeat()
                }
                audioManager?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
            }
            override fun swipeDown() = Unit
            override fun swipeLeft() = Unit
            override fun swipeRight() = Unit
            override fun swipeUp() = Unit

            override fun onText(text: CharSequence) {
                listener.onText(
                    if (shiftEnabled || capsLockEnabled) {
                        text.toString().uppercase(currentLanguage)
                    } else {
                        text.toString()
                    }
                )
                if (shiftEnabled && !capsLockEnabled) {
                    shiftEnabled = false
                    keyboardView.isShifted = false
                    keyboardView.invalidateAllKeys()
                }
            }

            override fun onKey(primaryCode: Int, keyCodes: IntArray) {
                when (primaryCode) {
                    Keyboard.KEYCODE_SHIFT -> toggleShift()
                    Keyboard.KEYCODE_DELETE -> {
                        if (!deleteRepeatTriggered) listener.onBackspace()
                    }
                    Keyboard.KEYCODE_DONE -> listener.onEditorAction()
                    Keyboard.KEYCODE_CANCEL -> listener.onDismiss()
                    Keyboard.KEYCODE_MODE_CHANGE -> {
                        symbolsVisible = !symbolsVisible
                        loadKeyboard(resolveKeyboardResource())
                    }
                    KEYCODE_DOMAIN -> listener.onText(
                        when {
                            inputType and InputType.TYPE_MASK_VARIATION ==
                                InputType.TYPE_TEXT_VARIATION_URI -> ".com"
                            inputType and InputType.TYPE_MASK_VARIATION ==
                                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> "@"
                            else -> "."
                        }
                    )
                    KEYCODE_SCHEME -> listener.onText("https://")
                    else -> handleCharacter(primaryCode)
                }
            }
        }

    private fun roundedBackground(color: Int, outline: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(color)
            setStroke(dp(1), outline)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEYCODE_DOMAIN = -102
        const val KEYCODE_SCHEME = -103
        const val DOUBLE_TAP_SHIFT_MS = 350L
        const val DELETE_REPEAT_START_DELAY_MS = 260L
        const val DELETE_REPEAT_INTERVAL_MS = 35L

        val SUPPORTED_LANGUAGES = listOf(
            Locale.US,
            Locale("pt", "PT"),
            Locale("pt", "BR")
        )

        fun findInitialLanguage(context: Context): Int {
            val currentImeLocale = context.getSystemService(InputMethodManager::class.java)
                ?.currentInputMethodSubtype
                ?.locale
                ?.takeIf(String::isNotBlank)
                ?.let { Locale.forLanguageTag(it.replace('_', '-')) }
            val preferredLocale = currentImeLocale
                ?: context.resources.configuration.locales[0]
                ?: Locale.getDefault()
            return SUPPORTED_LANGUAGES.indexOfFirst {
                it.language == preferredLocale.language &&
                    it.country == preferredLocale.country
            }.takeIf { it >= 0 } ?: SUPPORTED_LANGUAGES.indexOfFirst {
                it.language == preferredLocale.language
            }.takeIf { it >= 0 } ?: 0
        }

        fun isSecureInput(inputType: Int): Boolean {
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return when (inputClass) {
                InputType.TYPE_CLASS_TEXT ->
                    variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                InputType.TYPE_CLASS_NUMBER ->
                    variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }

        fun shouldStartShifted(inputType: Int): Boolean =
            inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 ||
                inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0 ||
                inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0
    }
}
