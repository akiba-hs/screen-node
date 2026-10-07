package space.akiba.remote

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Замечает, что на экране появился лаунчер прошивки, — чтобы служба пульта при включении
 * проектора открыла вместо него экран akiba (см. RemoteService.openAkibaInsteadOfHome).
 *
 * Прошивка после загрузки сама фокусирует объектив и ищет сигнал HDMI и только потом открывает
 * лаунчер; если HDMI нашёлся, остаётся экран входа и лаунчер не появляется. Поэтому akiba
 * открывается не по времени, а именно в ответ на появление лаунчера.
 *
 * Служба специальных возможностей: только названия пакетов открывающихся окон, содержимое окон
 * ей недоступно. Включается службой пульта на пару минут после включения проектора
 * (WRITE_SECURE_SETTINGS) и выключается, как только akiba открыт или время вышло.
 */
class HomeWatchService : AccessibilityService() {
    // Лаунчеры: экраны HOME, кроме мастера настройки прошивки (он же ведёт загрузку: фокус,
    // поиск HDMI) и запасного FallbackHome системы (отрицательный приоритет).
    private val launchers: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val wizard = packageManager.queryIntentActivities(Intent(home).addCategory(CATEGORY_SETUP_WIZARD), 0)
            .map { it.activityInfo.packageName }.toSet()
        packageManager.queryIntentActivities(home, 0)
            .filter { it.priority >= 0 && it.activityInfo.packageName !in wizard }
            .map { it.activityInfo.packageName }.toSet()
    }

    override fun onServiceConnected() {
        Log.i(TAG, "экран akiba при включении: жду лаунчер $launchers")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() in launchers) onHome?.invoke()
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "ProjectorRemote"
        private const val CATEGORY_SETUP_WIZARD = "android.intent.category.SETUP_WIZARD"

        /** Вызывается (в главном потоке) каждый раз, когда открылось окно лаунчера. */
        @Volatile var onHome: (() -> Unit)? = null

        /** Включить службу. false — нет права менять настройки. */
        fun enable(context: Context): Boolean = try {
            AccessibilitySwitch.enable(context, HomeWatchService::class.java)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "экран akiba при включении: нет права WRITE_SECURE_SETTINGS")
            false
        }

        fun disable(context: Context) {
            onHome = null
            try {
                AccessibilitySwitch.disable(context, HomeWatchService::class.java)
            } catch (e: SecurityException) {
                Log.w(TAG, "экран akiba при включении: не удалось выключить службу")
            }
        }
    }
}
