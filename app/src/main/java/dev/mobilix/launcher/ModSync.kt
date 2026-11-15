package dev.mobilix.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import dev.mobilix.launcher.MainActivity.Companion.AGREEMENT_URL
import dev.mobilix.launcher.MainActivity.Companion.ASSETS_URL
import dev.mobilix.launcher.MainActivity.Companion.DEFAULT_MIRROR
import dev.mobilix.launcher.MainActivity.Companion.LWJGL_REPOS
import dev.mobilix.launcher.MainActivity.Companion.MANIFEST_URL
import dev.mobilix.launcher.MainActivity.Companion.NICK
import dev.mobilix.launcher.MainActivity.Task
import dev.mobilix.launcher.MainActivity.Preset
import dev.mobilix.launcher.MainActivity.CTheme
import dev.mobilix.launcher.MainActivity.Companion.RUNTIME_REPOS
import dev.mobilix.launcher.MainActivity.Companion.VERSION
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject

// Моды: синхронизация, проверка совместимости, откат конфликтов, автообновление.
// Функции вынесены из MainActivity как расширения без изменения логики.

/** Записывает выбранный пресет в options.txt этой папки игры один раз; дальше игрок может менять параметры сам. */
/** Какие загрузчики поддерживает мод (по файлам описания внутри jar). */
internal fun MainActivity.modLoaders(jar: File): Set<String> = try {
    java.util.zip.ZipFile(jar).use { z ->
        val r = HashSet<String>()
        if (z.getEntry("fabric.mod.json") != null) r.add("fabric")
        if (z.getEntry("quilt.mod.json") != null) { r.add("quilt"); r.add("fabric") }
        if (z.getEntry("META-INF/neoforge.mods.toml") != null) r.add("neoforge")
        if (z.getEntry("META-INF/mods.toml") != null || z.getEntry("mcmod.info") != null) r.add("forge")
        r
    }
} catch (_: Exception) { emptySet() }

/** Моды не под текущий загрузчик уходят в mods-disabled, подходящие возвращаются обратно: чужой мод не должен ломать запуск. */
internal fun MainActivity.syncMods(gameDir: File, loader: String?) {
    if (loader == null) return
    try {
        val mods = File(gameDir, "mods"); val off = File(gameDir, "mods-disabled")
        val moved = ArrayList<String>()
        mods.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.forEach { f ->
            val ls = modLoaders(f)
            if (ls.isNotEmpty() && !ls.contains(loader) && !(loader == "quilt" && ls.contains("fabric"))) { off.mkdirs(); if (f.renameTo(File(off, f.name))) moved.add(f.name) }
        }
        off.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.forEach { f ->
            val lz = modLoaders(f)
            if (lz.contains(loader) || (loader == "quilt" && lz.contains("fabric"))) { mods.mkdirs(); f.renameTo(File(mods, f.name)) }
        }
        if (moved.isNotEmpty()) {
            log("Моды не под $loader отключены (папка mods-disabled): " + moved.joinToString(", "))
            runOnUiThread { Toast.makeText(this, tr("Отключено модов не под этот загрузчик: ${moved.size}", "Mods for another loader disabled: ${moved.size}"), Toast.LENGTH_LONG).show() }
        }
    } catch (e: Throwable) { log("Проверка загрузчиков модов: $e") }
}

/** Подтягивает файлы скачанных модов под текущую версию игры и загрузчик, докачивает обязательные зависимости, предупреждает о нехватке. */
// ---- проверка несовместимостей модов Fabric по fabric.mod.json (breaks) ----
internal fun MainActivity.cmpVer(a: String, b: String): Int {
    fun p(x: String) = x.substringBefore('+').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val x = p(a); val y = p(b)
    for (i in 0 until maxOf(x.size, y.size)) { val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 }); if (c != 0) return c }
    return 0
}

internal fun MainActivity.rangeHit(range: String, ver: String): Boolean = range.split("||").any { part ->
    part.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { c ->
        when {
            c == "*" -> true
            c.startsWith(">=") -> cmpVer(ver, c.drop(2)) >= 0
            c.startsWith("<=") -> cmpVer(ver, c.drop(2)) <= 0
            c.startsWith(">") -> cmpVer(ver, c.drop(1)) > 0
            c.startsWith("<") -> cmpVer(ver, c.drop(1)) < 0
            c.startsWith("=") -> cmpVer(ver, c.drop(1)) == 0
            c.startsWith("~") || c.startsWith("^") -> cmpVer(ver, c.drop(1)) >= 0
            c.contains('x') || c.contains('*') -> {
                val pre = c.split('.').takeWhile { it != "x" && it != "*" }
                val vp = ver.substringBefore('+').split('.')
                pre.indices.all { vp.getOrNull(it) == pre[it] }
            }
            else -> cmpVer(ver, c) == 0
        }
    }
}

