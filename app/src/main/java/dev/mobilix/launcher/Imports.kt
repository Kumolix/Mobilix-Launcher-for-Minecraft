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

// Импорт и экспорт: файлы, карты, сборки (zip, CurseForge, Modrinth).
// Функции вынесены из MainActivity как расширения без изменения логики.

/** Импорт: тип каждого файла определяется сам (мод, шейдер, ресурспак, датапак, карта, сборка Mobilix). */
internal fun MainActivity.importFiles(after: (() -> Unit)? = null) {
    importAfter = after
    MobDialog(this).setTitle(tr("Импорт файлов", "Import files"))
        .setMessage(tr("Выберите один или несколько файлов. Тип определяется автоматически: моды (.jar), шейдеры, ресурспаки, датапаки, карты (zip) и сборки Mobilix (zip) и сборки Modrinth (.mrpack). Файлы копируются в папку текущей версии или сборки.",
            "Pick one or more files. The type is detected automatically: mods (.jar), shaders, resource packs, datapacks, maps (zip) Mobilix builds (zip) and Modrinth packs (.mrpack). Files are copied into the current version or build folder."))
        .setNegativeButton(tr("Отмена", "Cancel"), null)
        .setPositiveButton(tr("Выбрать файлы", "Choose files")) { _, _ ->
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }, 78)
            } catch (e: Throwable) { Toast.makeText(this, "Ошибка: $e", Toast.LENGTH_LONG).show() }
        }.show()
}

internal fun MainActivity.uriName(u: android.net.Uri): String {
    var name = "file"
    contentResolver.query(u, null, null, null, null)?.use { c ->
        val ix = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && ix >= 0) name = c.getString(ix) ?: name
    }
    return name.replace('/', '_')
}

internal fun MainActivity.zipClean(n: String) = n.replace('\\', '/').trimStart('/')

/** Читает оглавление zip (и mobilix-build.json, если он есть). */
internal fun MainActivity.zipScan(u: android.net.Uri): Pair<List<String>, String?> {
    val names = ArrayList<String>(); var manifest: String? = null
    java.util.zip.ZipInputStream(contentResolver.openInputStream(u)!!).use { z ->
        var e = z.nextEntry
        while (e != null) {
            val n = zipClean(e.name)
            if (!n.startsWith("__MACOSX/")) {
                names.add(if (e.isDirectory && !n.endsWith("/")) "$n/" else n)
                if (n == "mobilix-build.json") manifest = String(z.readBytes(), Charsets.UTF_8)
            }
            e = z.nextEntry
        }
    }
    return names to manifest
}

/** Тип zip по содержимому: build, saves, shaderpacks, resourcepacks, datapacks или null. */
internal fun MainActivity.zipKind(names: List<String>, hasManifest: Boolean): String? {
    val files = names.filter { !it.endsWith("/") }
    return when {
        hasManifest -> "build"
        files.any { it.substringAfterLast('/').equals("level.dat", true) } -> "saves"
        files.any { Regex("^([^/]+/)?shaders/.+").matches(it) } && files.none { it == "pack.mcmeta" && files.any { f -> f.startsWith("assets/") } } -> "shaderpacks"
        files.contains("pack.mcmeta") && files.any { it.startsWith("assets/") } -> "resourcepacks"
        files.contains("pack.mcmeta") && files.any { it.startsWith("data/") } -> "datapacks"
        files.any { Regex("^([^/]+/)?shaders/.+").matches(it) } -> "shaderpacks"
        else -> null
    }
}

