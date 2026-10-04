package space.akiba.screen_node

import android.graphics.Canvas
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Каталог сцен заставки: заставка проигрывает их сама, в случайном порядке (см. ScenePlayer).
 */
internal object SceneCatalog {
    val scenes: List<Scene> = listOf(
        Scene("Динозаврик и птеродактиль", run {
            val jump = SceneVariant("Прыжок, слева направо", 7f, dinoAndPterodactyl(duck = false))
            val duck = SceneVariant("Пригибается, слева направо", 7f, dinoAndPterodactyl(duck = true))
            listOf(jump, jump.mirrored("Прыжок, справа налево"), duck, duck.mirrored("Пригибается, справа налево"))
        }),
        Scene("Динозаврик перепрыгивает кактус на дороге", run {
            val right = SceneVariant("Справа", 6f, dinoJumpsCactus)
            listOf(right, right.mirrored("Слева"))
        }),
        Scene("Пакман и призраки", run {
            val leftToRight = SceneVariant("Слева направо", 8f, pacman)
            listOf(leftToRight, leftToRight.mirrored("Справа налево"))
        }),
        Scene("Космические захватчики", listOf(SceneVariant("Основная", 8f, spaceInvaders))),
        Scene("Летающая тарелка похищает корову", run {
            val right = SceneVariant("Справа", 7f, saucerAbduction)
            listOf(right, right.mirrored("Слева"))
        }),
        Scene("Астероиды", run {
            val leftTop = SceneVariant("Слева вверху", 7f, asteroids)
            val leftBottom = leftTop.flippedVertically("Слева внизу")
            listOf(leftTop, leftTop.mirrored("Справа вверху"), leftBottom, leftBottom.mirrored("Справа внизу"))
        }),
        Scene("Понг", List(PONG_MATCHES) { i ->
            val match = pongMatch(i)
            SceneVariant("Матч ${i + 1}", match.playSeconds + PONG_TAIL_S, pong(match))
        }),
        Scene("Змейка", List(SNAKE_LAYOUTS) { i -> SceneVariant("Расклад ${i + 1}", 8f, snake(i)) }),
        Scene("Утиная охота", run {
            val left = SceneVariant("Слева", 7f, duckHunt)
            listOf(left, left.mirrored("Справа"))
        }),
        Scene("Код Konami", listOf(SceneVariant("Основная", 8f, konamiCode))),
        Scene("Огненный шар «хадукен»", run {
            val leftToRight = SceneVariant("Слева направо", 5f, fireball)
            listOf(leftToRight, leftToRight.mirrored("Справа налево"))
        }),
        Scene("Золотые кольца", listOf(SceneVariant("Основная", 6f, goldenRings))),
        Scene("Спорткар OutRun", listOf(SceneVariant("Основная", 7f, sportsCar))),
        Scene("Сердечки здоровья", listOf(
            SceneVariant("Минус одно сердечко", 6f, healthHearts(lost = 1)),
            SceneVariant("Минус два сердечка", 6f, healthHearts(lost = 2)),
        )),
        Scene("Дискета", run {
            val rightToLeft = SceneVariant("Справа налево", 6f, floppyDisk)
            listOf(rightToLeft, rightToLeft.mirrored("Слева направо"))
        }),
        Scene("Галага: рой пришельцев", listOf(
            SceneVariant("Рой влетает петлёй", 8f, galagaLoop),
            SceneVariant("Пикирование на истребитель", 8f, galagaDive),
            SceneVariant("Луч захвата флагмана", 7.5f, galagaTractorBeam),
            SceneVariant("Двойная спираль", 8f, galagaSpiral),
        )),
        Scene("Сундук с сокровищем", listOf(SceneVariant("Основная", 7f, treasureChest))),
        Scene("Flappy Bird", run {
            val upper = SceneVariant("Слева направо, сверху", 6.5f, flappyBird(FlappyFlight(0.16f, 0.03f, 2.6f, 11)))
            val upperBack = SceneVariant("Слева направо, сверху, второй ритм", 6.5f, flappyBird(FlappyFlight(0.17f, 0.035f, 1.9f, 23)))
                .mirrored("Справа налево, сверху")
            val lower = SceneVariant("Слева направо, снизу", 6.5f, flappyBird(FlappyFlight(0.82f, 0.04f, 2.2f, 37)))
            val lowerBack = SceneVariant("Слева направо, снизу, второй ритм", 6.5f, flappyBird(FlappyFlight(0.80f, 0.03f, 3.1f, 41)))
                .mirrored("Справа налево, снизу")
            listOf(upper, upperBack, lower, lowerBack)
        }),
        Scene("Doodle Jump", listOf(
            SceneVariant("Прямо по центру", DOODLE_HOP_DURATION, doodleHops(FloatArray(DOODLE_HOPS + 1) { 0f })),
            SceneVariant("Зигзагом", DOODLE_HOP_DURATION, doodleHops(FloatArray(DOODLE_HOPS + 1) { if (it % 2 == 0) -1.6f else 1.6f })),
            SceneVariant("По диагонали вправо", DOODLE_HOP_DURATION, doodleHops(FloatArray(DOODLE_HOPS + 1) { lerp(-3.5f, 3f, it / DOODLE_HOPS.toFloat()) })),
            SceneVariant("По диагонали влево", DOODLE_HOP_DURATION, doodleHops(FloatArray(DOODLE_HOPS + 1) { lerp(3.5f, -3f, it / DOODLE_HOPS.toFloat()) })),
            SceneVariant("Змейкой справа", DOODLE_HOP_DURATION, doodleHops(FloatArray(DOODLE_HOPS + 1) { 2.6f + 1.4f * sin(it * 1.3f) })),
            SceneVariant("Реактивный ранец", 4.5f, doodleJetpack),
        )),
    )
}

private const val WHITE = 0xFFFFFFFF.toInt()
private const val YELLOW = 0xFFFFD23F.toInt()
private const val GOLD = 0xFFFFC53D.toInt()
private const val CYAN = 0xFF36E2FF.toInt()
private const val BLUE = 0xFF3D5AFE.toInt()
private const val PURPLE = 0xFFC04DFF.toInt()
private const val ORANGE = 0xFFFF8C1A.toInt()
private const val RED = 0xFFFF3B53.toInt()
private const val GREEN = 0xFF33DD66.toInt()
private const val LIGHT_GREEN = 0xFF9DFF6B.toInt()
private const val GRAY = 0xFF9AA0A6.toInt()
private const val DARK = 0xFF26262B.toInt()
private const val PELLET = 0xFFFFB8AE.toInt()
private val RAINBOW = intArrayOf(RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE)

private const val PI_F = PI.toFloat()

/** Изменяемая точка для промежуточных расчётов без выделения памяти в кадре. */
private class Point(var x: Float = 0f, var y: Float = 0f)

private fun cubic(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
    val m = 1 - t
    return m * m * m * a + 3 * m * m * t * b + 3 * m * t * t * c + t * t * t * d
}

/** Спрайт, повёрнутый на degrees вокруг своего центра (centerX, centerY). */
private fun SceneCanvas.rotatedSprite(c: Canvas, sprite: PixelSprite, centerX: Float, centerY: Float, scale: Float, degrees: Float) {
    c.save()
    c.rotate(degrees, centerX, centerY)
    sprite(c, sprite, centerX, centerY + sprite.height * scale / 2, scale)
    c.restore()
}

/** Снаряд, летящий из (x0, y0) в (x1, y1) за [from, to] секунд. */
private fun SceneCanvas.bullet(c: Canvas, t: Float, from: Float, to: Float, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
    if (t < from || t > to) return
    val k = (t - from) / (to - from)
    val x = lerp(x0, x1, k)
    val y = lerp(y0, y1, k)
    val u = stage.unit
    rect(c, x - 0.6f * u, y - 3 * u, x + 0.6f * u, y, color)
}

// ---------------------------------------------------------------- 1. Динозаврик и птеродактиль

private const val DINO_SCALE = 0.24f // экранных единиц на пиксель листа спрайтов

/**
 * Динозаврик бежит по горизонту слева направо, птеродактиль быстро летит навстречу. Встреча —
 * посередине свободной полосы левее логотипа: динозаврик либо перепрыгивает низко летящего
 * птеродактиля, либо пригибается под высоко летящим.
 */