internal fun MainActivity.fabricInfo(jar: File): JSONObject? = try {
    java.util.zip.ZipFile(jar).use { z -> z.getEntry("fabric.mod.json")?.let { JSONObject(z.getInputStream(it).bufferedReader().readText()) } }
} catch (_: Throwable) { null }

/** Моды, которые по своему fabric.mod.json ломаются с уже установленными: (файл-нарушитель, id цели). */
internal fun MainActivity.modConflicts(mods: File): List<Pair<File, String>> {
    val infos = HashMap<String, Pair<File, JSONObject>>()
    mods.listFiles { f -> f.name.endsWith(".jar") }?.forEach { f -> fabricInfo(f)?.let { infos[it.optString("id")] = Pair(f, it) } }
    val res = ArrayList<Pair<File, String>>()
    for ((_, pr) in infos) {
        val br = pr.second.optJSONObject("breaks") ?: continue
        val keys = br.keys()
        while (keys.hasNext()) {
            val tid = keys.next(); val tgt = infos[tid] ?: continue
            val v = br.opt(tid)
            val ranges = if (v is JSONArray) (0 until v.length()).map { v.getString(it) } else listOf(v.toString())
            if (ranges.any { rangeHit(it, tgt.second.optString("version")) }) res.add(Pair(pr.first, tid))
        }
    }
    return res
}

