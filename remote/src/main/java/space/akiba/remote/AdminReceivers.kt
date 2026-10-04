package space.akiba.remote

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Администратор устройства службы пульта. Через adb её делают владельцем устройства
 * (install.sh: dpm set-device-owner space.akiba.remote/.AdminReceiver) — тогда
 * обновления с сервера ставятся без подтверждения на экране. Никаких ограничений служба при
 * этом не включает.
 */
class AdminReceiver : DeviceAdminReceiver()

/**
 * Служебные команды — только из оболочки adb (получатель защищён разрешением DUMP):
 *
 *   adb shell am broadcast -a space.akiba.remote.CHECK_UPDATE -n space.akiba.remote/.ShellReceiver
 *       — проверить обновления на сервере сейчас;
 *   adb shell am broadcast -a space.akiba.remote.CLEAR_OWNER -n space.akiba.remote/.ShellReceiver
 *       — перестать быть владельцем устройства (иначе приложение нельзя удалить).
 */
class ShellReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CHECK_UPDATE -> {
                val service = RemoteService.instance
                if (service == null) {
                    RemoteService.start(context)
                    setResult(Activity.RESULT_CANCELED, "служба пульта не была запущена — запущена, повторите", null)
                } else {
                    service.checkUpdates()
                    setResult(Activity.RESULT_OK, "проверка обновлений запущена (см. logcat -s ProjectorRemote)", null)
                }
            }
            ACTION_CLEAR_OWNER -> {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (!dpm.isDeviceOwnerApp(context.packageName)) {
                    setResult(Activity.RESULT_OK, "служба пульта и так не владелец устройства", null)
                    return
                }
                @Suppress("DEPRECATION")
                dpm.clearDeviceOwnerApp(context.packageName)
                Log.i(TAG, "служба пульта больше не владелец устройства")
                setResult(Activity.RESULT_OK, "ok: владелец устройства снят", null)
            }
        }
    }

    private companion object {
        const val TAG = "ProjectorRemote"
        const val ACTION_CHECK_UPDATE = "space.akiba.remote.CHECK_UPDATE"
        const val ACTION_CLEAR_OWNER = "space.akiba.remote.CLEAR_OWNER"
    }
}
