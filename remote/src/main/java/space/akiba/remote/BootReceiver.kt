package space.akiba.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.os.UserManagerCompat

/**
 * Запускает службу пульта при включении проектора и после обновления приложения:
 * пульт должен работать, даже если приложение akiba ещё ни разу не открывали.
 * При включении проектора служба ещё и открывает экран akiba — вместо лаунчера прошивки.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Экран akiba — только по LOCKED_BOOT_COMPLETED: BOOT_COMPLETED приходит позже (до трёх
            // минут после загрузки), к этому времени на проекторе уже могли открыть что-то другое.
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // Сообщение приходит до «разблокировки» пользователя, а до неё система не находит
                // ни службу пульта, ни экран akiba (они не directBootAware) — ждём её.
                val pending = goAsync()
                whenUnlocked(context.applicationContext, UNLOCK_TRIES) {
                    RemoteService.start(context.applicationContext, openAkiba = true)
                    pending.finish()
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                RemoteService.start(context) // повторный старт уже запущенной службы ничего не делает
        }
    }

    private fun whenUnlocked(context: Context, tries: Int, action: () -> Unit) {
        if (tries <= 0 || UserManagerCompat.isUserUnlocked(context)) {
            action()
        } else {
            main.postDelayed({ whenUnlocked(context, tries - 1, action) }, UNLOCK_POLL_MS)
        }
    }

    private companion object {
        val main = Handler(Looper.getMainLooper())
        const val UNLOCK_POLL_MS = 250L
        // Не дольше 8 с: на обработку сообщения система даёт получателю 10 с.
        const val UNLOCK_TRIES = 32
    }
}
