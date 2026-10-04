package space.akiba.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Маленький HTTP- и WebSocket-сервер службы пульта: страница пульта (assets/web) и приём команд
 * со страниц пульта напрямую, без сервера трансляций — так задержка минимальна, и пульт не
 * зависит от сервера.
 *
 *  - GET / и файлы страницы — из assets/web;
 *  - GET /ws с Upgrade: websocket — соединение страницы пульта (текстовые JSON-сообщения).
 *
 * Принимаются только WebSocket-запросы со своей же страницы (заголовок Origin совпадает с Host):
 * чужой сайт, открытый в браузере участника, не сможет управлять проектором. Допуск к командам
 * (пропуск сервера) проверяет [RemoteService].
 *
 * Каждое соединение — свой поток чтения и свой поток записи (сеть нельзя трогать из главного
 * потока). Колбэки [Listener] вызываются в главном потоке.
 */
class LocalServer(context: Context, private val port: Int, private val listener: Listener) {
    interface Listener {
        fun onOpen(conn: Conn)
        fun onMessage(conn: Conn, text: String)
        fun onClose(conn: Conn)
    }

    private val assets = context.applicationContext.assets
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool { Thread(it, "pult-http").apply { isDaemon = true } }
    private val nextId = AtomicLong()
    private val active = AtomicInteger()
    @Volatile private var socket: ServerSocket? = null

