package space.akiba.remote

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Подтверждение установки обновления без человека — для прошивок, где служба пульта не может
 * стать владельцем устройства (на этом проекторе нет функции android.software.device_admin).
 *
 * Без владельца устройства система показывает окно «Установить обновление?» установщика
 * пакетов. Эта служба специальных возможностей нажимает в нём «Установить» (а затем «Готово»),
 * но только:
 *  - в окне установщика пакетов (packageNames в res/xml/update_confirm.xml — других окон
 *    служба не видит);
 *  - пока [Updater] ждёт подтверждения обновления, которое уже проверил по подписи сервера,
 *    хэшу и сертификату (см. [awaiting]).
 * Включается только на время установки ([enable]) и сразу выключается ([disable]); включать её
 * служба пульта может сама — у неё есть WRITE_SECURE_SETTINGS (выдаётся через adb).
 */
class UpdateConfirmService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    // Кнопка «Установить» бывает недоступна первые мгновения (защита от подмены нажатий):
    // повторяем попытку, пока окно открыто.
    private val retry = Runnable { tryConfirm() }

    override fun onServiceConnected() {
        Log.i(TAG, "подтверждение обновлений: служба включена")
        tryConfirm()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == INSTALLER) tryConfirm()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        main.removeCallbacks(retry)
        super.onDestroy()
    }

    private fun tryConfirm() {
        main.removeCallbacks(retry)
        if (awaiting == null) return
        val root = rootInActiveWindow ?: run {
            main.postDelayed(retry, RETRY_MS)
            return
        }
        if (root.packageName != INSTALLER) return
        for (id in BUTTONS) {
            val button = root.findAccessibilityNodeInfosByViewId("$INSTALLER:id/$id").firstOrNull() ?: continue
            if (button.isEnabled && button.isVisibleToUser) {
                Log.i(TAG, "подтверждение обновлений: нажимаю «$id» для $awaiting")
                button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
        main.postDelayed(retry, RETRY_MS)
    }

    companion object {
        private const val TAG = "ProjectorRemote"
        private const val INSTALLER = "com.android.packageinstaller"
        // «Установить» в окне подтверждения и «Готово» в окне результата.
        private val BUTTONS = listOf("ok_button", "done_button")
        private const val RETRY_MS = 500L

        /** Пакет, обновление которого ждёт подтверждения; null — ничего не нажимать. */
        @Volatile var awaiting: String? = null

        /** Включить службу (на время установки). false — нет права менять настройки. */
        fun enable(context: Context): Boolean = try {
            AccessibilitySwitch.enable(context, UpdateConfirmService::class.java)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "подтверждение обновлений: нет права WRITE_SECURE_SETTINGS")
            false
        }

        /** Выключить службу (установка закончилась или служба пульта перезапустилась). */
        fun disable(context: Context) {
            awaiting = null
            try {
                if (AccessibilitySwitch.disable(context, UpdateConfirmService::class.java)) {
                    Log.i(TAG, "подтверждение обновлений: служба выключена")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "подтверждение обновлений: не удалось выключить службу")
            }
        }
    }
}