private fun dinoAndPterodactyl(duck: Boolean): SceneRender = { c, t ->
    val u = stage.unit
    val ground = stage.horizon
    val scale = DINO_SCALE * u
    val start = -0.08f * stage.width
    val finish = 1.08f * stage.width
    val dinoSpeed = (finish - start) / duration
    val meetX = max(stage.logo.left / 2, 0.06f * stage.width)
    val meetAt = (meetX - start) / dinoSpeed
    val dinoX = start + dinoSpeed * t
    // Птеродактиль появляется из-за правого края в начале сцены и встречает динозаврика в meetX.
    val pteroX = meetX + (meetAt - t) * (finish - meetX) / meetAt
    val frame = (t * 10).toInt() % 2
    if (duck) {
        val ducking = abs(dinoX - pteroX) < 0.25f * stage.width
        bitmap(c, (if (ducking) SceneAssets.dinoDuck else SceneAssets.dinoRun)[frame], dinoX, ground, scale)
    } else {
        val jumpStart = meetAt - 0.45f
        val jump = if (t in jumpStart..(jumpStart + 0.9f)) sin(PI_F * (t - jumpStart) / 0.9f) * 34 * u else 0f
        bitmap(c, if (jump > 0) SceneAssets.dinoStand else SceneAssets.dinoRun[frame], dinoX, ground - jump, scale)
    }
    // Низкий птеродактиль пролетает под прыжком, высокий — над пригнувшимся динозавриком.
    val pteroBottom = ground - (if (duck) 14f else 3f) * u
    if (pteroX > -0.1f * stage.width) bitmap(c, SceneAssets.pterodactyl[(t * 5).toInt() % 2], pteroX, pteroBottom, scale)
}

// ---------------------------------------------------------------- 2. Динозаврик и кактус на дороге

private const val ROAD_SPRITE_SCALE = 0.7f

/** Кактус едет по правой полосе к зрителю, динозаврик догоняет его из-за горизонта и перепрыгивает. */
private val dinoJumpsCactus: SceneRender = { c, t ->
    val u = stage.unit
    val lane = 5f
    val cacti = SceneAssets.cacti
    val cactus = cacti[(random(0) * cacti.size).toInt().coerceAtMost(cacti.lastIndex)]
    val cactusS = t / 5.5f
    val dinoS = (t - 2f) / 2.2f
    val dinoVisible = t >= 2f && dinoS <= 1.15f
    val cactusVisible = cactusS in 0f..1.1f

    fun drawCactus() = bitmap(c, cactus, stage.roadX(lane, cactusS), stage.roadY(cactusS), ROAD_SPRITE_SCALE * u * stage.roadScale(cactusS))

    fun drawDino() {
        val k = stage.roadScale(dinoS)
        val gap = abs(dinoS - cactusS)
        val clearance = (cactus.height * ROAD_SPRITE_SCALE + 10) * u * k
        val jump = if (gap < 0.09f) sin(PI_F * (1 - gap / 0.09f) / 2) * clearance else 0f
        val bmp = if (jump > 0) SceneAssets.dinoStand else SceneAssets.dinoRun[(t * 12).toInt() % 2]
        bitmap(c, bmp, stage.roadX(lane, dinoS), stage.roadY(dinoS) - jump, ROAD_SPRITE_SCALE * u * k)
    }

    // Ближний к зрителю объект рисуется поверх дальнего.
    if (dinoVisible && cactusVisible && dinoS < cactusS) {
        drawDino(); drawCactus()
    } else {
        if (cactusVisible) drawCactus()
        if (dinoVisible) drawDino()
    }
}

// ---------------------------------------------------------------- 3. Пакман

/**
 * Пакман ест точки, за ним гонятся призраки; съев энергетик, разворачивается, призраки синеют
 * и убегают. Точки и энергетик стоят в узлах одной равномерной сетки по оси X.
 */
private val pacman: SceneRender = { c, t ->
    val u = stage.unit
    val y = stage.height * 0.16f
    val spacing = 10 * u
    val firstDot = stage.width * 0.1f
    val dotCount = ((stage.width * 0.92f - firstDot) / spacing).toInt() + 1
    val powerIndex = ((stage.width * 0.62f - firstDot) / spacing).roundToInt().coerceIn(1, dotCount - 1)
    val power = firstDot + powerIndex * spacing
    val turn = 4.4f
    val pacX = if (t < turn) lerp(stage.width * 0.06f, power, t / turn) else power - (t - turn) * stage.width * 0.2f
    val eatenUpTo = if (t < turn) pacX else power
    for (i in 0 until dotCount) {
        val x = firstDot + i * spacing
        if (i == powerIndex || x < eatenUpTo) continue
        rect(c, x - u, y - u, x + u, y + u, PELLET)
    }
    if (t < turn) circle(c, power, y, 3 * u, PELLET, if ((t * 4).toInt() % 2 == 0) 255 else 120)
    // Пакман — сектор круга с открывающимся ртом.
    val mouth = 40f * triangleWave(t * 8f)
    val radius = 7 * u
    val heading = if (t < turn) 0f else 180f
    fill.color = YELLOW
    fill.alpha = 255
    c.drawArc(pacX - radius, y - radius, pacX + radius, y + radius, heading + mouth, 360 - 2 * mouth, true, fill)
    val scared = t >= turn
    for (i in 0 until 4) {
        val ghostX = pacX - (22 + 14 * i) * u - if (scared) (t - turn) * stage.width * 0.1f else 0f
        sprite(c, if (scared) SceneSprites.ghostScared else SceneSprites.ghosts[i], ghostX, y + 7 * u, u, flip = scared)
    }
}

// ---------------------------------------------------------------- 4. Космические захватчики

private val INVADER_TARGETS = intArrayOf(3, 8, 1, 10, 5) // порядок сбиваемых захватчиков

/** Строй захватчиков качается и опускается, пушка плавно наводится и сбивает их по одному. */
private val spaceInvaders: SceneRender = { c, t ->
    val u = stage.unit
    val columns = 6
    val targets = INVADER_TARGETS
    val flight = 0.45f
    val cannonY = stage.horizon - 2 * u

    fun formationX(index: Int, time: Float) = stage.centerX + ((index % columns) - (columns - 1) / 2f) * 20 * u + sin(time * 1.4f) * 18 * u
    fun formationY(index: Int, time: Float) = stage.height * 0.08f + time * 2 * u + (index / columns) * 14 * u
    fun fireAt(shot: Int) = 1f + 1.1f * shot

    // Пушка наводится туда, где окажется цель к моменту попадания, и едет между целями плавно.
    var from = stage.centerX
    var fromTime = 0f
    var cannonX = Float.NaN
    for (k in targets.indices) {
        val fire = fireAt(k)
        val aim = formationX(targets[k], fire + flight)
        if (t <= fire) {
            cannonX = lerp(from, aim, easeInOut((t - fromTime) / (fire - fromTime)))
            break
        }
        from = aim
        fromTime = fire + 0.1f
    }
    if (cannonX.isNaN()) cannonX = lerp(from, stage.centerX, easeInOut((t - fromTime) / 1.5f))

    targets.forEachIndexed { k, index ->
        val fire = fireAt(k)
        val aim = formationX(index, fire + flight)
        bullet(c, t, fire, fire + flight, aim, cannonY - 8 * u, aim, formationY(index, fire + flight), WHITE)
    }
    for (i in 0 until columns * 2) {
        val shot = targets.indexOf(i)
        val hit = if (shot >= 0) fireAt(shot) + flight else Float.MAX_VALUE
        val x = formationX(i, t)
        val bottom = formationY(i, t) + 8 * u
        when {
            t < hit -> sprite(c, if ((t * 3).toInt() % 2 == 0) SceneSprites.invaderA else SceneSprites.invaderB, x, bottom, u)
            t < hit + 0.25f -> sprite(c, SceneSprites.explosion, formationX(i, hit), formationY(i, hit) + 8 * u, u)
        }
    }
    sprite(c, SceneSprites.cannon, cannonX, cannonY, u)
    if (t > 6f) sprite(c, SceneSprites.saucer, lerp(-0.1f * stage.width, 1.1f * stage.width, (t - 6f) / 2f), stage.height * 0.05f, u)
}

// ---------------------------------------------------------------- 5. Летающая тарелка

