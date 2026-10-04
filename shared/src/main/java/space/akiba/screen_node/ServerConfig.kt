package space.akiba.screen_node

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Адрес сервера (хост:порт) и токен проектора — общие для экрана akiba и службы пульта.
 *
 * По умолчанию берутся из сборки (значения передаёт приложение: у каждого модуля свой
 * BuildConfig), а поменять их можно только через adb — широковещательным сообщением
 * [ConfigReceiver] (его может отправить лишь оболочка adb, см. install.sh).
 * Настройка хранится в данных приложения и переживает обновления.
 */
class ServerConfig(context: Context, private val defaultServer: String, private val defaultToken: String) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Хост и порт HTTP сервера, например 192.168.8.115:8080. */
    val server: String get() = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: defaultServer

    /** Токен проектора (секрет, общий с сервером). */
    val token: String get() = prefs.getString(KEY_TOKEN, null) ?: defaultToken

    /** WebSocket сигналинга экрана akiba. */
    val signalingUrl: String get() = "ws://$server/ws"

    /** WebSocket связи службы пульта с сервером. */
    val agentUrl: String get() = "ws://$server/remote/ws"

    /** Корень HTTP сервера со слешем на конце: страница трансляции, обновления, API. */
    val httpBase: String get() = "http://$server/"

    companion object {
        const val ACTION_SET = "space.akiba.SET_SERVER"
        const val EXTRA_SERVER = "server"
        const val EXTRA_TOKEN = "token"
        private const val PREFS = "server"
        private const val KEY_SERVER = "server"
        private const val KEY_TOKEN = "token"

        val SERVER_RE = Regex("^[A-Za-z0-9.-]{1,253}:[0-9]{1,5}$")
        val TOKEN_RE = Regex("^[A-Za-z0-9._~-]{0,128}$")

        private val main = Handler(Looper.getMainLooper())
        private val listeners = LinkedHashSet<() -> Unit>()

        /** Подписка на смену настройки (колбэк — в главном потоке). */
        fun addListener(l: () -> Unit) = listeners.add(l)

        fun removeListener(l: () -> Unit) = listeners.remove(l)

        /** Сохраняет настройку; null — не менять. false — значение некорректно. */
        fun save(context: Context, server: String?, token: String?): Boolean {
            if (server != null && !SERVER_RE.matches(server)) return false
            if (token != null && !TOKEN_RE.matches(token)) return false
            val edit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            server?.let { edit.putString(KEY_SERVER, it) }
            token?.let { edit.putString(KEY_TOKEN, it) }
            edit.commit()
            main.post { listeners.toList().forEach { it() } }
            return true
        }
    }
}