internal fun MainActivity.extractWorlds(u: android.net.Uri, names: List<String>, base: String, dest: File) {
    var roots = names.filter { !it.endsWith("/") && it.substringAfterLast('/').equals("level.dat", true) }
        .map { if (it.contains('/')) it.substringBeforeLast('/') + "/" else "" }.distinct()
    roots = roots.filter { r -> roots.none { o -> o != r && o.isNotEmpty() && r.startsWith(o) } }
    val targets = HashMap<String, File>()
    for (root in roots) {
        val wn = (if (root.isEmpty()) base else root.trimEnd('/').substringAfterLast('/')).replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { base }
        var target = File(dest, wn); var k = 2
        while (target.exists() || targets.containsValue(target)) { target = File(dest, "$wn ($k)"); k++ }
        targets[root] = target
    }
    java.util.zip.ZipInputStream(contentResolver.openInputStream(u)!!).use { z ->
        var e = z.nextEntry
        while (e != null) {
            val n = zipClean(e.name)
            val root = if (e.isDirectory) null else roots.firstOrNull { n.startsWith(it) }
            if (root != null) {
                val target = targets[root]!!
                val out = File(target, n.substring(root.length))
                if (out.canonicalPath.startsWith(target.canonicalPath + File.separator)) { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
            e = z.nextEntry
        }
    }
}

internal fun MainActivity.importUris(uris: List<android.net.Uri>) {
    val gameDir = curGameDir()
    Toast.makeText(this, tr("Копирование…", "Copying…"), Toast.LENGTH_SHORT).show()
    Thread {
        val counts = LinkedHashMap<String, Int>(); var fail = 0
        val builds = ArrayList<Pair<android.net.Uri, JSONObject>>()
        val dps = ArrayList<File>()
        val mrpacks = ArrayList<Pair<android.net.Uri, JSONObject>>()
        val cfpacks = ArrayList<Pair<android.net.Uri, JSONObject>>()
        for (u in uris) {
            try {
                val name = uriName(u)
                val low = name.lowercase()
                when {
                    low.endsWith(".jar") -> {
                        val d = File(gameDir, "mods").apply { mkdirs() }
                        contentResolver.openInputStream(u)!!.use { inp -> File(d, name).outputStream().use { inp.copyTo(it) } }
                        counts["mods"] = (counts["mods"] ?: 0) + 1
                    }
                    low.endsWith(".zip") && readCfManifest(u) != null -> { cfpacks.add(u to readCfManifest(u)!!) }
                    low.endsWith(".zip") -> {
                        val (names, mf) = zipScan(u)
                        val kind = zipKind(names, mf != null) ?: throw Exception("unknown")
                        if (kind == "build") { builds.add(u to JSONObject(mf!!)) }
                        else if (kind == "saves") {
                            val d = File(gameDir, "saves").apply { mkdirs() }
                            extractWorlds(u, names, name.substring(0, name.length - 4), d)
                            counts[kind] = (counts[kind] ?: 0) + 1
                        } else {
                            val d = File(gameDir, kind).apply { mkdirs() }
                            contentResolver.openInputStream(u)!!.use { inp -> File(d, name).outputStream().use { inp.copyTo(it) } }
                            counts[kind] = (counts[kind] ?: 0) + 1
                            if (kind == "datapacks") dps.add(File(d, name))
                        }
                    }
                    low.endsWith(".mrpack") -> {
                        mrpacks.add(u to (readMrpackIndex(u) ?: throw Exception("unknown")))
                    }
                    else -> throw Exception("unknown")
                }
            } catch (_: Throwable) { fail++ }
        }
        runOnUiThread {
            val label = mapOf("mods" to tr("моды", "mods"), "shaderpacks" to tr("шейдеры", "shaders"), "resourcepacks" to tr("ресурспаки", "resource packs"),
                "datapacks" to tr("датапаки", "datapacks"), "saves" to tr("карты", "maps"))
            val parts = counts.map { (k, v) -> "${label[k]}: $v" }.toMutableList()
            if (builds.isNotEmpty()) parts.add(tr("сборки: ${builds.size}", "builds: ${builds.size}"))
            if (mrpacks.isNotEmpty()) parts.add(tr("сборки Modrinth: ${mrpacks.size}", "Modrinth packs: ${mrpacks.size}"))
            if (cfpacks.isNotEmpty()) parts.add(tr("сборки CurseForge: ${cfpacks.size}", "CurseForge packs: ${cfpacks.size}"))
            if (fail > 0) parts.add(tr("не распознано: $fail", "unrecognized: $fail"))
            Toast.makeText(this, (if (parts.isEmpty()) tr("Ничего не импортировано", "Nothing imported") else tr("Импорт: ", "Imported: ") + parts.joinToString(", ")), Toast.LENGTH_LONG).show()
            importAfter?.invoke()
            builds.forEach { (u, m) -> importBuild(u, m) }
            mrpacks.forEach { (u, m) -> importMrpack(u, m) }
            cfpacks.forEach { (u, m) -> importCfPack(u, m) }
            // датапаки: по очереди спрашиваем, в какой мир положить
            fun offer(i: Int) { if (i < dps.size) offerDatapackWorld(dps[i]) { offer(i + 1) } }
            offer(0)
        }
    }.start()
}

/** Сборка Mobilix из zip: создаёт новую сборку, распаковывает файлы и ставит нужную версию игры, если её ещё нет. */
internal fun MainActivity.importBuild(u: android.net.Uri, m: JSONObject) {
    val nm = m.optString("name", tr("Сборка", "Build")).ifBlank { tr("Сборка", "Build") }
    val kind = m.optString("kind", "vanilla"); val mc = m.optString("mc")
    if (mc.isEmpty()) { Toast.makeText(this, tr("Файл сборки повреждён", "Build file is damaged"), Toast.LENGTH_LONG).show(); return }
    val ver = kindLabel(kind, mc)
    MobDialog(this).setTitle(tr("Импорт сборки", "Import build"))
        .setMessage("«$nm»\n" + tr("Версия: $ver. Если её нет, лаунчер скачает её сам.", "Version: $ver. If it is missing, the launcher will download it."))
        .setNegativeButton(tr("Отмена", "Cancel"), null)
        .setPositiveButton(tr("Импортировать", "Import")) { _, _ ->
            val id = "b" + System.currentTimeMillis()
            val dir = buildDir(id)
            Toast.makeText(this, tr("Распаковка сборки…", "Unpacking build…"), Toast.LENGTH_SHORT).show()
            Thread {
                try {
                    val allowed = listOf("mods/", "resourcepacks/", "shaderpacks/", "saves/", "datapacks/")
                    dir.mkdirs()
                    java.util.zip.ZipInputStream(contentResolver.openInputStream(u)!!).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            val n = zipClean(e.name)
                            if (n == "icon.png") { buildIcon(id).parentFile?.mkdirs(); buildIcon(id).outputStream().use { z.copyTo(it) } }
                            if (!e.isDirectory && allowed.any { n.startsWith(it) }) {
                                val out = File(dir, n)
                                if (out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                            }
                            e = z.nextEntry
                        }
                    }
                    runOnUiThread {
                        fun finish(profile: String) {
                            val all = builds()
                            all.put(JSONObject().put("id", id).put("name", nm).put("profile", profile))
                            buildsSave(all)
                            prefs.edit().putString("profile", profile).putString("build", id).apply()
                            versionText.text = verTitle()
                            Toast.makeText(this, tr("Сборка «$nm» добавлена и выбрана", "Build “$nm” added and selected"), Toast.LENGTH_LONG).show()
                        }
                        val have = findProfile(kind, mc)
                        if (have != null) finish(have)
                        else installProfile(kind, mc) { runOnUiThread { finish(findProfile(kind, mc) ?: selectedProfile()) } }
                    }
                } catch (e: Throwable) {
                    dir.deleteRecursively()
                    runOnUiThread { Toast.makeText(this, tr("Не удалось импортировать сборку: ", "Could not import the build: ") + e.message, Toast.LENGTH_LONG).show() }
                }
            }.start()
        }.show()
}

/** Читает manifest.json сборки CurseForge; null, если это не она. */
internal fun MainActivity.readCfManifest(u: android.net.Uri): JSONObject? {
    try {
        java.util.zip.ZipInputStream(contentResolver.openInputStream(u)!!).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (zipClean(e.name) == "manifest.json") {
                    val j = JSONObject(String(z.readBytes(), Charsets.UTF_8))
                    return if (j.optString("manifestType") == "minecraftModpack" && j.optJSONObject("minecraft") != null && j.optJSONArray("files") != null) j else null
                }
                e = z.nextEntry
            }
        }
    } catch (_: Throwable) {}
    return null
}

