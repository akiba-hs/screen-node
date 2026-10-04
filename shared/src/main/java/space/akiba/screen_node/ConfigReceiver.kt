package space.akiba.screen_node

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Смена адреса сервера и токена проектора — только через adb:
 *
 *   adb shell am broadcast -f 0x20 -a space.akiba.SET_SERVER -n <пакет>/space.akiba.screen_node.ConfigReceiver \
 *       --es server 192.168.8.115:8080 [--es token <токен>]
 *
 * В манифесте получатель защищён разрешением android.permission.DUMP: оно есть у оболочки adb,
 * а обычным приложениям его не выдать без того же adb — значит, подменить сервер на проекторе
 * снаружи нельзя. Флаг -f 0x20 (FLAG_INCLUDE_STOPPED_PACKAGES) нужен для ещё не запускавшегося
 * приложения. Результат виден в выводе am broadcast (data=…).
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ServerConfig.ACTION_SET) return
        val server = intent.getStringExtra(ServerConfig.EXTRA_SERVER)
        val token = intent.getStringExtra(ServerConfig.EXTRA_TOKEN)
        if (server == null && token == null) {
            setResult(Activity.RESULT_CANCELED, "нужен --es server хост:порт и/или --es token …", null)
            return
        }
        if (!ServerConfig.save(context, server, token)) {
            setResult(Activity.RESULT_CANCELED, "некорректный адрес (нужно хост:порт) или токен", null)
            return
        }
        Log.i(TAG, "сервер изменён через adb: ${server ?: "(прежний)"}${if (token != null) ", токен обновлён" else ""}")
        setResult(Activity.RESULT_OK, "ok: server=${server ?: "(прежний)"}", null)
    }

    private companion object {
        const val TAG = "ProjectorConfig"
    }
}
