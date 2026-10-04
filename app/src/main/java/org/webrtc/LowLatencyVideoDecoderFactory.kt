// Файл лежит в пакете org.webrtc намеренно: MediaCodecWrapper, MediaCodecWrapperFactory и
// AndroidVideoDecoder в libwebrtc package-private, а нам нужно вмешаться в configure().
package org.webrtc

import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * Фабрика видеодекодеров как DefaultVideoDecoderFactory (аппаратный декодер + программный
 * запасной), но аппаратный MediaCodec настраивается в режим низкой задержки.
 *
 * Если в SPS потока нет VUI bitstream_restriction (так кодирует, например,
 * VideoToolbox в Safari), декодер вправе ждать переупорядочивания кадров и держит каждый
 * кадр до прихода следующего. На Snapdragon 695 это ~60 мс на кадр вместо ~15.
 * KEY_LOW_LATENCY (Android 11+) и вендорские ключи велят отдавать кадр сразу.
 */
class LowLatencyVideoDecoderFactory(
    sharedContext: EglBase.Context?,
    private val lowLatency: Boolean = true,
) : VideoDecoderFactory {
    private val hardware = HardwareVideoDecoderFactory(sharedContext)
    private val software = SoftwareVideoDecoderFactory()
    private val platformSoftware = PlatformSoftwareVideoDecoderFactory(sharedContext)
    private val supported = DefaultVideoDecoderFactory(sharedContext).supportedCodecs

    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? {
        val hw = hardware.createDecoder(info)?.also { if (lowLatency) enableLowLatency(it) }
        val sw = software.createDecoder(info) ?: platformSoftware.createDecoder(info)
        return if (hw != null && sw != null) VideoDecoderFallback(sw, hw) else hw ?: sw
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = supported

    /** Подменяет фабрику MediaCodec внутри AndroidVideoDecoder до вызова initDecode. */
    private fun enableLowLatency(decoder: VideoDecoder) {
        if (decoder !is AndroidVideoDecoder) return
        try {
            val field = AndroidVideoDecoder::class.java.declaredFields
                .first { it.type == MediaCodecWrapperFactory::class.java }
            field.isAccessible = true
            val original = field.get(decoder) as MediaCodecWrapperFactory
            field.set(decoder, MediaCodecWrapperFactory { name -> LowLatencyCodec(original.createByCodecName(name)) })
        } catch (e: Exception) {
            Log.w(TAG, "не удалось включить режим низкой задержки декодера", e)
        }
    }

    private class LowLatencyCodec(private val codec: MediaCodecWrapper) : MediaCodecWrapper by codec {
        override fun configure(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
            val tuned = copyOf(format)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tuned.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            VENDOR_LOW_LATENCY_KEYS.forEach { tuned.setInteger(it, 1) }
            try {
                codec.configure(tuned, surface, crypto, flags)
                Log.i(TAG, "${codecName()}: режим низкой задержки включён")
            } catch (e: RuntimeException) {
                // Кодек отверг ключи — настройка без них. MediaCodec после
                // неудачного configure сам сбрасывается в Uninitialized, повтор допустим.
                Log.w(TAG, "${codecName()}: ключи низкой задержки отвергнуты, обычный режим", e)
                codec.configure(format, surface, crypto, flags)
            }
        }

        private fun codecName(): String = try {
            codec.codecInfo.name
        } catch (e: Exception) {
            "decoder"
        }
    }

    companion object {
        private const val TAG = "ProjectorDecoder"

        /**
         * Копия формата: исходный нужен нетронутым для повторного configure без ключей.
         * Конструктор MediaFormat(MediaFormat) есть только с Android 10; на Android 9 (проектор
         * Wanbo) его вызов даёт NoSuchMethodError в потоке декодера, и libwebrtc роняет
         * приложение. AndroidVideoDecoder кладёт в формат только mime, размеры и (без EGL)
         * color-format — их и переносим.
         */
        private fun copyOf(format: MediaFormat): MediaFormat {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return MediaFormat(format)
            val copy = MediaFormat.createVideoFormat(
                format.getString(MediaFormat.KEY_MIME) ?: return format,
                format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT),
            )
            if (format.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
                copy.setInteger(MediaFormat.KEY_COLOR_FORMAT, format.getInteger(MediaFormat.KEY_COLOR_FORMAT))
            }
            return copy
        }

        /** Ключи низкой задержки распространённых SoC; неизвестные ключи кодеки игнорируют. */
        private val VENDOR_LOW_LATENCY_KEYS = listOf(
            "vendor.qti-ext-dec-low-latency.enable", // Qualcomm
            "vendor.qti-ext-dec-picture-order.enable", // Qualcomm: выдача в порядке декодирования
            "vendor.rtc-ext-dec-low-latency.enable", // MediaTek
            "vendor.low-latency.enable", // Exynos / прочие Codec2
        )
    }
}
