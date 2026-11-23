package dev.mobilix.launcher

import android.content.Context
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

// Установка Java-рантайма, LWJGL, MobileGlues и ANGLE; конфиг рендерера.
// Функции вынесены из MainActivity как расширения без изменения логики.

/** Берёт libmobileglues.so из плагина MobileGlues (LGPL-2.1). Возвращает путь к файлу или null. */
internal fun MainActivity.ensureMobileGlues(): String? {
    val dir = File(filesDir, "mg")
    fun find() = if (!dir.exists()) null else dir.walkTopDown()
        .firstOrNull { it.isFile && it.name == "libmobileglues.so" && it.parentFile?.name == apkAbiDir }
    find()?.let {
        log("MobileGlues уже установлен.")
        return it.path
    }
    log("Установка MobileGlues...")
    try {
        val json = JSONArray(fetchText("https://api.github.com/repos/MobileGL-Dev/MobileGlues-release/releases?per_page=10"))
        var best: JSONObject? = null
        search@ for (i in 0 until json.length()) {
            val assets = json.getJSONObject(i).optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val n = a.getString("name")
                if (n.endsWith(".apk", true) && n.contains("mobileglues", true)) {
                    best = a
                    break@search
                }
            }
        }
        if (best == null) {
            log("  APK MobileGlues не найден в релизах")
            return null
        }
        log("  скачивание ${best.getString("name")}")
        val apk = File(dataRoot(), "mg-plugin.apk")
        downloadBig(best.getString("browser_download_url"), apk)
        dir.deleteRecursively()
        dir.mkdirs()
        extractZip(apk, dir)
        val f = find()
        if (f == null) {
            log("  libmobileglues.so не найдена в APK. Содержимое:")
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach { log("    " + it.relativeTo(dir).path) }
            return null
        }
        f.setReadable(true, false)
        log("  готово: ${f.name}")
        return f.path
    } catch (e: Exception) {
        log("  ошибка MobileGlues: $e")
        return null
    }
}

// ---------- LWJGL под Android ----------

/** Возвращает значение для -Dorg.lwjgl.librarypath или null, если библиотеки получить не удалось. */
internal fun MainActivity.ensureLwjgl(version: String): String? {
    if (version.isEmpty()) {
        log("Версия LWJGL не определена, пропуск.")
        return null
    }
    val dir = File(filesDir, "lwjgl")
    fun hasMain() = dir.exists() && dir.walkTopDown().any { it.isFile && it.name == "liblwjgl.so" }
    // нативные библиотеки должны точно совпадать с jar игры (3.2.2 для 1.17–1.18, 3.3.x для остальных)
    val stamp = File(dir, ".mobilix-lwjgl")
    val want = if (isArm64) version else "pojav"
    val have = try { stamp.readText().trim() } catch (e: Exception) { "" }
    if (hasMain() && have != want) {
        log("LWJGL: установлена другая версия ('" + (have.ifEmpty { "?" }) + "'), нужна '$want', переустановка")
        dir.deleteRecursively()
    }
    if (!hasMain()) {
        log("Установка LWJGL $version для Android...")
        val archive = (if (isArm64) downloadLwjgl(version) else downloadPojavLwjgl()) ?: return null
        dir.deleteRecursively()
        dir.mkdirs()
        try {
            if (archive.name.endsWith(".zip")) extractZip(archive, dir) else extractTar(archive, dir)
        } catch (e: Exception) {
            log("  ошибка распаковки: $e")
            return null
        }
        try { stamp.writeText(want) } catch (e: Exception) {}
    } else {
        log("LWJGL уже установлен.")
    }
    if (!hasMain()) {
        log("liblwjgl.so не найдена в архиве. Содержимое:")
        dir.walkTopDown().maxDepth(5).forEach { log("  " + it.relativeTo(dir).path) }
        return null
    }
    val libs = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.toList()
    libs.forEach { it.setReadable(true, false); it.setExecutable(true, false) }
    log("LWJGL: библиотек ${libs.size}: " + libs.joinToString(", ") { it.name })
    return libs.map { it.parentFile!!.path }.distinct().joinToString(":")
}

