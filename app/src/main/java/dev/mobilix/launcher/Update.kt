package dev.mobilix.launcher

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File

/** Режим обновления лаунчера: 0 — не проверять, 1 — только сообщать, 2 — скачать APK и предложить установку. */
internal fun MainActivity.updateMode(): Int = prefs.getInt("updMode", 1)

/** Режим «скачать»: берёт APK из релиза, кладёт в cache/share и предлагает установить. Каждую версию скачивает один раз. */
internal fun MainActivity.autoDownloadUpdate(rel: JSONObject, version: String) {
    if (BuildConfig.LABS) return // MobiLabs ставится отдельно, у него другое приложение
    val assets = rel.optJSONArray("assets") ?: return
    var url: String? = null
    for (i in 0 until assets.length()) {
        val a = assets.getJSONObject(i)
        if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
    }
    if (url.isNullOrEmpty()) return
    val dir = File(cacheDir, "share").apply { mkdirs() }
    val apk = File(dir, "Mobilix-$version.apk")
    if (!apk.isFile || apk.length() < 1_000_000) {
        dir.listFiles()?.filter { it.name.startsWith("Mobilix-") && it.name.endsWith(".apk") }?.forEach { it.delete() }
        val tmp = File(dir, "Mobilix-$version.part")
        try { if (!download(url, tmp, null)) return; tmp.renameTo(apk) } catch (_: Throwable) { tmp.delete(); return }
    }
    if (prefs.getString("updAsked", "") == version) return
    prefs.edit().putString("updAsked", version).apply()
    runOnUiThread {
        if (isFinishing) return@runOnUiThread
        MobDialog(this).setTitle(tr("Обновление $version готово", "Update $version is ready"))
            .setMessage(tr("Новая версия Mobilix скачана. Установить сейчас? Настройки и данные игры сохранятся.", "The new Mobilix version is downloaded. Install now? Your settings and game data are kept."))
            .setPositiveButton(tr("Установить", "Install")) { _, _ -> installApk(apk) }
            .setNegativeButton(tr("Позже", "Later"), null).show()
    }
}

private fun MainActivity.installApk(apk: File) {
    if (!packageManager.canRequestPackageInstalls()) {
        Toast.makeText(this, tr("Разрешите лаунчеру устанавливать приложения и нажмите «Установить» ещё раз", "Allow the launcher to install apps, then tap Install again"), Toast.LENGTH_LONG).show()
        prefs.edit().putString("updAsked", "").apply()
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        return
    }
    val uri = Uri.parse("content://$packageName.share/${apk.name}")
    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}
