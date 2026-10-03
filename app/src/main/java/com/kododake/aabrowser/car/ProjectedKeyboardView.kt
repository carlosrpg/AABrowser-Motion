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
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.FrameLayout
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
    private val keyboardContainer = FrameLayout(context)
    private var accentPopup: LinearLayout? = null
    var onPointerEvent: ((Int) -> Unit)? = null
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
    private val clearButton = TextView(context)
    private val keyboardView = LayoutInflater.from(context)
        .inflate(R.layout.inline_keyboard_view, keyboardContainer, false) as ProjectedKeyboardSurface
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val enabledLanguages = SUPPORTED_LANGUAGES
    private var languageIndex = findInitialLanguage(context)
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
        setPadding(dp(8), dp(6), dp(8), dp(6))
        clipChildren = false
        clipToPadding = false
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(context.getColor(R.color.projected_keyboard_background))
        }
        clipToOutline = true

        addView(createPreviewRow(), LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        keyboardContainer.apply {
            clipChildren = false
            clipToPadding = false
            addView(
                keyboardView,
                FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
            )
        }
        keyboardView.apply {
            setPreviewEnabled(false)
            setProximityCorrectionEnabled(false)
            setOnKeyboardActionListener(createKeyboardActionListener())
            onAccentLongPress = ::showAccentPopup
        }
        addView(
            keyboardContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        loadKeyboard(resolveKeyboardResource())
    }

    fun configure(newEditorInfo: EditorInfo) {
        hideAccentPopup()
        val newInputType = newEditorInfo.inputType
        val inputTypeChanged = newInputType != inputType
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
    }

    fun setInputSession(session: SystemInputSession) {
        hideAccentPopup()
        previewSession = session
        schedulePreviewRender()
    }

    fun refreshPreview() {
        schedulePreviewRender()
    }

    fun clearInputSession() {
        hideAccentPopup()
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
        return containsPointInWindow(x, y)
    }

    fun containsPointInBounds(x: Float, y: Float): Boolean {
        return containsPointInWindow(x, y)
    }

    fun containsPointInWindow(x: Float, y: Float): Boolean {
        val location = IntArray(2)
        getLocationInWindow(location)
        return Rect(
            location[0],
            location[1],
            location[0] + width,
            location[1] + height
        ).contains(x.toInt(), y.toInt())
    }

    fun hideAccentPopupIfVisible(): Boolean {
        if (accentPopup == null) return false
        hideAccentPopup()
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        onPointerEvent?.invoke(event.actionMasked)
        return super.dispatchTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        cancelDeleteRepeat()
        hideAccentPopup()
        super.onDetachedFromWindow()
    }

    private fun showAccentPopup(key: Keyboard.Key): Boolean {
        val popupCharacters = key.popupCharacters?.toString()?.takeIf { it.length > 1 }
            ?: return false
        hideAccentPopup()

        val options = popupCharacters.map { character ->
            character.toString().let {
                if (shiftEnabled || capsLockEnabled) it.uppercase(currentLanguage) else it
            }
        }
        val horizontalPadding = dp(8)
        val buttonWidth = if (keyboardContainer.width > 0) {
            ((keyboardContainer.width - horizontalPadding) / options.size)
                .coerceAtMost(dp(48))
                .coerceAtLeast(dp(24))
        } else {
            dp(48)
        }
        val popup = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = context.getDrawable(R.drawable.inline_keyboard_popup_background)
            elevation = dp(4).toFloat()
            isClickable = true
        }
        options.forEach { option ->
            val accentKey = TextView(context).apply {
                gravity = Gravity.CENTER
                text = option
                textSize = 20f
                setTextColor(context.getColor(R.color.projected_keyboard_text))
                contentDescription = "Accent $option"
                isClickable = true
                isFocusable = false
                background = context.getDrawable(R.drawable.inline_keyboard_key)
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    audioManager?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
                    listener.onText(option)
                    if (shiftEnabled && !capsLockEnabled) {
                        shiftEnabled = false
                        keyboardView.isShifted = false
                        keyboardView.invalidateAllKeys()
                    }
                    hideAccentPopup()
                }
            }
            popup.addView(accentKey, LinearLayout.LayoutParams(buttonWidth, dp(48)))
        }

        val popupWidth = buttonWidth * options.size + horizontalPadding
        val maxLeft = (keyboardContainer.width - popupWidth).coerceAtLeast(0)
        val keyCenterX = keyboardView.left + key.x + key.width / 2
        val left = (keyCenterX - popupWidth / 2).coerceIn(0, maxLeft)
        val top = (keyboardView.top + key.y - dp(56)).coerceAtLeast(0)
        keyboardContainer.addView(
            popup,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = left
                topMargin = top
            }
        )
        popup.bringToFront()
        accentPopup = popup
        return true
    }

    private fun hideAccentPopup() {
        accentPopup?.let(keyboardContainer::removeView)
        accentPopup = null
    }

    private fun createPreviewRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        previewText.apply {
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            setHorizontallyScrolling(true)
            setTextColor(context.getColor(R.color.projected_keyboard_text))
            setHintTextColor(context.getColor(R.color.projected_keyboard_secondary))
            setPadding(dp(10), 0, dp(10), 0)
            contentDescription = "Current field text"
            isClickable = true
            setOnClickListener { }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        hideAccentPopup()
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
                context.getColor(R.color.projected_keyboard_field),
                context.getColor(R.color.projected_keyboard_outline)
            )
            addView(
                previewText,
                ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            )
        }
        row.addView(
            previewScroller,
            LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(6)
            }
        )

        clearButton.apply {
            text = "\u00D7"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.projected_keyboard_secondary))
            contentDescription = "Clear field text"
            isClickable = true
            isFocusable = false
            setOnClickListener {
                hideAccentPopup()
                listener.onClearText()
            }
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
        listener.onLanguageChanged(currentLanguage)
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
            currentKeyboard?.let(::applyPopupCharacters)
            updateActionLabel()
            return
        }

        val keyboard = Keyboard(context, resourceId)
        keyboard.keys.forEach { key ->
            if (key.label?.isEmpty() == true) key.label = null
        }
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
            if (code !in 'a'.code..'z'.code) {
                key.popupCharacters = null
                return@forEach
            }
            val popupCharacters = KeyboardAccentOptions.forKey(code.toChar(), language)
            key.popupCharacters = popupCharacters
        }
    }

    private fun updateActionLabel() {
        val keyboard = currentKeyboard ?: return
        keyboard.keys.firstOrNull {
            it.codes.firstOrNull() == KEYCODE_DOMAIN
        }?.label = when {
            inputType and InputType.TYPE_MASK_VARIATION ==
                InputType.TYPE_TEXT_VARIATION_URI -> ".com"
            inputType and InputType.TYPE_MASK_VARIATION ==
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> "@"
            else -> "."
        }
        keyboardView.invalidateAllKeys()
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
                hideAccentPopup()
                if (primaryCode == KEYCODE_SPACER) return
                if (primaryCode == Keyboard.KEYCODE_DELETE) {
                    deleteKeyHeld = true
                    deleteRepeatTriggered = false
                    removeCallbacks(deleteRepeatRunnable)
                    postDelayed(deleteRepeatRunnable, DELETE_REPEAT_START_DELAY_MS)
                }
                keyboardView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            override fun onRelease(primaryCode: Int) {
                if (primaryCode == KEYCODE_SPACER) return
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
                    KEYCODE_SPACER -> Unit
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
                    KEYCODE_LANGUAGE -> cycleLanguage()
                    KEYCODE_DOMAIN -> listener.onText(
                        when {
                            inputType and InputType.TYPE_MASK_VARIATION ==
                                InputType.TYPE_TEXT_VARIATION_URI -> ".com"
                            inputType and InputType.TYPE_MASK_VARIATION ==
                                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> "@"
                            else -> "."
                        }
                    )
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
        const val KEYCODE_LANGUAGE = -104
        const val KEYCODE_SPACER = -105
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

internal class ProjectedKeyboardSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : KeyboardView(context, attrs) {
    var onAccentLongPress: ((Keyboard.Key) -> Boolean)? = null

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLongPress(popupKey: Keyboard.Key): Boolean =
        onAccentLongPress?.invoke(popupKey) ?: false
}