internal fun MainActivity.downloadLwjgl(version: String): File? {
    val archiveRe = Regex("(?i).*\\.(zip|tar\\.xz|tar\\.gz)$")
    val skipRe = Regex("(?i)src|source|sha|sums")
    for (repo in LWJGL_REPOS) {
        try {
            log("Источник LWJGL: $repo")
            val json = JSONArray(fetchText("https://api.github.com/repos/$repo/releases?per_page=30"))
            var best: JSONObject? = null
            var bestScore = -1
            for (i in 0 until json.length()) {
                val rel = json.getJSONObject(i)
                val tag = rel.optString("tag_name")
                val assets = rel.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val name = a.getString("name")
                    if (!archiveRe.matches(name) || skipRe.containsMatchIn(name)) continue
                    if (!tag.contains(version) && !name.contains(version)) continue
                    var score = 1
                    if (name.contains("arm64", true) || name.contains("aarch64", true)) score += 2
                    if (name.contains("lwjgl", true)) score += 1
                    if (score > bestScore) {
                        bestScore = score
                        best = a
                    }
                }
            }
            if (best == null) {
                log("  нет файла для версии $version")
                continue
            }
            val name = best.getString("name")
            val ext = when {
                name.endsWith(".zip") -> ".zip"
                name.endsWith(".tar.gz") -> ".tar.gz"
                else -> ".tar.xz"
            }
            val dest = File(dataRoot(), "lwjgl-natives$ext")
            log("  скачивание $name (${best.optLong("size") / 1024} КБ)")
            downloadBig(best.getString("browser_download_url"), dest)
            return dest
        } catch (e: Exception) {
            log("  ошибка: $e")
        }
    }
    log("ПРЕДУПРЕЖДЕНИЕ: LWJGL для Android не получен. Игра остановится на загрузке liblwjgl.so.")
    return null
}

/**
 * Нативная часть JNA для Android: libjnidispatch.so лежит в AAR (jni/arm64-v8a) на Maven Central, версия должна совпадать с jar игры
 * (JNA сверяет версии). Версии до 5.x и не-arm64 не трогаем. Ошибка не мешает запуску: игра стартует и без системной информации.
 */
internal fun MainActivity.ensureJna(ver: String): String? {
    if (!isArm64 || ver.isEmpty()) return null
    val major = ver.substringBefore('.').toIntOrNull() ?: return null
    if (major < 5) { log("JNA $ver: версии до 5.x не поддерживаются, нативная часть не подставляется."); return null }
    val dir = File(filesDir, "jna/$ver")
    val so = File(dir, "libjnidispatch.so")
    if (so.exists() && so.length() > 10_000) { log("JNA $ver: libjnidispatch.so уже установлена."); return dir.path }
    return try {
        dir.mkdirs()
        val aar = File(dataRoot(), "jna-$ver.aar")
        log("JNA $ver: скачивание libjnidispatch.so (AAR)")
        downloadBig("https://repo1.maven.org/maven2/net/java/dev/jna/jna/$ver/jna-$ver.aar", aar)
        java.util.zip.ZipFile(aar).use { z ->
            val e = z.getEntry("jni/arm64-v8a/libjnidispatch.so") ?: throw RuntimeException("в AAR нет jni/arm64-v8a/libjnidispatch.so")
            z.getInputStream(e).use { i -> so.outputStream().use { o -> i.copyTo(o) } }
        }
        aar.delete()
        so.setReadable(true, false); so.setExecutable(true, false)
        dir.path
    } catch (e: Exception) {
        so.delete()
        log("JNA $ver: не удалось получить libjnidispatch.so ($e)")
        null
    }
}

