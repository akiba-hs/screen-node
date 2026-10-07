package space.akiba.remote

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Включение и выключение своих служб специальных возможностей через настройки системы.
 * Нужно право WRITE_SECURE_SETTINGS (выдаётся через adb) — без него методы бросают
 * SecurityException. Чужие службы (например, HiRMService прошивки) остаются включёнными.
 */
internal object AccessibilitySwitch {
    fun enable(context: Context, service: Class<out AccessibilityService>) {
        val r = context.contentResolver
        val list = enabled(context)
        val id = id(context, service)
        if (id !in list) {
            Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (list + id).joinToString(":"))
        }
        Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    /** false — служба и не была включена. */
    fun disable(context: Context, service: Class<out AccessibilityService>): Boolean {
        val list = enabled(context)
        val id = id(context, service)
        if (id !in list) return false
        val rest = list - id
        val r = context.contentResolver
        Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, rest.joinToString(":"))
        if (rest.isEmpty()) Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        return true
    }

    private fun id(context: Context, service: Class<out AccessibilityService>) =
        ComponentName(context, service).flattenToString()

    private fun enabled(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() }
}
