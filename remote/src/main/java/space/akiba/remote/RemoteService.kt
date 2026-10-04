package space.akiba.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import space.akiba.screen_node.ServerConfig
import space.akiba.screen_node.SignalingClient
import java.util.concurrent.Executors

/**
 * Служба веб-пульта: раздаёт страницу пульта и выполняет её команды на самом проекторе. Работает
 * всё время, пока включён проектор, в каком бы приложении или на каком входе он ни был:
 * запускается при загрузке ([BootReceiver]), после обновления, при старте экрана akiba.
 * adb для работы не нужен.
 *
 * Пульт работает напрямую с проектором: страницы пульта подключаются к встроенному серверу
 * службы ([LocalServer], порт [PULT_PORT]), сервер трансляций в пути команд не участвует.
 * Команды выполняются только по пропуску, подписанному сервером ([PultTicket]); пока проектор
 * забронирован, — только у страниц с ключом владельца брони (ключ сообщает сервер).
 *
 * С сервером трансляций служба держит отдельное соединение (/remote/ws, роль "agent") — для
 * того, что нужно серверу: открыть akiba под начатую трансляцию, сообщить бронь, подсказать
 * про обновление приложений ([Updater]); туда же уходит состояние проектора.
 *
 * Пульт — отдельное приложение без экранов (space.akiba.remote), а не часть akiba: по «Домой»
 * служба прошивки com.newlink.nlprovision принудительно останавливает (force-stop) все пакеты
 * с окнами в фоне, кроме своего белого списка. У пакета без окон фоновых задач не бывает, и
 * пульт переживает и «Домой», и закрытие akiba. Подробности — в PROJECTOR_CHANGES.md.
 *
 * Команды страниц пульта (проверяются здесь же, см. [valid]):
 *  - key — кнопка пульта Wanbo, kbd — клавиша клавиатуры компьютера, button/move/wheel — мышь:
 *    пишутся в устройства ввода ядра (см. [InputInjector]) и обрабатываются системой как
 *    настоящий пульт; удержание кнопок работает (повтор делает сама система);
 *  - text — текст: через клавиатуру пульта [RemoteIme] (любой язык), без неё — латиница клавишами;
 *  - ime — страница начала или закончила набор текста: включить или вернуть клавиатуру;
 *  - launch — источник (HDMI, AV, USB) или служебный экран, open — приложение с экраном
 *    запуска (по пакету, с запасными пакетами alt);
 *  - apps — список приложений с названиями на языке страницы и значками (ответ — только ей);
 *  - ping — проверка связи (ответ pong).
 * Страница закрылась — всё, что она держала, отпускается.
 *
 * Состояние (громкость, звук, клавиатура) уходит страницам пульта и серверу при изменениях.
 * Всё выполняется в главном потоке.
 */