internal fun MainActivity.extractZip(archive: File, dest: File) {
    val destPath = dest.canonicalPath
    ZipInputStream(BufferedInputStream(archive.inputStream())).use { zin ->
        var e = zin.nextEntry
        while (e != null) {
            val out = File(dest, e.name)
            val p = out.canonicalPath
            if (p == destPath || p.startsWith(destPath + File.separator)) {
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o -> zin.copyTo(o) }
                }
            }
            e = zin.nextEntry
        }
    }
}

// ---------- Java-рантайм ----------

internal fun MainActivity.findJava(rt: File): File? {
    if (!rt.exists()) return null
    return rt.walkTopDown().maxDepth(4)
        .firstOrNull { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
}

internal fun MainActivity.ensureRuntime(root: File, javaMajor: Int): File {
    val rt = runtimeDir(javaMajor)
    findJava(rt)?.let {
        log("Java-рантайм уже установлен.")
        return it
    }
    if (javaMajor >= 22) return ensurePojavRuntime(rt, 25)
    if (!isArm64) return ensurePojavRuntime(rt, 21)
    log("Установка Java-рантайма...")
    val local = listOf("runtime.tar.xz", "runtime.tar.gz").map { File(root, it) }
        .firstOrNull { it.exists() && it.length() > 0 }
    if (local != null) log("Найден локальный архив ${local.name}")
    val archive = local ?: downloadRuntime(root)
    rt.deleteRecursively()
    rt.mkdirs()
    log("Распаковка ${archive.name} (${archive.length() / 1024 / 1024} МБ)...")
    extractTar(archive, rt)
    val java = findJava(rt)
    if (java == null) {
        log("В рантайме нет bin/java. Содержимое:")
        rt.walkTopDown().maxDepth(3).forEach { log("  " + it.relativeTo(rt).path) }
        throw RuntimeException("Рантайм имеет неожиданную структуру")
    }
    java.parentFile?.listFiles()?.forEach { it.setExecutable(true, false) }
    val home = java.parentFile!!.parentFile!!
    File(home, "release").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("JAVA_VERSION") }?.let { log("Рантайм: $it (нужна $javaMajor)") }
    return java
}

/** Java 21 для arm / x86 / x86_64: общая часть (universal) + части под ABI (bin-<abi>), обе распаковываются в одну папку. */
internal fun MainActivity.ensurePojavRuntime(rt: File, ver: Int): File {
    log("Установка Java $ver для $abiId...")
    rt.deleteRecursively(); rt.mkdirs()
    for (n in listOf("pojav-jre$ver-universal.tar.xz", "pojav-jre$ver-bin-$abiId.tar.xz")) {
        val dest = File(dataRoot(), n)
        log("  скачивание $n")
        downloadBig(MIRROR + n, dest)
        log("  распаковка $n")
        extractTar(dest, rt)
        dest.delete()
    }
    val java = findJava(rt) ?: run {
        rt.walkTopDown().maxDepth(3).forEach { log("  " + it.relativeTo(rt).path) }
        throw RuntimeException("В рантайме нет bin/java")
    }
    java.parentFile?.listFiles()?.forEach { it.setExecutable(true, false) }
    File(java.parentFile!!.parentFile!!, "release").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("JAVA_VERSION") }?.let { log("Рантайм: $it") }
    return java
}

internal fun MainActivity.downloadPojavLwjgl(): File? = try {
    val n = "pojav-lwjgl-natives-$abiId.zip"
    val dest = File(dataRoot(), n)
    log("  нативы LWJGL для $abiId: $n")
    downloadBig(MIRROR + n, dest)
    dest
} catch (e: Exception) { log("  ошибка: $e"); null }