/** Тарелка прилетает слева, зависает в правой части экрана и лучом утаскивает корову с горизонта. */
private val saucerAbduction: SceneRender = { c, t ->
    val u = stage.unit
    val hoverX = stage.width * 0.62f
    val saucerX = when {
        t < 2f -> lerp(-0.1f * stage.width, hoverX, easeOut(t / 2f))
        t < 5f -> hoverX + sin(t * 3) * 3 * u
        else -> lerp(hoverX, 1.2f * stage.width, (t - 5f) / 1.6f)
    }
    val saucerY = stage.height * 0.22f + sin(t * 4) * 2 * u
    if (t in 2f..5f) {
        val beam = clamp01((t - 2f) / 0.3f) * clamp01((5f - t) / 0.3f)
        fill.color = LIGHT_GREEN
        fill.alpha = (70 * beam).toInt()
        path.reset()
        path.moveTo(saucerX - 6 * u, saucerY)
        path.lineTo(saucerX + 6 * u, saucerY)
        path.lineTo(saucerX + 16 * u, stage.horizon)
        path.lineTo(saucerX - 16 * u, stage.horizon)
        path.close()
        c.drawPath(path, fill)
        val lift = clamp01((t - 2.6f) / 2f)
        if (lift < 1f) {
            val cowY = lerp(stage.horizon, saucerY + 6 * u, easeInOut(lift))
            sprite(c, SceneSprites.cow, saucerX, cowY, u * (1f - 0.5f * lift), alpha = (255 * (1 - lift * lift)).toInt())
        }
    } else if (t < 2f) {
        sprite(c, SceneSprites.cow, hoverX, stage.horizon, u)
    }
    sprite(c, SceneSprites.saucer, saucerX, saucerY, 1.4f * u)
}

// ---------------------------------------------------------------- 6. Астероиды

/**
 * Кораблик расстреливает астероид, тот раскалывается, кораблик улетает. Действие целиком
 * в области слева от логотипа и выше его центра; остальные углы получаются отражениями.
 */
private val asteroids: SceneRender = { c, t ->
    val u = stage.unit
    val left = 4 * u
    val right = max(left + 8 * u, stage.logo.left - 4 * u)
    val top = 4 * u
    val bottom = stage.logo.centerY()
    val areaWidth = right - left
    val areaHeight = bottom - top
    val radius = min(10 * u, areaWidth * 0.16f)
    val rockX = lerp(-radius * 2, left + areaWidth * 0.6f, easeOut(t / 3.5f))
    val rockY = top + areaHeight * 0.35f

    fun rock(x: Float, y: Float, size: Float, salt: Int, rotation: Float, alpha: Int) {
        path.reset()
        for (i in 0 until 10) {
            val angle = i / 10f * 2 * PI_F + rotation
            val r = size * (0.75f + 0.35f * random(salt + i))
            if (i == 0) path.moveTo(x + cos(angle) * r, y + sin(angle) * r) else path.lineTo(x + cos(angle) * r, y + sin(angle) * r)
        }
        path.close()
        stroke.color = WHITE
        stroke.alpha = alpha
        stroke.strokeWidth = 0.6f * u
        c.drawPath(path, stroke)
    }

    val breakAt = 3.6f
    if (t < breakAt) {
        rock(rockX, rockY, radius, 0, t * 0.4f, 255)
    } else {
        val k = (t - breakAt) / 2.4f
        val alpha = (255 * (1 - clamp01(k))).toInt()
        val drift = areaWidth * 0.3f
        rock(rockX - k * drift, rockY - k * drift * 0.8f, radius * 0.6f, 20, t, alpha)
        rock(rockX + k * drift * 0.6f, rockY + k * drift, radius * 0.5f, 40, -t, alpha)
        sparks(c, rockX, rockY, k * 2, 10, radius * 1.6f, 60, WHITE)
    }

    // Кораблик ниже астероида: поворачивается к нему, стреляет, затем улетает к внешнему краю.
    val departAt = 4.5f
    val shipX = if (t < departAt) left + areaWidth * 0.3f else left + areaWidth * 0.3f - (t - departAt).pow(2) * 90 * u
    val shipY = top + areaHeight * 0.85f
    val heading = if (t < departAt) atan2(rockY - shipY, rockX - shipX) else PI_F
    val shipSize = min(7 * u, areaWidth * 0.11f)
    path.reset()
    path.moveTo(shipX + cos(heading) * shipSize, shipY + sin(heading) * shipSize)
    path.lineTo(shipX + cos(heading + 2.5f) * shipSize * 0.7f, shipY + sin(heading + 2.5f) * shipSize * 0.7f)
    path.lineTo(shipX + cos(heading - 2.5f) * shipSize * 0.7f, shipY + sin(heading - 2.5f) * shipSize * 0.7f)
    path.close()
    stroke.color = WHITE
    stroke.alpha = 255
    stroke.strokeWidth = 0.6f * u
    c.drawPath(path, stroke)
    if (t > departAt && (t * 12).toInt() % 2 == 0) {
        line(c, shipX + shipSize * 0.7f, shipY, shipX + shipSize * 1.3f, shipY, ORANGE, 0.8f * u)
    }
    for (k in 0..2) {
        val fire = 1.8f + 0.55f * k
        if (t in fire..(fire + 0.6f)) {
            val q = (t - fire) / 0.6f
            circle(c, lerp(shipX, rockX, q), lerp(shipY, rockY, q), 0.8f * u, WHITE)
        }
    }
}

// ---------------------------------------------------------------- 7. Понг

private const val PONG_MATCHES = 8
private const val PONG_TAIL_S = 0.8f // мяч уходит за край и сцена гаснет

/** Отрезок полёта мяча до ракетки: высота прихода (доля поля 0..1) и отскоки от стенок. */
private class PongLeg(val toY: Float, val bounces: Int, val downFirst: Boolean)

/** Сценарий розыгрыша: подача, отрезки полёта мяча; на последнем отрезке ракетка промахивается. */
private class PongMatch(val serveFromLeft: Boolean, val startY: Float, val legSeconds: Float, val legs: List<PongLeg>, val missOffset: Float) {
    val playSeconds get() = legSeconds * legs.size
}

/** Розыгрыш №index: фиксированное зерно даёт одинаковый сценарий при каждом показе. */
private fun pongMatch(index: Int): PongMatch {
    val random = Random(7_301 + index * 97)
    val legSeconds = 0.78f + 0.05f * (index % 4)
    var legCount = (6.6f / legSeconds).toInt()
    // Чётность числа отрезков определяет, кто пропускает: в половине матчей — левая ракетка.
    if ((legCount % 2 == 0) != (index % 2 == 0)) legCount--
    val legs = List(legCount) {
        val bounces = random.nextFloat().let { r -> if (r < 0.4f) 0 else if (r < 0.85f) 1 else 2 }
        PongLeg(0.1f + 0.8f * random.nextFloat(), bounces, random.nextBoolean())
    }
    val lastY = legs.last().toY
    return PongMatch(index < PONG_MATCHES / 2, 0.2f + 0.6f * random.nextFloat(), legSeconds, legs, if (lastY > 0.5f) -0.34f else 0.34f)
}

/** Высота в «развёрнутой» системе, где отскоки от стенок — прямая линия; см. [triangleWave]. */
private fun unfoldedTarget(leg: PongLeg): Float = when {
    leg.bounces == 0 -> leg.toY
    leg.downFirst -> leg.bounces + if (leg.bounces % 2 == 1) 1 - leg.toY else leg.toY
    else -> -(leg.bounces - 1) - if (leg.bounces % 2 == 1) leg.toY else 1 - leg.toY
}

private fun pong(match: PongMatch): SceneRender = { c, t ->
    val u = stage.unit
    val top = stage.height * 0.08f
    val bottom = stage.horizon - 4 * u
    val ball = 1.5f * u
    val paddleHalf = 9 * u
    val leftX = stage.width * 0.06f
    val rightX = stage.width * 0.94f
    val leftFace = leftX + 1.5f * u + ball
    val rightFace = rightX - 1.5f * u - ball
    val duration = match.playSeconds + PONG_TAIL_S
    val alpha = (255 * fadeInOut(t, duration, 0.3f)).toInt()
    val legs = match.legs

    fun fieldY(fraction: Float) = lerp(top + ball, bottom - ball, fraction)
    fun movesRight(leg: Int) = (leg % 2 == 0) == match.serveFromLeft

    // Мяч.
    val legIndex = min((t / match.legSeconds).toInt(), legs.lastIndex)
    val q = (t - legIndex * match.legSeconds) / match.legSeconds
    val fromY = if (legIndex == 0) match.startY else legs[legIndex - 1].toY
    val ballY = fieldY(triangleWave(lerp(fromY, unfoldedTarget(legs[legIndex]), q)))
    val ballX = if (movesRight(legIndex)) lerp(leftFace, rightFace, q) else lerp(rightFace, leftFace, q)

    // Ракетка встречает мяч в точке прихода; на последнем отрезке опаздывает и смещается мимо.
    fun paddleY(left: Boolean): Float {
        var keyTime = 0f
        var keyY = if (left == match.serveFromLeft) match.startY else 0.5f
        for (i in legs.indices) {
            if (movesRight(i) == left) continue // этот отрезок летит к другой ракетке
            val last = i == legs.lastIndex
            val arrive = (i + 1) * match.legSeconds + if (last) 0.3f else 0f
            val target = if (last) (legs[i].toY + match.missOffset).coerceIn(0f, 1f) else legs[i].toY
            if (t < arrive) {
                val start = max(keyTime + 0.15f, arrive - match.legSeconds * 1.3f)
                return fieldY(lerp(keyY, target, easeInOut((t - start) / (arrive - 0.05f - start))))
            }
            keyTime = arrive
            keyY = target
        }
        return fieldY(keyY)
    }

    for (side in 0..1) {
        val left = side == 0
        val x = if (left) leftX else rightX
        val y = paddleY(left).coerceIn(top + paddleHalf, bottom - paddleHalf)
        rect(c, x - 1.5f * u, y - paddleHalf, x + 1.5f * u, y + paddleHalf, WHITE, alpha)
    }
    if (ballX > -ball && ballX < stage.width + ball) rect(c, ballX - ball, ballY - ball, ballX + ball, ballY + ball, WHITE, alpha)
    var dashY = top
    while (dashY < bottom) {
        rect(c, stage.centerX - 0.5f * u, dashY, stage.centerX + 0.5f * u, dashY + 3 * u, WHITE, alpha / 3)
        dashY += 6 * u
    }
}

