package space.akiba.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Запускает службу пульта при включении проектора и после обновления приложения:
 * пульт должен работать, даже если приложение akiba ещё ни разу не открывали.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                RemoteService.start(context) // повторный старт уже запущенной службы ничего не делает
        }
    }
}
