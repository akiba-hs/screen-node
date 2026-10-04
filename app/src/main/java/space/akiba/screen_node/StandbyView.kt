package space.akiba.screen_node

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Заставка ожидания трансляции — анимация без единой надписи.
 *
 * Слои (снизу вверх):
 *  1. «цифровой дождь» из катаканы и hex-символов, отрисованный в пониженном разрешении;
 *  2. неоновая перспективная сетка-дорога, бегущая к зрителю;
 *  3. короткие сцены (см. [ScenePlayer], [SceneCatalog]) — изредка, по одной;
 *  4. логотип AKIBA: проявление из пикселей, пульсация свечения, периодический глитч
 *     (расслоение каналов RGB и сдвиг горизонтальных полос);
 *  5. оверлей экрана: строки развёртки, пробегающая светлая полоса, виньетка.
 *
 * Переходы (управляет MainActivity):
 *  - [hold] — неподвижный чёрный экран при запуске, пока неизвестно, идёт ли уже трансляция;
 *  - [intro] — появление: из дали надвигается дорога, следом включается логотип и начинается
 *    дождь. После трансляции (fromFrame) сначала [DARKEN_S] с темнеет последний кадр — не до
 *    конца, он остаётся едва заметен сквозь заставку;
 *  - [outro] — уход в трансляцию: фон растворяется в видео, логотип гаснет, дорога обрывается,
 *    дождь перестаёт рождать символы, и упавшие уходят за нижний край.
 *
 * Состояние проектора (см. [Mode]) передаётся без текста: скоростью дождя и сетки, частотой
 * глитча и серым логотипом, когда сервер остановил проектор.
 *
 * Тяжёлые ресурсы (логотип, свечение, пиксельные версии) рисуются в bitmap один раз при смене
 * размера; кадр сводится к их копированию. ~30 к/с; пока вид не показан, анимация стоит.
 */
class StandbyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Состояние проектора, передаваемое ритмом анимации. */
    enum class Mode {
        /** Нет связи с сервером — дождь медленный и тусклый. */
        OFFLINE,
        /** Сервер на связи, трансляции нет — обычный ритм. */
        WAITING,
        /** Идёт подключение к трансляции — всё быстрее, глитч чаще. */
        CONNECTING,
        /** Сервер остановил проектор (вытеснен или отказ в доступе) — серый мигающий логотип. */
        HALTED,
    }

    private enum class Phase { HOLD, INTRO, IDLE, OUTRO }

    var mode: Mode = Mode.OFFLINE
        set(value) {
            if (field == value) return
            field = value
            if (value == Mode.CONNECTING) nextGlitchAt = now() + 0.2f
            invalidate()
        }

    /** Заставка ещё не начиналась (чёрный экран ожидания решения). */
    val isHolding get() = phase == Phase.HOLD

    private val logo = AkibaLogo.load(context)
    private val random = Random(SystemClock.uptimeMillis())
    private val createdAt = SystemClock.uptimeMillis()
    private var lastFrame = 0f

    // Фазы и переходы; все моменты — секунды от создания вида.
    private var phase = Phase.HOLD
    private var phaseAt = 0f
    private var backgroundFrom = 1f          // непрозрачность фона в начале фазы
    private var restBackground = 1f          // непрозрачность фона в покое (после трансляции — не полная)
    private var introDarken = 0f             // длительность затемнения последнего кадра
    private var roadAt = 0f                  // начало надвигания дороги
    private var logoAt = Float.MAX_VALUE     // появление логотипа и начало дождя
    private var roadNearAtOutro = 1f         // докуда дошла дорога к началу ухода
    private var logoOffAt = -1f              // начало исчезновения логотипа (-1 — не исчезает)
    private var rainSpawning = false         // рождаются ли новые символы дождя
    private var outroDone: (() -> Unit)? = null

    private val stage = SceneStage()
    private val scenes = ScenePlayer(stage, random)

    // Ресурсы, подготовленные при смене размера.
    private var logoBmp: Bitmap? = null
    private var glowBmp: Bitmap? = null
    private var pixelBmps: List<Bitmap> = emptyList()
    private val logoDst = RectF()
    private val glowDst = RectF()
    private var rainBmp: Bitmap? = null
    private var rainCanvas: Canvas? = null
    private var rainColumns = FloatArray(0)
    private var rainSpeeds = FloatArray(0)
    private var scanlinePaint = Paint()
    private var vignettePaint = Paint()
    private var sweepPaint = Paint()
    private var sweepHeight = 0f

    // Краски.
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixelPaint = Paint() // без фильтрации: пиксели остаются крупными и чёткими
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val redChannel = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = channelFilter(r = 1f, g = 0f, b = 0f)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val cyanChannels = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = channelFilter(r = 0f, g = 1f, b = 1f)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val grayPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
    // Пересвеченный логотип для эффектов включения и выключения.
    private val overexposedPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 150f, 0f, 1f, 0f, 0f, 150f, 0f, 0f, 1f, 0f, 150f, 0f, 0f, 0f, 1f, 0f))
        )
    }
    private val whitePaint = Paint()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    // Шлейф дождя гаснет в прозрачность (DST_OUT), а не в чёрный: на чёрном фоне разницы нет,
    // а при переходах сквозь заставку видна трансляция.
    private val rainFade = Paint().apply {
        color = Color.argb(34, 0, 0, 0)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val rainGlyph = Paint().apply { typeface = Typeface.MONOSPACE; isAntiAlias = false }
    private val rainBlit = Paint() // масштабирование буфера дождя без сглаживания
    private val src = Rect()
    private val dst = RectF()

    // Глитч логотипа.
    private var nextGlitchAt = 2.5f
    private var glitchUntil = 0f

    private val tick = Runnable { invalidate() }

    // ---------- управление ----------

    /** Чёрный экран без анимации: ждём, идёт ли уже трансляция. */
    fun hold() {
        phase = Phase.HOLD
        phaseAt = now()
        resetScene()
        invalidate()
    }

    /**
     * Появление заставки. fromFrame — на экране остался последний кадр трансляции: он
     * [DARKEN_S] с темнеет (не до конца), затем надвигается дорога, потом логотип и дождь.
     */
    fun intro(fromFrame: Boolean) {
        val t = now()
        // Из HOLD при fromFrame вид был скрыт поверх идущей трансляции: затемнение начинается
        // с полностью видимого кадра.
        backgroundFrom = when {
            phase != Phase.HOLD -> currentBackground(t)
            fromFrame -> 0f
            else -> 1f
        }
        restBackground = if (fromFrame) FRAME_REST_BACKGROUND else 1f
        introDarken = if (fromFrame) DARKEN_S else 0f
        phase = Phase.INTRO
        phaseAt = t
        resetScene()
        roadAt = t + if (fromFrame) DARKEN_S else 0f
        logoAt = roadAt + ROAD_S
        invalidate()
    }

    /** Уход в трансляцию; onDone вызывается, когда всё растворилось и вид можно скрыть. */
    fun outro(onDone: () -> Unit) {
        val t = now()
        if (phase == Phase.OUTRO) {
            outroDone = onDone
            return
        }
        backgroundFrom = currentBackground(t)
        roadNearAtOutro = currentRoadNear(t)
        logoOffAt = if (logoVisible(t)) t else -1f
        phase = Phase.OUTRO
        phaseAt = t
        rainSpawning = false
        scenes.stop()
        outroDone = onDone
        invalidate()
    }

    /** Сервер остановил проектор: сразу серый логотип, без переходов. */
    fun halt() {
        mode = Mode.HALTED
        phase = Phase.IDLE
        phaseAt = now()
        restBackground = 1f
        logoAt = phaseAt
        scenes.stop()
        invalidate()
    }

    private fun resetScene() {
        rainSpawning = false
        logoOffAt = -1f
        logoAt = Float.MAX_VALUE
        outroDone = null
        scenes.stop()
        rainCanvas?.drawColor(0, PorterDuff.Mode.CLEAR)
        rainColumns.fill(Float.MAX_VALUE) // колонки пусты: дождь начнётся с верхнего края
    }

    // ---------- жизненный цикл вида ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recycle()
        if (w <= 0 || h <= 0) return
        prepareLogo(w, h)
        prepareRain(w, h)
        prepareOverlay(w, h)
        stage.width = w.toFloat()
        stage.height = h.toFloat()
        stage.horizon = h * HORIZON
        stage.logo.set(logoDst)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) invalidate() else removeCallbacks(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        recycle()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val t = now()
        val dt = (t - lastFrame).coerceIn(0f, 0.1f)
        lastFrame = t
        advance(t)

        val background = currentBackground(t)
        canvas.drawColor(Color.argb((255 * background).toInt(), 0, 0, 0), PorterDuff.Mode.SRC)
        if (phase != Phase.HOLD) {
            if (mode != Mode.HALTED) drawRain(canvas, dt, t)
            drawGrid(canvas, t, currentRoadNear(t), currentRoadFar(t))
            if (phase == Phase.IDLE && mode != Mode.HALTED) scenes.draw(canvas, t)
            drawLogo(canvas, t)
        }
        // В HOLD экран полностью чёрный и неподвижен; перерисовку запускают intro() и halt().
        if (phase == Phase.HOLD) return
        drawOverlay(canvas, t, background)

        if (isShown) {
            removeCallbacks(tick)
            postOnAnimationDelayed(tick, FRAME_MS)
        }
    }

    /** Смена фаз по времени. */
    private fun advance(t: Float) {
        when (phase) {
            Phase.INTRO -> {
                if (!rainSpawning && t >= logoAt) rainSpawning = true
                if (t >= logoAt + LOGO_REVEAL_S) {
                    phase = Phase.IDLE
                    phaseAt = t
                    scenes.schedule(t)
                }
            }
            Phase.OUTRO -> if (t - phaseAt >= OUTRO_S) {
                outroDone?.let { done ->
                    outroDone = null
                    post(done)
                }
            }
            else -> Unit
        }
    }

    private fun currentBackground(t: Float): Float = when (phase) {
        Phase.HOLD -> 1f
        Phase.INTRO -> if (introDarken > 0f) lerp(backgroundFrom, restBackground, easeInOut((t - phaseAt) / introDarken)) else restBackground
        Phase.IDLE -> restBackground
        Phase.OUTRO -> lerp(backgroundFrom, 0f, easeInOut((t - phaseAt) / BACKGROUND_FADE_S))
    }

    /** Докуда дошла дорога: 0 — горизонт, 1 — нижний край. */
    private fun currentRoadNear(t: Float): Float = when (phase) {
        Phase.HOLD -> 0f
        Phase.INTRO -> easeOut((t - roadAt) / ROAD_S)
        Phase.IDLE -> 1f
        Phase.OUTRO -> roadNearAtOutro
    }

    /** Откуда начинается дорога: при уходе её дальний край обрывается к зрителю. */
    private fun currentRoadFar(t: Float): Float =
        if (phase == Phase.OUTRO) easeInOut((t - phaseAt) / ROAD_END_S) else 0f

    private fun logoVisible(t: Float) = phase == Phase.IDLE || (phase == Phase.INTRO && t >= logoAt)

    // ---------- подготовка ----------

    private fun prepareLogo(w: Int, h: Int) {
        val lw = min(w * 0.62f, h * 0.36f * logo.width / logo.height)
        val lh = lw * logo.height / logo.width
        val cx = w / 2f
        val cy = h * 0.40f
        logoDst.set(cx - lw / 2, cy - lh / 2, cx + lw / 2, cy + lh / 2)

        val bw = lw.toInt().coerceAtLeast(1)
        val bh = lh.toInt().coerceAtLeast(1)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val full = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
            logo.draw(Canvas(it), RectF(0f, 0f, bw.toFloat(), bh.toFloat()), fill)
        }
        logoBmp = full

        // Свечение: силуэт логотипа, размытый фирменным красным. Размытие считается в уменьшенном
        // bitmap (в GLOW_SCALE раз по стороне) и растягивается с фильтрацией: результат тот же,
        // а подготовка в десятки раз быстрее.
        val pad = lh * 0.35f
        val gw = max(1, ((bw + pad * 2) * GLOW_SCALE).toInt())
        val gh = max(1, ((bh + pad * 2) * GLOW_SCALE).toInt())
        glowBmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888).also {
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(max(1f, pad * 0.45f * GLOW_SCALE), BlurMaskFilter.Blur.NORMAL)
            }
            val gp = pad * GLOW_SCALE
            logo.draw(Canvas(it), RectF(gp, gp, gp + bw * GLOW_SCALE, gp + bh * GLOW_SCALE), glow, AkibaLogo.RED)
        }
        glowDst.set(logoDst.left - pad, logoDst.top - pad, logoDst.right + pad, logoDst.bottom + pad)

        // Уровни пикселизации для проявления: от 1/64 ширины до 1/4.
        pixelBmps = PIXEL_LEVELS.map { f -> Bitmap.createScaledBitmap(full, max(2, bw / f), max(1, bh / f), true) }
    }

    private fun prepareRain(w: Int, h: Int) {
        val rw = max(1, w / RAIN_SCALE)
        val rh = max(1, h / RAIN_SCALE)
        rainBmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888).also { rainCanvas = Canvas(it) }
        val cols = rw / RAIN_STEP
        // Если дождь уже идёт (смена размера в покое) — сразу по всей высоте.
        rainColumns = FloatArray(cols) { if (rainSpawning) random.nextFloat() * rh else Float.MAX_VALUE }
        rainSpeeds = FloatArray(cols) { randomRainSpeed() }
        rainGlyph.textSize = RAIN_GLYPH.toFloat()
    }

    private fun prepareOverlay(w: Int, h: Int) {
        // Строки развёртки: повторяющийся узор 1×3 с одной тёмной строкой.
        val px = max(2, (h / 360f).toInt())
        val pattern = Bitmap.createBitmap(1, px * 3, Bitmap.Config.ARGB_8888)
        for (y in px * 2 until px * 3) pattern.setPixel(0, y, Color.argb(70, 0, 0, 0))
        scanlinePaint = Paint().apply { shader = BitmapShader(pattern, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        vignettePaint = Paint().apply {
            shader = RadialGradient(
                w / 2f, h / 2f, max(w, h) * 0.72f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(190, 0, 0, 0)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        }
        sweepHeight = h * 0.12f
        sweepPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, sweepHeight,
                intArrayOf(Color.TRANSPARENT, Color.argb(22, 255, 255, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
        }
    }

    // ---------- слои ----------

    private fun drawRain(canvas: Canvas, dt: Float, t: Float) {
        val bmp = rainBmp ?: return
        val c = rainCanvas ?: return
        val speedFactor = when (mode) {
            Mode.CONNECTING -> 2.5f
            Mode.OFFLINE -> 0.6f
            else -> 1f
        }
        // При уходе в трансляцию оставшиеся символы должны успеть упасть за край к OUTRO_S.
        val remaining = if (phase == Phase.OUTRO) max(0.1f, RAIN_END_S - (t - phaseAt)) else 0f
        // Шлейф: весь буфер немного гаснет, рисуются только «головы» колонок.
        c.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), rainFade)
        val bottom = bmp.height + RAIN_GLYPH
        for (i in rainColumns.indices) {
            var y = rainColumns[i]
            if (y > bottom) {
                if (!rainSpawning) continue
                y = -random.nextFloat() * bmp.height * 0.5f
                rainSpeeds[i] = randomRainSpeed()
            }
            var speed = rainSpeeds[i] * speedFactor
            if (remaining > 0f) speed = max(speed, (bottom - y) / remaining)
            y += speed * dt
            rainColumns[i] = y
            if (y > bottom) continue
            val head = random.nextFloat() < 0.12f
            rainGlyph.color = if (head) Color.argb(230, 255, 190, 200) else Color.argb(200, 188, 39, 58)
            val k = random.nextInt(RAIN_CHARS.length)
            c.drawText(RAIN_CHARS, k, k + 1, (i * RAIN_STEP).toFloat(), y, rainGlyph)
        }
        rainBlit.alpha = if (mode == Mode.OFFLINE) 70 else 110
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bmp, null, dst, rainBlit)
    }

    private fun randomRainSpeed() = 18f + random.nextFloat() * 42f

    /**
     * Сетка-дорога между far и near (доли глубины от горизонта); в покое far = 0, near = 1.
     * При появлении near растёт — дорога надвигается; при уходе far растёт — дорога обрывается.
     */
    private fun drawGrid(canvas: Canvas, t: Float, near: Float, far: Float) {
        if (near <= far) return
        val w = stage.width
        val h = stage.height
        val horizon = stage.horizon
        val depth = stage.depth
        val yFar = horizon + depth * far
        val yNear = horizon + depth * near
        val color = if (mode == Mode.HALTED) 0xFF3A0A12.toInt() else AkibaLogo.RED
        gridPaint.strokeWidth = max(1f, h / 540f)
        // Поперечные линии бегут к зрителю; квадратичная перспектива.
        val offset = (t * 0.45f * (if (mode == Mode.CONNECTING) 2f else 1f)) % 1f
        val lines = 12
        for (i in 0 until lines) {
            val k = (i + offset) / lines
            val y = horizon + depth * k * k
            if (y < yFar || y > yNear) continue
            gridPaint.color = withAlpha(color, (30 + 170 * k).toInt())
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        // Лучи из точки схода — только на видимом участке дороги (та же перспектива, что у сцен).
        gridPaint.color = withAlpha(color, 110)
        val sFar = sqrt(far)
        val sNear = sqrt(near)
        for (i in -SceneStage.ROAD_RAYS..SceneStage.ROAD_RAYS) {
            val lane = i.toFloat()
            canvas.drawLine(stage.roadX(lane, sFar), yFar, stage.roadX(lane, sNear), yNear, gridPaint)
        }
        // Горизонт (или край обрыва) — яркая линия.
        gridPaint.color = withAlpha(color, 200)
        canvas.drawLine(0f, yFar, w, yFar, gridPaint)
    }

    private fun drawLogo(canvas: Canvas, t: Float) {
        val full = logoBmp ?: return
        val glow = glowBmp ?: return

        if (phase == Phase.OUTRO) {
            if (logoOffAt >= 0f) drawLogoPowerOff(canvas, full, glow, t - logoOffAt)
            return
        }
        if (!logoVisible(t)) return

        if (mode == Mode.HALTED) {
            grayPaint.alpha = if (sin(t * PI * 0.8).toFloat() > -0.2f) 150 else 90
            canvas.drawBitmap(full, null, logoDst, grayPaint)
            return
        }

        val since = t - logoAt
        if (since < POWER_ON_S) {
            drawLogoPowerOn(canvas, since)
            return
        }

        // Пульсация свечения.
        val pulse = 0.5f + 0.5f * sin(t * 2 * PI / 3.2).toFloat()
        glowPaint.alpha = (70 + 120 * pulse).toInt()
        canvas.drawBitmap(glow, null, glowDst, glowPaint)

        // Проявление из пикселей: уровни от крупного к мелкому.
        val level = ((since - POWER_ON_S) / PIXEL_STEP_S).toInt()
        if (level < pixelBmps.size) {
            pixelPaint.alpha = (140 + 115 * (level + 1) / pixelBmps.size).coerceAtMost(255)
            canvas.drawBitmap(pixelBmps[level], null, logoDst, pixelPaint)
            return
        }

        // Периодический глитч.
        if (t >= nextGlitchAt) {
            glitchUntil = t + 0.18f + random.nextFloat() * 0.25f
            val every = if (mode == Mode.CONNECTING) 1.2f else 4.5f
            nextGlitchAt = t + every + random.nextFloat() * every
        }
        if (t < glitchUntil) {
            drawGlitch(canvas, full)
        } else {
            bmpPaint.alpha = 235 + random.nextInt(21) // лёгкое мерцание яркости
            canvas.drawBitmap(full, null, logoDst, bmpPaint)
        }
    }

    private fun drawGlitch(canvas: Canvas, full: Bitmap, strength: Float = 1f) {
        val shift = logoDst.width() * (0.006f + random.nextFloat() * 0.018f) * strength
        // Расслоение каналов: красный и голубой расходятся, при сложении дают исходные цвета.
        dst.set(logoDst); dst.offset(-shift, 0f)
        canvas.drawBitmap(full, null, dst, redChannel)
        dst.set(logoDst); dst.offset(shift, 0f)
        canvas.drawBitmap(full, null, dst, cyanChannels)
        // Сдвиг нескольких горизонтальных полос.
        val bands = 3 + random.nextInt(4)
        val scale = logoDst.width() / full.width
        repeat(bands) {
            val y0 = random.nextInt(full.height)
            val bh = max(2, (full.height * (0.03f + random.nextFloat() * 0.09f)).toInt())
            val y1 = min(full.height, y0 + bh)
            src.set(0, y0, full.width, y1)
            val dx = (random.nextFloat() - 0.5f) * logoDst.width() * 0.08f * strength
            dst.set(logoDst.left + dx, logoDst.top + y0 * scale, logoDst.right + dx, logoDst.top + y1 * scale)
            canvas.drawBitmap(full, src, dst, bmpPaint)
        }
    }

    /** Включение логотипа: яркая горизонтальная линия раскрывается по вертикали в логотип. */
    private fun drawLogoPowerOn(canvas: Canvas, since: Float) {
        val cx = logoDst.centerX()
        val cy = logoDst.centerY()
        val line = max(2f, logoDst.height() * 0.02f)
        val lineS = POWER_ON_S * 0.4f
        if (since < lineS) {
            val k = easeOut(since / lineS)
            whitePaint.color = Color.WHITE
            whitePaint.alpha = 255
            val hw = logoDst.width() / 2 * k
            canvas.drawRect(cx - hw, cy - line / 2, cx + hw, cy + line / 2, whitePaint)
            whitePaint.alpha = 60
            canvas.drawRect(cx - hw, cy - line * 3, cx + hw, cy + line * 3, whitePaint)
        } else {
            val k = easeOut((since - lineS) / (POWER_ON_S - lineS))
            val hh = max(line / 2, logoDst.height() / 2 * k)
            dst.set(logoDst.left, cy - hh, logoDst.right, cy + hh)
            overexposedPaint.alpha = 255
            canvas.drawBitmap(pixelBmps.firstOrNull() ?: return, null, dst, overexposedPaint)
        }
    }

    /**
     * Выключение логотипа за [LOGO_OFF_S]: сильный глитч, схлопывание в пересвеченную
     * горизонтальную линию, сжатие линии в точку и вспышка.
     */
    private fun drawLogoPowerOff(canvas: Canvas, full: Bitmap, glow: Bitmap, e: Float) {
        if (e >= LOGO_OFF_S) return
        val cx = logoDst.centerX()
        val cy = logoDst.centerY()
        val line = max(2f, logoDst.height() * 0.02f)
        glowPaint.alpha = (190 * clamp01(1 - e / 0.5f)).toInt()
        if (glowPaint.alpha > 0) canvas.drawBitmap(glow, null, glowDst, glowPaint)
        when {
            e < 0.3f -> drawGlitch(canvas, full, strength = 3f)
            e < 0.65f -> {
                val k = easeInOut((e - 0.3f) / 0.35f)
                val hh = max(line / 2, logoDst.height() / 2 * (1 - k))
                dst.set(logoDst.left, cy - hh, logoDst.right, cy + hh)
                overexposedPaint.alpha = 255
                canvas.drawBitmap(full, null, dst, overexposedPaint)
            }
            e < 0.88f -> {
                val k = easeInOut((e - 0.65f) / 0.23f)
                val hw = max(line, logoDst.width() / 2 * (1 - k))
                whitePaint.color = Color.WHITE
                whitePaint.alpha = 255
                canvas.drawRect(cx - hw, cy - line / 2, cx + hw, cy + line / 2, whitePaint)
                whitePaint.alpha = 70
                canvas.drawRect(cx - hw, cy - line * 3, cx + hw, cy + line * 3, whitePaint)
            }
            else -> {
                val k = (e - 0.88f) / (LOGO_OFF_S - 0.88f)
                whitePaint.color = Color.WHITE
                whitePaint.alpha = (255 * (1 - k)).toInt()
                canvas.drawCircle(cx, cy, line * (1.5f + 4 * k), whitePaint)
            }
        }
    }

    /** Оверлей поверх всех слоёв: пробегающая полоса, строки развёртки, виньетка. */
    private fun drawOverlay(canvas: Canvas, t: Float, background: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val a = (255 * background).toInt() // при уходе в трансляцию оверлей растворяется вместе с фоном
        if (a <= 0) return
        val y = ((t % 5.5f) / 5.5f) * (h + sweepHeight) - sweepHeight
        sweepPaint.alpha = a
        canvas.save()
        canvas.translate(0f, y)
        canvas.drawRect(0f, 0f, w, sweepHeight, sweepPaint)
        canvas.restore()
        scanlinePaint.alpha = a
        canvas.drawRect(0f, 0f, w, h, scanlinePaint)
        vignettePaint.alpha = a
        canvas.drawRect(0f, 0f, w, h, vignettePaint)
    }

    // ---------- утилиты ----------

    private fun now() = (SystemClock.uptimeMillis() - createdAt) / 1000f

    private fun recycle() {
        logoBmp?.recycle(); logoBmp = null
        glowBmp?.recycle(); glowBmp = null
        pixelBmps.forEach { it.recycle() }; pixelBmps = emptyList()
        rainBmp?.recycle(); rainBmp = null; rainCanvas = null
    }

    companion object {
        private const val HORIZON = 0.70f // линия горизонта сетки, доля высоты
        // Задержка следующего кадра: при 60 Гц кадр выпадает на каждый второй vsync (~30 к/с) —
        // плавно и посильно для слабых приставок. Ровно 33 мс попадали бы на третий vsync (20 к/с).
        private const val FRAME_MS = 25L
        private const val RAIN_SCALE = 4 // буфер дождя — 1/4 разрешения экрана
        private const val RAIN_GLYPH = 9 // высота символа в буфере дождя, px
        private const val RAIN_STEP = RAIN_GLYPH + 3 // шаг колонок дождя, px
        private const val RAIN_CHARS = "アキバハッカースペース0123456789ABCDEF<>/{}#$"
        private const val GLOW_SCALE = 0.25f // разрешение bitmap свечения относительно экрана
        private val PIXEL_LEVELS = listOf(64, 32, 16, 8, 4) // делители ширины уровней пикселизации

        // Переходы, секунды.
        private const val DARKEN_S = 4f                 // последний кадр темнеет в фон заставки
        private const val FRAME_REST_BACKGROUND = 0.95f // …но не до конца: кадр едва заметен
        private const val ROAD_S = 0.8f                 // дорога надвигается от горизонта до края
        private const val POWER_ON_S = 0.25f            // включение логотипа
        private const val PIXEL_STEP_S = 0.1f           // длительность одного уровня пикселизации
        private val LOGO_REVEAL_S = POWER_ON_S + PIXEL_LEVELS.size * PIXEL_STEP_S
        private const val BACKGROUND_FADE_S = 1.5f      // фон растворяется в трансляцию
        private const val LOGO_OFF_S = 1.0f             // логотип исчезает
        private const val ROAD_END_S = 1.7f             // дорога обрывается
        private const val RAIN_END_S = 1.8f             // последние символы дождя уходят за край
        private const val OUTRO_S = 2.0f                // уход закончен

        private fun withAlpha(color: Int, alpha: Int) =
            Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

        private fun channelFilter(r: Float, g: Float, b: Float) = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    r, 0f, 0f, 0f, 0f,
                    0f, g, 0f, 0f, 0f,
                    0f, 0f, b, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }
}