// ---------------------------------------------------------------- 8. Змейка

private const val SNAKE_LAYOUTS = 25
private const val SNAKE_STEP_S = 0.14f
private const val SNAKE_STEPS = 57 // 8 с по SNAKE_STEP_S
private const val SNAKE_APPLE_STEP = 20
private const val SNAKE_SHORT = 5
private const val SNAKE_LONG = 9
private const val SNAKE_PADDING = 0.1f // отступ области змейки от краёв экрана, доля размера

/** Маршрут змейки: клетки сетки по шагам; яблоко лежит в клетке шага [SNAKE_APPLE_STEP]. */
private class SnakeRoute(val cell: Float, val originX: Float, val originY: Float, val cols: IntArray, val rows: IntArray)

/** Маршруты раскладов для текущего размера экрана (строятся один раз на размер). */
private object SnakeRoutes {
    private var width = 0f
    private var height = 0f
    private val routes = arrayOfNulls<SnakeRoute>(SNAKE_LAYOUTS)

    fun get(stage: SceneStage, layout: Int): SnakeRoute {
        if (stage.width != width || stage.height != height) {
            routes.fill(null)
            width = stage.width
            height = stage.height
        }
        return routes[layout] ?: build(stage, layout).also { routes[layout] = it }
    }

    /**
     * Случайный (с фиксированным зерном расклада) маршрут внутри экрана с отступом от краёв,
     * не заходящий под логотип и не пересекающий собственный хвост.
     */
    private fun build(stage: SceneStage, layout: Int): SnakeRoute {
        val u = stage.unit
        val cell = 4.5f * u
        val gridCols = ((stage.width * (1 - 2 * SNAKE_PADDING)) / cell).toInt()
        val gridRows = ((stage.height * (1 - 2 * SNAKE_PADDING)) / cell).toInt()
        val originX = (stage.width - gridCols * cell) / 2
        val originY = (stage.height - gridRows * cell) / 2
        val keepOut = RectF(stage.logo).apply { inset(-3 * u, -3 * u) }
        fun free(col: Int, row: Int) = col in 0 until gridCols && row in 0 until gridRows &&
            !keepOut.intersects(originX + col * cell, originY + row * cell, originX + (col + 1) * cell, originY + (row + 1) * cell)

        val random = Random(9_001 + layout * 7_919)
        val dx = intArrayOf(1, 0, -1, 0)
        val dy = intArrayOf(0, 1, 0, -1)
        val cols = IntArray(SNAKE_STEPS + 1)
        val rows = IntArray(SNAKE_STEPS + 1)
        var best = 0
        var bestCols = cols.copyOf()
        var bestRows = rows.copyOf()
        repeat(400) {
            cols[0] = random.nextInt(gridCols.coerceAtLeast(1))
            rows[0] = random.nextInt(gridRows.coerceAtLeast(1))
            if (!free(cols[0], rows[0])) return@repeat
            var dir = random.nextInt(4)
            var straight = 3 + random.nextInt(7)
            var length = 1
            fun canStep(d: Int): Boolean {
                val col = cols[length - 1] + dx[d]
                val row = rows[length - 1] + dy[d]
                if (!free(col, row)) return false
                for (i in max(0, length - SNAKE_LONG - 1) until length) if (cols[i] == col && rows[i] == row) return false
                return true
            }
            while (length <= SNAKE_STEPS) {
                // Порядок попыток: прямо (пока не исчерпан прямой участок), затем повороты в случайном порядке.
                val firstTurn = if (random.nextBoolean()) (dir + 1) % 4 else (dir + 3) % 4
                val secondTurn = (firstTurn + 2) % 4
                val next = when {
                    straight > 0 && canStep(dir) -> dir
                    canStep(firstTurn) -> firstTurn
                    canStep(secondTurn) -> secondTurn
                    canStep(dir) -> dir
                    else -> break
                }
                if (next == dir) straight-- else {
                    dir = next
                    straight = 3 + random.nextInt(7)
                }
                cols[length] = cols[length - 1] + dx[dir]
                rows[length] = rows[length - 1] + dy[dir]
                length++
            }
            if (length > best) {
                best = length
                bestCols = cols.copyOf(length)
                bestRows = rows.copyOf(length)
            }
            if (length > SNAKE_STEPS) return SnakeRoute(cell, originX, originY, bestCols, bestRows)
        }
        return SnakeRoute(cell, originX, originY, bestCols.copyOf(max(1, best)), bestRows.copyOf(max(1, best)))
    }
}

/** Змейка ползёт по клеткам, съедает яблоко и вырастает. */
private fun snake(layout: Int): SceneRender = { c, t ->
    val route = SnakeRoutes.get(stage, layout)
    val cell = route.cell
    val step = (t / SNAKE_STEP_S).toInt().coerceAtMost(route.cols.lastIndex)
    val length = if (step > SNAKE_APPLE_STEP) SNAKE_LONG else SNAKE_SHORT
    val alpha = (255 * fadeInOut(t, duration, 0.4f)).toInt()
    fun cellRect(col: Int, row: Int, color: Int) {
        val x = route.originX + col * cell
        val y = route.originY + row * cell
        rect(c, x + 1, y + 1, x + cell - 1, y + cell - 1, color, alpha)
    }
    if (step <= SNAKE_APPLE_STEP && SNAKE_APPLE_STEP <= route.cols.lastIndex) {
        cellRect(route.cols[SNAKE_APPLE_STEP], route.rows[SNAKE_APPLE_STEP], RED)
    }
    val tail = max(0, step + 1 - length)
    for (i in tail..step) cellRect(route.cols[i], route.rows[i], if (i == step) LIGHT_GREEN else GREEN)
}

// ---------------------------------------------------------------- 9. Утиная охота

/** Утка взлетает зигзагом, прицел догоняет её, выстрел — утка падает. */
private val duckHunt: SceneRender = { c, t ->
    val u = stage.unit
    val shot = 3.8f
    fun duckX(time: Float) = stage.width * 0.3f + sin(time * 2.2f) * stage.width * 0.14f
    fun duckY(time: Float) = lerp(stage.horizon, stage.height * 0.14f, clamp01(time / 3.6f))
    val flight = min(t, shot)
    val x = duckX(flight)
    val flyY = duckY(flight)
    val y = if (t < shot + 0.4f) flyY else lerp(flyY, stage.horizon, clamp01((t - shot - 0.4f) / 1f))
    if (t < shot) {
        val wings = if ((t * 6).toInt() % 2 == 0) SceneSprites.duckWingsUp else SceneSprites.duckWingsDown
        sprite(c, wings, x, flyY, 1.4f * u, flip = cos(flight * 2.2f) < 0)
    } else if (y < stage.horizon - 1) {
        c.save()
        c.scale(1f, -1f, x, y - 5 * u)
        sprite(c, SceneSprites.duckWingsDown, x, y, 1.4f * u)
        c.restore()
    }
    // Прицел идёт по траектории утки с запаздыванием и к выстрелу совпадает с ней.
    val lagged = max(0f, flight - 0.35f)
    val catchUp = clamp01(t / shot)
    val aimX = if (t < shot) lerp(duckX(lagged), x, catchUp) else x
    val aimY = (if (t < shot) lerp(duckY(lagged), flyY, catchUp) else flyY) - 5 * u
    if (t < shot + 0.6f) {
        stroke.color = RED
        stroke.alpha = 230
        stroke.strokeWidth = 0.6f * u
        c.drawCircle(aimX, aimY, 8 * u, stroke)
        line(c, aimX - 12 * u, aimY, aimX + 12 * u, aimY, RED, 0.6f * u, 230)
        line(c, aimX, aimY - 12 * u, aimX, aimY + 12 * u, RED, 0.6f * u, 230)
    }
    if (t in shot..(shot + 0.08f)) rect(c, 0f, 0f, stage.width, stage.height, WHITE, 70)
}

