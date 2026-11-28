package dev.mobilix.launcher

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Corelix: загрузчик с оптимизациями для клиента (репозиторий с публичными релизами). */
internal object CorelixManager {
    const val REPO = "Kumolix/OptiCore"
    const val MAIN = "dev.corelix.loader.CorelixMain"
    /** Версии Minecraft, под которые собран Corelix (минимум; релиз может дополнить список строкой «mc: 1.21.3, 1.21.4» в описании). */
    val SUPPORTED = setOf("1.21.3")
    const val BUNDLE_ASSET = "corelix-bundle.zip"
}

// Распакованный релиз: corelix-loader.jar, папки libs и mods
internal class CorelixBundle(val dir: File, val tag: String) {
    val loader = File(dir, "corelix-loader.jar")
    val libs = File(dir, "libs")
    val mods = File(dir, "mods")
}

/** Профиль Corelix: отдельный загрузчик поверх ванильной версии, id вида corelix-1.21.3. */
internal fun corelixProfileId(mc: String) = "corelix-$mc"

private fun httpText(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 6000
    c.readTimeout = 10000
    c.setRequestProperty("User-Agent", "Mobilix-Labs")
    c.setRequestProperty("Accept", "application/vnd.github+json")
    try {
        if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally { c.disconnect() }
}

private fun sha256Of(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { i ->
        val buf = ByteArray(65536)
        while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Версии, для которых Corelix доступен: встроенный минимум + список из последнего скачанного релиза. */
internal fun MainActivity.corelixSupported(): Set<String> =
    CorelixManager.SUPPORTED + (prefs.getString("corelixMc", "") ?: "").split(",").map { it.trim() }.filter { it.matches(Regex("[0-9][0-9A-Za-z._-]{0,15}")) }

/** Есть ли уже скачанный релиз Corelix (кеш). */
internal fun MainActivity.corelixCached(): Boolean {
    val tag = prefs.getString("corelixTag", "") ?: ""
    return tag.isNotEmpty() && CorelixBundle(File(File(filesDir, "corelix"), "bundle-$tag"), tag).isValid()
}

private fun CorelixBundle.isValid() = File(dir, "READY").isFile && loader.isFile

private fun unzipSafe(zip: File, dest: File) {
    dest.mkdirs()
    val root = dest.canonicalPath + File.separator
    ZipInputStream(zip.inputStream().buffered()).use { zin ->
        while (true) {
            val e = zin.nextEntry ?: break
            val out = File(dest, e.name)
            if (!out.canonicalPath.startsWith(root)) throw java.io.IOException("bad entry ${e.name}")
            if (e.isDirectory) { out.mkdirs(); continue }
            out.parentFile?.mkdirs()
            out.outputStream().use { zin.copyTo(it) }
        }
    }
}

/**
 * Возвращает последний релиз Corelix. Проверяет GitHub Releases; если вышел новый, скачивает бандл,
 * сверяет sha256 и распаковывает. Без сети (или при сбое) берёт ранее скачанный. null: Corelix недоступен.
 */
internal fun MainActivity.ensureCorelix(): CorelixBundle? {
    val base = File(filesDir, "corelix").apply { mkdirs() }
    val cachedTag = prefs.getString("corelixTag", "") ?: ""
    val cached = if (cachedTag.isNotEmpty()) CorelixBundle(File(base, "bundle-$cachedTag"), cachedTag).takeIf { it.isValid() } else null
    try {
        val rel = JSONObject(httpText("https://api.github.com/repos/${CorelixManager.REPO}/releases/latest"))
        val tag = rel.getString("tag_name")
        // список версий Minecraft из описания релиза: строка «mc: 1.21.3, 1.21.4»
        Regex("(?im)^\\s*(?:mc|minecraft)\\s*[:=]\\s*(.+)$").find(rel.optString("body"))?.let { m ->
            prefs.edit().putString("corelixMc", m.groupValues[1].split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")).apply()
        }
        if (!tag.matches(Regex("[A-Za-z0-9._-]{1,40}"))) throw java.io.IOException("bad tag")
        if (cached != null && cached.tag == tag) return cached
        val assets = rel.getJSONArray("assets")
        var zipUrl: String? = null
        var shaUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            when (a.getString("name")) {
                CorelixManager.BUNDLE_ASSET -> zipUrl = a.getString("browser_download_url")
                CorelixManager.BUNDLE_ASSET + ".sha256" -> shaUrl = a.getString("browser_download_url")
            }
        }
        if (zipUrl == null || shaUrl == null) throw java.io.IOException("в релизе $tag нет бандла")
        log(tr("Corelix: скачиваю релиз $tag...", "Corelix: downloading release $tag..."))
        val expected = httpText(shaUrl).trim().lowercase().take(64)
        val tmpZip = File(base, "download.zip").also { it.delete() }
        if (!download(zipUrl, tmpZip, null)) throw java.io.IOException(lastDownloadError)
        val actual = sha256Of(tmpZip)
        if (actual != expected) { tmpZip.delete(); throw java.io.IOException("sha256 не совпал") }
        val target = File(base, "bundle-$tag")
        val tmpDir = File(base, "bundle-$tag.tmp").also { it.deleteRecursively() }
        unzipSafe(tmpZip, tmpDir)
        tmpZip.delete()
        if (!File(tmpDir, "corelix-loader.jar").isFile) { tmpDir.deleteRecursively(); throw java.io.IOException("в бандле нет загрузчика") }
        File(tmpDir, "READY").writeText(tag)
        target.deleteRecursively()
        if (!tmpDir.renameTo(target)) throw java.io.IOException("не удалось сохранить бандл")
        prefs.edit().putString("corelixTag", tag).apply()
        base.listFiles()?.filter { it.name.startsWith("bundle-") && it != target }?.forEach { it.deleteRecursively() }
        log(tr("Corelix $tag установлен.", "Corelix $tag installed."))
        return CorelixBundle(target, tag)
    } catch (e: Exception) {
        if (cached != null) {
            log(tr("Corelix: проверка обновления не удалась ($e), использую ${cached.tag}.", "Corelix: update check failed ($e), using ${cached.tag}."))
            return cached
        }
        log(tr("Corelix: не удалось получить релиз ($e), запускаю ванильную игру.", "Corelix: could not get a release ($e), launching vanilla."))
        return null
    }
}
