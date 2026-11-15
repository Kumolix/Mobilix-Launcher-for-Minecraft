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

// Сеть и скачивание: файлы, кэш, зеркала, JSON, проверка SHA-1, список файлов игры.
// Функции вынесены из MainActivity как расширения без изменения логики.

/** Большой файл с прогрессом; пишется во временный файл и переименовывается после полной загрузки. */
internal fun MainActivity.downloadBig(url: String, dest: File) {
    val tmp = File(dest.path + ".part")
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 20000
    c.readTimeout = 60000
    c.setRequestProperty("User-Agent", "Mobilix/1.4")
    val code = c.responseCode
    if (code != 200) throw RuntimeException("HTTP $code")
    val total = c.contentLengthLong
    var read = 0L
    dest.parentFile?.mkdirs()
    c.inputStream.use { i ->
        tmp.outputStream().use { o ->
            val buf = ByteArray(65536)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                read += n
                if (total > 0) runOnUiThread { progress.progress = (read * 100 / total).toInt() }
            }
        }
    }
    runOnUiThread { progress.progress = 0 }
    if (total > 0 && tmp.length() != total) {
        tmp.delete()
        throw RuntimeException("файл скачан не полностью")
    }
    dest.delete()
    if (!tmp.renameTo(dest)) throw RuntimeException("не удалось сохранить файл")
    log("  готово, ${dest.length() / 1024 / 1024} МБ")
}

/** Правила Mojang. Для Android считаем систему linux, фичи (demo и т.п.) отключены. */
internal fun MainActivity.allowed(o: JSONObject): Boolean {
    val rules = o.optJSONArray("rules") ?: return true
    var ok = false
    for (i in 0 until rules.length()) {
        val r = rules.getJSONObject(i)
        if (r.has("features")) continue
        val os = r.optJSONObject("os")
        val match = os == null ||
            (os.optString("name").let { it.isEmpty() || it.startsWith("linux") } && os.optString("arch") != "x86")
        if (match) ok = r.getString("action") == "allow"
    }
    return ok
}

internal fun MainActivity.collectArgs(arr: JSONArray?, sub: (String) -> String): List<String> {
    val out = ArrayList<String>()
    if (arr == null) return out
    for (i in 0 until arr.length()) {
        val a = arr.get(i)
        if (a is String) {
            out.add(sub(a))
        } else if (a is JSONObject && allowed(a)) {
            val value = a.get("value")
            if (value is String) {
                out.add(sub(value))
            } else if (value is JSONArray) {
                for (j in 0 until value.length()) out.add(sub(value.getString(j)))
            }
        }
    }
    return out
}

internal fun MainActivity.offlineUuid(name: String): String {
    val b = MessageDigest.getInstance("MD5").digest("OfflinePlayer:$name".toByteArray())
    b[6] = ((b[6].toInt() and 0x0f) or 0x30).toByte()
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
    val bb = ByteBuffer.wrap(b)
    return UUID(bb.long, bb.long).toString()
}

internal fun MainActivity.fetchOne(u: String, connectMs: Int, readMs: Int): String {
    val c = URL(u).openConnection() as HttpURLConnection
    c.connectTimeout = connectMs
    c.readTimeout = readMs
    c.setRequestProperty("User-Agent", "Mobilix/1.4")
    return c.inputStream.bufferedReader().use { it.readText() }
}

/** Официальный адрес и зеркало опрашиваются одновременно, берётся первый ответ (раньше шли по очереди: недоступный адрес стоил до 45 с). */
internal fun MainActivity.fetchText(url: String): String {
    val urls = listOf(url) + mirrorsFor(url)
    if (urls.size == 1) return fetchOne(url, 15000, 30000)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(urls.size)
    try {
        return pool.invokeAny(urls.map { u -> java.util.concurrent.Callable { fetchOne(u, 10000, 30000) } })
    } catch (e: java.util.concurrent.ExecutionException) {
        throw (e.cause as? Exception) ?: e
    } finally {
        pool.shutdownNow()
    }
}

/** Списки версий почти не меняются: свежий кэш (до [ttlMs]) отдаётся сразу, при сбое сети берётся и устаревший. */
internal fun MainActivity.fetchCached(url: String, key: String, ttlMs: Long = 6L * 3600 * 1000): String {
    val f = File(cacheDir, "list_$key.txt")
    if (f.exists() && System.currentTimeMillis() - f.lastModified() < ttlMs) try { return f.readText() } catch (_: Exception) {}
    return try {
        val t = fetchText(url)
        try { f.writeText(t) } catch (_: Exception) {}
        t
    } catch (e: Exception) {
        if (f.exists()) f.readText() else throw e
    }
}

internal fun MainActivity.sha1Of(f: File): String {
    val md = MessageDigest.getInstance("SHA-1")
    f.inputStream().use { i ->
        val buf = ByteArray(65536)
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Версии Forge для игры $mc, от лучшей к худшей: рекомендуемая, последняя, затем свежие из maven-metadata и BMCLAPI. */
internal fun MainActivity.forgeCandidates(mc: String): List<String> {
    val out = LinkedHashSet<String>()
    try {
        val promos = JSONObject(fetchText("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")).getJSONObject("promos")
        promos.optString("$mc-recommended", "").takeIf { it.isNotEmpty() }?.let { out.add(it) }
        promos.optString("$mc-latest", "").takeIf { it.isNotEmpty() }?.let { out.add(it) }
    } catch (_: Throwable) {}
    fun key(v: String) = v.split(".", "-").map { it.toIntOrNull() ?: 0 }
    val cmp = Comparator<String> { a, b ->
        val x = key(a); val y = key(b)
        var r = 0
        for (i in 0 until maxOf(x.size, y.size)) { r = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 }); if (r != 0) break }
        r
    }
    try {
        val xml = fetchText("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml")
        val vs = Regex("<version>" + Regex.escape(mc) + "-([^<]+)</version>").findAll(xml).map { it.groupValues[1] }.toList()
        out.addAll(vs.sortedWith(cmp).reversed().take(6))
    } catch (_: Throwable) {}
    try {
        val arr = JSONArray(fetchText("https://bmclapi2.bangbang93.com/forge/minecraft/$mc"))
        val vs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("version", "")?.takeIf { v -> v.isNotEmpty() } }
        out.addAll(vs.sortedWith(cmp).reversed().take(6))
    } catch (_: Throwable) {}
    return out.toList().take(8)
}

