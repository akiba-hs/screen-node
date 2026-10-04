package space.akiba.screen_node

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import space.akiba.screen_node.StandbyView.Mode
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Единственный экран проектора: полноэкранное видео или анимированная заставка с логотипом.
 * На экран не выводится ни одной надписи; состояние видно по ритму заставки.
 * Взаимодействие с пользователем не требуется.
 *
 * Анимация — только когда трансляции нет. Если при запуске трансляция уже идёт, видео
 * показывается сразу: пока неизвестно, будет ли трансляция, экран чёрный ([StandbyView.hold]).
 * Служба пульта открывает экран под начатую трансляцию с флагом [EXTRA_STREAM] — тогда чёрный
 * экран держится, пока не придёт первый кадр (до [STREAM_WAIT_MS]), без анимации запуска окна
 * и без заставки; то же — когда сервер сообщает «incoming» (трансляция идёт, offer в пути).
 * Переходы заставка ⇄ трансляция — см. StandbyView.
 *
 * Пока Activity на переднем плане, экран не гаснет и устройство не засыпает: флаг окна
 * FLAG_KEEP_SCREEN_ON и wake lock (см. [holdWakeLock]).
 *
 * Адрес сервера и токен проектора — [ServerConfig]: по умолчанию из сборки, поменять можно
 * только через adb ([ConfigReceiver], install.sh). На экран они не выводятся.
 *
 * Параметры запуска (сохраняются и используются при следующих запусках):
 *   --es orientation landscape|auto    ориентация экрана (по умолчанию landscape)
 *   --ez low_latency true|false        нулевая задержка воспроизведения (по умолчанию true)
 *   --ez low_latency_decoder true|false режим низкой задержки аппаратного декодера (по умолчанию true)
 *   --ez debug_overlay true|false      часы телефона (мс) и статистика поверх видео — для замера задержки
 * Разовый (не сохраняется):
 *   --ez stream true                   открыть под уже идущую трансляцию (так запускает служба пульта)
 */
class MainActivity : Activity(), SignalingClient.Listener, WebRtcReceiver.Listener {