internal fun MainActivity.autoUpdateMods(gameDir: File, loader: String?, baseVer: String) {
    if (loader == null || !prefs.getBoolean("modAuto", true)) return
    val t0 = System.currentTimeMillis()
    try {
        val index = idx()
        val ids = ArrayList<String>(); val keys = index.keys(); while (keys.hasNext()) ids.add(keys.next())
        fun enc(x: String) = java.net.URLEncoder.encode(x, "UTF-8")
        fun versionsFor(id: String) = JSONArray(apiGet("/project/$id/version?game_versions=" + enc("[\"$baseVer\"]") + "&loaders=" + enc(JSONArray(loaderCats(loader)).toString())))
        // запросы версий идут параллельно (раньше по одному), дальше результаты разбираются по порядку как раньше
        val prefetch = HashMap<String, java.util.concurrent.Future<JSONArray>>()
        val lookupPool = Executors.newFixedThreadPool(6)
        try {
            for (id in ids.take(40)) {
                val rec = index.optJSONObject(id) ?: continue
                if (rec.optString("t") != "mod" || !File(gameDir, "mods/" + rec.optString("f")).exists()) continue
                prefetch[id] = lookupPool.submit(java.util.concurrent.Callable { versionsFor(id) })
            }
        } finally { lookupPool.shutdown() }
        fun fetch(id: String, v: JSONObject, title: String): String? {
            val files = v.getJSONArray("files"); var f = files.getJSONObject(0)
            for (i in 0 until files.length()) if (files.getJSONObject(i).optBoolean("primary")) f = files.getJSONObject(i)
            val name = f.getString("filename")
            val dest = File(gameDir, "mods/$name")
            if (!dest.exists() && !download(f.getString("url"), dest, f.optJSONObject("hashes")?.optString("sha1")?.ifEmpty { null })) return null
            index.put(id, JSONObject().put("t", "mod").put("f", name).put("n", title).put("l", loader).put("v", baseVer))
            return name
        }
        var updated = 0; var added = 0
        val noVersion = ArrayList<String>(); val noDeps = ArrayList<String>()
        for (id in ids.take(40)) {
            if (System.currentTimeMillis() - t0 > 40000) { log("Автообновление модов: остановлено по времени"); break }
            val rec = index.optJSONObject(id) ?: continue
            if (rec.optString("t") != "mod") continue
            val cur = File(gameDir, "mods/" + rec.optString("f"))
            if (!cur.exists()) continue
            val title = rec.optString("n", id)
            try {
                val vers = prefetch[id]?.get() ?: versionsFor(id)
                if (vers.length() == 0) { noVersion.add(title); continue }
                val v = vers.getJSONObject(0)
                val old = rec.optString("f")
                val nn = fetch(id, v, title)
                if (nn != null && nn != old) { cur.delete(); updated++ }
                val deps = v.optJSONArray("dependencies")
                if (deps != null) for (k in 0 until deps.length()) {
                    val d = deps.getJSONObject(k)
                    val pid = d.optString("project_id", "")
                    if (d.optString("dependency_type") != "required" || pid.isEmpty()) continue
                    val have = index.optJSONObject(pid)
                    if (have != null && File(gameDir, "mods/" + have.optString("f")).exists()) continue
                    try {
                        val dv = versionsFor(pid)
                        if (dv.length() == 0) { noDeps.add(title); continue }
                        val pt = try { JSONObject(apiGet("/project/$pid")).optString("title", pid) } catch (_: Exception) { pid }
                        if (fetch(pid, dv.getJSONObject(0), pt) != null) added++ else noDeps.add("$pt ($title)")
                    } catch (_: Exception) { noDeps.add(title) }
                }
            } catch (_: Exception) {}
        }
        // несовместимые пары (например Sodium 0.6.13 ломает Iris < 1.8.7): откатываем «нарушителя» на более старую версию
        if (loader == "fabric") {
            val modsDir = File(gameDir, "mods")
            for (round in 0 until 3) {
                val cf = modConflicts(modsDir)
                if (cf.isEmpty() || System.currentTimeMillis() - t0 > 60000) break
                for ((badFile, target) in cf) {
                    val pid = ids.firstOrNull { index.optJSONObject(it)?.optString("f") == badFile.name } ?: continue
                    val title = index.optJSONObject(pid)?.optString("n", pid) ?: pid
                    log("Конфликт модов: «$title» несовместим с «$target», ищу более старую версию")
                    val vers = try { versionsFor(pid) } catch (_: Exception) { continue }
                    var fixed = false
                    for (k in 0 until minOf(vers.length(), 12)) {
                        val v = vers.getJSONObject(k)
                        val files = v.getJSONArray("files"); var f = files.getJSONObject(0)
                        for (q in 0 until files.length()) if (files.getJSONObject(q).optBoolean("primary")) f = files.getJSONObject(q)
                        if (f.getString("filename") == badFile.name) continue
                        val tmp = File(cacheDir, "conflict-try.jar")
                        if (!download(f.getString("url"), tmp, f.optJSONObject("hashes")?.optString("sha1")?.ifEmpty { null })) continue
                        val info = fabricInfo(tmp) ?: continue
                        val br = info.optJSONObject("breaks")
                        val tv = fabricInfo(File(modsDir, modsDir.listFiles { x -> x.name.endsWith(".jar") }?.firstOrNull { x -> fabricInfo(x)?.optString("id") == target }?.name ?: "")) ?.optString("version") ?: ""
                        val v2 = br?.opt(target)
                        val rs = if (v2 == null) emptyList() else if (v2 is JSONArray) (0 until v2.length()).map { v2.getString(it) } else listOf(v2.toString())
                        if (rs.any { rangeHit(it, tv) }) continue
                        val dest = File(modsDir, f.getString("filename"))
                        tmp.copyTo(dest, true); tmp.delete(); badFile.delete()
                        index.put(pid, JSONObject().put("t", "mod").put("f", dest.name).put("n", title).put("l", loader).put("v", baseVer))
                        log("Конфликт модов: «$title» заменён на ${dest.name}")
                        fixed = true; break
                    }
                    if (!fixed) log("Конфликт модов: подходящей версии «$title» не найдено")
                }
            }
        }
        idxSave(index)
        if (updated > 0 || added > 0) log("Автообновление модов: обновлено $updated, докачано зависимостей $added")
        val warn = ArrayList<String>()
        if (noVersion.isNotEmpty()) warn.add(tr("нет версии под $baseVer/$loader: ", "no build for $baseVer/$loader: ") + noVersion.joinToString(", "))
        if (noDeps.isNotEmpty()) warn.add(tr("не хватает зависимостей: ", "missing dependencies: ") + noDeps.joinToString(", "))
        if (warn.isNotEmpty() || updated > 0 || added > 0) {
            val msg = (if (updated + added > 0) tr("Моды обновлены: $updated, зависимостей: $added. ", "Mods updated: $updated, dependencies: $added. ") else "") +
                warn.joinToString(". ") + (if (warn.isNotEmpty()) tr(". Автообновление можно отключить в Настройках.", ". You can turn auto-update off in Settings.") else "")
            log("Автообновление модов: $msg")
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
    } catch (e: Throwable) { log("Автообновление модов: $e") }
}
