package space.akiba.remote

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Невидимая клавиатура (IME) веб-пульта: печатает текст с телефона или ноутбука в поле ввода
 * любого приложения проектора — на любом языке, с эмодзи. Своей экранной клавиатуры у неё нет.
 *
 * Включается только на время набора текста на странице пульта и потом возвращает прежнюю
 * клавиатуру системы (см. [ImeSwitcher]). Команды получает от [RemoteService] в том же процессе.
 */
class RemoteIme : InputMethodService() {
    // Сейчас в фокусе поле ввода (а не просто окно приложения).
    var hasEditor = false
        private set
    private val downTimes = HashMap<Int, Long>()
    private val repeats = HashMap<Int, Int>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Клавиатура могла остаться включённой после гибели процесса — служба пульта вернёт прежнюю.
        RemoteService.start(this)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onEvaluateInputViewShown() = false

    override fun onEvaluateFullscreenMode() = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        hasEditor = attribute != null && attribute.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_NULL
        Log.i(TAG, "IME: ввод в ${attribute?.packageName}, поле ввода: $hasEditor")
        onEditorChanged?.invoke()
    }

    override fun onFinishInput() {
        hasEditor = false
        super.onFinishInput()
    }

    /** Вставить текст в поле ввода; false — поля нет. */
    fun commit(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        if (!hasEditor) return false
        ic.commitText(text, 1)
        return true
    }

    /**
     * Клавиша редактирования через поле ввода.
     *
     * Backspace, Delete и Enter без модификаторов выполняются операциями самого поля
     * (удалить символ, «действие» поля): они идут строго по порядку с набранным текстом.
     * Нажатия клавиш приложения обрабатывают отдельно от текста — Chrome, например, применяет
     * Backspace уже после следующей вставки, и «abc», Backspace, «d» превращается в «abc».
     */
    fun key(code: Int, down: Boolean, meta: Int): Boolean {
        val ic = currentInputConnection ?: return false
        if (!hasEditor) return false
        if (meta == 0 && code in EDIT_KEYS) {
            if (down) edit(ic, code)
            return true
        }
        val now = SystemClock.uptimeMillis()
        if (down) {
            val repeat = if (code in downTimes) (repeats[code] ?: 0) + 1 else 0
            if (repeat == 0) downTimes[code] = now
            repeats[code] = repeat
            ic.sendKeyEvent(event(code, KeyEvent.ACTION_DOWN, now, repeat, meta))
        } else {
            repeats.remove(code)
            ic.sendKeyEvent(event(code, KeyEvent.ACTION_UP, now, 0, meta))
        }
        return true
    }

    private fun edit(ic: InputConnection, code: Int) {
        when (code) {
            KeyEvent.KEYCODE_DEL -> {
                // Выделение удаляется целиком, иначе — один символ (эмодзи — тоже один).
                if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1)
                else ic.deleteSurroundingTextInCodePoints(1, 0)
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1)
                else ic.deleteSurroundingTextInCodePoints(0, 1)
            }
            KeyEvent.KEYCODE_ENTER -> {
                val info = currentInputEditorInfo
                val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
                val hasAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 &&
                    action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
                when {
                    hasAction -> ic.performEditorAction(action) // поиск, «перейти», отправить
                    info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 -> ic.commitText("\n", 1)
                    else -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }
            }
        }
    }

    private fun event(code: Int, action: Int, now: Long, repeat: Int, meta: Int): KeyEvent {
        val downTime = (if (action == KeyEvent.ACTION_UP) downTimes.remove(code) else downTimes[code]) ?: now
        return KeyEvent(
            downTime, now, action, code, repeat, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
        )
    }

    companion object {
        private const val TAG = "ProjectorRemote"
        private val EDIT_KEYS = setOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ENTER)

        /** Запущенная клавиатура (только главный поток). */
        var instance: RemoteIme? = null
            private set

        /** Поле ввода сменилось — служба пульта досылает текст, ждавший включения клавиатуры. */
        var onEditorChanged: (() -> Unit)? = null
    }
}

/**
 * Переключение системной клавиатуры на [RemoteIme] и обратно. Нужно разрешение
 * WRITE_SECURE_SETTINGS — его один раз выдаёт `install.sh` (`pm grant`), оно переживает
 * перезагрузки и обновления приложения. Без него печать идёт запасным путём (латиница
 * клавишами, см. [RemoteService]).
 *
 * Прежняя клавиатура запоминается в настройках приложения: если процесс погибнет при
 * включённой клавиатуре пульта, при следующем старте службы она будет восстановлена.
 */
class ImeSwitcher(private val context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val id: String = ComponentName(context, RemoteIme::class.java).flattenToShortString()

    val canSwitch: Boolean
        get() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    val isActive: Boolean
        get() = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD) == id

    /** Включить клавиатуру пульта; true — переключение действительно произошло (нужно подождать привязки). */
    fun enable(): Boolean {
        if (!canSwitch || isActive) return false
        return try {
            val current = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
            prefs.edit().putString(KEY_PREVIOUS, current).apply()
            val enabled = enabledList()
            if (enabled.none { it.substringBefore(';') == id }) putEnabled(enabled + id)
            Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, id)
            Log.i(TAG, "IME: включена клавиатура пульта (прежняя: $current)")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "IME: нет права менять клавиатуру", e)
            false
        }
    }

    /** Вернуть прежнюю клавиатуру системы, если сейчас включена клавиатура пульта. */
    fun restore() {
        if (!canSwitch) return
        try {
            val previous = prefs.getString(KEY_PREVIOUS, null)
            if (isActive) {
                // Прежняя неизвестна — первая другая включённая клавиатура.
                val back = previous?.takeIf { it.isNotEmpty() && it != id }
                    ?: enabledList().map { it.substringBefore(';') }.firstOrNull { it != id }
                    ?: ""
                Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, back)
                Log.i(TAG, "IME: возвращена клавиатура $back")
            }
            val enabled = enabledList()
            if (enabled.any { it.substringBefore(';') == id }) putEnabled(enabled.filter { it.substringBefore(';') != id })
            prefs.edit().remove(KEY_PREVIOUS).apply()
        } catch (e: SecurityException) {
            Log.w(TAG, "IME: нет права менять клавиатуру", e)
        }
    }

    // Формат ENABLED_INPUT_METHODS: "id[;подтип…]:id2…".
    private fun enabledList(): List<String> =
        Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS).orEmpty()
            .split(':').filter { it.isNotEmpty() }

    private fun putEnabled(list: List<String>) {
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_INPUT_METHODS, list.joinToString(":"))
    }

    companion object {
        private const val TAG = "ProjectorRemote"
        private const val PREFS = "remote"
        private const val KEY_PREVIOUS = "previous_ime"
    }
}