class RemoteService : Service(), SignalingClient.Listener, LocalServer.Listener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var input: InputInjector
    private lateinit var ime: ImeSwitcher
    private lateinit var audio: AudioManager
    private lateinit var apps: AppCatalog
    // Сборка списка приложений (значки, ресурсы чужих пакетов) — не в главном потоке.
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var config: ServerConfig
    private lateinit var updater: Updater
    private var local: LocalServer? = null
    private var client: SignalingClient? = null
    private var online = false
    private var lastState = ""
    private var lastControllersState = ""

    // Страницы пульта: номер соединения → состояние.
    private val controllers = HashMap<Long, Controller>()
    // Ключ пульта владельца брони (от сервера); пусто — брони нет, пульт доступен всем.
    private var lockKey = ""
    private val onConfigChanged: () -> Unit = { reconnectServer() }
    private val periodicUpdate = object : Runnable {
        override fun run() {
            checkUpdates()
            main.postDelayed(this, UPDATE_EVERY_MS)
        }
    }

    // Зажатые клавиши и кнопки: что → кто держит и с какого момента.
    private val held = HashMap<Held, Hold>()

    // Пульты, которые сейчас набирают текст (им нужна клавиатура пульта).
    private val imeWanted = HashSet<Long>()
    // Клавиатура только что включена: до привязки к полю ввода (или до этого срока) текст и
    // клавиши редактирования копятся в pending, чтобы не потерять первые символы и порядок.
    private var imeWaitUntil = 0L
    private val pending = ArrayList<(Boolean) -> Unit>()

    private val imeOff = Runnable { if (imeWanted.isEmpty()) ime.restore() }
    private val flushPending = Runnable { flush() }
    private val sendStateSoon = Runnable { sendState() }
    private val watchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            held.entries.filter { now - it.value.since > HOLD_LIMIT_MS }.forEach {
                Log.w(TAG, "отпускаю ${it.key}: удерживается дольше ${HOLD_LIMIT_MS / 1000} с")
                release(it.key)
            }
            if (held.isNotEmpty()) main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val audioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = stateChanged()
    }
    private val imeObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = stateChanged()
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        input = InputInjector()
        ime = ImeSwitcher(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        apps = AppCatalog(this)
        // Клавиатура пульта могла остаться включённой после прошлого запуска — возвращаем прежнюю.
        ime.restore()
        RemoteIme.onEditorChanged = { if (pending.isNotEmpty() && readyIme() != null) flush() }

        val filter = IntentFilter().apply {
            addAction(ACTION_VOLUME_CHANGED)
            addAction(ACTION_MUTE_CHANGED)
        }
        ContextCompat.registerReceiver(this, audioReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, imeObserver,
        )

        config = ServerConfig(this, BuildConfig.SERVER, BuildConfig.PROJECTOR_TOKEN)
        ServerConfig.addListener(onConfigChanged)
        updater = Updater(this) { config.httpBase }
        // Служба подтверждения обновлений могла остаться включённой, если процесс завершила
        // установка самой службы пульта, — выключаем.
        UpdateConfirmService.disable(this)
        updater.onAkibaUpdated = { startSafely(Intent(Intent.ACTION_MAIN).setComponent(AKIBA)) }
        instance = this

        Log.i(TAG, "служба пульта запущена: сервер ${config.server}, клавиатура ввода: ${input.hasKeyboard}, " +
            "мышь: ${input.hasMouse}, смена IME: ${ime.canSwitch}, владелец устройства: ${updater.isDeviceOwner}")
        if (config.token.isEmpty()) Log.w(TAG, "токен проектора не задан: пульт не проверяет пропуски сервера")
        local = LocalServer(this, PULT_PORT, this).also { it.start() }
        connectServer()
        main.postDelayed(periodicUpdate, FIRST_UPDATE_MS)
    }

    private fun connectServer() {
        client = SignalingClient(config.agentUrl, config.token, { 0L }, this, role = "agent") { hello ->
            hello.put("port", PULT_PORT)
        }.also { it.start() }
    }

    /** Адрес сервера сменили через adb — переподключаемся. */
    private fun reconnectServer() {
        Log.i(TAG, "сервер изменён: ${config.server}")
        client?.stop()
        client = null
        online = false
        connectServer()
        lastControllersState = ""
        sendState()
    }

    /** Проверить обновления на сервере (подсказка сервера, таймер или adb). */
    fun checkUpdates() = updater.check()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        ServerConfig.removeListener(onConfigChanged)
        local?.stop()
        local = null
        client?.stop()
        client = null
        releaseAll()
        imeWanted.clear()
        ime.restore()
        RemoteIme.onEditorChanged = null
        main.removeCallbacksAndMessages(null)
        unregisterReceiver(audioReceiver)
        contentResolver.unregisterContentObserver(imeObserver)
        input.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------- Сервер трансляций ----------
    // Пульт от этого соединения не зависит: страницы пульта подключены к службе напрямую.

    override fun onConnected() {
        online = true
        lastState = ""
        sendState()
    }

    override fun onDisconnected(reason: String) {
        online = false
    }

    override fun onMessage(msg: JSONObject) {
        when (val type = msg.optString("type")) {
            "launch" -> when (msg.optString("target")) {
                "akiba" -> launch("akiba")
                // Трансляция уже идёт: экран akiba — сразу под неё, без анимаций.
                "akiba_stream" -> startSafely(
                    Intent(Intent.ACTION_MAIN).setComponent(AKIBA)
                        .putExtra(EXTRA_STREAM, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }
            "lock" -> setLock(msg.optString("key"))
            "update" -> checkUpdates()
            "forbidden" -> {
                // Неверный токен: переподключения ничего не изменят.
                Log.w(TAG, "сервер отказал службе пульта: ${msg.optString("text")} — проверьте токен")
                stopClient()
            }
            "replaced" -> {
                // Подключилась служба пульта другого проектора — не «перетягиваем» роль.
                Log.w(TAG, "сервер: подключилась другая служба пульта")
                stopClient()
            }
            else -> Log.d(TAG, "сервер: неизвестное сообщение $type")
        }
    }

    private fun stopClient() {
        client?.stop()
        client = null
        online = false
    }

    /** Бронь проектора сменилась: пульт других страниц перестаёт действовать сразу. */
    private fun setLock(key: String) {
        if (key == lockKey) return
        lockKey = key
        Log.i(TAG, if (key.isEmpty()) "бронь снята: пульт доступен всем" else "проектор забронирован: пульт — только у владельца брони")
        for (c in controllers.values) if (c.authed && !allowed(c)) forget(c.id)
        lastControllersState = ""
        sendState()
    }

    // ---------- Страницы пульта ----------

    private class Controller(val conn: LocalServer.Conn) {
        val id get() = conn.id
        var authed = false
        var key = ""
    }

    private fun allowed(c: Controller) = lockKey.isEmpty() || c.key == lockKey

    override fun onOpen(conn: LocalServer.Conn) {
        controllers[conn.id] = Controller(conn)
        // Без приветствия с пропуском соединение не держим.
        main.postDelayed({ controllers[conn.id]?.takeIf { !it.authed }?.conn?.close() }, HELLO_TIMEOUT_MS)
    }

    override fun onClose(conn: LocalServer.Conn) {
        if (controllers.remove(conn.id) == null) return
        forget(conn.id)
        sendState()
    }

    /** Отпустить всё, что держала страница, и её запрос клавиатуры. */
    private fun forget(id: Long) {
        releaseFrom(id)
        if (imeWanted.remove(id) && imeWanted.isEmpty()) scheduleImeOff()
    }

    override fun onMessage(conn: LocalServer.Conn, text: String) {
        val c = controllers[conn.id] ?: return
        val msg = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        val type = msg.optString("type")
        if (!c.authed) {
            if (type == "hello") hello(c, msg) else conn.close()
            return
        }
        if (type == "ping") {
            conn.send("""{"type":"pong"}""")
            return
        }
        // Список приложений не управляет проектором — его видно и при чужой брони.
        if (type != "apps" && !allowed(c)) return
        if (!valid(type, msg)) {
            conn.send(JSONObject().put("type", "error").put("text", "некорректная команда пульта").toString())
            return
        }
        val from = c.id
        when (type) {
            "key" -> remoteKey(from, msg.optString("key"), msg.optBoolean("down"))
            "kbd" -> keyboard(from, msg.optString("key"), msg.optBoolean("down"), msg.optInt("mods"))
            "text" -> typeText(msg.optString("text"))
            "move" -> input.move(msg.optInt("dx"), msg.optInt("dy"))
            // Горизонтальной прокрутки у «Hi mouse» нет (REL_HWHEEL не заявлен) — h не используется.
            "wheel" -> msg.optInt("v").takeIf { it != 0 }?.let { input.wheel(it) }
            "button" -> mouseButton(from, msg.optString("button"), msg.optBoolean("down"))
            "launch" -> launch(msg.optString("target"))
            "open" -> open(msg.optString("target"), msg.optJSONArray("alt"))
            "apps" -> sendApps(c, msg.optString("lang"))
            "ime" -> imeRequest(from, msg.optBoolean("on"))
            "volume" -> setVolume(msg.optInt("v"))
        }
    }

    /** Приветствие страницы: пропуск сервера (подпись токеном проектора) и его ключ пульта. */
    private fun hello(c: Controller, msg: JSONObject) {
        val token = config.token
        val key = if (token.isEmpty()) "" else PultTicket.verify(token, msg.optString("ticket"))
        if (key == null) {
            // За пропуском — на сервер: он выдаст новый и вернёт на эту страницу.
            c.conn.send(
                JSONObject().put("type", "forbidden")
                    .put("text", "Нужен пропуск пульта — откройте пульт со страницы трансляции.")
                    .put("login", config.httpBase + "remote").toString(),
            )
            c.conn.close()
            return
        }
        c.authed = true
        c.key = key
        c.conn.send(stateFor(c, statusJson()))
        sendState() // число страниц пульта — серверу
    }

    /** Проверка команды страницы пульта: известные значения и разумные пределы. */
    private fun valid(type: String, m: JSONObject): Boolean = when (type) {
        "key" -> m.optString("key") in KeyMap.REMOTE && m.has("down")
        "kbd" -> KBD_CODE.matches(m.optString("key")) && m.has("down") && m.optInt("mods") in 0..15
        "text" -> m.optString("text").let { it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_TEXT }
        "move" -> m.optInt("dx").let { dx -> m.optInt("dy").let { dy ->
            abs(dx) <= MAX_MOVE && abs(dy) <= MAX_MOVE && (dx != 0 || dy != 0) } }
        "wheel" -> abs(m.optInt("v")) <= MAX_WHEEL && abs(m.optInt("h")) <= MAX_WHEEL
        "button" -> m.optString("button") in setOf("left", "right", "middle") && m.has("down")
        "launch" -> m.optString("target") in LAUNCH_TARGETS
        "open" -> PACKAGE.matches(m.optString("target")) && (m.optJSONArray("alt")?.let { alt ->
            alt.length() <= MAX_ALT && (0 until alt.length()).all { PACKAGE.matches(alt.optString(it)) } } ?: true)
        "ime" -> m.has("on")
        "volume" -> m.optInt("v", -1) in 0..1000
        "apps" -> m.optString("lang").let { it.isEmpty() || LANG.matches(it) }
        else -> false
    }

    /** Громкость медиа с ползунка страницы пульта; больше нуля — заодно снять «без звука». */
    private fun setVolume(v: Int) {
        val stream = AudioManager.STREAM_MUSIC
        val value = v.coerceIn(0, audio.getStreamMaxVolume(stream))
        if (value > 0 && audio.isStreamMute(stream)) audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
        audio.setStreamVolume(stream, value, 0)
        stateChanged()
    }

    private fun abs(v: Int) = if (v < 0) -v else v

    private fun stateChanged() {
        main.removeCallbacks(sendStateSoon)
        main.postDelayed(sendStateSoon, STATE_DEBOUNCE_MS)
    }

    private fun statusJson(): JSONObject {
        val stream = AudioManager.STREAM_MUSIC
        return JSONObject()
            .put("agent", true)
            .put("volume", audio.getStreamVolume(stream))
            .put("max", audio.getStreamMaxVolume(stream))
            .put("muted", audio.isStreamMute(stream))
            .put("ime", ime.canSwitch)
            .put("imeActive", ime.isActive)
            .put("input", input.hasKeyboard)
    }

    /** Состояние для страницы пульта: забронирован ли проектор не ею и где страница трансляции. */
    private fun stateFor(c: Controller, status: JSONObject): String =
        JSONObject().put("type", "state").put(
            "status",
            JSONObject(status.toString()).put("locked", !allowed(c)).put("server", config.httpBase),
        ).toString()

    private fun sendState() {
        val status = statusJson()
        val pages = status.toString() + lockKey
        if (pages != lastControllersState) {
            lastControllersState = pages
            for (c in controllers.values) if (c.authed) c.conn.send(stateFor(c, status))
        }
        if (!online) return
        status.put("controllers", controllers.values.count { it.authed })
            .put("versions", updater.versions())
            .put("owner", updater.isDeviceOwner)
        val text = status.toString()
        if (text == lastState) return
        if (client?.send(JSONObject().put("type", "state").put("status", status)) == true) lastState = text
    }

    // ---------- Кнопки и клавиши ----------

    private fun remoteKey(from: Long, key: String, down: Boolean) {
        val code = KeyMap.REMOTE[key] ?: return
        if (input.hasKeyboard) {
            press(from, Held(Kind.KEY, code), down)
        } else if (down) {
            fallbackKey(key)
        }
    }

    /** Без устройства ввода: то, что можно сделать обычным API (громкость, «Домой», настройки). */
    private fun fallbackKey(key: String) {
        val stream = AudioManager.STREAM_MUSIC
        when (key) {
            "vol_up" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "vol_down" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            "home" -> startSafely(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            "settings" -> startSafely(Intent(Settings.ACTION_SETTINGS))
            else -> Log.w(TAG, "кнопка «$key» недоступна: нет устройства ввода")
        }
    }

    private fun keyboard(from: Long, key: String, down: Boolean, mods: Int) {
        val imeCode = KeyMap.IME_KEYS[key]
        // Клавиша, нажатая через evdev до включения клавиатуры пульта, отпускается там же.
        val evdevHeld = KeyMap.KEYBOARD[key]?.let { Held(Kind.KEY, it) in held } == true
        if (imeCode != null && !evdevHeld && (imeWaiting() || readyIme() != null)) {
            viaIme { useIme ->
                if (!useIme || readyIme()?.key(imeCode, down, KeyMap.metaState(mods)) != true) {
                    keyboardEvdev(from, key, down, mods)
                }
            }
            return
        }
        keyboardEvdev(from, key, down, mods)
    }

    private fun keyboardEvdev(from: Long, key: String, down: Boolean, mods: Int) {
        val code = KeyMap.KEYBOARD[key] ?: return
        if (down) {
            syncMods(from, mods)
            press(from, Held(Kind.KEY, code), true)
        } else {
            press(from, Held(Kind.KEY, code), false)
            // Модификаторы держим, пока у этого пульта зажата хоть одна обычная клавиша.
            val stillHeld = held.any { (h, hold) ->
                hold.from == from && h.kind == Kind.KEY && h.code !in KeyMap.MODIFIERS.values
            }
            if (!stillHeld) syncMods(from, 0)
        }
    }

    /** Нажимает и отпускает модификаторы так, чтобы их набор совпал с mods. */
    private fun syncMods(from: Long, mods: Int) {
        for ((bit, code) in KeyMap.MODIFIERS) {
            val want = mods and bit != 0
            val h = Held(Kind.KEY, code)
            if (want && h !in held) press(from, h, true)
            if (!want && held[h]?.from == from) press(from, h, false)
        }
    }

    private fun mouseButton(from: Long, button: String, down: Boolean) {
        val code = when (button) {
            "left" -> InputInjector.BTN_LEFT
            "right" -> InputInjector.BTN_RIGHT
            "middle" -> InputInjector.BTN_MIDDLE
            else -> return
        }
        press(from, Held(Kind.BUTTON, code), down)
    }

    /**
     * Нажатие с учётом удержания. Повторное «нажато» для уже зажатой клавиши не шлётся: повтор
     * при удержании система делает сама, иначе он шёл бы вдвое чаще.
     */
    private fun press(from: Long, h: Held, down: Boolean) {
        if (down) {
            if (h in held) return
            if (!inject(h, true)) return
            held[h] = Hold(from, SystemClock.elapsedRealtime())
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, WATCHDOG_MS)
        } else if (held.remove(h) != null) {
            inject(h, false)
        }
    }

    private fun inject(h: Held, down: Boolean) = when (h.kind) {
        Kind.KEY -> input.key(h.code, down)
        Kind.BUTTON -> input.button(h.code, down)
    }

    private fun release(h: Held) {
        if (held.remove(h) != null) inject(h, false)
    }

    private fun releaseFrom(from: Long) {
        held.filterValues { it.from == from }.keys.forEach(::release)
    }

    private fun releaseAll() {
        held.keys.toList().forEach(::release)
    }

    // ---------- Текст ----------

    private fun imeRequest(from: Long, on: Boolean) {
        if (on) {
            imeWanted += from
            main.removeCallbacks(imeOff)
            if (ime.enable()) imeWaitUntil = SystemClock.elapsedRealtime() + IME_BIND_WAIT_MS
        } else if (imeWanted.remove(from) && imeWanted.isEmpty()) {
            scheduleImeOff()
        }
    }

    // Выключение с задержкой: фокус на странице иногда на миг уходит и возвращается.
    private fun scheduleImeOff() {
        main.removeCallbacks(imeOff)
        main.postDelayed(imeOff, IME_OFF_DELAY_MS)
    }

    private fun typeText(text: String) {
        if (text.isEmpty()) return
        viaIme { useIme ->
            if (!useIme || readyIme()?.commit(text) != true) typeKeys(text)
        }
    }

    /** Запасной путь без клавиатуры пульта: символы US-раскладки клавишами, остальное теряется. */
    private fun typeKeys(text: String) {
        if (!input.hasKeyboard) return
        val shift = KeyMap.MODIFIERS.getValue(KeyMap.MOD_SHIFT)
        var lost = 0
        for (ch in text) {
            val k = KeyMap.ascii(ch)
            if (k == null) {
                lost++
                continue
            }
            val (code, withShift) = k
            if (withShift) input.key(shift, true)
            input.key(code, true)
            input.key(code, false)
            if (withShift) input.key(shift, false)
        }
        if (lost > 0) Log.w(TAG, "без клавиатуры пульта напечатать можно только латиницу: пропущено символов: $lost")
    }

    /** Клавиатура пульта включена и привязана к полю ввода. */
    private fun readyIme(): RemoteIme? = RemoteIme.instance?.takeIf { it.hasEditor && ime.isActive }

    private fun imeWaiting() = SystemClock.elapsedRealtime() < imeWaitUntil

    /**
     * Выполняет действие через клавиатуру пульта (true) или запасным путём (false). Пока клавиатура
     * только включается, действия копятся и выполняются по порядку, как только она привяжется
     * к полю ввода (или по истечении ожидания — запасным путём).
     */
    private fun viaIme(action: (Boolean) -> Unit) {
        if (pending.isEmpty() && !imeWaiting()) {
            action(readyIme() != null)
            return
        }
        pending += action
        if (readyIme() != null) {
            flush()
        } else {
            main.removeCallbacks(flushPending)
            main.postDelayed(flushPending, (imeWaitUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
    }

    private fun flush() {
        main.removeCallbacks(flushPending)
        imeWaitUntil = 0
        val useIme = readyIme() != null
        val actions = pending.toList()
        pending.clear()
        actions.forEach { it(useIme) }
    }

    // ---------- Приложения и входы ----------

    private fun launch(target: String) {
        val intent = when (target) {
            "akiba" -> Intent(Intent.ACTION_MAIN).setComponent(AKIBA)
            "android_settings" -> androidSettingsIntent()
            "hdmi1" -> sourceIntent(systemPropInt("zysys.source.hdmi1", 9))
            "hdmi2" -> sourceIntent(systemPropInt("zysys.source.hdmi2", 10))
            // AV — композитный вход (CVBS1), как в меню «Источник» прошивки.
            "av" -> sourceIntent(SOURCE_CVBS)
            // USB — файловый менеджер прошивки: его открывает пункт «USB» меню «Источник».
            "usb" -> appIntent("com.newlink.filemanager")
            else -> null
        }
        if (intent == null) {
            Log.w(TAG, "запуск «$target»: на проекторе нет подходящего приложения")
            return
        }
        startSafely(intent)
    }

    /** Открывает pkg, а если его нет — первый установленный из запасных alt. */
    private fun open(pkg: String, alt: JSONArray?) {
        val candidates = listOf(pkg) + (0 until (alt?.length() ?: 0)).map { alt!!.optString(it) }
        // Только приложения с экраном запуска — как в лаунчере, никаких произвольных экранов.
        val intent = candidates.firstNotNullOfOrNull { apps.intentFor(it) }
        if (intent == null) {
            Log.w(TAG, "открыть $candidates: нет такого приложения с экраном запуска")
            return
        }
        startSafely(intent)
    }

    private fun sendApps(c: Controller, lang: String) {
        worker.execute {
            val list = try {
                apps.toJson(lang)
            } catch (e: Exception) {
                Log.w(TAG, "не удалось собрать список приложений", e)
                return@execute
            }
            main.post { c.conn.send(JSONObject().put("type", "apps").put("apps", list).toString()) }
        }
    }

    /**
     * Стандартные настройки Android TV. ACTION_SETTINGS на проекторе открывает упрощённые
     * настройки производителя (com.zhiying.settings), поэтому экран задан явно.
     */
    private fun androidSettingsIntent(): Intent =
        STOCK_SETTINGS.asSequence()
            .map { Intent(Intent.ACTION_MAIN).setComponent(it) }
            .firstOrNull { it.resolveActivity(packageManager) != null }
            ?: Intent(Settings.ACTION_SETTINGS)

    private fun appIntent(pkg: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)

    /**
     * Вход так же, как его открывает меню «Источник» прошивки (com.zhiying.sourceui): экран
     * источника com.newlink.nlsource (или com.hisilicon.tvui) с номером источника SourceName
     * (HDMI — из свойств zysys.source.hdmiN, AV — [SOURCE_CVBS]).
     */
    private fun sourceIntent(source: Int): Intent? {
        return SOURCE_ACTIVITIES.asSequence()
            .map { Intent().setComponent(it).putExtra("SourceName", source).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION) }
            .firstOrNull { it.resolveActivity(packageManager) != null }
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "запуск: $intent")
        } catch (e: Exception) {
            Log.w(TAG, "не удалось запустить $intent", e)
        }
    }

    @Suppress("PrivateApi")
    private fun systemPropInt(key: String, def: Int): Int = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            .invoke(null, key, def) as Int
    } catch (e: Exception) {
        def
    }

    // ---------- Служба переднего плана ----------

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.remote_channel), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_remote)
            .setContentTitle(getString(R.string.remote_notification))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private enum class Kind { KEY, BUTTON }

    private data class Held(val kind: Kind, val code: Int)

    private data class Hold(val from: Long, val since: Long)

    companion object {
        private const val TAG = "ProjectorRemote"
        private const val CHANNEL = "remote"
        private const val NOTIFICATION_ID = 1
        // Скрытые, но стабильные широковещательные сообщения AudioManager.
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val ACTION_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
        private const val STATE_DEBOUNCE_MS = 100L
        private const val IME_BIND_WAIT_MS = 1_500L
        private const val IME_OFF_DELAY_MS = 2_000L
        // Защита от «залипания», если отпускание потерялось: дольше никто кнопку не держит.
        private const val HOLD_LIMIT_MS = 30_000L
        private const val WATCHDOG_MS = 5_000L

        // Порт страницы пульта на проекторе (сервер узнаёт его из hello и ведёт туда /remote).
        const val PULT_PORT = 8090
        private const val HELLO_TIMEOUT_MS = 10_000L
        // Обновления: первая проверка вскоре после старта, дальше — раз в 30 минут (и по подсказке сервера).
        private const val FIRST_UPDATE_MS = 60_000L
        private const val UPDATE_EVERY_MS = 30 * 60_000L
        // Пределы команд страниц пульта: защита от мусора и «залипания» огромными значениями.
        private const val MAX_TEXT = 256
        private const val MAX_MOVE = 4000
        private const val MAX_WHEEL = 50
        private const val MAX_ALT = 4
        private val KBD_CODE = Regex("^[A-Za-z0-9]{1,24}$")
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        private val LANG = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8}){0,3}$")
        private val LAUNCH_TARGETS = setOf("akiba", "hdmi1", "hdmi2", "av", "usb", "android_settings")
        // Номер композитного входа (CVBS1) у Hisilicon; так его открывает меню «Источник».
        private const val SOURCE_CVBS = 3

        // Экран проектора akiba — в основном приложении; флаг — «открыт под трансляцию».
        private val AKIBA = ComponentName("space.akiba.screen_node", "space.akiba.screen_node.MainActivity")
        private const val EXTRA_STREAM = "stream"

        /** Запущенная служба (для служебных команд из adb, см. [ShellReceiver]). */
        var instance: RemoteService? = null
            private set

        private val STOCK_SETTINGS = listOf(
            ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings"),
            ComponentName("com.android.settings", "com.android.settings.Settings"),
        )

        private val SOURCE_ACTIVITIES = listOf(
            ComponentName("com.newlink.nlsource", "com.newlink.nlsource.MainActivity"),
            ComponentName("com.hisilicon.tvui", "com.hisilicon.tvui.MainActivity"),
        )

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RemoteService::class.java))
            } catch (e: Exception) {
                // Новые Android запрещают запуск служб из фона; на проекторе (Android 9) этого нет.
                Log.w(TAG, "не удалось запустить службу пульта", e)
            }
        }
    }
}