/** Сборка CurseForge (zip с manifest.json): моды качаются через API; запрещённые автором к скачиванию предлагаются вручную. */
internal fun MainActivity.importCfPack(u: android.net.Uri, man: JSONObject) {
    if (BuildConfig.CF_KEY.isEmpty()) { Toast.makeText(this, tr("В этой сборке лаунчера нет ключа CurseForge", "This launcher build has no CurseForge key"), Toast.LENGTH_LONG).show(); return }
    val nm = man.optString("name", tr("Сборка", "Build")).ifBlank { tr("Сборка", "Build") }
    val mcj = man.getJSONObject("minecraft"); val mc = mcj.getString("version")
    var loaderId = ""
    mcj.optJSONArray("modLoaders")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (loaderId.isEmpty() || o.optBoolean("primary")) loaderId = o.optString("id") } }
    val kind = when { loaderId.startsWith("fabric") -> "fabric"; loaderId.startsWith("quilt") -> "quilt"; loaderId.startsWith("neoforge") -> "neoforge"; loaderId.startsWith("forge") -> "forge"; else -> "vanilla" }
    val files = man.getJSONArray("files")
    val overrides = man.optString("overrides", "overrides").ifEmpty { "overrides" }
    MobDialog(this).setTitle(tr("Импорт сборки CurseForge", "Import CurseForge pack"))
        .setMessage("«$nm»\n" + tr("Версия: ${kindLabel(kind, mc)}. Модов: ${files.length()}. Версию игры, если её нет, лаунчер скачает сам.", "Version: ${kindLabel(kind, mc)}. Mods: ${files.length()}. The game version is downloaded if missing."))
        .setNegativeButton(tr("Отмена", "Cancel"), null)
        .setPositiveButton(tr("Импортировать", "Import")) { _, _ ->
            val id = "b" + System.currentTimeMillis()
            val dir = buildDir(id)
            Toast.makeText(this, tr("Скачивание сборки…", "Downloading the pack…"), Toast.LENGTH_LONG).show()
            Thread {
                try {
                    dir.mkdirs()
                    val fileIds = (0 until files.length()).map { files.getJSONObject(it).getInt("fileID") }
                    val modIds = (0 until files.length()).map { files.getJSONObject(it).getInt("projectID") }.distinct()
                    val info = HashMap<Int, JSONObject>()       // fileId -> файл
                    for (ch in fileIds.chunked(100)) {
                        val d = cfGet("/mods/files", JSONObject().put("fileIds", JSONArray(ch)).toString()).getJSONArray("data")
                        for (i in 0 until d.length()) info[d.getJSONObject(i).getInt("id")] = d.getJSONObject(i)
                    }
                    val mods = HashMap<Int, JSONObject>()       // modId -> проект (класс, ссылка)
                    for (ch in modIds.chunked(100)) {
                        val d = cfGet("/mods", JSONObject().put("modIds", JSONArray(ch)).put("filterPcOnly", false).toString()).getJSONArray("data")
                        for (i in 0 until d.length()) mods[d.getJSONObject(i).getInt("id")] = d.getJSONObject(i)
                    }
                    class Task(val url: String, val dest: File, val sha: String?)
                    val tasks = ArrayList<Task>(); val manual = ArrayList<Pair<String, String>>()   // (название, ссылка)
                    for (i in 0 until files.length()) {
                        val fe = files.getJSONObject(i); val fid = fe.getInt("fileID"); val pid = fe.getInt("projectID")
                        val f = info[fid]; val proj = mods[pid]
                        val site = proj?.optJSONObject("links")?.optString("websiteUrl") ?: ""
                        if (f == null) { manual.add((proj?.optString("name") ?: "mod $pid") to (if (site.isNotEmpty()) "$site/files/$fid" else "")); continue }
                        val url = if (f.isNull("downloadUrl")) "" else f.optString("downloadUrl")
                        if (url.isEmpty()) { manual.add(f.optString("displayName", f.optString("fileName")) to (if (site.isNotEmpty()) "$site/files/$fid" else "")); continue }
                        val sub = when (proj?.optInt("classId")) { 12 -> "resourcepacks"; 6552 -> "shaderpacks"; 6945 -> "datapacks"; else -> "mods" }
                        val dest = File(dir, sub + "/" + zipClean(f.getString("fileName")).substringAfterLast('/'))
                        var sha: String? = null
                        f.optJSONArray("hashes")?.let { hs -> for (k in 0 until hs.length()) if (hs.getJSONObject(k).optInt("algo") == 1) sha = hs.getJSONObject(k).optString("value").ifEmpty { null } }
                        tasks.add(Task(url, dest, sha))
                    }
                    val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
                    val done = java.util.concurrent.atomic.AtomicInteger(0)
                    val futures = tasks.map { t ->
                        pool.submit<Boolean> {
                            val ok = download(t.url, t.dest, t.sha)
                            val n = done.incrementAndGet()
                            if (n % 10 == 0 || n == tasks.size) log("Сборка CurseForge: скачано $n из ${tasks.size}")
                            ok
                        }
                    }
                    val failed = futures.count { !it.get() }
                    pool.shutdown()
                    if (failed > 0) throw RuntimeException(tr("не скачалось файлов: $failed. Проверьте интернет и повторите", "files failed to download: $failed. Check your connection and retry"))
                    java.util.zip.ZipInputStream(contentResolver.openInputStream(u)!!).use { z ->
                        var e = z.nextEntry
                        val prefix = zipClean(overrides).trimEnd('/') + "/"
                        while (e != null) {
                            val n = zipClean(e.name)
                            if (!e.isDirectory && n.startsWith(prefix)) {
                                val out = File(dir, n.removePrefix(prefix))
                                if (out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                            }
                            e = z.nextEntry
                        }
                    }
                    runOnUiThread {
                        fun finish(profile: String) {
                            val all = builds()
                            all.put(JSONObject().put("id", id).put("name", nm).put("profile", profile))
                            buildsSave(all)
                            prefs.edit().putString("profile", profile).putString("build", id).apply()
                            versionText.text = verTitle()
                            Toast.makeText(this, tr("Сборка «$nm» добавлена и выбрана", "Build “$nm” added and selected"), Toast.LENGTH_LONG).show()
                            if (manual.isNotEmpty()) {
                                MobDialog(this).setTitle(tr("Скачайте вручную (${manual.size})", "Download manually (${manual.size})"))
                                    .setItems(manual.map { it.first }.toTypedArray()) { _, i ->
                                        if (manual[i].second.isNotEmpty()) try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(manual[i].second))) } catch (_: Throwable) {}
                                    }
                                    .setNegativeButton(tr("Закрыть", "Close"), null).show()
                                Toast.makeText(this, tr("Авторы этих модов запретили скачивание в лаунчерах. Скачайте файлы на сайте и добавьте через «Импорт» в каталоге.", "These mods' authors disabled launcher downloads. Download them on the site and add via Import."), Toast.LENGTH_LONG).show()
                            }
                        }
                        val have = findProfile(kind, mc)
                        if (have != null) finish(have)
                        else installProfile(kind, mc) { runOnUiThread { finish(findProfile(kind, mc) ?: selectedProfile()) } }
                    }
                } catch (e: Throwable) {
                    dir.deleteRecursively()
                    runOnUiThread { Toast.makeText(this, tr("Не удалось импортировать сборку: ", "Could not import the pack: ") + e.message, Toast.LENGTH_LONG).show() }
                }
            }.start()
        }.show()
}