    private lateinit var eglBase: EglBase
    private lateinit var video: SurfaceViewRenderer
    private lateinit var standby: StandbyView
    private lateinit var debugView: TextView
    private var debugOverlay = false
    private var lastStats = ""
    // Обновляет часы оверлея каждый кадр дисплея (vsync).
    private val debugTick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            debugView.text = "t=${System.currentTimeMillis()}\n$lastStats"
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private lateinit var receiver: WebRtcReceiver
    private var signaling: SignalingClient? = null
    private lateinit var config: ServerConfig
    // Сменился адрес сервера (через adb) — переподключаемся по новому.
    private val onConfigChanged: () -> Unit = { if (signaling != null) restartSignaling() }
    // Экран открыт под уже идущую трансляцию: ждать первого кадра, а не начинать заставку.
    private var expectStream = false
    // Экран на виду (между onStart и onStop).
    private var started = false
    // Сервер остановил этот проектор (вытеснен другим или отказ в доступе) — не переподключаемся.
    private var halted = false
    private var serverOnline = false
    // На экране видео трансляции (заставка ушла или уходит).
    private var videoShown = false
    // Подряд неудачных медиасоединений — для нарастающей паузы перед restart.
    private var lostInRow = 0
    private val main = Handler(Looper.getMainLooper())
    // Запуск без ответа сервера: если за DECIDE_MS не пришли ни offer, ни idle, начинаем заставку.
    private val decide = Runnable { startWaiting() }
    private var lowLatency = true
    private var lowLatencyDecoder = true
    // Размер области вывода (px): сообщается серверу, чтобы отправитель видел пропорции проектора.
    private var displayWidth = 0
    private var displayHeight = 0
    // Лимит аппаратного H.264-декодера — отправитель не кодирует больше него.
    private val decoderLimits by lazy { DecoderLimits.forMime("video/avc") }
    // Держит экран включённым на прошивках, где флага окна недостаточно (см. holdWakeLock).
    private val wakeLock by lazy {
        @Suppress("DEPRECATION")
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Флаг действует, только пока окно на экране: в фоне устройство засыпает как обычно.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContentView(R.layout.activity_main)
        video = findViewById(R.id.video)
        standby = findViewById(R.id.standby)
        debugView = findViewById(R.id.debug)

        config = ServerConfig(this, BuildConfig.SERVER, BuildConfig.PROJECTOR_TOKEN)
        ServerConfig.addListener(onConfigChanged)
        applyLaunchSettings(intent)
        hideSystemBars()
        findViewById<View>(R.id.root).addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w > 0 && h > 0 && (w != displayWidth || h != displayHeight)) {
                displayWidth = w
                displayHeight = h
                Log.i(TAG, "область вывода ${w}x$h")
                sendDisplay()
            }
        }

        eglBase = EglBase.create()
        video.init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() = Unit

            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                Log.i(TAG, "разрешение видео ${width}x$height, поворот $rotation")
            }
        })
        applyFit(FIT_CONTAIN)
        // Масштабирование аппаратным скейлером поверхности вместо GL — дешевле для слабых приставок.
        video.setEnableHardwareScaler(true)

        receiver = WebRtcReceiver(this, eglBase, video, this, lowLatency, lowLatencyDecoder)
        debugView.visibility = if (debugOverlay) View.VISIBLE else View.GONE
        startRemote()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchSettings(intent)
        // Под трансляцию — без анимации перехода окна.
        if (expectStream) overridePendingTransition(0, 0)
        if (expectStream && started) {
            // Экран уже на виду (например, переподключается к серверу): ждём трансляцию дольше.
            expectStream = false
            if (!videoShown && standby.isHolding) {
                main.removeCallbacks(decide)
                main.postDelayed(decide, STREAM_WAIT_MS)
            }
        }
        // Повторный запуск (например, из install.sh) оживляет остановленный сервером проектор.
        if (halted) restartSignaling()
    }

    override fun onStart() {
        super.onStart()
        started = true
        // Пока неизвестно, идёт ли уже трансляция, — чёрный экран без анимации.
        videoShown = false
        standby.visibility = View.VISIBLE
        standby.hold()
        main.postDelayed(decide, if (expectStream) STREAM_WAIT_MS else DECIDE_MS)
        expectStream = false // разовый флаг: следующий показ экрана — обычный
        startSignaling()
        if (debugOverlay) Choreographer.getInstance().postFrameCallback(debugTick)
    }

    override fun onResume() {
        super.onResume()
        holdWakeLock(true)
    }

    override fun onPause() {
        holdWakeLock(false)
        super.onPause()
    }

    override fun onStop() {
        started = false
        // Проектор «виден» серверу, только пока приложение на экране.
        main.removeCallbacksAndMessages(null)
        stopSignaling()
        serverOnline = false
        receiver.close()
        video.clearImage()
        videoShown = false
        Choreographer.getInstance().removeFrameCallback(debugTick)
        super.onStop()
    }

    override fun onDestroy() {
        ServerConfig.removeListener(onConfigChanged)
        main.removeCallbacksAndMessages(null)
        receiver.dispose()
        video.release()
        eglBase.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------- Сигналинг ----------

    private fun startSignaling() {
        if (signaling != null) return
        halted = false
        // В hello передаётся текущая сессия: после короткого обрыва сигналинга сервер
        // продолжит её без переговоров, и видео не прервётся.
        signaling = SignalingClient(config.signalingUrl, config.token, { receiver.session }, this)
            .also { it.start() }
    }

    private fun stopSignaling() {
        signaling?.stop()
        signaling = null
    }

    private fun restartSignaling() {
        stopSignaling()
        receiver.close()
        standby.hold()
        videoShown = false
        main.postDelayed(decide, DECIDE_MS)
        startSignaling()
    }

    private fun send(msg: JSONObject) {
        signaling?.send(msg)
    }

    override fun onConnected() {
        serverOnline = true
        sendDisplay()
        updateMode()
    }

    private fun sendDisplay() {
        if (displayWidth <= 0 || displayHeight <= 0) return
        val display = JSONObject().put("width", displayWidth).put("height", displayHeight)
        decoderLimits?.let {
            display.put("maxDecodeLong", it.longSide).put("maxDecodeShort", it.shortSide)
        }
        send(JSONObject().put("type", "display").put("display", display))
    }

    override fun onDisconnected(reason: String) {
        // Медиа идёт напрямую от ноутбука: короткий обрыв сигналинга картинку не прерывает.
        // Если сервер не дождётся нас, после переподключения придёт idle или новый offer.
        serverOnline = false
        updateMode()
    }

    override fun onMessage(msg: JSONObject) {
        val type = msg.optString("type")
        val session = msg.optLong("session")
        when (type) {
            "offer" -> {
                // Трансляция есть — ждём первый кадр; если он так и не придёт, через
                // STREAM_WAIT_MS начнётся заставка (а не вечный чёрный экран).
                main.removeCallbacks(decide)
                if (!videoShown && standby.isHolding) main.postDelayed(decide, STREAM_WAIT_MS)
                applyFit(msg.optString("fit", FIT_CONTAIN))
                receiver.handleOffer(session, msg.optString("sdp"))
                updateMode()
            }
            "fit" -> if (session == receiver.session) applyFit(msg.optString("fit"))
            "candidate" -> msg.optJSONObject("candidate")?.let { receiver.addRemoteCandidate(session, it) }
            "stop" -> if (session == receiver.session) {
                receiver.close()
                leaveStream()
            }
            "idle" -> {
                receiver.close()
                leaveStream()
            }
            // Трансляция уже идёт, offer вот-вот придёт: держим чёрный экран, заставку не начинаем.
            "incoming" -> if (!videoShown && standby.isHolding) {
                main.removeCallbacks(decide)
                main.postDelayed(decide, STREAM_WAIT_MS)
            }
            "replaced" -> {
                // Другой экран стал проектором. Не переподключаемся, чтобы не «перетягивать» роль.
                Log.w(TAG, "сервер: подключился другой проектор")
                halt()
            }
            "forbidden" -> {
                // Неверный токен: переподключения ничего не изменят, только засорят лог сервера.
                Log.w(TAG, "сервер отказал в доступе: ${msg.optString("text")} — проверьте токен в сборке")
                halt()
            }
            "error" -> Log.w(TAG, "сервер: ${msg.optString("text")}")
            else -> Log.d(TAG, "неизвестное сообщение: $type")
        }
    }

    // ---------- WebRTC ----------

    override fun onAnswer(session: Long, sdp: String) {
        send(JSONObject().put("type", "answer").put("session", session).put("sdp", sdp))
    }

    override fun onLocalCandidate(session: Long, candidate: JSONObject) {
        send(JSONObject().put("type", "candidate").put("session", session).put("candidate", candidate))
    }

    override fun onConnected(session: Long) {
        Log.i(TAG, "сессия $session: медиасоединение установлено")
        lostInRow = 0
    }

    override fun onFirstFrame(session: Long) {
        Log.i(TAG, "сессия $session: первый кадр на экране")
        enterStream()
    }

    override fun onStats(summary: String) {
        lastStats = summary
    }

    override fun onConnectionLost(session: Long) {
        receiver.close()
        leaveStream()
        // Сервер начнёт новую сессию; устаревший номер он просто проигнорирует. Пауза растёт
        // при повторных сбоях, чтобы при устойчивой проблеме не гонять переговоры по кругу.
        lostInRow++
        val delay = minOf(10_000L, 1000L shl (lostInRow - 1).coerceAtMost(4))
        main.postDelayed({ send(JSONObject().put("type", "restart").put("session", session)) }, delay)
    }

    // ---------- UI ----------

    /**
     * Вписывание картинки в экран. Оба режима масштабируют равномерно — пропорции и
     * геометрия исходника сохраняются всегда (круг остаётся кругом):
     *  - contain: картинка целиком, по краям чёрные поля (view по размеру видео, по центру);
     *  - cover: экран заполнен, выступающие за его пропорции края картинки обрезаются.
     * Режима «растянуть» нет намеренно.
     */
    private fun applyFit(fit: String) {
        val cover = fit == FIT_COVER
        val size = if (cover) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        video.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        video.setScalingType(
            if (cover) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT
        )
        Log.i(TAG, "режим вписывания: ${if (cover) FIT_COVER else FIT_CONTAIN}")
    }

    /** Пришёл первый кадр трансляции. */
    private fun enterStream() {
        main.removeCallbacks(decide)
        if (videoShown) return
        videoShown = true
        if (standby.isHolding) {
            // Трансляция уже шла, когда приложение запустилось, — сразу видео, без анимаций.
            standby.visibility = View.GONE
            return
        }
        // Заставка растворяется в трансляцию; затем вид скрывается (анимация стоит).
        standby.outro { if (videoShown) standby.visibility = View.GONE }
    }

    /** Трансляция закончилась или оборвалась. */
    private fun leaveStream() {
        if (halted) return
        if (!videoShown) {
            startWaiting()
            return
        }
        videoShown = false
        // Последний кадр остаётся на экране и медленно темнеет в заставку (не до конца).
        standby.visibility = View.VISIBLE
        standby.intro(fromFrame = true)
        updateMode()
    }

    /** Трансляции нет: если заставка ещё «держит» чёрный экран — начинаем её. */
    private fun startWaiting() {
        main.removeCallbacks(decide)
        if (halted || videoShown) return
        standby.visibility = View.VISIBLE
        if (standby.isHolding) standby.intro(fromFrame = false)
        updateMode()
    }

    private fun updateMode() {
        if (halted) return
        standby.mode = when {
            !serverOnline -> Mode.OFFLINE
            receiver.session != 0L -> Mode.CONNECTING
            else -> Mode.WAITING
        }
    }

    private fun halt() {
        halted = true
        main.removeCallbacks(decide)
        stopSignaling()
        serverOnline = false
        receiver.close()
        video.clearImage()
        videoShown = false
        standby.visibility = View.VISIBLE
        standby.halt()
    }

    /**
     * Wake lock яркого экрана, пока приложение на переднем плане. Дополняет флаг окна
     * FLAG_KEEP_SCREEN_ON: его учитывают прошивки проекторов и ТВ-приставок с собственным
     * таймером сна.
     */
    @SuppressLint("WakelockTimeout") // освобождается в onPause
    private fun holdWakeLock(on: Boolean) {
        if (on && !wakeLock.isHeld) wakeLock.acquire()
        if (!on && wakeLock.isHeld) wakeLock.release()
    }

    /**
     * Будит службу веб-пульта — отдельное приложение space.akiba.remote, которое работает
     * независимо от этого экрана. Обычно она уже запущена (при загрузке); здесь — на случай,
     * если её остановили вручную. Нет приложения пульта — ничего страшного.
     */
    private fun startRemote() {
        try {
            val intent = Intent().setClassName(REMOTE_PACKAGE, "$REMOTE_PACKAGE.RemoteService")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "служба веб-пульта не запущена: ${e.message}")
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------- Настройки запуска ----------

    private fun applyLaunchSettings(intent: Intent?) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        intent?.getStringExtra(EXTRA_ORIENTATION)?.let { edit.putString(KEY_ORIENTATION, it) }
        if (intent?.hasExtra(EXTRA_DEBUG_OVERLAY) == true) {
            edit.putBoolean(KEY_DEBUG_OVERLAY, intent.getBooleanExtra(EXTRA_DEBUG_OVERLAY, false))
        }
        if (intent?.hasExtra(EXTRA_LOW_LATENCY_DECODER) == true) {
            edit.putBoolean(KEY_LOW_LATENCY_DECODER, intent.getBooleanExtra(EXTRA_LOW_LATENCY_DECODER, true))
        }
        if (intent?.hasExtra(EXTRA_LOW_LATENCY) == true) {
            edit.putBoolean(KEY_LOW_LATENCY, intent.getBooleanExtra(EXTRA_LOW_LATENCY, true))
        }
        edit.apply()

        // Применяется при создании Activity (библиотека WebRTC инициализируется один раз).
        lowLatency = prefs.getBoolean(KEY_LOW_LATENCY, true)
        lowLatencyDecoder = prefs.getBoolean(KEY_LOW_LATENCY_DECODER, true)
        debugOverlay = prefs.getBoolean(KEY_DEBUG_OVERLAY, false)
        requestedOrientation = when (prefs.getString(KEY_ORIENTATION, "landscape")) {
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        expectStream = intent?.getBooleanExtra(EXTRA_STREAM, false) == true
        Log.i(TAG, "сервер: ${config.server}${if (expectStream) ", открыт под трансляцию" else ""}")
    }

    companion object {
        private const val TAG = "Projector"
        private const val REMOTE_PACKAGE = "space.akiba.remote"
        private const val WAKE_LOCK_TAG = "akiba:standby"
        private const val DECIDE_MS = 800L
        // Открыт под трансляцию: сколько ждать первого кадра на чёрном экране до заставки.
        private const val STREAM_WAIT_MS = 12_000L
        const val EXTRA_STREAM = "stream"
        private const val FIT_CONTAIN = "contain"
        private const val FIT_COVER = "cover"
        private const val PREFS = "settings"
        private const val KEY_ORIENTATION = "orientation"
        private const val EXTRA_ORIENTATION = "orientation"
        private const val KEY_LOW_LATENCY = "low_latency"
        private const val EXTRA_LOW_LATENCY = "low_latency"
        private const val KEY_LOW_LATENCY_DECODER = "low_latency_decoder"
        private const val EXTRA_LOW_LATENCY_DECODER = "low_latency_decoder"
        private const val KEY_DEBUG_OVERLAY = "debug_overlay"
        private const val EXTRA_DEBUG_OVERLAY = "debug_overlay"
    }
}