internal fun MainActivity.downloadRuntime(root: File): File {
    val pattern = Regex("(?i)^(?!.*src).*21.*(arm64|aarch64).*\\.tar\\.(xz|gz)$")
    var lastError = "нет источников"
    for (repo in RUNTIME_REPOS) {
        try {
            log("Источник Java: $repo")
            val json = JSONArray(fetchText("https://api.github.com/repos/$repo/releases?per_page=15"))
            var asset: JSONObject? = null
            search@ for (i in 0 until json.length()) {
                val assets = json.getJSONObject(i).optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (pattern.matches(a.getString("name"))) {
                        asset = a
                        break@search
                    }
                }
            }
            if (asset == null) {
                lastError = "$repo: нет подходящего файла"
                log("  нет подходящего файла")
                continue
            }
            val name = asset.getString("name")
            val ext = if (name.endsWith(".gz")) "tar.gz" else "tar.xz"
            val dest = File(root, "runtime.$ext")
            log("  скачивание $name (${asset.optLong("size") / 1024 / 1024} МБ)")
            downloadBig(asset.getString("browser_download_url"), dest)
            return dest
        } catch (e: Exception) {
            lastError = "$repo: $e"
            log("  ошибка: $e")
        }
    }
    throw RuntimeException(
        "Не удалось скачать Java ($lastError). Можно положить архив вручную в ${root.path} под именем runtime.tar.xz"
    )
}

internal fun MainActivity.extractTar(archive: File, dest: File) {
    val destPath = dest.canonicalPath
    val raw = BufferedInputStream(archive.inputStream())
    val stream = if (archive.name.endsWith(".gz")) GZIPInputStream(raw) else XZCompressorInputStream(raw)
    TarArchiveInputStream(stream).use { tin ->
        var e = tin.nextTarEntry
        while (e != null) {
            val out = File(dest, e.name)
            val p = out.canonicalPath
            if (p == destPath || p.startsWith(destPath + File.separator)) {
                when {
                    e.isDirectory -> out.mkdirs()
                    e.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        Files.createSymbolicLink(out.toPath(), Paths.get(e.linkName))
                    }
                    e.isLink -> {
                        out.parentFile?.mkdirs()
                        try {
                            Files.copy(File(dest, e.linkName).toPath(), out.toPath())
                        } catch (_: Exception) {
                        }
                    }
                    e.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o -> tin.copyTo(o) }
                        if ((e.mode and 73) != 0) out.setExecutable(true, false)
                    }
                }
            }
            e = tin.nextTarEntry
        }
    }
}

/**
 * Старый Forge вызывает java.util.jar.Pack200, которого нет в Java 14+. Заменяем ссылку на наш класс (строка той же длины)
 * прямо в jar Forge; подписи удаляем, иначе изменённый класс не пройдёт проверку. Делается один раз.
 */

/** Профиль рендера Renderlix (настройки MobileGlues): 0 — стандарт, 1 — моды-шейдеры (Sodium/Iris). Пишет config.json и возвращает папку. */
/** Каталог с libEGL_angle.so / libGLESv2_angle.so, если выбран рендерер «MobileGlues + ANGLE»: сначала наша копия (files/angle), потом плагин MobileGlues. */
internal fun MainActivity.angleActive(): File? {
    if (prefs.getInt("renderer", 0) != 2) return null
    val own = File(filesDir, "angle")
    if (File(own, "libEGL_angle.so").exists() && File(own, "libGLESv2_angle.so").exists()) return own
    val dir = File(filesDir, "mg")
    val egl = dir.walkTopDown().firstOrNull { it.isFile && it.name == "libEGL_angle.so" && it.path.contains("arm64") }
    val gles = dir.walkTopDown().firstOrNull { it.isFile && it.name == "libGLESv2_angle.so" && it.path.contains("arm64") }
    if (egl == null || gles == null || egl.parentFile != gles.parentFile) return null
    return egl.parentFile
}

