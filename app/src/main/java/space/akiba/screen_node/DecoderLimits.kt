package space.akiba.screen_node

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * Ограничения аппаратного видеодекодера проектора.
 *
 * Разрешение экрана не гарантирует, что аппаратный декодер потянет кадр такого размера:
 * например, на Snapdragon 695 экран 2412x1080, а c2.qti.avc.decoder принимает максимум
 * 1920x1088 (стороны можно менять местами). Кадр больше лимита libwebrtc декодирует
 * программно — это ~100 мс на кадр вместо ~12. Поэтому проектор сообщает отправителю
 * лимит, и тот кодирует не больше него (равномерно, с сохранением пропорций).
 *
 * @property longSide максимальная длинная сторона кадра, px
 * @property shortSide максимальная короткая сторона при длинной = longSide, px
 */
data class DecoderLimits(val codec: String, val longSide: Int, val shortSide: Int) {
    companion object {
        private const val TAG = "ProjectorDecoder"

        /** Лимиты первого аппаратного декодера для mime (в порядке предпочтения системы, как у libwebrtc). */
        fun forMime(mime: String = "video/avc"): DecoderLimits? {
            val infos = try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            } catch (e: RuntimeException) {
                Log.w(TAG, "MediaCodecList недоступен", e)
                return null
            }
            for (info in infos) {
                if (info.isEncoder || !isHardware(info)) continue
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val caps = try {
                    info.getCapabilitiesForType(mime).videoCapabilities
                } catch (e: IllegalArgumentException) {
                    null
                } ?: continue
                val long = maxOf(caps.supportedWidths.upper, caps.supportedHeights.upper)
                val short = maxShortSide(caps, long)
                if (short <= 0) continue
                return DecoderLimits(info.name, long, short).also {
                    Log.i(TAG, "аппаратный декодер $mime: ${info.name}, до ${long}x$short")
                }
            }
            Log.w(TAG, "аппаратный декодер $mime не найден")
            return null
        }

        /** Бинарный поиск наибольшей чётной короткой стороны, при которой кадр long x short поддерживается. */
        private fun maxShortSide(caps: MediaCodecInfo.VideoCapabilities, long: Int): Int {
            fun ok(s: Int) = caps.isSizeSupported(long, s) || caps.isSizeSupported(s, long)
            var lo = 0 // заведомо поддерживается (0 — «ничего»)
            var hi = long / 2 // в чётных шагах
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (ok(mid * 2)) lo = mid else hi = mid - 1
            }
            return lo * 2
        }

        private fun isHardware(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
            val n = info.name.lowercase()
            return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."))
        }
    }
}