/** Установка модпака из каталога: скачивает архив во временную папку и передаёт обычному импорту. */
internal fun MainActivity.installModpack(id: String, title: String, btn: Button) {
    btn.isEnabled = false; btn.alpha = 0.6f; btn.text = tr("Скачивание…", "Downloading…")
    Thread {
        var res = ""; var site = ""
        var file: File? = null
        var cf: JSONObject? = null; var mr: JSONObject? = null
        try {
            val tmp = File(cacheDir, "pack_" + System.currentTimeMillis() + (if (id.startsWith("cf:")) ".zip" else ".mrpack"))
            if (id.startsWith("cf:")) {
                val mid = id.substring(3).toInt()
                val fl = cfGet("/mods/$mid/files?pageSize=20").getJSONArray("data")
                var f: JSONObject? = null; var best = -1L
                for (i in 0 until fl.length()) {
                    val o = fl.getJSONObject(i)
                    val sc = (4 - o.optInt("releaseType", 3)) * 1_000_000_000_000L + (o.optString("fileDate").filter { it.isDigit() }.take(14).toLongOrNull() ?: 0L) / 1000
                    if (sc > best) { best = sc; f = o }
                }
                val ff = f ?: throw RuntimeException(tr("у сборки нет файлов", "the pack has no files"))
                val url = if (ff.isNull("downloadUrl")) "" else ff.optString("downloadUrl")
                if (url.isEmpty()) { res = "__site"; site = try { cfGet("/mods/$mid").getJSONObject("data").getJSONObject("links").optString("websiteUrl") } catch (_: Throwable) { "" } }
                else {
                    var sha: String? = null
                    ff.optJSONArray("hashes")?.let { hs -> for (k in 0 until hs.length()) if (hs.getJSONObject(k).optInt("algo") == 1) sha = hs.getJSONObject(k).optString("value").ifEmpty { null } }
                    if (!download(url, tmp, sha)) throw RuntimeException(tr("ошибка загрузки", "download failed"))
                    file = tmp; cf = readCfManifest(android.net.Uri.fromFile(tmp)) ?: throw RuntimeException(tr("это не сборка CurseForge", "not a CurseForge pack"))
                }
            } else {
                val vers = JSONArray(apiGet("/project/$id/version"))
                if (vers.length() == 0) throw RuntimeException(tr("у сборки нет файлов", "the pack has no files"))
                val files = vers.getJSONObject(0).getJSONArray("files")
                var f = files.getJSONObject(0)
                for (i in 0 until files.length()) if (files.getJSONObject(i).optBoolean("primary")) f = files.getJSONObject(i)
                if (!download(f.getString("url"), tmp, f.optJSONObject("hashes")?.optString("sha1")?.ifEmpty { null })) throw RuntimeException(tr("ошибка загрузки", "download failed"))
                file = tmp; mr = readMrpackIndex(android.net.Uri.fromFile(tmp)) ?: throw RuntimeException(tr("это не сборка Modrinth (.mrpack)", "not a Modrinth pack (.mrpack)"))
            }
            if (res.isEmpty()) res = "ok"
        } catch (e: Throwable) { res = e.message ?: e.toString() }
        runOnUiThread {
            btn.isEnabled = true; btn.alpha = 1f; btn.text = tr("Установить сборку", "Install pack")
            when {
                res == "ok" && file != null -> { val u = android.net.Uri.fromFile(file); if (cf != null) importCfPack(u, cf) else if (mr != null) importMrpack(u, mr) }
                res == "__site" -> MobDialog(this).setTitle(title)
                    .setMessage(tr("Автор отключил скачивание через сторонние лаунчеры. Скачайте архив на сайте CurseForge и добавьте через «Импорт».", "The author disabled third-party downloads. Download the archive on CurseForge and add it via Import."))
                    .setPositiveButton(tr("Открыть сайт", "Open site")) { _, _ -> if (site.isNotEmpty()) try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(site + "/files"))) } catch (_: Throwable) {} }
                    .setNegativeButton(tr("Закрыть", "Close"), null).show()
                else -> Toast.makeText(this, res, Toast.LENGTH_LONG).show()
            }
        }
    }.start()
}