/** Скачивает ANGLE (arm64) из runtime-libs, если выбран «MG + ANGLE» и библиотек ещё нет. Сбой не фатален: ANGLE просто выключится. */
internal fun MainActivity.ensureAngle() {
    if (prefs.getInt("renderer", 0) != 2 || !isArm64) return
    if (angleActive() != null) return
    val dir = File(filesDir, "angle").apply { mkdirs() }
    try {
        for (n in listOf("libEGL_angle.so", "libGLESv2_angle.so")) {
            val f = File(dir, n)
            if (!f.exists() || f.length() < 100_000) { log("  скачивание ANGLE: $n"); downloadBig(MIRROR + "angle/arm64-v8a/" + n, f) }
            f.setReadable(true, false)
        }
        // копия рядом с libmobileglues.so: MobileGlues может искать ANGLE именно там
        try { ensureMobileGlues()?.let { mg -> File(mg).parentFile?.let { d -> for (n in listOf("libEGL_angle.so", "libGLESv2_angle.so")) File(dir, n).copyTo(File(d, n), true) } } } catch (_: Exception) {}
        log("ANGLE: библиотеки готовы (${dir.path})")
    } catch (e: Exception) {
        File(dir, "libEGL_angle.so").delete(); File(dir, "libGLESv2_angle.so").delete()
        log("ANGLE недоступен ($e), MobileGlues работает без него.")
    }
}

internal fun MainActivity.writeRenderConfig(): File {
    val dir = File(filesDir, "mg"); dir.mkdirs()
    val preset = 2   // Renderlix убран из настроек: профиль Sodium/Iris включается сам, если в сборке есть такие моды
    val shaders = preset == 1 || (preset == 2 && try { CrashDoctor.hasRenderMods(curGameDir()) } catch (_: Throwable) { false })
    if (preset == 3) {
        val cc = JSONObject()
            .put("enableANGLE", if (angleActive() != null) 3 else 0)
            .put("enableNoError", prefs.getInt("rl_noerr", 0))
            .put("enableExtComputeShader", prefs.getInt("rl_compute", 0))
            .put("enableExtTimerQuery", 1)
            .put("enableExtDirectStateAccess", 1)
            .put("angleDepthClearFixMode", 0)
            .put("customGLVersion", prefs.getInt("rl_gl", 0))
            .put("maxGlslCacheSize", prefs.getInt("rl_cache", 0))
            .put("fsr1Setting", prefs.getInt("rl_fsr", 0))
            .put("hideMGEnvLevel", 0)
        File(dir, "config.json").writeText(cc.toString())
        log("Renderlix: свой профиль: " + cc)
        return dir
    }
    val c = JSONObject()
        .put("enableANGLE", if (angleActive() != null) 3 else 0)
        .put("enableNoError", if (shaders) 2 else 0)            // 2 = игнорировать ошибки шейдеров (частично)
        .put("enableExtComputeShader", if (shaders) 1 else 0)
        .put("enableExtTimerQuery", 1)
        .put("enableExtDirectStateAccess", 1)
        .put("angleDepthClearFixMode", 0)
        .put("customGLVersion", if (shaders) 45 else 0)         // 0 = по умолчанию (4.0)
        .put("maxGlslCacheSize", if (shaders) 256 else 0)       // МБ
        .put("fsr1Setting", 0)
        .put("hideMGEnvLevel", 0)
    File(dir, "config.json").writeText(c.toString())
    log("Renderlix: профиль «" + (if (shaders) "Sodium/Iris" else "стандарт") + "»" + (if (preset == 2) " (авто)" else "") + ", конфиг: " + dir.path)
    return dir
}

/** Память игры (МБ): ручная настройка или авто по ОЗУ устройства. */
internal fun MainActivity.gameXmxMb(modern: Boolean = true): Int {
    val mi = android.app.ActivityManager.MemoryInfo()
    (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
    val totalMb = mi.totalMem / 1024 / 1024
    val manualX = prefs.getInt("xmx", 0)
    val v = if (manualX > 0) manualX else when { totalMb < 3500 -> (totalMb * (if (modern) 30 else 25) / 100).toInt().coerceIn(512, 1024)   // слабые устройства: львиная доля ОЗУ занята системой
        totalMb < 5000 -> if (modern) 1280 else 1024; totalMb < 10000 -> if (modern) 2048 else 1536; else -> 2048 }.toInt()
    return if (is32bit) v.coerceAtMost(1024) else v   // 32-битный процесс: адресного пространства ~3 ГБ на всё
}
