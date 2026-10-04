package space.akiba.screen_node

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * WebSocket-клиент сигналинга с автопереподключением (экспоненциальная задержка с джиттером).
 * Все колбэки Listener вызываются в главном потоке.
 * Им же пользуется служба пульта (роль "agent" на /remote/ws, см. RemoteService).
 * helloExtra дополняет приветствие (служба пульта сообщает в нём порт своей страницы).
 */
class SignalingClient(
    private val url: String,
    private val token: String,
    // Текущая сессия (0 — нет): сервер продолжит её после обрыва, не начиная переговоры заново.
    private val resumeSession: () -> Long,
    private val listener: Listener,
    private val role: String = "projector",
    private val helloExtra: (JSONObject) -> Unit = {},
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onMessage(msg: JSONObject)
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // Пинги ловят «тихо умершее» соединение (например, сервер пропал из сети).
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    // true после onOpen и отправки hello. До этого send() ничего не шлёт: OkHttp поставил бы
    // сообщение в очередь раньше hello, и сервер закрыл бы соединение.
    private var open = false
    private var running = false
    private var attempt = 0
    // Поколение сокета: колбэки от старых сокетов игнорируются.
    private var generation = 0
    private val reconnect = Runnable { connect() }

    fun start() {
        if (running) return
        running = true
        attempt = 0
        connect()
    }

    fun stop() {
        running = false
        generation++
        main.removeCallbacks(reconnect)
        ws?.close(1000, "stop")
        ws = null
        open = false
    }

    fun send(msg: JSONObject): Boolean = if (open) ws?.send(msg.toString()) ?: false else false

    private fun connect() {
        if (!running) return
        val gen = ++generation
        Log.i(TAG, "подключение к $url (попытка ${attempt + 1})")
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "некорректный адрес сервера: $url", e)
            listener.onDisconnected("некорректный адрес сервера")
            return
        }
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = post(gen) {
                attempt = 0
                val hello = JSONObject().put("type", "hello").put("role", role)
                if (token.isNotEmpty()) hello.put("token", token)
                resumeSession().takeIf { it != 0L }?.let { hello.put("session", it) }
                helloExtra(hello)
                webSocket.send(hello.toString())
                open = true
                Log.i(TAG, "подключено к серверу ($role)")
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = post(gen) {
                val msg = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    Log.w(TAG, "не JSON: $text")
                    return@post
                }
                listener.onMessage(msg)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post(gen) {
                dropped("закрыто сервером ($code $reason)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post(gen) {
                dropped(t.message ?: t.javaClass.simpleName)
            }
        })
    }

    private fun post(gen: Int, block: () -> Unit) {
        main.post { if (running && gen == generation) block() }
    }

    private fun dropped(reason: String) {
        generation++
        ws = null
        open = false
        val delay = backoffMs(attempt++)
        Log.w(TAG, "соединение потеряно: $reason; повтор через $delay мс")
        listener.onDisconnected(reason)
        main.postDelayed(reconnect, delay)
    }

    private fun backoffMs(attempt: Int): Long {
        val base = (500L shl attempt.coerceAtMost(5)).coerceAtMost(MAX_BACKOFF_MS)
        return base / 2 + Random.nextLong(base / 2 + 1)
    }

    companion object {
        private const val TAG = "ProjectorSignaling"
        private const val MAX_BACKOFF_MS = 10_000L
    }
}