// ---------------------------------------------------------------- 10. Код Konami

/** Стрелки ↑↑↓↓←→←→ и кнопки B A появляются по очереди, затем разлетаются салютом. */
private val konamiCode: SceneRender = { c, t ->
    val u = stage.unit
    val rotations = KONAMI_KEYS
    val size = 12 * u
    val y = stage.height * 0.88f
    val firstX = stage.centerX - (rotations.size - 1) * size * 0.75f
    rotations.forEachIndexed { i, rotation ->
        val appearAt = 0.45f * i
        if (t < appearAt) return@forEachIndexed
        val pop = easeOut((t - appearAt) / 0.2f)
        val x = firstX + i * size * 1.5f
        val burst = t > 5f
        val alpha = if (burst) (255 * clamp01(1 - (t - 5f) / 0.5f)).toInt() else 255
        val color = if (t > 4.6f) RAINBOW[(i + (t * 8).toInt()) % RAINBOW.size] else DARK
        rect(c, x - size / 2 * pop, y - size / 2 * pop, x + size / 2 * pop, y + size / 2 * pop, color, alpha)
        val glyph = when (rotation) {
            KEY_B -> SceneSprites.letterB
            KEY_A -> SceneSprites.letterA
            else -> SceneSprites.arrow
        }
        c.save()
        if (rotation >= 0) c.rotate(rotation, x, y)
        sprite(c, glyph, x, y + glyph.height * u * pop / 2, u * pop, alpha = alpha)
        c.restore()
        if (burst) sparks(c, x, y, (t - 5f) / 2.5f, 12, 60 * u, i * 20, RAINBOW[i % RAINBOW.size])
    }
}

private const val KEY_B = -1f
private const val KEY_A = -2f

/** Последовательность кода: углы поворота стрелки вверх, затем кнопки B и A. */
private val KONAMI_KEYS = floatArrayOf(0f, 0f, 180f, 180f, 270f, 90f, 270f, 90f, KEY_B, KEY_A)

// ---------------------------------------------------------------- 11. Огненный шар

/** Энергетический шар заряжается у левого края и проносится через экран со шлейфом. */
private val fireball: SceneRender = { c, t ->
    val u = stage.unit
    val y = stage.height * 0.52f
    if (t < 0.5f) {
        circle(c, stage.width * 0.03f, y, 14 * u * easeOut(t / 0.5f), CYAN, (120 * (t / 0.5f)).toInt())
    } else {
        val x = lerp(-0.05f * stage.width, 1.15f * stage.width, (t - 0.5f) / 4f)
        for (i in 0 until 14) {
            circle(c, x - i * 5 * u, y + sin((t * 20) + i) * 2 * u, (9 - i * 0.5f) * u, if (i % 2 == 0) CYAN else BLUE, (180 - i * 12).coerceAtLeast(0))
        }
        circle(c, x, y, 10 * u * (1 + 0.1f * sin(t * 30)), 0xFFB8F4FF.toInt())
        circle(c, x, y, 6 * u, WHITE)
        stroke.color = CYAN
        stroke.alpha = 200
        stroke.strokeWidth = 1.2f * u
        c.drawArc(x - 14 * u, y - 14 * u, x + 14 * u, y + 14 * u, t * 900f, 120f, false, stroke)
        c.drawArc(x - 14 * u, y - 14 * u, x + 14 * u, y + 14 * u, t * 900f + 180f, 120f, false, stroke)
    }
}

// ---------------------------------------------------------------- 12. Золотые кольца

/** Кольца дугой вращаются над горизонтом и по одному собираются со вспышкой. */
private val goldenRings: SceneRender = { c, t ->
    val u = stage.unit
    for (i in 0 until 8) {
        val x = lerp(stage.width * 0.28f, stage.width * 0.72f, i / 7f)
        val y = stage.height * 0.56f - sin(PI_F * i / 7f) * stage.height * 0.1f
        val collectAt = 1.6f + 0.35f * i
        val appear = easeOut((t - i * 0.08f) / 0.3f)
        if (t < collectAt) {
            val spin = abs(cos(t * 4 + i * 0.5f))
            stroke.color = GOLD
            stroke.alpha = 255
            stroke.strokeWidth = 1.6f * u
            val halfWidth = 6 * u * max(0.15f, spin) * appear
            c.drawOval(x - halfWidth, y - 6 * u * appear, x + halfWidth, y + 6 * u * appear, stroke)
        } else {
            sparks(c, x, y, (t - collectAt) / 0.6f, 8, 12 * u, i * 10, YELLOW)
        }
    }
}

// ---------------------------------------------------------------- 13. Спорткар

/** Спорткар уезжает по дороге к горизонту, петляя и оставляя дым. */
private val sportsCar: SceneRender = { c, t ->
    val s = 1.08f - (t / 6f).pow(0.7f)
    if (s > 0.02f) {
        val u = stage.unit
        val k = stage.roadScale(s)
        val x = stage.roadX(sin(t * 1.4f) * 1.6f, s)
        val y = stage.roadY(s)
        for (i in 1..4) {
            val age = (t * 3 + i * 0.25f) % 1f
            circle(c, x + (random(i) - 0.5f) * 20 * u * k, y + age * 10 * u * k, (2 + 5 * age) * u * k, GRAY, (120 * (1 - age)).toInt())
        }
        sprite(c, SceneSprites.carRear, x, y, 4f * u * k)
    }
}

// ---------------------------------------------------------------- 14. Сердечки здоровья

/** Три сердечка; последние lost разбиваются по очереди и затем одновременно восстанавливаются. */
private fun healthHearts(lost: Int): SceneRender = { c, t ->
    val u = stage.unit
    val alpha = (255 * clamp01((duration - t) / 0.8f)).toInt()
    val restoreAt = 3.6f
    for (i in 0 until 3) {
        val x = stage.width * 0.05f + i * 13 * u
        val y = stage.height * 0.1f
        val pop = easeOut((t - i * 0.2f) / 0.35f)
        if (pop <= 0f) continue
        val scale = 1.4f * u * pop
        val isLost = i >= 3 - lost
        val breakAt = 2f + (2 - i) * 0.3f
        if (isLost && t in breakAt..restoreAt) {
            sprite(c, SceneSprites.heartEmpty, x, y, scale, alpha = alpha)
            val k = (t - breakAt) / 1.2f
            if (k < 1f) {
                val shardAlpha = (255 * (1 - k)).toInt()
                c.save()
                c.clipRect(x - 10 * u, y - 20 * u, x, y + 10 * u)
                sprite(c, SceneSprites.heart, x - k * 6 * u, y + k * k * 30 * u, scale, alpha = shardAlpha)
                c.restore()
                c.save()
                c.clipRect(x, y - 20 * u, x + 10 * u, y + 10 * u)
                sprite(c, SceneSprites.heart, x + k * 6 * u, y + k * k * 30 * u, scale, alpha = shardAlpha)
                c.restore()
            }
        } else {
            sprite(c, SceneSprites.heart, x, y, scale, alpha = alpha)
        }
        if (isLost) sparks(c, x, y - 6 * u, (t - restoreAt) / 0.7f, 10, 14 * u, 5 + i * 31, YELLOW)
    }
}

// ---------------------------------------------------------------- 15. Дискета

/** Дискета пролетает по дуге справа налево, кувыркаясь и оставляя радужный след. */
private val floppyDisk: SceneRender = { c, t ->
    val u = stage.unit
    fun trajectoryX(q: Float) = lerp(1.08f * stage.width, -0.08f * stage.width, q)
    fun trajectoryY(q: Float) = stage.height * 0.85f - sin(PI_F * q) * stage.height * 0.62f
    val q = t / 6f
    for (i in 1..6) {
        val trail = max(0f, q - i * 0.012f)
        val x = trajectoryX(trail)
        val y = trajectoryY(trail)
        rect(c, x - u, y - u, x + u, y + u, RAINBOW[i % RAINBOW.size], 200 - i * 30)
    }
    val x = trajectoryX(q)
    val y = trajectoryY(q)
    c.save()
    c.rotate(t * 220f, x, y)
    sprite(c, SceneSprites.floppy, x, y + SceneSprites.floppy.height * 1.6f * u / 2, 1.6f * u)
    c.restore()
}

