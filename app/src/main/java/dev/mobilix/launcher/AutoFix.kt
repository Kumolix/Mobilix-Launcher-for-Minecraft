package dev.mobilix.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Автоподбор модов после вылета: версии модов меняются по порядку (от новых к старым), и каждый вариант проверяется
 * тихим запуском игры (невидимое окно 1×1). Как только игра дошла до главного меню, она закрывается, а лаунчер пишет «Готово».
 * Логи разбирает CrashDoctor: он называет виновника, а цикл подбирает ему версию, докачивает зависимости или отключает мод.
 */
internal object AutoFixState {
    @Volatile var running = false
    @Volatile var cancel = false
}

internal class ProbeResult(val ok: Boolean, val what: String, val start: Long)

/** Тихий запуск игры. ok = игра дошла до главного меню. */
internal fun MainActivity.probeLaunch(): ProbeResult {
    val res = File(dataRoot(), "probe.result").apply { delete() }
    val start = System.currentTimeMillis()
    // прежний процесс игры должен умереть: JVM в одном процессе второй раз не создать
    var w = 0
    while (CrashDoctor.gameAlive(this) && w++ < 20) Thread.sleep(500)
    prepareAndRun(play = true, probe = true)
    val t0 = System.currentTimeMillis()
    var seenAlive = false
    while (System.currentTimeMillis() - t0 < 300_000) {
        Thread.sleep(800)
        if (AutoFixState.cancel) return ProbeResult(false, "cancel", start)
        if (res.exists()) { val t = res.readText().trim(); return ProbeResult(t == "OK", t, start) }
        if (CrashDoctor.gameAlive(this)) seenAlive = true
        else if (seenAlive || System.currentTimeMillis() - t0 > 40_000) {
            Thread.sleep(600)
            if (res.exists()) { val t = res.readText().trim(); return ProbeResult(t == "OK", t, start) }
            return ProbeResult(false, "DIED", start)
        }
    }
    return ProbeResult(false, "TIMEOUT", start)
}

private fun MainActivity.backupMods(gd: File): File {
    val b = File(gd, "mods-autofix-backup").apply { deleteRecursively(); mkdirs() }
    File(gd, "mods").listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(b, it.name), true) }
    return b
}

/** Возвращает моды такими, какими они были до подбора. */
internal fun MainActivity.restoreModsBackup(gd: File): Boolean {
    val b = File(gd, "mods-autofix-backup")
    if (!b.isDirectory) return false
    val mods = File(gd, "mods").apply { mkdirs() }
    mods.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    b.listFiles()?.forEach { it.copyTo(File(mods, it.name), true) }
    return true
}

private fun enc(x: String) = java.net.URLEncoder.encode(x, "UTF-8")

/** Меняет файл мода на следующую по порядку версию с Modrinth, которую ещё не пробовали. */
private fun MainActivity.rotateVersion(mod: CrashDoctor.Mod, tried: MutableMap<String, MutableSet<String>>, accept: (JSONObject) -> Boolean = { true }): String? {
    val g = "?game_versions=" + enc("[\"${curBase()}\"]") + "&loaders=" + enc(JSONArray(loaderCats(loaderOfProfile(selectedProfile()))).toString())
    val vers = try { JSONArray(apiGet("/project/${mod.id}/version$g")) } catch (_: Throwable) { return null }
    val seen = tried.getOrPut(mod.id) { mutableSetOf() }
    seen.add(mod.file.name)
    for (i in 0 until minOf(vers.length(), 40)) {
        val v = vers.getJSONObject(i)
        if (!accept(v)) continue
        val files = v.getJSONArray("files")
        if (files.length() == 0) continue
        var f = files.getJSONObject(0)
        for (k in 0 until files.length()) if (files.getJSONObject(k).optBoolean("primary")) f = files.getJSONObject(k)
        val name = f.getString("filename")
        if (name in seen) continue
        val tmp = File(cacheDir, "autofix.jar")
        try {
            if (!download(f.getString("url"), tmp, f.optJSONObject("hashes")?.optString("sha1")?.ifEmpty { null })) { seen.add(name); continue }
            val old = mod.file.name
            mod.file.delete()
            tmp.copyTo(File(mod.file.parentFile, name), true)
            seen.add(name)
            return mod.name + ": " + old + " → " + name
        } finally { tmp.delete() }
    }
    return null
}

