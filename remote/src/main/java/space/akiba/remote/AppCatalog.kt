package space.akiba.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Запускаемые приложения проектора для страницы пульта: те, у которых есть экран запуска
 * (LAUNCHER или LEANBACK_LAUNCHER). Название — как в лаунчере, но на языке страницы пульта
 * (если у приложения есть такой перевод), значок — тот же, что в лаунчере.
 *
 * Значки кэшируются по пакету и времени его обновления: повторный список собирается быстро.
 * Методы потокобезопасны; [toJson] стоит вызывать не в главном потоке.
 */
class AppCatalog(private val context: Context) {
    private val pm = context.packageManager
    private val icons = HashMap<String, Pair<Long, String>>()

    /** Экраны запуска: по одному на пакет, обычный лаунчер важнее Leanback. */
    fun launchable(): Map<String, ResolveInfo> {
        val result = LinkedHashMap<String, ResolveInfo>()
        for (category in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                result.putIfAbsent(ri.activityInfo.packageName, ri)
            }
        }
        return result
    }

    /** Intent запуска пакета; null — у пакета нет экрана запуска (или его нет вовсе). */
    fun intentFor(pkg: String): Intent? {
        val ri = launchable()[pkg] ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
    }

    /** [{pkg, label, icon: PNG в base64}], по алфавиту названий. */
    fun toJson(lang: String): JSONArray {
        val locale = lang.takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it) }
        val apps = launchable().values.map { ri ->
            val pkg = ri.activityInfo.packageName
            Triple(pkg, label(ri, locale), icon(ri))
        }.sortedBy { it.second.lowercase(locale ?: Locale.getDefault()) }
        return JSONArray().apply {
            for ((pkg, label, icon) in apps) put(JSONObject().put("pkg", pkg).put("label", label).put("icon", icon))
        }
    }

    // Название из ресурсов приложения в нужной локали; нет перевода — системное название.
    private fun label(ri: ResolveInfo, locale: Locale?): String {
        val ai = ri.activityInfo
        val res = if (ai.labelRes != 0) ai.labelRes else ai.applicationInfo.labelRes
        if (locale != null && res != 0) {
            try {
                val conf = Configuration(context.resources.configuration).apply { setLocale(locale) }
                val localized = context.createPackageContext(ai.packageName, 0)
                    .createConfigurationContext(conf).resources.getString(res)
                if (localized.isNotBlank()) return localized
            } catch (e: Exception) {
                Log.d(TAG, "нет названия ${ai.packageName} для $locale: ${e.message}")
            }
        }
        return ri.loadLabel(pm).toString()
    }

    private fun icon(ri: ResolveInfo): String {
        val pkg = ri.activityInfo.packageName
        val updated = try {
            pm.getPackageInfo(pkg, 0).lastUpdateTime
        } catch (_: PackageManager.NameNotFoundException) {
            0L
        }
        synchronized(icons) {
            icons[pkg]?.takeIf { it.first == updated }?.let { return it.second }
        }
        val drawable = ri.loadIcon(pm)
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(Canvas(bitmap))
        val png = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()
        val encoded = Base64.encodeToString(png, Base64.NO_WRAP)
        synchronized(icons) { icons[pkg] = updated to encoded }
        return encoded
    }

    companion object {
        private const val TAG = "ProjectorRemote"
        private const val ICON_PX = 96
    }
}