// ---------------------------------------------------------------- 16. Галага

private const val GALAGA_SCALE = 1.3f

private fun SceneCanvas.fighterY() = stage.horizon - 2 * stage.unit

/** Рой пчёл влетает петлёй, истребитель сбивает двух. */
private val galagaLoop: SceneRender = { c, t ->
    val u = stage.unit
    val shipX = stage.centerX + sin(t * 1.3f) * 30 * u
    val shipY = fighterY()
    for (i in 0 until 8) {
        val q = (t - i * 0.25f) / 6.5f
        if (q < 0f || q > 1f) continue
        val angle = q * 2.4f * PI_F
        val x = stage.centerX + cos(angle) * stage.width * 0.28f * (1 - q * 0.3f) + (1 - q) * stage.width * 0.2f
        val y = stage.height * 0.3f + sin(angle) * stage.height * 0.18f - (1 - q * 2).coerceAtLeast(0f) * stage.height * 0.3f
        val hitAt = when (i) {
            2 -> 3.5f
            5 -> 4.4f
            else -> Float.MAX_VALUE
        }
        when {
            t < hitAt -> sprite(c, SceneSprites.bee, x, y, GALAGA_SCALE * u, flip = (t * 4).toInt() % 2 == 0)
            t < hitAt + 0.3f -> sprite(c, SceneSprites.explosion, x, y, GALAGA_SCALE * u)
        }
        if (t in (hitAt - 0.4f)..hitAt) {
            val k = (t - hitAt + 0.4f) / 0.4f
            val bx = lerp(shipX, x, k)
            val by = lerp(shipY - 12 * u, y, k)
            rect(c, bx - 0.6f * u, by - 3 * u, bx + 0.6f * u, by, RED)
        }
    }
    sprite(c, SceneSprites.fighter, shipX, shipY, GALAGA_SCALE * u)
}

/** Пикирующий пришелец: время старта, время попадания (MAX_VALUE — уходит вниз и возвращается в строй). */
private class GalagaDive(val slot: Int, val start: Float, val hitAt: Float)

private val GALAGA_DIVES = arrayOf(GalagaDive(1, 0.8f, 2.35f), GalagaDive(6, 2.8f, 4.35f), GalagaDive(3, 4.6f, Float.MAX_VALUE))
private const val GALAGA_DIVE_S = 2.2f
private const val DIVE_TURN_END = 0.25f // доля пикирования: конец дуги к краю логотипа
private const val DIVE_DROP_END = 0.6f  // доля пикирования: конец спуска вдоль края
private const val GALAGA_RETURN_S = 1.2f
private const val GALAGA_BULLET_S = 0.35f
private val divePoint = Point()

/** Строй над логотипом; пришельцы по очереди пикируют в обход логотипа на истребитель, двое сбиты. */
private val galagaDive: SceneRender = { c, t ->
    val u = stage.unit
    val scale = GALAGA_SCALE * u
    val count = 8
    val rowY = stage.height * 0.11f
    val shipY = fighterY()
    fun slotX(i: Int, time: Float) = stage.centerX + (i - (count - 1) / 2f) * 16 * u + sin(time * 1.2f) * 8 * u
    fun shipX(time: Float) = stage.centerX + sin(time * 0.8f + 1f) * 36 * u

    /**
     * Точка пикирования в момент time; false — пришелец уже за нижним краем. Путь из трёх
     * участков, не заходящий под логотип: дуга вбок к краю логотипа, спуск вдоль края ниже
     * логотипа, дуга на истребитель и за нижний край.
     */
    fun divePosition(dive: GalagaDive, time: Float, out: Point): Boolean {
        val q = (time - dive.start) / GALAGA_DIVE_S
        if (q > 1f) return false
        val startX = slotX(dive.slot, dive.start)
        val side = if (startX < stage.centerX) -1f else 1f
        val edgeX = if (side < 0) stage.logo.left - 8 * u else stage.logo.right + 8 * u
        val belowLogo = stage.logo.bottom + 10 * u
        val targetX = shipX(dive.start + 1f)
        when {
            q < DIVE_TURN_END -> {
                val k = q / DIVE_TURN_END
                out.x = cubic(startX, startX, edgeX, edgeX, k)
                out.y = cubic(rowY, rowY - 14 * u, rowY - 14 * u, rowY, k)
            }
            q < DIVE_DROP_END -> {
                out.x = edgeX
                out.y = lerp(rowY, belowLogo, (q - DIVE_TURN_END) / (DIVE_DROP_END - DIVE_TURN_END))
            }
            else -> {
                val k = (q - DIVE_DROP_END) / (1f - DIVE_DROP_END)
                val dropSpeed = (belowLogo - rowY) / (DIVE_DROP_END - DIVE_TURN_END) * (1f - DIVE_DROP_END) / 3
                out.x = cubic(edgeX, edgeX, targetX, targetX, k)
                out.y = cubic(belowLogo, belowLogo + dropSpeed, stage.height - 20 * u, stage.height + 12 * u, k)
            }
        }
        return true
    }

    for (i in 0 until count) {
        val alien = if (i % 3 == 1) SceneSprites.butterfly else SceneSprites.bee
        val dive = GALAGA_DIVES.firstOrNull { it.slot == i }
        if (dive == null || t < dive.start) {
            sprite(c, alien, slotX(i, t), rowY + alien.height * scale / 2, scale, flip = (t * 3).toInt() % 2 == 0)
            continue
        }
        if (t >= dive.hitAt) {
            if (t < dive.hitAt + 0.3f && divePosition(dive, dive.hitAt, divePoint)) {
                sprite(c, SceneSprites.explosion, divePoint.x, divePoint.y + 4 * u, scale)
            }
            continue
        }
        if (divePosition(dive, t, divePoint)) {
            val x = divePoint.x
            val y = divePoint.y
            divePosition(dive, min(t + 0.02f, dive.start + GALAGA_DIVE_S), divePoint)
            val heading = Math.toDegrees(atan2(divePoint.y - y, divePoint.x - x).toDouble()).toFloat() - 90f
            rotatedSprite(c, alien, x, y, scale, heading)
        } else {
            // Ушёл за нижний край — возвращается в строй сверху.
            val back = clamp01((t - dive.start - GALAGA_DIVE_S) / GALAGA_RETURN_S)
            val x = slotX(i, t)
            sprite(c, alien, x, lerp(-12 * u, rowY + alien.height * scale / 2, easeOut(back)), scale)
        }
        if (dive.hitAt != Float.MAX_VALUE && divePosition(dive, dive.hitAt, divePoint)) {
            val fire = dive.hitAt - GALAGA_BULLET_S
            bullet(c, t, fire, dive.hitAt, shipX(fire), shipY - 12 * u, divePoint.x, divePoint.y, RED)
        }
    }
    sprite(c, SceneSprites.fighter, shipX(t), shipY, scale)
}

