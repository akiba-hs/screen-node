package space.akiba.remote

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Проверка пропуска пульта, выданного сервером (см. internal/remote/ticket.go сервера):
 * "1.<срок, unix>.<ключ пульта>.<подпись>", подпись — HMAC-SHA256 на токене проектора. Токен
 * знают только сервер и проектор, так что пропуск может выдать только сервер, а проверить его
 * проектор может сам, без связи с сервером.
 */
object PultTicket {
    private val KEY_RE = Regex("^[0-9a-f]{0,64}$")

    /** Ключ пульта из действующего пропуска (может быть пустым); null — пропуск недействителен. */
    fun verify(token: String, ticket: String, nowMs: Long = System.currentTimeMillis()): String? {
        val parts = ticket.split('.')
        if (parts.size != 4 || parts[0] != "1" || !KEY_RE.matches(parts[2])) return null
        val exp = parts[1].toLongOrNull() ?: return null
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(token.toByteArray(), "HmacSHA256")) }
        val want = Base64.encodeToString(
            mac.doFinal("proector-pult-v1.${parts[1]}.${parts[2]}".toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        if (!MessageDigest.isEqual(want.toByteArray(), parts[3].toByteArray())) return null
        if (nowMs / 1000 > exp) return null
        return parts[2]
    }
}