/** Один шаг исправления по отчёту разбора лога. null — больше делать нечего. Возвращает описание шага. */
private fun MainActivity.nextFixStep(rep: CrashDoctor.Report, gd: File, tried: MutableMap<String, MutableSet<String>>, triedDeps: MutableSet<String>): String? {
    // 1. не хватает модов-зависимостей: докачиваем (каждую один раз)
    val need = (rep.missing + CrashDoctor.missingDeps(CrashDoctor.enabledMods(gd))).distinct().filter { triedDeps.add(it) }
    if (need.isNotEmpty()) {
        val (ok, _) = installMissingDeps(need, gd)
        if (ok.isNotEmpty()) return tr("Докачано: ", "Downloaded: ") + ok.joinToString(", ")
    }
    // 2. мод устарел для другого мода («Sodium требует Iris новее X»): новая версия устаревшего, иначе подбор версии «требователя»
    for ((uid, needVer, breaker) in rep.updates) {
        val old = rep.mods.firstOrNull { it.id == uid } ?: continue
        rotateVersion(old, tried) { v -> CrashDoctor.versionAtLeast(v.optString("version_number"), needVer) }?.let { return it }
        val fixed = try { downgradeToMatch(breaker, old.id, old.file, gd) } catch (_: Throwable) { null }
        if (fixed != null) return fixed
        val off = CrashDoctor.disable(rep, listOf(old))
        if (off.isNotEmpty()) return tr("Отключён (нет подходящей версии): ", "Disabled (no suitable version): ") + off.joinToString(", ")
    }
    // 3. подозреваемые: следующая версия, а когда версии кончились — отключение
    for (s in rep.suspects) {
        rotateVersion(s.mod, tried)?.let { return it }
        val off = CrashDoctor.disable(rep, listOf(s.mod))
        if (off.isNotEmpty()) return tr("Отключён (версии кончились): ", "Disabled (no more versions): ") + off.joinToString(", ")
    }
    // 4. вины в логе нет, но стоят рендер-моды: сначала профиль Renderlix, потом отключаем их по одному
    if (rep.hasRenderMod) {
        if (prefs.getInt("renderPreset", 2) == 0) { prefs.edit().putInt("renderPreset", 1).apply(); return tr("Включён профиль Renderlix «Sodium / Iris»", "Renderlix “Sodium / Iris” profile enabled") }
        val order = listOf("iris", "oculus", "distanthorizons", "nvidium", "sodium-extra", "indium", "sodium", "embeddium", "rubidium")
        val m = rep.mods.filter { it.id in order }.minByOrNull { order.indexOf(it.id) }
        if (m != null) { val off = CrashDoctor.disable(rep, listOf(m)); if (off.isNotEmpty()) return tr("Отключён рендер-мод: ", "Render mod disabled: ") + off.joinToString(", ") }
    }
    return null
}

private fun MainActivity.notifyAuto(text: String) {
    try {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("auto", "Подбор модов", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "auto") else Notification.Builder(this)
        nm.notify(3, b.setContentTitle("Mobilix").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentIntent(open).setAutoCancel(true).build())
    } catch (_: Throwable) {}
}