/** Флагман с эскортом зависает слева от логотипа и включает луч; истребитель подбивает его двумя выстрелами. */
private val galagaTractorBeam: SceneRender = { c, t ->
    val u = stage.unit
    val scale = GALAGA_SCALE * u
    val bandX = max(16 * u, stage.logo.left / 2)
    val hoverY = stage.height * 0.16f
    val shipY = fighterY()
    val firstHit = 3.05f
    val escortHit = 3.65f
    val finalHit = 4.25f

    fun flagshipX(time: Float) = lerp(bandX + stage.width * 0.3f, bandX, easeOut(time / 1.5f)) + sin(time * 2f) * u
    fun flagshipY(time: Float) = lerp(-15 * u, hoverY, easeOut(time / 1.5f)) + sin(time * 3f) * u
    fun escortX(side: Int, time: Float) = flagshipX(time - 0.15f) + side * 14 * u
    fun escortY(side: Int, time: Float): Float {
        val base = flagshipY(time - 0.15f) + 4 * u
        // Уцелевший правый эскорт после гибели флагмана улетает вверх.
        return if (side > 0 && time > finalHit + 0.3f) base - (time - finalHit - 0.3f).pow(2) * 40 * u else base
    }
    fun shipX(time: Float) = when {
        time < 2.6f -> lerp(stage.centerX, bandX + 26 * u, easeInOut(time / 2.6f))
        time < 4.8f -> bandX + 26 * u
        else -> lerp(bandX + 26 * u, stage.centerX, easeInOut((time - 4.8f) / 1.8f))
    }

    // Луч захвата: мерцающие полосы от флагмана до горизонта.
    if (t in 1.6f..finalHit) {
        val grow = easeOut((t - 1.6f) / 0.6f)
        val fx = flagshipX(t)
        val beamTop = flagshipY(t) + 2 * u
        val beamBottom = lerp(beamTop, stage.horizon, grow)
        val stripes = 6
        for (i in 0 until stripes) {
            val k0 = ((i + (t * 2.5f) % 1f) / stripes).coerceAtMost(1f)
            val k1 = ((i + 0.55f + (t * 2.5f) % 1f) / stripes).coerceAtMost(1f)
            val y0 = lerp(beamTop, beamBottom, k0)
            val y1 = lerp(beamTop, beamBottom, k1)
            val half0 = lerp(4 * u, 16 * u, k0 * grow)
            val half1 = lerp(4 * u, 16 * u, k1 * grow)
            fill.color = if (i % 2 == 0) CYAN else PURPLE
            fill.alpha = 70 + (30 * sin(t * 25 + i)).toInt()
            path.reset()
            path.moveTo(fx - half0, y0)
            path.lineTo(fx + half0, y0)
            path.lineTo(fx + half1, y1)
            path.lineTo(fx - half1, y1)
            path.close()
            c.drawPath(path, fill)
        }
    }

    for (side in -1..1 step 2) {
        val x = escortX(side, t)
        val y = escortY(side, t)
        when {
            side < 0 && t >= escortHit -> if (t < escortHit + 0.3f) sprite(c, SceneSprites.explosion, escortX(side, escortHit), escortY(side, escortHit) + 4 * u, scale)
            y > -10 * u -> sprite(c, SceneSprites.bee, x, y + 4 * u, scale, flip = (t * 3).toInt() % 2 == 0)
        }
    }
    if (t < finalHit) {
        val boss = if (t < firstHit) SceneSprites.flagship else SceneSprites.flagshipDamaged
        sprite(c, boss, flagshipX(t), flagshipY(t) + boss.height * scale / 2, scale)
    } else {
        sprite(c, SceneSprites.explosion, flagshipX(finalHit), flagshipY(finalHit) + 5 * u, 1.6f * scale, alpha = (255 * clamp01(1 - (t - finalHit) / 0.4f)).toInt())
        sparks(c, flagshipX(finalHit), flagshipY(finalHit), (t - finalHit) / 0.9f, 16, 20 * u, 77, YELLOW)
    }
    for (k in 0 until 3) {
        val hit = when (k) {
            0 -> firstHit
            1 -> escortHit
            else -> finalHit
        }
        val fire = hit - GALAGA_BULLET_S
        val targetX = if (k == 1) escortX(-1, hit) else flagshipX(hit)
        val targetY = if (k == 1) escortY(-1, hit) else flagshipY(hit)
        bullet(c, t, fire, hit, shipX(fire), shipY - 12 * u, targetX, targetY + 4 * u, RED)
    }
    sprite(c, SceneSprites.fighter, shipX(t), shipY, scale)
}

private const val SPIRAL_PER_SIDE = 5
private const val SPIRAL_S = 1.8f
private const val SPIRAL_SETTLE_S = 0.7f
private const val SPIRAL_EXIT_AT = 5.4f
private const val SPIRAL_EXIT_S = 1.4f
private val SPIRAL_HITS = floatArrayOf(3.4f, 3.8f, 4.2f, 4.6f)
private val SPIRAL_TARGETS = arrayOf(-1 to 1, 1 to 2, -1 to 3, 1 to 0) // сторона, номер в потоке
private val spiralPoint = Point()

/** Два потока пришельцев влетают зеркальными спиралями, встают в строй; истребитель сбивает четверых, остальные уходят вниз. */
private val galagaSpiral: SceneRender = { c, t ->
    val u = stage.unit
    val scale = GALAGA_SCALE * u
    val rowY = stage.height * 0.1f
    val shipY = fighterY()
    fun shipX(time: Float) = stage.centerX + sin(time * 1.1f) * 30 * u
    fun slotX(side: Int, j: Int, time: Float) = stage.centerX + side * (10 * u + j * 15 * u) + sin(time * 1.3f) * 6 * u

    /** Позиция пришельца j из потока side в момент time; false — его нет на экране. */
    fun position(side: Int, j: Int, time: Float, out: Point): Boolean {
        val local = time - j * 0.16f
        if (local < 0f) return false
        val exitOrder = j + (if (side > 0) SPIRAL_PER_SIDE else 0)
        val exitStart = SPIRAL_EXIT_AT + (exitOrder % SPIRAL_PER_SIDE) * 0.25f + (if (side > 0) 0.12f else 0f)
        when {
            local < SPIRAL_S -> {
                val q = local / SPIRAL_S
                val radius = lerp(stage.height * 0.34f, stage.height * 0.05f, q)
                val angle = q * 2.5f * PI_F
                out.x = stage.centerX + side * stage.width * 0.3f + side * radius * cos(angle)
                out.y = stage.height * 0.3f + radius * sin(angle)
            }
            local < SPIRAL_S + SPIRAL_SETTLE_S -> {
                val q = easeInOut((local - SPIRAL_S) / SPIRAL_SETTLE_S)
                val angle = 2.5f * PI_F
                val fromX = stage.centerX + side * stage.width * 0.3f + side * stage.height * 0.05f * cos(angle)
                val fromY = stage.height * 0.3f + stage.height * 0.05f * sin(angle)
                out.x = lerp(fromX, slotX(side, j, time), q)
                out.y = lerp(fromY, rowY, q)
            }
            time < exitStart -> {
                out.x = slotX(side, j, time)
                out.y = rowY
            }
            else -> {
                val q = (time - exitStart) / SPIRAL_EXIT_S
                if (q > 1f) return false
                val startX = slotX(side, j, exitStart)
                out.x = cubic(startX, startX + side * 20 * u, startX - side * 10 * u, startX + side * stage.width * 0.25f, q)
                out.y = cubic(rowY, rowY - 20 * u, stage.height * 0.6f, stage.height + 12 * u, q)
            }
        }
        return true
    }

    for (side in -1..1 step 2) {
        val alien = if (side < 0) SceneSprites.bee else SceneSprites.butterfly
        for (j in 0 until SPIRAL_PER_SIDE) {
            val shot = SPIRAL_TARGETS.indexOfFirst { it.first == side && it.second == j }
            val hitAt = if (shot >= 0) SPIRAL_HITS[shot] else Float.MAX_VALUE
            if (t >= hitAt) {
                if (t < hitAt + 0.3f && position(side, j, hitAt, spiralPoint)) {
                    sprite(c, SceneSprites.explosion, spiralPoint.x, spiralPoint.y + 4 * u, scale)
                }
                continue
            }
            if (!position(side, j, t, spiralPoint)) continue
            val x = spiralPoint.x
            val y = spiralPoint.y
            val alpha = (255 * clamp01((t - j * 0.16f) / 0.15f)).toInt()
            if (position(side, j, t + 0.02f, spiralPoint) && (abs(spiralPoint.x - x) + abs(spiralPoint.y - y)) > 0.05f * u) {
                val heading = Math.toDegrees(atan2(spiralPoint.y - y, spiralPoint.x - x).toDouble()).toFloat() - 90f
                c.save()
                c.rotate(heading, x, y)
                sprite(c, alien, x, y + alien.height * scale / 2, scale, alpha = alpha)
                c.restore()
            } else {
                sprite(c, alien, x, y + alien.height * scale / 2, scale, flip = (t * 3).toInt() % 2 == 0)
            }
        }
    }
    SPIRAL_TARGETS.forEachIndexed { k, (side, j) ->
        val hit = SPIRAL_HITS[k]
        if (position(side, j, hit, spiralPoint)) {
            val fire = hit - GALAGA_BULLET_S
            bullet(c, t, fire, hit, shipX(fire), shipY - 12 * u, spiralPoint.x, spiralPoint.y, RED)
        }
    }
    sprite(c, SceneSprites.fighter, shipX(t), shipY, scale)
}

// ---------------------------------------------------------------- 17. Сундук с сокровищем

// Смещения трёх треугольников (верхний, левый, правый) в долях стороны.
private val TRIANGLE_OFFSET_X = floatArrayOf(0f, -0.5f, 0.5f)
private val TRIANGLE_OFFSET_Y = floatArrayOf(-1f, 0f, 0f)

