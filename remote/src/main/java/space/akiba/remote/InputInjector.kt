package space.akiba.remote

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Нажатия «как с настоящего пульта»: события пишутся прямо в виртуальные устройства ввода ядра
 * (evdev) проектора, и система обрабатывает их точно так же, как сигналы ИК-пульта.
 *
 * На Wanbo X2 Max (Hisilicon, Android 9) ИК-демон `android_ir_user` сам пишет нажатия пульта
 * в виртуальное устройство «Hi keyboard», а для режима «аэромыши» есть «Hi mouse» с настоящим
 * курсором. Узлы /dev/input/event* в этой прошивке открыты на запись всем (rw-rw-rw-), SELinux —
 * permissive, поэтому обычное приложение без root и без adb может писать в них. Работают все
 * кнопки, включая системные (питание, громкость, «Домой», фокус объектива, настройки), в любом
 * приложении и на входах HDMI.
 *
 * Если устройств нет (другая прошивка), [hasKeyboard]/[hasMouse] — false, а служба пульта
 * переходит на запасные способы (см. [RemoteService]).
 *
 * Методы вызываются из главного потока: запись в evdev занимает микросекунды.
 */
class InputInjector {
    private val keyboard = EvdevDevice(KEYBOARD_NAMES)
    private val mouse = EvdevDevice(MOUSE_NAMES)

    val hasKeyboard: Boolean get() = keyboard.ready()
    val hasMouse: Boolean get() = mouse.ready()

    /** Клавиша (Linux-код KEY_*) нажата или отпущена. */
    fun key(code: Int, down: Boolean): Boolean = keyboard.write(intArrayOf(EV_KEY, code, if (down) 1 else 0))

    /** Относительное движение курсора, в «пикселях мыши». */
    fun move(dx: Int, dy: Int): Boolean = mouse.write(intArrayOf(EV_REL, REL_X, dx), intArrayOf(EV_REL, REL_Y, dy))

    /** Колесо: «+» — вверх (так считает Linux). */
    fun wheel(v: Int): Boolean = mouse.write(intArrayOf(EV_REL, REL_WHEEL, v))

    /** Кнопка мыши (BTN_LEFT/BTN_RIGHT/BTN_MIDDLE). */
    fun button(code: Int, down: Boolean): Boolean = mouse.write(intArrayOf(EV_KEY, code, if (down) 1 else 0))

    fun close() {
        keyboard.close()
        mouse.close()
    }

    /** Устройство /dev/input/eventN, найденное по имени из /sys/class/input. */
    private class EvdevDevice(private val names: Set<String>) {
        private var out: FileOutputStream? = null
        private var path: String? = null
        // Неудачные попытки открыть не повторяются чаще раза в RETRY_MS: на других прошивках
        // устройства просто нет, и искать его на каждое нажатие незачем.
        private var lastAttempt = 0L

        fun ready(): Boolean = open() != null

        /** Пишет события одной пачкой и завершает её SYN_REPORT. */
        fun write(vararg events: IntArray): Boolean {
            val stream = open() ?: return false
            val buf = ByteBuffer.allocate((events.size + 1) * EVENT_SIZE).order(ByteOrder.nativeOrder())
            for (e in events) put(buf, e[0], e[1], e[2])
            put(buf, EV_SYN, SYN_REPORT, 0)
            return try {
                stream.write(buf.array())
                true
            } catch (e: IOException) {
                Log.w(TAG, "запись в $path не удалась: ${e.message}")
                close()
                false
            }
        }

        fun close() {
            try {
                out?.close()
            } catch (_: IOException) {
            }
            out = null
        }

        private fun open(): FileOutputStream? {
            out?.let { return it }
            val now = SystemClock.elapsedRealtime()
            if (lastAttempt != 0L && now - lastAttempt < RETRY_MS) return null
            lastAttempt = now
            val dev = find() ?: run {
                Log.w(TAG, "устройство ввода ${names.joinToString()} не найдено")
                return null
            }
            return try {
                FileOutputStream(dev).also {
                    out = it
                    path = dev
                    Log.i(TAG, "устройство ввода ${names.joinToString()}: $dev")
                }
            } catch (e: IOException) {
                Log.w(TAG, "нет доступа на запись к $dev: ${e.message}")
                null
            }
        }

        private fun find(): String? {
            val nodes = File("/sys/class/input").listFiles { f -> f.name.startsWith("event") } ?: return null
            return nodes.sortedBy { it.name }.firstOrNull { node ->
                val name = try {
                    File(node, "device/name").readText().trim()
                } catch (_: IOException) {
                    ""
                }
                name in names
            }?.let { "/dev/input/${it.name}" }
        }

        // struct input_event: timeval (время ставит ядро — пишем нули), u16 type, u16 code, s32 value.
        // Размер timeval зависит от разрядности процесса: ядро разбирает запись по ней же.
        private fun put(buf: ByteBuffer, type: Int, code: Int, value: Int) {
            repeat(EVENT_SIZE - 8) { buf.put(0) }
            buf.putShort(type.toShort())
            buf.putShort(code.toShort())
            buf.putInt(value)
        }
    }

    companion object {
        private const val TAG = "ProjectorRemote"
        private const val RETRY_MS = 5_000L

        // Имена виртуальных устройств Hisilicon (см. /proc/bus/input/devices на проекторе).
        private val KEYBOARD_NAMES = setOf("Hi keyboard")
        private val MOUSE_NAMES = setOf("Hi mouse")

        private val EVENT_SIZE = if (Process.is64Bit()) 24 else 16

        private const val EV_SYN = 0
        private const val EV_KEY = 1
        private const val EV_REL = 2
        private const val SYN_REPORT = 0
        private const val REL_X = 0
        private const val REL_Y = 1
        private const val REL_WHEEL = 8

        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112
    }
}