/** Окно подбора: ход работы виден, есть кнопка «Остановить». Сам подбор идёт в фоновом потоке. */
internal fun MainActivity.autoFixStart() {
    if (AutoFixState.running) return
    AutoFixState.running = true; AutoFixState.cancel = false; DownloadService.swiped = false
    val gd = curGameDir()
    val status = TextView(this).apply { textSize = 13f; setTextColor(Th.text); setPadding(dp(8), dp(4), dp(8), dp(4)); text = tr("Готовлю…", "Preparing…") }
    val dlg = MobDialog(this).setTitle(tr("Подбор модов", "Mod auto-fix")).setView(status).setCancelable(false)
        .setNegativeButton(tr("Остановить", "Stop")) { _, _ -> AutoFixState.cancel = true }.show()
    val lines = ArrayList<String>()
    fun say(t: String) { lines.add(t); log("[Подбор] $t"); runOnUiThread { status.text = lines.takeLast(8).joinToString("\n") } }
    DownloadService.start(this)
    Thread {
        var ok = false; var why = ""
        val changes = ArrayList<String>()
        try {
            backupMods(gd)
            val tried = HashMap<String, MutableSet<String>>(); val triedDeps = HashSet<String>()
            say(tr("Проверяю запуск в фоне (игра в невидимом окне)…", "Checking launch in the background (invisible window)…"))
            var res = probeLaunch()
            var attempt = 0
            while (!res.ok && attempt < 14 && !AutoFixState.cancel && !DownloadService.swiped) {
                attempt++
                val crashed = File(gd, "crash-reports").listFiles()?.any { it.lastModified() >= res.start } == true
                val rep = CrashDoctor.analyze(CrashDoctor.Detected(gd, res.start, if (crashed) "crash" else "native"))
                val step = nextFixStep(rep, gd, tried, triedDeps)
                if (step == null) { why = tr("Исправлять больше нечего: по логу виновник не найден.", "Nothing left to fix: the log does not point to a culprit."); break }
                changes.add(step); say("$attempt. $step")
                say(tr("Проверяю запуск…", "Checking launch…"))
                res = probeLaunch()
            }
            ok = res.ok
            if (!ok && why.isEmpty()) why = if (AutoFixState.cancel || DownloadService.swiped) tr("Остановлено.", "Stopped.") else tr("Игра так и не дошла до меню за 14 попыток.", "The game still did not reach the menu after 14 attempts.")
        } catch (e: Throwable) { why = tr("Ошибка подбора: ", "Auto-fix error: ") + e }
        AutoFixState.running = false
        DownloadService.stop(this)
        Thread.sleep(1500)
        val swiped = DownloadService.swiped
        val msg = StringBuilder()
        if (ok) msg.append(tr("Готово! Игра дошла до главного меню и закрыта. Можно играть.", "Done! The game reached the main menu and was closed. You can play now."))
        else msg.append(why)
        if (changes.isNotEmpty()) msg.append("\n\n").append(tr("Что изменено:\n", "Changes:\n")).append(changes.joinToString("\n") { "• $it" })
        if (!swiped && !isResumedNow()) notifyAuto(if (ok) tr("Готово: игра запускается, можно играть", "Done: the game launches, you can play") else tr("Подбор закончен: не удалось запустить игру", "Auto-fix finished: could not launch the game"))
        runOnUiThread {
            try { dlg.dismiss() } catch (_: Throwable) {}
            if (isFinishing || swiped) return@runOnUiThread
            val d = MobDialog(this).setTitle(if (ok) tr("Готово", "Done") else tr("Не удалось", "Failed")).setMessage(msg.toString()).setPositiveButton("OK", null)
            if (!ok && changes.isNotEmpty()) d.setNegativeButton(tr("Вернуть моды как были", "Restore original mods")) { _, _ ->
                Thread { val r = restoreModsBackup(gd); runOnUiThread { android.widget.Toast.makeText(this, if (r) tr("Моды возвращены", "Mods restored") else tr("Копии нет", "No backup"), android.widget.Toast.LENGTH_LONG).show() } }.start()
            }
            d.show()
        }
    }.start()
}