/** Читает modrinth.index.json из .mrpack; null, если это не сборка Modrinth. */
internal fun MainActivity.readMrpackIndex(u: android.net.Uri): JSONObject? {
    java.util.zip.ZipInputStream(contentResolver.openInputStream(u)!!).use { z ->
        var e = z.nextEntry
        while (e != null) {
            if (zipClean(e.name) == "modrinth.index.json") {
                val j = JSONObject(String(z.readBytes(), Charsets.UTF_8))
                return if (j.optJSONObject("dependencies")?.optString("minecraft", "").isNullOrEmpty()) null else j
            }
            e = z.nextEntry
        }
    }
    return null
}

/** Сборка Modrinth (.mrpack): новая сборка, файлы скачиваются по хэшам, затем копируются overrides. Версия загрузчика берётся рекомендуемая лаунчером. */
internal fun MainActivity.importMrpack(u: android.net.Uri, idx: JSONObject) {
    val nm = idx.optString("name", tr("Сборка", "Build")).ifBlank { tr("Сборка", "Build") }
    val deps = idx.getJSONObject("dependencies"); val mc = deps.getString("minecraft")
    val kind = when {
        deps.has("fabric-loader") -> "fabric"
        deps.has("quilt-loader") -> "quilt"
        deps.has("neoforge") -> "neoforge"
        deps.has("forge") -> "forge"
        else -> "vanilla"
    }
    val files = idx.optJSONArray("files") ?: JSONArray()
    val ver = kindLabel(kind, mc)
    MobDialog(this).setTitle(tr("Импорт сборки Modrinth", "Import Modrinth pack"))
        .setMessage("«$nm»\n" + tr("Версия: $ver. Файлов для скачивания: ${files.length()}. Версию игры, если её нет, лаунчер скачает сам.", "Version: $ver. Files to download: ${files.length()}. The launcher downloads the game version if it is missing."))
        .setNegativeButton(tr("Отмена", "Cancel"), null)
        .setPositiveButton(tr("Импортировать", "Import")) { _, _ ->
            val id = "b" + System.currentTimeMillis()
            val dir = buildDir(id)
            Toast.makeText(this, tr("Скачивание сборки…", "Downloading the pack…"), Toast.LENGTH_LONG).show()
            Thread {
                try {
                    dir.mkdirs()
                    val tasks = ArrayList<Triple<List<String>, File, String?>>(); val optional = HashSet<File>()
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        val rel = zipClean(f.getString("path"))
                        val env = f.optJSONObject("env")
                        if (env?.optString("client", "required") == "unsupported") continue
                        val dest = File(dir, rel)
                        if (!dest.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue
                        val urls = f.getJSONArray("downloads").let { a -> (0 until a.length()).map { a.getString(it).replace("http://", "https://") } }
                        tasks.add(Triple(urls, dest, f.optJSONObject("hashes")?.optString("sha1", "")?.ifEmpty { null }))
                        if (env?.optString("client", "required") == "optional") optional.add(dest)
                    }
                    val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
                    val done = java.util.concurrent.atomic.AtomicInteger(0)
                    val futures = tasks.map { (urls, dest, sha) ->
                        pool.submit<Boolean> {
                            var ok = false
                            for (url in urls) { if (download(url, dest, sha)) { ok = true; break } }
                            val n = done.incrementAndGet()
                            if (n % 10 == 0 || n == tasks.size) log("Сборка Modrinth: скачано $n из ${tasks.size}")
                            ok || optional.contains(dest)
                        }
                    }
                    val failed = futures.count { !it.get() }
                    pool.shutdown()
                    if (failed > 0) throw RuntimeException(tr("не скачалось файлов: $failed. Проверьте интернет и повторите", "files failed to download: $failed. Check your connection and retry"))
                    // overrides: сначала общие, затем клиентские
                    for (prefix in listOf("overrides/", "client-overrides/")) {
                        java.util.zip.ZipInputStream(contentResolver.openInputStream(u)!!).use { z ->
                            var e = z.nextEntry
                            while (e != null) {
                                val n = zipClean(e.name)
                                if (!e.isDirectory && n.startsWith(prefix)) {
                                    val out = File(dir, n.removePrefix(prefix))
                                    if (out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                                }
                                e = z.nextEntry
                            }
                        }
                    }
                    runOnUiThread {
                        fun finish(profile: String) {
                            val all = builds()
                            all.put(JSONObject().put("id", id).put("name", nm).put("profile", profile))
                            buildsSave(all)
                            prefs.edit().putString("profile", profile).putString("build", id).apply()
                            versionText.text = verTitle()
                            Toast.makeText(this, tr("Сборка «$nm» добавлена и выбрана", "Build “$nm” added and selected"), Toast.LENGTH_LONG).show()
                        }
                        val have = findProfile(kind, mc)
                        if (have != null) finish(have)
                        else installProfile(kind, mc) { runOnUiThread { finish(findProfile(kind, mc) ?: selectedProfile()) } }
                    }
                } catch (e: Throwable) {
                    dir.deleteRecursively()
                    runOnUiThread { Toast.makeText(this, tr("Не удалось импортировать сборку: ", "Could not import the pack: ") + e.message, Toast.LENGTH_LONG).show() }
                }
            }.start()
        }.show()
}

/** Экспорт сборки в zip и отправка в любое приложение (Telegram и т.п.). */
internal fun MainActivity.shareBuild(b: JSONObject) {
    var worlds = false
    val pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    pane.addView(TextView(this).apply {
        text = tr("В архив попадут: название, версия игры, моды, ресурспаки и шейдеры.", "The archive will contain: name, game version, mods, resource packs and shaders.")
        textSize = 14f; setTextColor(Th.text2); setPadding(0, 0, 0, dp(12))
    })
    val chip = TextView(this).apply {
        textSize = 14f; setTextColor(Th.chipText); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
        fun paint() { setBackgroundColor(if (worlds) Th.act else Th.chip); text = (if (worlds) "✓  " else "") + tr("Миры и датапаки", "Worlds and datapacks") }
        paint(); setOnClickListener { worlds = !worlds; paint() }
    }
    pane.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    pane.addView(TextView(this).apply { text = tr("Миры могут быть большими: архив станет тяжелее.", "Worlds can be large: the archive will be heavier."); textSize = 12f; setTextColor(Th.dim); setPadding(0, dp(4), 0, 0) })
    MobDialog(this).setTitle(tr("Поделиться сборкой", "Share build")).setView(pane)
        .setNegativeButton(tr("Отмена", "Cancel"), null)
        .setPositiveButton(tr("Создать и отправить", "Create and send")) { _, _ ->
            val nm = b.optString("name"); val (kind, mc) = profileKindMc(b.optString("profile"))
            val src = buildDir(b.optString("id"))
            val withWorlds = worlds
            Toast.makeText(this, tr("Создаю архив…", "Creating the archive…"), Toast.LENGTH_SHORT).show()
            Thread {
                try {
                    val dir = File(cacheDir, "share").apply { deleteRecursively(); mkdirs() }
                    val fn = nm.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "build" } + ".zip"
                    val out = File(dir, fn)
                    java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { z ->
                        z.putNextEntry(java.util.zip.ZipEntry("mobilix-build.json"))
                        z.write(JSONObject().put("mobilix", 1).put("name", nm).put("kind", kind).put("mc", mc).put("profile", b.optString("profile")).toString().toByteArray())
                        z.closeEntry()
                        buildIcon(b.optString("id")).takeIf { it.exists() }?.let { ic ->
                            z.putNextEntry(java.util.zip.ZipEntry("icon.png")); ic.inputStream().use { it.copyTo(z) }; z.closeEntry()
                        }
                        val parts = mutableListOf("mods", "resourcepacks", "shaderpacks")
                        if (withWorlds) { parts.add("saves"); parts.add("datapacks") }
                        for (p in parts) File(src, p).walkTopDown().filter { it.isFile }.forEach { f ->
                            z.putNextEntry(java.util.zip.ZipEntry(p + "/" + f.relativeTo(File(src, p)).path.replace('\\', '/')))
                            f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                        }
                    }
                    val uri = android.net.Uri.parse("content://dev.mobilix.launcher.share/" + android.net.Uri.encode(fn))
                    runOnUiThread {
                        try {
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, nm); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                clipData = android.content.ClipData.newRawUri(fn, uri)
                            }, tr("Отправить сборку", "Send build")))
                        } catch (e: Throwable) { Toast.makeText(this, "Ошибка: $e", Toast.LENGTH_LONG).show() }
                    }
                } catch (e: Throwable) {
                    runOnUiThread { Toast.makeText(this, tr("Не удалось создать архив: ", "Could not create the archive: ") + e.message, Toast.LENGTH_LONG).show() }
                }
            }.start()
        }.show()
}