/** Сундук открывается, из него в лучах света поднимается вращающийся золотой триангл. */
private val treasureChest: SceneRender = { c, t ->
    val u = stage.unit
    val x = stage.centerX
    val y = stage.height * 0.94f
    val alpha = (255 * clamp01((duration - t) / 0.8f)).toInt()
    val pop = easeOut(t / 0.5f)
    if (t > 1f) {
        fill.color = GOLD
        for (i in 0 until 8) {
            fill.alpha = (50 * clamp01((t - 1f) / 0.5f) * alpha / 255).toInt()
            val angle = i * PI_F / 4 + t * 0.6f
            path.reset()
            path.moveTo(x, y - 8 * u)
            path.lineTo(x + cos(angle - 0.12f) * stage.height * 0.5f, y - 8 * u + sin(angle - 0.12f) * stage.height * 0.5f)
            path.lineTo(x + cos(angle + 0.12f) * stage.height * 0.5f, y - 8 * u + sin(angle + 0.12f) * stage.height * 0.5f)
            path.close()
            c.drawPath(path, fill)
        }
        // Три треугольника; вращение передаётся сжатием по X.
        val rise = easeOut((t - 1.2f) / 2.5f)
        val triY = lerp(y - 12 * u, stage.height * 0.58f, rise)
        val side = 16 * u
        val halfHeight = side * 0.87f / 2
        fill.color = GOLD
        fill.alpha = alpha
        c.save()
        c.scale(cos(t * 2.2f), 1f, x, triY)
        for (k in 0 until 3) {
            val cx = x + TRIANGLE_OFFSET_X[k] * side
            val cy = triY + TRIANGLE_OFFSET_Y[k] * side * 0.87f
            path.reset()
            path.moveTo(cx, cy - halfHeight)
            path.lineTo(cx + side / 2, cy + halfHeight)
            path.lineTo(cx - side / 2, cy + halfHeight)
            path.close()
            c.drawPath(path, fill)
        }
        c.restore()
        sparks(c, x, triY, ((t - 1.2f) % 1.2f) / 1.2f, 8, 20 * u, (t / 1.2f).toInt() * 13, YELLOW)
    }
    sprite(c, if (t > 1f) SceneSprites.chestOpen else SceneSprites.chestClosed, x, y, 2f * u * pop, alpha = alpha)
}

// ---------------------------------------------------------------- 18. Flappy Bird

private const val FLAPPY_GRAVITY = 2.6f // доли высоты экрана в секунду²
private const val FLAPPY_FLAP = -0.62f // скорость после взмаха, доли высоты в секунду
private const val FLAPPY_SCALE = 0.55f

/**
 * Полёт птицы: взмахи подбираются заранее «автопилотом», который держит птицу около
 * плавающей высоты [altitude] ± [swing] (доли высоты экрана) с периодом [period] с.
 * Результат — моменты взмахов и высоты в эти моменты; кадр считается по формуле падения.
 */
private class FlappyFlight(val altitude: Float, val swing: Float, val period: Float, seed: Int) {
    val flapTimes: FloatArray
    val flapHeights: FloatArray

    init {
        val random = Random(seed)
        val times = ArrayList<Float>()
        val heights = ArrayList<Float>()
        val dt = 1f / 240f
        var y = altitude
        var velocity = 0f
        var sinceFlap = 1f
        val phase = random.nextFloat() * 2 * PI_F
        var time = 0f
        while (time < 8f) {
            val target = altitude + swing * sin(2 * PI_F * time / period + phase) + (random.nextFloat() - 0.5f) * 0.01f
            if (y > target && velocity > 0f && sinceFlap > 0.28f) {
                times += time
                heights += y
                velocity = FLAPPY_FLAP
                sinceFlap = 0f
            }
            velocity += FLAPPY_GRAVITY * dt
            y += velocity * dt
            sinceFlap += dt
            time += dt
        }
        flapTimes = times.toFloatArray()
        flapHeights = heights.toFloatArray()
    }
}

/** Птица летит слева направо без труб, подпрыгивая взмахами, как в оригинальной игре. */
private fun flappyBird(flight: FlappyFlight): SceneRender = { c, t ->
    val u = stage.unit
    var index = -1
    while (index + 1 < flight.flapTimes.size && flight.flapTimes[index + 1] <= t) index++
    val since = if (index < 0) t else t - flight.flapTimes[index]
    val startY = if (index < 0) flight.altitude else flight.flapHeights[index]
    val startVelocity = if (index < 0) 0f else FLAPPY_FLAP
    val y = (startY + startVelocity * since + FLAPPY_GRAVITY * since * since / 2) * stage.height
    val velocity = startVelocity + FLAPPY_GRAVITY * since
    val x = lerp(-0.06f * stage.width, 1.06f * stage.width, t / 6.5f)
    // Нос вверх после взмаха, вниз при падении.
    val tilt = (velocity * 60f).coerceIn(-25f, 70f)
    val frames = SceneAssets.bird
    val frame = when ((t * 12).toInt() % 4) {
        0 -> 0
        2 -> 2
        else -> 1
    }
    val bmp = frames[frame]
    val scale = FLAPPY_SCALE * u
    c.save()
    c.rotate(tilt, x, y)
    bitmap(c, bmp, x, y + bmp.height * scale / 2, scale)
    c.restore()
}

// ---------------------------------------------------------------- 19. Doodle Jump

private const val DOODLE_HOPS = 10
private const val DOODLE_HOP_S = 0.5f
private const val DOODLE_HOP_DURATION = DOODLE_HOPS * DOODLE_HOP_S + 0.6f
private const val DOODLE_SCALE = 0.45f
private const val DOODLE_DEPTH_STEP = 0.075f // расстояние между соседними платформами по глубине дороги

/**
 * Персонаж прыгает по платформам на дороге от нижнего края экрана к горизонту.
 * lanes — полоса (в лучах сетки) каждой из DOODLE_HOPS + 1 платформ.
 */
private fun doodleHops(lanes: FloatArray): SceneRender = { c, t ->
    val u = stage.unit
    fun depth(i: Int) = 1f - i * DOODLE_DEPTH_STEP
    val fade = (255 * clamp01((DOODLE_HOP_DURATION - t) / 0.4f)).toInt()
    // Платформы — от дальней к ближней.
    val platform = SceneAssets.platform
    for (i in lanes.indices.reversed()) {
        val s = depth(i)
        val scale = DOODLE_SCALE * u * stage.roadScale(s)
        bitmap(c, platform, stage.roadX(lanes[i], s), stage.roadY(s) + platform.height * scale * 0.6f, scale, alpha = fade, smooth = true)
    }
    val hop = min((t / DOODLE_HOP_S).toInt(), DOODLE_HOPS)
    val q = if (hop < DOODLE_HOPS) (t - hop * DOODLE_HOP_S) / DOODLE_HOP_S else 0f
    val next = min(hop + 1, DOODLE_HOPS)
    val s = lerp(depth(hop), depth(next), q)
    val k = stage.roadScale(s)
    val x = stage.roadX(lerp(lanes[hop], lanes[next], q), s)
    val lift = sin(PI_F * q) * 16 * u * k
    val pushing = hop == DOODLE_HOPS || q < 0.12f || q > 0.92f
    // Смотрит в сторону бокового смещения; при прыжке прямо — вправо.
    val facingLeft = lanes[next] < lanes[hop] || (lanes[next] == lanes[hop] && hop > 0 && lanes[hop] < lanes[hop - 1])
    val body = if (pushing) SceneAssets.doodlerPush else SceneAssets.doodler
    bitmap(c, body, x, stage.roadY(s) - lift, DOODLE_SCALE * u * k, flip = facingLeft, alpha = fade, smooth = true)
}

/** Персонаж с реактивным ранцем взлетает снизу вверх в случайном месте экрана. */
private val doodleJetpack: SceneRender = { c, t ->
    val u = stage.unit
    val scale = 0.5f * u
    val body = SceneAssets.doodler
    val facingLeft = random(1) < 0.5f
    val x = lerp(stage.width * 0.15f, stage.width * 0.85f, random(0)) + sin(t * 9f) * 1.2f * u
    val progress = (t / 4.2f).pow(1.6f)
    val bottom = lerp(stage.height + body.height * scale * 1.6f, -body.height * scale * 0.2f, progress)
    // Ранец за спиной: смещения из исходной игры (в пикселях спрайта персонажа 62×60).
    val left = x - body.width * scale / 2
    val top = bottom - body.height * scale
    val jet = SceneAssets.jetpack[(t * 15).toInt() % SceneAssets.jetpack.size]
    val jetCenterX = if (facingLeft) left + (35 + 16) * scale else left + (-5 + 16) * scale
    bitmap(c, body, x, bottom, scale, flip = facingLeft, smooth = true)
    bitmap(c, jet, jetCenterX, top + (20 + 62) * scale, scale, flip = !facingLeft, smooth = true)
}
