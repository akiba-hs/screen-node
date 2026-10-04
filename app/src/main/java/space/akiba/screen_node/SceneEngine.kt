package space.akiba.screen_node

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Геометрия экрана, на котором проигрываются сцены заставки. */
internal class SceneStage {
    var width = 0f
    var height = 0f
    var horizon = 0f

    /** Границы логотипа: сцены, которым нельзя заходить под логотип, обходят этот прямоугольник. */
    val logo = RectF()

    val depth get() = height - horizon
    val centerX get() = width / 2

    /** Базовая единица размеров сцен: 1/160 высоты экрана (~6,75 px при 1080p). */
    val unit get() = height / 160f

    // Дорога в той же перспективе, что и сетка заставки: s = 0 — горизонт, s = 1 — нижний край.
    fun roadY(s: Float) = horizon + depth * s * s
    fun roadX(lane: Float, s: Float) = centerX + lane * (width / ROAD_RAYS) * (0.08f + 1.52f * s * s)
    fun roadScale(s: Float) = (0.08f + 1.52f * s * s) / 1.6f

    companion object {
        /** Число лучей сетки по каждую сторону от центра. */
        const val ROAD_RAYS = 18
    }
}

/** Пиксельный спрайт из строк; символы — ключи палитры, остальные — прозрачные. */
internal class PixelSprite(private val rows: List<String>, private val palette: Map<Char, Int> = SPRITE_PALETTE) {
    val width = rows.maxOf { it.length }
    val height = rows.size
    val bitmap: Bitmap by lazy(LazyThreadSafetyMode.NONE) {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            rows.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> palette[ch]?.let { setPixel(x, y, it) } } }
        }
    }

    fun recolor(colors: Map<Char, Int>) = PixelSprite(rows, palette + colors)
}

/** Отрисовка кадра сцены в момент t (секунды от начала вариации). */
internal typealias SceneRender = SceneCanvas.(Canvas, Float) -> Unit

/** Вариация сцены: название, длительность в секундах и отрисовка. */
internal class SceneVariant(val title: String, val duration: Float, val render: SceneRender)

/** Сцена заставки — набор вариаций одного сюжета. */
internal class Scene(val title: String, val variants: List<SceneVariant>)

/** Та же вариация, зеркально отражённая по горизонтали относительно центра экрана. */
internal fun SceneVariant.mirrored(title: String) = SceneVariant(title, duration) { c, t ->
    c.save()
    c.scale(-1f, 1f, stage.centerX, 0f)
    render(this, c, t)
    c.restore()
}

/** Та же вариация, отражённая по вертикали относительно центра логотипа. */
internal fun SceneVariant.flippedVertically(title: String) = SceneVariant(title, duration) { c, t ->
    c.save()
    c.scale(1f, -1f, 0f, stage.logo.centerY())
    render(this, c, t)
    c.restore()
}

/** Инструменты отрисовки сцен: геометрия, краски и детерминированные случайные числа. */
internal class SceneCanvas(val stage: SceneStage) {
    /** Зерно случайных чисел текущего проигрывания. */
    var seed = 1

    /** Длительность текущей вариации, с. */
    var duration = 0f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    val path = Path()
    private val pixelPaint = Paint() // без фильтрации: пиксели остаются чёткими при масштабировании
    private val smoothPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    /** Псевдослучайное число [0, 1) для i-го объекта: одинаково на всех кадрах одного проигрывания. */
    fun random(i: Int): Float {
        var x = (seed * 374761393 + i * 668265263).toLong() and 0xffffffffL
        x = (x xor (x shr 13)) * 1274126177L and 0xffffffffL
        return ((x xor (x shr 16)) and 0xffffffL).toFloat() / 0x1000000
    }

    /** Пиксельный спрайт с опорной точкой в центре нижнего края; scale — экранных px на пиксель. */
    fun sprite(c: Canvas, sprite: PixelSprite, centerX: Float, bottom: Float, scale: Float, flip: Boolean = false, alpha: Int = 255) =
        bitmap(c, sprite.bitmap, centerX, bottom, scale, flip, alpha)

    /** Bitmap с опорной точкой в центре нижнего края; smooth — фильтрация для нерастровой графики. */
    fun bitmap(
        c: Canvas, bmp: Bitmap, centerX: Float, bottom: Float, scale: Float,
        flip: Boolean = false, alpha: Int = 255, smooth: Boolean = false,
    ) {
        val paint = if (smooth) smoothPaint else pixelPaint
        val halfWidth = bmp.width * scale / 2
        dst.set(centerX - halfWidth, bottom - bmp.height * scale, centerX + halfWidth, bottom)
        paint.alpha = alpha.coerceIn(0, 255)
        if (flip) {
            c.save()
            c.scale(-1f, 1f, centerX, bottom)
            c.drawBitmap(bmp, null, dst, paint)
            c.restore()
        } else {
            c.drawBitmap(bmp, null, dst, paint)
        }
    }

    fun rect(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int, alpha: Int = 255) {
        fill.color = color
        fill.alpha = alpha.coerceIn(0, 255)
        c.drawRect(left, top, right, bottom, fill)
    }

    fun circle(c: Canvas, x: Float, y: Float, radius: Float, color: Int, alpha: Int = 255) {
        fill.color = color
        fill.alpha = alpha.coerceIn(0, 255)
        c.drawCircle(x, y, radius, fill)
    }

    fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float, alpha: Int = 255) {
        stroke.color = color
        stroke.alpha = alpha.coerceIn(0, 255)
        stroke.strokeWidth = width
        c.drawLine(x0, y0, x1, y1, stroke)
    }

    /** Искры: count квадратиков разлетаются из (x, y); age — доля времени жизни 0..1. */
    fun sparks(c: Canvas, x: Float, y: Float, age: Float, count: Int, spread: Float, salt: Int, color: Int) {
        if (age < 0f || age > 1f) return
        for (i in 0 until count) {
            val angle = random(salt + i) * 2 * PI.toFloat()
            val distance = spread * (0.3f + 0.7f * random(salt + 100 + i)) * easeOut(age)
            val size = stage.unit * (1.2f - age)
            val px = x + cos(angle) * distance
            val py = y + sin(angle) * distance
            rect(c, px - size, py - size, px + size, py + size, color, (255 * (1 - age)).toInt())
        }
    }
}

/**
 * Проигрыватель сцен заставки. Сцены запускаются по одной через случайный интервал
 * [MIN_GAP_S]..[MAX_GAP_S] либо по команде [play].
 *
 * Выбор не повторяет недавнее: сцена выбирается случайно среди всех, кроме последних
 * проигранных, затем так же выбирается её вариация (см. [RecentChoices]). История живёт,
 * пока жив проигрыватель, то есть до закрытия приложения, и не сбрасывается [stop].
 */
internal class ScenePlayer(
    stage: SceneStage,
    private val random: Random,
    private val scenes: List<Scene> = SceneCatalog.scenes,
) {
    private val canvas = SceneCanvas(stage)
    private val recentScenes = RecentChoices(recentCapacity(scenes.size))
    private val recentVariants = scenes.map { RecentChoices(recentCapacity(it.variants.size)) }
    private var active: SceneVariant? = null
    private var startedAt = 0f
    private var nextAt = Float.MAX_VALUE

    /** Планирует следующую случайную сцену через случайный интервал от момента t. */
    fun schedule(t: Float) {
        nextAt = t + MIN_GAP_S + random.nextFloat() * (MAX_GAP_S - MIN_GAP_S)
    }

    /** Останавливает текущую сцену и снимает расписание; история выбора сохраняется. */
    fun stop() {
        active = null
        nextAt = Float.MAX_VALUE
    }

    fun draw(c: Canvas, t: Float) {
        if (active == null && t >= nextAt) {
            val sceneIndex = recentScenes.pick(scenes.size, random)
            start(sceneIndex, recentVariants[sceneIndex].pick(scenes[sceneIndex].variants.size, random), t)
        }
        val variant = active ?: return
        val elapsed = t - startedAt
        if (elapsed > variant.duration) {
            active = null
            schedule(t)
            return
        }
        c.save()
        variant.render(canvas, c, elapsed)
        c.restore()
    }

    private fun start(sceneIndex: Int, variantIndex: Int, t: Float) {
        recentScenes.remember(sceneIndex)
        recentVariants[sceneIndex].remember(variantIndex)
        active = scenes[sceneIndex].variants[variantIndex]
        startedAt = t
        canvas.seed = random.nextInt(1, Int.MAX_VALUE)
        canvas.duration = active?.duration ?: 0f
        nextAt = Float.MAX_VALUE
    }

    private companion object {
        // Интервал между сценами: 58,3–141,7 с.
        const val MIN_GAP_S = 70f / 1.2f
        const val MAX_GAP_S = 170f / 1.2f
    }
}

/** Последние выбранные индексы; [pick] выбирает случайный индекс, исключая их. */
internal class RecentChoices(private val capacity: Int) {
    private val recent = ArrayDeque<Int>()

    fun pick(count: Int, random: Random): Int {
        val candidates = (0 until count).filter { it !in recent }
        return if (candidates.isEmpty()) random.nextInt(count) else candidates.random(random)
    }

    fun remember(index: Int) {
        recent.remove(index)
        recent.addLast(index)
        while (recent.size > capacity) recent.removeFirst()
    }
}

/**
 * Сколько последних выборов исключать из случайного выбора среди count вариантов: не больше
 * трёх и так, чтобы при трёх и более вариантах оставалось минимум два кандидата. Один вариант —
 * без истории, два — чередование.
 */
internal fun recentCapacity(count: Int) = when {
    count <= 1 -> 0
    count == 2 -> 1
    else -> min(3, count - 2)
}

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
internal fun clamp01(v: Float) = v.coerceIn(0f, 1f)
internal fun easeOut(t: Float) = 1 - (1 - clamp01(t)).pow(3)
internal fun easeInOut(t: Float): Float {
    val x = clamp01(t)
    return x * x * (3 - 2 * x)
}

/** Плавное появление и исчезновение: 0 на краях интервала [0, duration], 1 внутри. */
internal fun fadeInOut(t: Float, duration: Float, edge: Float = 0.4f) = min(clamp01(t / edge), clamp01((duration - t) / edge))

/** Треугольная волна 0..1..0 с периодом 2. */
internal fun triangleWave(x: Float) = 1 - abs((x % 2f + 2f) % 2f - 1f)