/** Зеркало BMCLAPI: подстановка для хостов Mojang, Forge и Fabric. Файлы всё равно проверяются по sha1. */
internal fun MainActivity.mirrorsFor(url: String): List<String> {
    val m = "https://bmclapi2.bangbang93.com"
    return when {
        url.startsWith("https://maven.neoforged.net/releases/") -> listOf(m + "/maven/" + url.removePrefix("https://maven.neoforged.net/releases/"))
        url.startsWith("https://meta.quiltmc.org/") -> listOf(m + "/quilt-meta/" + url.removePrefix("https://meta.quiltmc.org/"))
        url.startsWith("https://maven.quiltmc.org/repository/release/") -> listOf(m + "/quilt-maven/" + url.removePrefix("https://maven.quiltmc.org/repository/release/"))
        url.startsWith("https://resources.download.minecraft.net/") ->
            listOf(m + "/assets/" + url.removePrefix("https://resources.download.minecraft.net/"))
        url.startsWith("https://libraries.minecraft.net/") ->
            listOf(m + "/maven/" + url.removePrefix("https://libraries.minecraft.net/"))
        url.startsWith("https://piston-data.mojang.com/") ->
            listOf(m + "/" + url.removePrefix("https://piston-data.mojang.com/"))
        url.startsWith("https://piston-meta.mojang.com/") ->
            listOf(m + "/" + url.removePrefix("https://piston-meta.mojang.com/"))
        url.startsWith("https://launcher.mojang.com/") ->
            listOf(m + "/" + url.removePrefix("https://launcher.mojang.com/"))
        url.startsWith("https://maven.minecraftforge.net/") ->
            listOf(m + "/maven/" + url.removePrefix("https://maven.minecraftforge.net/"))
        url.startsWith("https://maven.fabricmc.net/") ->
            listOf(m + "/maven/" + url.removePrefix("https://maven.fabricmc.net/"))
        url.startsWith("https://meta.fabricmc.net/") ->
            listOf(m + "/fabric-meta/" + url.removePrefix("https://meta.fabricmc.net/"))
        else -> emptyList()
    }
}

internal fun MainActivity.download(url: String, dest: File, sha1: String?): Boolean {
    if (dest.exists() && dest.length() > 0) return true
    dest.parentFile?.mkdirs()
    val tmp = File(dest.path + ".part")
    val urls = listOf(url) + mirrorsFor(url)
    for (attempt in 0 until 4) {
        val u = urls[attempt % urls.size]
        try {
            dest.parentFile?.mkdirs()
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 40000
            c.setRequestProperty("User-Agent", "Mobilix/1.4")
            val code = c.responseCode
            if (code != 200) {
                lastDownloadError = "HTTP $code ($u)"
            } else {
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                if (sha1 == null || sha1Of(tmp) == sha1) {
                    dest.delete()
                    if (tmp.renameTo(dest)) return true
                    lastDownloadError = "не удалось сохранить ($u)"
                } else {
                    lastDownloadError = "sha1 не совпал ($u)"
                }
            }
        } catch (e: Exception) {
            lastDownloadError = "$e ($u)"
        }
        tmp.delete()
        try {
            Thread.sleep(700L * (attempt + 1))
        } catch (_: InterruptedException) {
        }
    }
    return false
}

/** Качает файлы в несколько проходов. fatal=false: при неудаче только предупреждение. */
internal fun MainActivity.downloadAll(label: String, tasks: List<Task>, fatal: Boolean = true) {
    if (tasks.isEmpty()) return
    log("$label: ${tasks.size} файлов")
    var pending = tasks.distinctBy { it.dest.path }.filter { !(it.dest.exists() && it.dest.length() > 0) }
    if (pending.size < tasks.size) log("  уже скачано: ${tasks.size - pending.size}, осталось: ${pending.size}")
    for (pass in 1..3) {
        if (pending.isEmpty()) break
        if (pass > 1) {
            log("  повтор, осталось ${pending.size}. Последняя ошибка: $lastDownloadError")
            Thread.sleep(2500)
        }
        val done = AtomicInteger(0)
        val total = pending.size
        val failedList = java.util.Collections.synchronizedList(ArrayList<Task>())
        val pool = Executors.newFixedThreadPool(if (prefs.getBoolean("multiDl", true)) 16 else 3)
        val futures = pending.map { t ->
            pool.submit(Runnable {
                if (!download(t.url, t.dest, t.sha1)) failedList.add(t)
                val d = done.incrementAndGet()
                if (d % 25 == 0 || d == total) {
                    runOnUiThread { progress.progress = d * 100 / total }
                }
            })
        }
        futures.forEach { it.get() }
        pool.shutdown()
        runOnUiThread { progress.progress = 0 }
        pending = ArrayList(failedList)
    }
    if (pending.isNotEmpty()) {
        val msg = "$label: не скачано ${pending.size} файлов. Причина: $lastDownloadError"
        if (fatal) throw RuntimeException(msg)
        log("ПРЕДУПРЕЖДЕНИЕ: $msg")
        log("Продолжаем без них (для проверки Java они не нужны).")
    }
}
