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

// Каталог: запросы к Modrinth и CurseForge, установка проектов.
// Функции вынесены из MainActivity как расширения без изменения логики.

// ---------- каталог (Modrinth) ----------
internal fun MainActivity.apiBases(): List<String> {
    val official = "https://api.modrinth.com/v2"
    val mirror = (prefs.getString("mirrorApi", "https://api.modrinth.black/v2") ?: official).trimEnd('/')
    return if (prefs.getInt("modSource", 2) != 1) listOf(mirror, official) else listOf(official, mirror)
}

internal fun MainActivity.apiGet(path: String): String {
    var err: Throwable? = null
    for (b in apiBases()) {
        try {
            val c = URL(b + path).openConnection() as HttpURLConnection
            c.connectTimeout = 12000; c.readTimeout = 25000
            c.setRequestProperty("User-Agent", "Kumolix/Mobilix/1.4 (Android launcher)")
            if (c.responseCode in 200..299) return c.inputStream.bufferedReader().use { it.readText() }
            err = RuntimeException("HTTP ${c.responseCode}")
        } catch (e: Throwable) { err = e }
    }
    throw err ?: RuntimeException("нет ответа")
}

/** Загрузчик и версия игры из самого файла мода (файлы, которых нет в списке скачанного). Пустая строка, если не определилось. */
internal fun MainActivity.modMeta(f: File): Pair<String, String> {
    try {
        java.util.zip.ZipFile(f).use { z ->
            fun text(n: String) = z.getEntry(n)?.let { z.getInputStream(it).bufferedReader().use { r -> r.readText() } }
            text("fabric.mod.json")?.let { t ->
                val mc = try { JSONObject(t).optJSONObject("depends")?.opt("minecraft")?.toString() ?: "" } catch (_: Exception) { "" }
                return "fabric" to cleanRange(mc)
            }
            text("quilt.mod.json")?.let { return "quilt" to "" }
            text("META-INF/neoforge.mods.toml")?.let { t -> return "neoforge" to cleanRange(Regex("modId\\s*=\\s*\"minecraft\"[\\s\\S]*?versionRange\\s*=\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: "") }
            text("META-INF/mods.toml")?.let { t -> return "forge" to cleanRange(Regex("modId\\s*=\\s*\"minecraft\"[\\s\\S]*?versionRange\\s*=\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: "") }
            text("mcmod.info")?.let { t ->
                val v = Regex("\"mcversion\"\\s*:\\s*\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: ""
                return "forge" to v
            }
        }
    } catch (_: Throwable) {}
    return "" to ""
}

internal fun MainActivity.cleanRange(s: String) = s.trim().removePrefix("[").removeSuffix(")").removeSuffix("]").replace(",", " – ").replace("~", "").replace(">=", "").replace("^", "").trim().let { if (it.length > 18) it.take(18) else it }

// ---------- CurseForge ----------
internal fun MainActivity.cfGet(path: String, body: String? = null): JSONObject {
    val c = URL("https://api.curseforge.com/v1" + path).openConnection() as HttpURLConnection
    c.connectTimeout = 12000; c.readTimeout = 25000
    c.setRequestProperty("Accept", "application/json")
    c.setRequestProperty("x-api-key", BuildConfig.CF_KEY)
    c.setRequestProperty("User-Agent", "Kumolix/Mobilix (Android launcher)")
    if (body != null) {
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }
    if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
    return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
}

internal fun MainActivity.cfLoaderType(l: String) = when (l) { "forge" -> 1; "fabric", "quilt" -> 4; "neoforge" -> 6; else -> 0 }

internal fun MainActivity.cfClass(type: String) = when (type) { "modpack" -> 4471; "resourcepack" -> 12; "shader" -> 6552; "datapack" -> 6945; else -> 6 }

internal fun MainActivity.cfSearch(query: String, type: String, loader: String, strict: Boolean, index: Int = 0): JSONObject {
    var p = "/mods/search?gameId=432&classId=${cfClass(type)}&pageSize=25&index=$index&sortOrder=desc&sortField=" + (if (query.isEmpty()) 2 else 1)
    if (query.isNotEmpty()) p += "&searchFilter=" + java.net.URLEncoder.encode(query, "UTF-8")
    if (strict && type != "modpack") {
        p += "&gameVersion=" + java.net.URLEncoder.encode(curBase(), "UTF-8")
        if (type == "mod" && cfLoaderType(loader) != 0) p += "&modLoaderType=" + cfLoaderType(loader)
    }
    return cfGet(p)
}

/** Скачивает файл CurseForge под текущую версию/загрузчик. Возвращает имя файла или бросает исключение. */
internal fun MainActivity.cfDownloadFile(modId: Int, type: String, loader: String, chosen: JSONObject? = null): String {
    if (chosen != null) return cfDownloadChosen(chosen, type, loader)
    var p = "/mods/$modId/files?pageSize=30&gameVersion=" + java.net.URLEncoder.encode(curBase(), "UTF-8")
    if (type == "mod" && cfLoaderType(loader) != 0) p += "&modLoaderType=" + cfLoaderType(loader)
    val files = cfGet(p).getJSONArray("data")
    if (files.length() == 0) throw RuntimeException(tr("нет файла под ${curBase()} / $loader", "no file for ${curBase()} / $loader"))
    var f: JSONObject? = null
    var best = -1L
    for (i in 0 until files.length()) {   // самый свежий релиз; релизы (1) важнее бета/альфа (2,3)
        val o = files.getJSONObject(i)
        val score = (4 - o.optInt("releaseType", 3)) * 1_000_000_000_000L + (o.optString("fileDate").filter { it.isDigit() }.take(14).toLongOrNull() ?: 0L) / 1000
        if (score > best) { best = score; f = o }
    }
    return cfDownloadChosen(f!!, type, loader)
}

internal fun MainActivity.cfDownloadChosen(file: JSONObject, type: String, loader: String): String {
    val name = file.getString("fileName")
    val url = if (file.isNull("downloadUrl")) "" else file.optString("downloadUrl")
    if (url.isEmpty()) throw RuntimeException("__cfsite")
    var sha: String? = null
    file.optJSONArray("hashes")?.let { hs -> for (i in 0 until hs.length()) if (hs.getJSONObject(i).optInt("algo") == 1) sha = hs.getJSONObject(i).optString("value").ifEmpty { null } }
    val dest = File(curGameDir(), typeDir(type) + "/" + name)
    if (!download(url, dest, sha)) throw RuntimeException(tr("ошибка загрузки", "download failed"))
    // обязательные зависимости (relationType 3): ставим, что получится
    if (type == "mod") {
        val deps = file.optJSONArray("dependencies")
        if (deps != null) for (i in 0 until deps.length()) {
            val d = deps.getJSONObject(i)
            if (d.optInt("relationType") != 3) continue
            val did = d.optInt("modId"); val key = "cf:$did"
            val rec = idx().optJSONObject(key)
            if (rec != null && packFile(rec.optString("t"), rec.optString("f")).exists()) continue
            try {
                val dn = cfDownloadFile(did, "mod", loader)
                idxSave(idx().apply { put(key, JSONObject().put("t", "mod").put("f", dn).put("n", try { cfGet("/mods/$did").getJSONObject("data").optString("name", "CF $did") } catch (_: Throwable) { "CF $did" }).put("l", loader).put("v", curBase())) })
            } catch (_: Throwable) {}
        }
    }
    return name
}

/** Режим «Вручную»: список всех файлов проекта CurseForge, совместимые с текущей версией игры сверху. */
internal fun MainActivity.cfPickFile(modId: Int, type: String, loader: String, title: String, btn: Button, onDone: () -> Unit) {
    btn.isEnabled = false; btn.alpha = 0.6f
    Thread {
        try {
            val arr = cfGet("/mods/$modId/files?pageSize=50").getJSONArray("data")
            runOnUiThread {
                btn.isEnabled = true; btn.alpha = 1f
                if (arr.length() == 0) { Toast.makeText(this, tr("У проекта нет файлов", "The project has no files"), Toast.LENGTH_LONG).show(); return@runOnUiThread }
                fun fits(o: JSONObject): Boolean { val g = o.optJSONArray("gameVersions") ?: return false; return (0 until g.length()).any { g.getString(it) == curBase() } }
                val all = (0 until arr.length()).map { arr.getJSONObject(it) }.sortedByDescending { if (fits(it)) 1 else 0 }
                val items = all.map { o ->
                    val g = o.optJSONArray("gameVersions")
                    val gs = if (g == null) "" else (0 until g.length()).map { g.getString(it) }.take(6).joinToString(", ")
                    (if (fits(o)) "✓ " else "✗ ") + o.optString("displayName", o.optString("fileName")) + "\n" + gs
                }
                MobDialog(this).setTitle(title + "\n" + tr("выберите файл (CurseForge)", "pick a file (CurseForge)"))
                    .setItems(items.toTypedArray()) { _, i -> cfInstall(modId, type, loader, title, btn, onDone, all[i]) }
                    .setNegativeButton(tr("Отмена", "Cancel"), null).show()
            }
        } catch (e: Throwable) { runOnUiThread { btn.isEnabled = true; btn.alpha = 1f; Toast.makeText(this, e.message ?: e.toString(), Toast.LENGTH_LONG).show() } }
    }.start()
}

internal fun MainActivity.cfInstall(modId: Int, type: String, loader: String, title: String, btn: Button, onDone: () -> Unit, chosen: JSONObject? = null) {
    btn.isEnabled = false; btn.alpha = 0.6f; btn.text = tr("Скачивание…", "Downloading…")
    Thread {
        var res = ""
        var destFile: File? = null
        var site = ""
        try {
            val name = cfDownloadFile(modId, type, loader, chosen)
            destFile = File(curGameDir(), typeDir(type) + "/" + name)
            idxSave(idx().apply { put("cf:$modId", JSONObject().put("t", type).put("f", name).put("n", title).put("l", if (type == "mod") loader else "").put("v", curBase())) })
            res = "ok"
        } catch (e: Throwable) {
            res = e.message ?: e.toString()
            if (res == "__cfsite") try { site = cfGet("/mods/$modId").getJSONObject("data").getJSONObject("links").optString("websiteUrl") } catch (_: Throwable) {}
        }
        runOnUiThread {
            if (res == "ok") { onDone(); if (type == "mod") suggestRenderlix(title); if (type == "datapack") destFile?.let { offerDatapackWorld(it) } }
            else {
                btn.isEnabled = true; btn.alpha = 1f; btn.text = tr("Скачать", "Download")
                if (res == "__cfsite") {
                    MobDialog(this).setTitle(title)
                        .setMessage(tr("Автор отключил скачивание через сторонние лаунчеры. Файл можно скачать на сайте CurseForge и добавить через «Импорт».", "The author disabled downloads via third-party launchers. Download the file on the CurseForge website and add it with “Import”."))
                        .setPositiveButton(tr("Открыть сайт", "Open site")) { _, _ ->
                            if (site.isNotEmpty()) try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(site + "/files"))) } catch (_: Throwable) {}
                        }.setNegativeButton(tr("Закрыть", "Close"), null).show()
                } else Toast.makeText(this, res, Toast.LENGTH_LONG).show()
            }
        }
    }.start()
}

internal fun MainActivity.installProject(id: String, type: String, loader: String, title: String, btn: Button, onDone: () -> Unit) {
    // «Скачивание модов: вручную» — пользователь сам выбирает версию файла
    if (prefs.getBoolean("modDlManual", false)) {
        if (id.startsWith("cf:")) cfPickFile(id.substring(3).toInt(), type, loader, title, btn, onDone)
        else openManualDownload(id, type, title, onDone)
        return
    }
    btn.isEnabled = false; btn.alpha = 0.6f; btn.text = tr("Скачивание…", "Downloading…")
    if (id.startsWith("cf:")) { cfInstall(id.substring(3).toInt(), type, loader, title, btn, onDone); return }
    Thread {
        var res = ""
        var destFile: File? = null
        try {
            val g = "?game_versions=" + java.net.URLEncoder.encode("[\"${curBase()}\"]", "UTF-8") +
                (if (type == "mod") "&loaders=" + java.net.URLEncoder.encode(JSONArray(loaderCats(loader)).toString(), "UTF-8") else "")
            val vers = JSONArray(apiGet("/project/$id/version$g"))
            if (vers.length() == 0) throw RuntimeException("__manual")
            val files = vers.getJSONObject(0).getJSONArray("files")
            var f = files.getJSONObject(0)
            for (i in 0 until files.length()) if (files.getJSONObject(i).optBoolean("primary")) f = files.getJSONObject(i)
            val dest = File(curGameDir(), typeDir(type) + "/" + f.getString("filename"))
            destFile = dest
            val ok = download(f.getString("url"), dest, f.optJSONObject("hashes")?.optString("sha1")?.ifEmpty { null })
            if (!ok) throw RuntimeException(tr("ошибка загрузки", "download failed"))
            idxSave(idx().apply { put(id, JSONObject().put("t", type).put("f", f.getString("filename")).put("n", title).put("l", if (type == "mod") loader else "").put("v", curBase())) })
            res = "ok"
        } catch (e: Throwable) { res = e.message ?: e.toString() }
        runOnUiThread {
            if (res == "ok") { onDone(); if (type == "mod") suggestRenderlix(title); if (type == "datapack") destFile?.let { offerDatapackWorld(it) } }
            else {
                btn.isEnabled = true; btn.alpha = 1f; btn.text = tr("Скачать", "Download")
                // нет файла под вашу версию/загрузчик: предлагаем выбрать любой файл вручную
                if (res == "__manual") openManualDownload(id, type, title, onDone)
                else Toast.makeText(this, res, Toast.LENGTH_LONG).show()
            }
        }
    }.start()
}
