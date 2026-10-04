package space.akiba.remote

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Обновление приложений проектора (экран akiba и сама служба пульта) с сервера — без adb.
 *
 * Сервер публикует манифест /update/manifest: для каждого пакета versionCode, SHA-256 и размер
 * APK, подписанные закрытым ключом сервера (ECDSA P-256). Открытый ключ зашит в эту сборку
 * (BuildConfig.UPDATE_KEY), поэтому установить можно только то, что подписал сервер:
 *  1. проверяется подпись записи и что versionCode больше установленного (старое подписанное
 *     обновление повторно не поставится);
 *  2. APK скачивается и сверяется по размеру и SHA-256 с подписанными;
 *  3. проверяются пакет, versionCode и сертификат подписи самого APK (тот же, что у этой службы);
 *  4. установка через PackageInstaller. Если служба — владелец устройства (dpm set-device-owner,
 *     см. install.sh), без вопросов; иначе система показывает окно подтверждения,
 *     и его нажимает [UpdateConfirmService] (на этом проекторе владелец устройства невозможен:
 *     в прошивке нет android.software.device_admin).
 *
 * Сначала обновляется akiba (если сейчас не идёт трансляция), последней — сама служба: её
 * процесс при установке завершается, а после обновления служба стартует снова (BootReceiver).
 * Все публичные методы — в главном потоке.
 */
