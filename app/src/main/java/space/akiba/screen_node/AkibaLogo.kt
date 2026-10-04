package space.akiba.screen_node

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser

/**
 * Логотип хакспейса из res/raw/akiba_logo.svg.
 *
 * SVG простой: контуры `<path fill=… d=…>` с fill-rule evenodd, поэтому разбираем его сами,
 * без SVG-библиотек. Тёмные мазки кисти (#201F24) на чёрном экране проектора невидимы —
 * рисуем их светлым, красные буквы AKIBA остаются фирменного цвета.
 */
class AkibaLogo private constructor(
    val width: Float,
    val height: Float,
    private val shapes: List<Pair<Path, Int>>,
) {
    /** Рисует логотип в прямоугольник dst (пропорции сохраняются, по центру). */
    fun draw(canvas: Canvas, dst: RectF, paint: Paint, tint: Int? = null) {
        val scale = minOf(dst.width() / width, dst.height() / height)
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dst.centerX() - width * scale / 2, dst.centerY() - height * scale / 2)
        }
        val p = Path()
        for ((path, color) in shapes) {
            path.transform(m, p)
            paint.color = tint ?: color
            canvas.drawPath(p, paint)
        }
    }

    companion object {
        /** Фирменный красный логотипа. */
        const val RED = 0xFFBC273A.toInt()
        private const val DARK = 0xFF201F24.toInt()
        private const val LIGHT = 0xFFF2F2F2.toInt()

        private val VIEW_BOX = Regex("""viewBox="([\d.\s-]+)"""")
        private val PATH = Regex("""<path\s+fill="(#[0-9A-Fa-f]{6})"\s+d="([^"]+)"""")

        fun load(context: Context): AkibaLogo {
            val svg = context.resources.openRawResource(R.raw.akiba_logo).bufferedReader().use { it.readText() }
            val box = VIEW_BOX.find(svg)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))?.map { it.toFloat() }
                ?: listOf(0f, 0f, 674f, 244f)
            val shapes = PATH.findAll(svg).map { m ->
                val color = Color.parseColor(m.groupValues[1]).let { if (it == DARK) LIGHT else it }
                val path = PathParser.createPathFromPathData(m.groupValues[2]).apply {
                    fillType = Path.FillType.EVEN_ODD
                    if (box[0] != 0f || box[1] != 0f) offset(-box[0], -box[1])
                }
                path to color
            }.toList()
            return AkibaLogo(box[2], box[3], shapes)
        }
    }
}