    fun start() {
        if (socket != null) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(port), BACKLOG)
        } catch (e: IOException) {
            Log.e(TAG, "страница пульта: порт $port занят", e)
            ss.close()
            return
        }
        socket = ss
        pool.execute {
            Log.i(TAG, "страница пульта: http://<IP проектора>:$port/")
            while (!ss.isClosed) {
                val s = try {
                    ss.accept()
                } catch (e: IOException) {
                    break
                }
                if (active.incrementAndGet() > MAX_CONNECTIONS) {
                    active.decrementAndGet()
                    s.close()
                    continue
                }
                pool.execute {
                    try {
                        serve(s)
                    } catch (e: Exception) {
                        Log.d(TAG, "страница пульта: соединение закрыто: ${e.message}")
                    } finally {
                        active.decrementAndGet()
                        runCatching { s.close() }
                    }
                }
            }
        }
    }

    fun stop() {
        socket?.close()
        socket = null
        pool.shutdownNow()
    }

    // ---------- HTTP ----------

    private class Request(val method: String, val path: String, val headers: Map<String, String>)

    private fun serve(s: Socket) {
        s.soTimeout = HTTP_TIMEOUT_MS
        s.tcpNoDelay = true
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        val req = readRequest(input) ?: return
        val upgrade = req.headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
        when {
            req.method == "GET" && req.path == "/ws" && upgrade -> websocket(s, input, out, req)
            req.method == "GET" || req.method == "HEAD" -> static(out, req)
            else -> respond(out, 405, "text/plain; charset=utf-8", "Метод не поддерживается".toByteArray())
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            if (head.size() > MAX_HEAD) return null
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return Request(parts[0], parts[1].substringBefore('?').substringBefore('#'), headers)
    }

    private fun static(out: OutputStream, req: Request) {
        val name = if (req.path == "/") "index.html" else req.path.removePrefix("/")
        if (!STATIC_NAME.matches(name)) {
            respond(out, 404, "text/plain; charset=utf-8", "Не найдено".toByteArray())
            return
        }
        val body = try {
            assets.open("web/$name").use { it.readBytes() }
        } catch (e: IOException) {
            respond(out, 404, "text/plain; charset=utf-8", "Не найдено".toByteArray())
            return
        }
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
        respond(out, 200, type, if (req.method == "HEAD") ByteArray(0) else body, body.size)
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray, length: Int = body.size) {
        val reason = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Method Not Allowed"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: $length\r\n" +
            // Страница меняется с обновлением приложения — кэш только мешает.
            "Cache-Control: no-cache\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    // ---------- WebSocket (RFC 6455) ----------

    private fun websocket(s: Socket, input: InputStream, out: OutputStream, req: Request) {
        val key = req.headers["sec-websocket-key"]
        val origin = req.headers["origin"]
        val host = req.headers["host"]
        // Только своя страница: Origin — это http://<тот же хост:порт>.
        if (key == null || host == null || (origin != null && !origin.equals("http://$host", ignoreCase = true))) {
            respond(out, 403, "text/plain; charset=utf-8", "Только со страницы пульта".toByteArray())
            return
        }
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.ISO_8859_1)),
            Base64.NO_WRAP,
        )
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        )
        out.flush()
        // Тишина дольше READ_TIMEOUT_MS — соединение мертво (страница шлёт ping каждые несколько секунд).
        s.soTimeout = WS_READ_TIMEOUT_MS
        val conn = Conn(nextId.incrementAndGet(), s, out)
        pool.execute { conn.writeLoop() }
        main.post { listener.onOpen(conn) }
        try {
            readLoop(conn, input)
        } catch (e: SocketTimeoutException) {
            Log.i(TAG, "страница пульта ${conn.id}: нет данных ${WS_READ_TIMEOUT_MS / 1000} с — отключаю")
        } catch (e: IOException) {
            Log.d(TAG, "страница пульта ${conn.id}: ${e.message}")
        } finally {
            conn.close()
            main.post { listener.onClose(conn) }
        }
    }

    private fun readLoop(conn: Conn, input: InputStream) {
        val message = ByteArrayOutputStream()
        var collecting = false
        while (true) {
            val b0 = readByte(input)
            val b1 = readByte(input)
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            val masked = b1 and 0x80 != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((readByte(input) shl 8) or readByte(input)).toLong()
            else if (len == 127L) {
                len = 0
                repeat(8) { len = (len shl 8) or readByte(input).toLong() }
            }
            // Клиент обязан маскировать кадры; огромные кадры — мусор или атака.
            if (!masked || len > MAX_MESSAGE) throw IOException("некорректный кадр")
            val mask = ByteArray(4).also { readFully(input, it) }
            val payload = ByteArray(len.toInt()).also { readFully(input, it) }
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (opcode) {
                OP_TEXT, OP_CONT -> {
                    if (opcode == OP_TEXT) {
                        message.reset()
                        collecting = true
                    } else if (!collecting) {
                        throw IOException("продолжение без начала")
                    }
                    message.write(payload)
                    if (message.size() > MAX_MESSAGE) throw IOException("слишком длинное сообщение")
                    if (fin) {
                        collecting = false
                        val text = message.toString(Charsets.UTF_8.name())
                        main.post { listener.onMessage(conn, text) }
                    }
                }
                OP_PING -> conn.enqueue(Frame(OP_PONG, payload))
                OP_PONG -> Unit
                OP_CLOSE -> {
                    conn.enqueue(Frame(OP_CLOSE, payload.take(2).toByteArray()))
                    return
                }
                else -> throw IOException("неизвестный кадр $opcode")
            }
        }
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    internal class Frame(val opcode: Int, val payload: ByteArray)

    /** Соединение страницы пульта. [send] и [close] можно вызывать из любого потока. */
    class Conn internal constructor(val id: Long, private val socket: Socket, private val out: OutputStream) {
        private val queue = ArrayBlockingQueue<Frame>(SEND_QUEUE)
        @Volatile private var closed = false

        /** Отправить текст; false — соединение закрыто или очередь переполнена. */
        fun send(text: String): Boolean = enqueue(Frame(OP_TEXT, text.toByteArray(Charsets.UTF_8)))

        /** Закрыть после отправки уже поставленного в очередь. */
        fun close() {
            if (closed) return
            closed = true
            queue.offer(Frame(OP_CLOSE, byteArrayOf(0x03, 0xE8.toByte()))) // 1000 — штатное закрытие
        }

        internal fun enqueue(f: Frame): Boolean = !closed && queue.offer(f)

        internal fun writeLoop() {
            // Ping раз в PING_MS при любом потоке исходящих: ответный pong браузера держит
            // соединение живым для потока чтения (его таймаут — WS_READ_TIMEOUT_MS).
            var lastPing = System.nanoTime()
            try {
                while (true) {
                    val sincePing = (System.nanoTime() - lastPing) / 1_000_000
                    val f = if (sincePing >= PING_MS) null else queue.poll(PING_MS - sincePing, TimeUnit.MILLISECONDS)
                    if (f == null) {
                        write(Frame(OP_PING, ByteArray(0)))
                        lastPing = System.nanoTime()
                        continue
                    }
                    write(f)
                    if (f.opcode == OP_CLOSE) break
                }
            } catch (e: Exception) {
                // соединение оборвалось — поток чтения тоже завершится
            } finally {
                closed = true
                runCatching { socket.close() }
            }
        }

        private fun write(f: Frame) {
            val n = f.payload.size
            val head = when {
                n < 126 -> byteArrayOf((0x80 or f.opcode).toByte(), n.toByte())
                n < 65536 -> byteArrayOf((0x80 or f.opcode).toByte(), 126, (n shr 8).toByte(), n.toByte())
                else -> ByteArray(10).also {
                    it[0] = (0x80 or f.opcode).toByte()
                    it[1] = 127
                    for (i in 0 until 8) it[9 - i] = (n.toLong() shr (8 * i)).toByte()
                }
            }
            out.write(head)
            out.write(f.payload)
            out.flush()
        }
    }

    private companion object {
        const val TAG = "ProjectorRemote"
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OP_CONT = 0x0
        const val OP_TEXT = 0x1
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
        const val BACKLOG = 16
        const val MAX_CONNECTIONS = 32
        const val MAX_HEAD = 16 * 1024
        const val MAX_MESSAGE = 128 * 1024L // самое длинное — вставка из буфера по 256 символов, с запасом
        const val HTTP_TIMEOUT_MS = 10_000
        const val WS_READ_TIMEOUT_MS = 20_000
        const val PING_MS = 10_000L
        // Движения мыши идут ~60 раз в секунду: очередь держит несколько секунд потока.
        const val SEND_QUEUE = 512
        val STATIC_NAME = Regex("^[a-z0-9_-]+\\.(html|js|css|svg|png)$")
    }
}