class Updater(private val context: Context, private val httpBase: () -> String) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "updater").apply { isDaemon = true } }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var busy = false
    // Неудачные версии: пакет → (versionCode, когда можно повторить).
    private val failed = HashMap<String, Pair<Long, Long>>()

    /** Колбэк: установлен akiba, и экран akiba был открыт — его стоит открыть снова. */
    var onAkibaUpdated: (() -> Unit)? = null

    val enabled: Boolean get() = BuildConfig.UPDATE_KEY.isNotEmpty()

    /** Служба — владелец устройства: обновления ставятся без подтверждения. */
    val isDeviceOwner: Boolean
        get() = (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isDeviceOwnerApp(context.packageName)

    /** Установленные versionCode приложений проектора (0 — не установлено). */
    fun versions(): JSONObject = JSONObject().apply {
        for (pkg in PACKAGES) put(pkg, installedVersion(pkg))
    }

    /** Проверить обновления (сервер подсказал или пришло время). */
    fun check() {
        if (!enabled) {
            Log.i(TAG, "обновления выключены: в сборке нет открытого ключа сервера")
            return
        }
        if (busy) return
        busy = true
        val base = httpBase()
        worker.execute {
            val plan = try {
                plan(base)
            } catch (e: Exception) {
                Log.w(TAG, "обновления: не удалось получить манифест: ${e.message}")
                null
            }
            main.post {
                if (plan.isNullOrEmpty()) busy = false else install(plan, 0)
            }
        }
    }

    private class Step(val pkg: String, val versionCode: Long, val file: File, val reopen: Boolean)

    /** Скачивает и проверяет всё, что нужно поставить (в фоне). */
    private fun plan(base: String): List<Step> {
        val manifest = http.newCall(Request.Builder().url(base + "update/manifest").build()).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            JSONObject(res.body!!.string())
        }
        val sharing = manifest.optBoolean("sharing")
        val projector = manifest.optBoolean("projector")
        val apps = manifest.optJSONArray("apps") ?: return emptyList()
        val byPkg = (0 until apps.length()).map { apps.getJSONObject(it) }.associateBy { it.optString("package") }
        val steps = ArrayList<Step>()
        for (pkg in PACKAGES) { // akiba первым, служба пульта — последней
            val app = byPkg[pkg] ?: continue
            val code = app.optLong("versionCode")
            val installed = installedVersion(pkg)
            if (code <= installed) continue
            if (!verifySignature(app)) {
                Log.e(TAG, "обновления: подпись записи $pkg не сходится с ключом сервера — пропускаю")
                continue
            }
            if (pkg == AKIBA && sharing) {
                Log.i(TAG, "обновления: akiba $code отложено — идёт трансляция")
                continue
            }
            val retry = failed[pkg]
            if (retry != null && retry.first == code && SystemClock.elapsedRealtime() < retry.second) continue
            val file = download(base, app) ?: continue
            if (!verifyApk(file, pkg, code)) {
                file.delete()
                continue
            }
            steps += Step(pkg, code, file, reopen = pkg == AKIBA && projector)
        }
        return steps
    }

    private fun verifySignature(app: JSONObject): Boolean = try {
        val payload = listOf(
            "akiba-update-v1", app.getString("package"), app.getLong("versionCode").toString(),
            app.getString("sha256"), app.getLong("size").toString(),
        ).joinToString("\n")
        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.decode(BuildConfig.UPDATE_KEY, Base64.DEFAULT)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(payload.toByteArray())
            verify(Base64.decode(app.getString("sig"), Base64.DEFAULT))
        }
    } catch (e: Exception) {
        Log.w(TAG, "обновления: подпись не проверена: ${e.message}")
        false
    }

    /** Скачивает APK, сверяя размер и SHA-256 с подписанными. */
    private fun download(base: String, app: JSONObject): File? {
        val pkg = app.getString("package")
        val size = app.getLong("size")
        val want = app.getString("sha256")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "$pkg.apk")
        val url = base + app.getString("url").removePrefix("/")
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                res.body!!.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > size) throw IllegalStateException("APK больше заявленного")
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (total != size || got != want) throw IllegalStateException("APK не совпадает с подписанным (размер или SHA-256)")
            }
            file
        } catch (e: Exception) {
            Log.w(TAG, "обновления: $pkg не скачан: ${e.message}")
            file.delete()
            null
        }
    }

    /** Пакет, версия и сертификат подписи скачанного APK — те, что ожидаются. */
    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, pkg: String, code: Long): Boolean {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
        if (info == null || info.packageName != pkg || PackageInfoCompat.getLongVersionCode(info) != code) {
            Log.e(TAG, "обновления: APK $pkg не тот (пакет ${info?.packageName}, версия ${info?.let { PackageInfoCompat.getLongVersionCode(it) }})")
            return false
        }
        val own = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        if (!sameSigners(info, own)) {
            Log.e(TAG, "обновления: APK $pkg подписан чужим сертификатом — не ставлю")
            return false
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun sameSigners(a: PackageInfo, b: PackageInfo): Boolean {
        val sa = a.signatures?.map { it.toCharsString() }?.toSet() ?: return false
        val sb = b.signatures?.map { it.toCharsString() }?.toSet() ?: return false
        return sa.isNotEmpty() && sa == sb
    }

    private fun install(steps: List<Step>, index: Int) {
        if (index >= steps.size) {
            busy = false
            return
        }
        val step = steps[index]
        // Подтверждение на экране могут не нажать вовсе — не ждём вечно.
        val timeout = Runnable { finish(false) }
        main.postDelayed(timeout, INSTALL_TIMEOUT_MS)
        pending = { ok ->
            main.removeCallbacks(timeout)
            UpdateConfirmService.disable(context)
            if (!ok) failed[step.pkg] = step.versionCode to SystemClock.elapsedRealtime() + RETRY_MS
            step.file.delete()
            if (ok && step.reopen) onAkibaUpdated?.invoke()
            install(steps, index + 1)
        }
        worker.execute {
            try {
                commit(step)
            } catch (e: Exception) {
                Log.w(TAG, "обновления: установка ${step.pkg} не началась", e)
                main.post { finish(false) }
            }
        }
    }

    private fun commit(step: Step) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(step.pkg) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, step.file.length()).use { out ->
                step.file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(context, InstallReceiver::class.java).putExtra(EXTRA_PKG, step.pkg)
            val sender = PendingIntent.getBroadcast(context, id, intent, flags).intentSender
            val owner = isDeviceOwner
            if (!owner) {
                // Окно подтверждения нажмёт служба специальных возможностей — только для этого пакета.
                UpdateConfirmService.awaiting = step.pkg
                if (!UpdateConfirmService.enable(context)) Log.w(TAG, "обновления: подтвердить придётся на экране вручную")
            }
            Log.i(TAG, "обновления: ставлю ${step.pkg} ${step.versionCode}${if (owner) " (владелец устройства)" else " (с подтверждением)"}")
            session.commit(sender)
        }
    }

    private fun installedVersion(pkg: String): Long = try {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(pkg, 0))
    } catch (_: PackageManager.NameNotFoundException) {
        0L
    }

    /** Результат установки от системы. */
    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra(EXTRA_PKG)
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Служба не владелец устройства: система спрашивает подтверждение на экране.
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        try {
                            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            Log.w(TAG, "обновления: окно подтверждения не открылось", e)
                            finish(false)
                        }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    Log.i(TAG, "обновления: $pkg установлен")
                    finish(true)
                }
                else -> {
                    Log.w(TAG, "обновления: $pkg не установлен ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                    finish(false)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ProjectorRemote"
        private const val EXTRA_PKG = "pkg"
        private const val AKIBA = "space.akiba.screen_node"
        // akiba первым, служба пульта — последней (её установка завершает этот процесс).
        val PACKAGES = listOf(AKIBA, "space.akiba.remote")
        // Неудачную версию (отказ в подтверждении, ошибка) повторяем не раньше чем через 30 минут.
        private const val RETRY_MS = 30 * 60_000L
        private const val INSTALL_TIMEOUT_MS = 10 * 60_000L

        // Ожидание результата текущей установки (один Updater на процесс).
        private var pending: ((Boolean) -> Unit)? = null

        private fun finish(ok: Boolean) {
            val p = pending ?: return
            pending = null
            p(ok)
        }
    }
}
