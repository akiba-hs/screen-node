package space.akiba.remote

import android.view.KeyEvent

/**
 * Сопоставление команд веб-пульта с кодами клавиш.
 *
 * Linux-коды (KEY_*) уходят в «Hi keyboard» (см. [InputInjector]); в Android-коды их переводит
 * раскладка прошивки /vendor/usr/keylayout/Vendor_0001_Product_0001.kl. Коды кнопок пульта
 * совпадают с теми, что шлёт ИК-демон по /atv/etc/key.xml (пульт Wanbo X2 Max) — поэтому
 * нажатие на странице неотличимо от нажатия на настоящем пульте.
 */
object KeyMap {
    // Модификаторы (поле mods команды kbd): бит → левая клавиша-модификатор.
    const val MOD_SHIFT = 1
    const val MOD_CTRL = 2
    const val MOD_ALT = 4
    const val MOD_META = 8
    val MODIFIERS = linkedMapOf(MOD_SHIFT to 42, MOD_CTRL to 29, MOD_ALT to 56, MOD_META to 125)

    // Enter. KEY_ENTER (28) в раскладке Hi keyboard — DPAD_CENTER (это «ОК» пульта): в поле
    // ввода он не переводит строку и не отправляет форму. KEY_KPENTER (96) — NUMPAD_ENTER,
    // его приложения понимают как Enter.
    const val KEY_KPENTER = 96

    /** Кнопки пульта (поле key команды key). */
    val REMOTE = mapOf(
        "power" to 116,
        "menu" to 139,
        "settings" to 176,
        "mute" to 113,
        "up" to 103,
        "down" to 108,
        "left" to 105,
        "right" to 106,
        "ok" to 28,
        "back" to 158,
        "home" to 102,
        "vol_up" to 115,
        "vol_down" to 114,
        "focus_cw" to 265,  // KEY_FOCUSUP, «F+» на пульте
        "focus_ccw" to 266, // KEY_FOCUSDOWN, «F-»
        "autofocus" to 289, // KEY_AUTOFOCUS: автофокус, как после включения
    )

    /**
     * Клавиши клавиатуры компьютера (KeyboardEvent.code) → Linux-код.
     * «Home» намеренно не сопоставлен: в раскладке прошивки KEY_HOME — системная кнопка
     * «Домой», а не «в начало строки» (в поле ввода её обрабатывает IME, см. [IME_KEYS]).
     */
    val KEYBOARD: Map<String, Int> = buildMap {
        "QWERTYUIOP".forEachIndexed { i, c -> put("Key$c", 16 + i) }
        "ASDFGHJKL".forEachIndexed { i, c -> put("Key$c", 30 + i) }
        "ZXCVBNM".forEachIndexed { i, c -> put("Key$c", 44 + i) }
        for (d in 1..9) put("Digit$d", 1 + d)
        put("Digit0", 11)
        putAll(
            mapOf(
                "Escape" to 1, "Minus" to 12, "Equal" to 13, "Backspace" to 14, "Tab" to 15,
                "BracketLeft" to 26, "BracketRight" to 27, "Enter" to KEY_KPENTER, "Semicolon" to 39,
                "Quote" to 40, "Backquote" to 41, "Backslash" to 43, "Comma" to 51, "Period" to 52,
                "Slash" to 53, "Space" to 57, "IntlBackslash" to 86,
                "F1" to 59, "F2" to 60, "F3" to 61, "F4" to 62, "F5" to 63, "F6" to 64,
                "F7" to 65, "F8" to 66, "F9" to 67, "F10" to 68, "F11" to 87, "F12" to 88,
                "ArrowUp" to 103, "ArrowLeft" to 105, "ArrowRight" to 106, "ArrowDown" to 108,
                "PageUp" to 104, "PageDown" to 109, "End" to 107, "Insert" to 110, "Delete" to 111,
                "ContextMenu" to 127,
                "Numpad0" to 82, "Numpad1" to 79, "Numpad2" to 80, "Numpad3" to 81, "Numpad4" to 75,
                "Numpad5" to 76, "Numpad6" to 77, "Numpad7" to 71, "Numpad8" to 72, "Numpad9" to 73,
                "NumpadDecimal" to 83, "NumpadAdd" to 78, "NumpadSubtract" to 74,
                "NumpadMultiply" to 55, "NumpadDivide" to 98, "NumpadEnter" to KEY_KPENTER,
                "AudioVolumeMute" to 113, "AudioVolumeDown" to 114, "AudioVolumeUp" to 115,
                "MediaPlayPause" to 164, "MediaTrackNext" to 163, "MediaTrackPrevious" to 165,
                "MediaStop" to 166, "BrowserBack" to 158,
            )
        )
    }

    /**
     * Клавиши редактирования, которые при активном IME пульта идут через него, а не через
     * evdev: так они строго упорядочены с набранным текстом («аб», Backspace, «в» не
     * перемешаются). Значение — Android-код.
     */
    val IME_KEYS = mapOf(
        "Backspace" to KeyEvent.KEYCODE_DEL,
        "Delete" to KeyEvent.KEYCODE_FORWARD_DEL,
        "Enter" to KeyEvent.KEYCODE_ENTER,
        "NumpadEnter" to KeyEvent.KEYCODE_ENTER,
        "Tab" to KeyEvent.KEYCODE_TAB,
        "ArrowLeft" to KeyEvent.KEYCODE_DPAD_LEFT,
        "ArrowRight" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "ArrowUp" to KeyEvent.KEYCODE_DPAD_UP,
        "ArrowDown" to KeyEvent.KEYCODE_DPAD_DOWN,
        "Home" to KeyEvent.KEYCODE_MOVE_HOME,
        "End" to KeyEvent.KEYCODE_MOVE_END,
    )

    /** mods → метасостояние Android KeyEvent. */
    fun metaState(mods: Int): Int {
        var meta = 0
        if (mods and MOD_SHIFT != 0) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (mods and MOD_CTRL != 0) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (mods and MOD_ALT != 0) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (mods and MOD_META != 0) meta = meta or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        return meta
    }

    /** Символ ASCII → (Linux-код, нужен ли Shift) по раскладке US; null — символа на клавиатуре нет. */
    fun ascii(ch: Char): Pair<Int, Boolean>? {
        when (ch) {
            in 'a'..'z' -> return KEYBOARD.getValue("Key${ch.uppercaseChar()}") to false
            in 'A'..'Z' -> return KEYBOARD.getValue("Key$ch") to true
            in '0'..'9' -> return KEYBOARD.getValue("Digit$ch") to false
            ' ' -> return 57 to false
            '\n' -> return KEY_KPENTER to false
            '\t' -> return 15 to false
        }
        val plain = PUNCT.indexOf(ch)
        if (plain >= 0) return PUNCT_KEYS[plain] to false
        val shifted = PUNCT_SHIFTED.indexOf(ch)
        if (shifted >= 0) return PUNCT_KEYS[shifted] to true
        val digit = DIGITS_SHIFTED.indexOf(ch)
        if (digit >= 0) return KEYBOARD.getValue("Digit${(digit + 1) % 10}") to true
        return null
    }

    // Знаки препинания US-раскладки: без Shift / с Shift / их клавиши.
    private const val PUNCT = "-=[];'`\\,./"
    private const val PUNCT_SHIFTED = "_+{}:\"~|<>?"
    private val PUNCT_KEYS = intArrayOf(12, 13, 26, 27, 39, 40, 41, 43, 51, 52, 53)
    // Shift+1…Shift+0.
    private const val DIGITS_SHIFTED = "!@#$%^&*()"
}
