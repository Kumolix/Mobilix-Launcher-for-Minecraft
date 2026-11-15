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

// Установка Forge: правка jar, старый установщик, запуск установщика без окна.
// Функции вынесены из MainActivity как расширения без изменения логики.

internal fun MainActivity.patchForgeJar(jar: File) {
    val mark = File(jar.path + ".mobilix2")
    if (mark.exists() || !jar.exists()) return
    // пары замен одинаковой длины: Pack200 и внутренние sun.reflect.*, которых нет в Java 12+
    val pairs = listOf(
        "java/util/jar/Pack200" to "dev/mobilix/Pack200ab",
        "sun.reflect.ReflectionFactory" to "dev.mobilix.ReflectionFactory",
        "sun.reflect.FieldAccessor" to "dev.mobilix.FieldAccessor",
        "sun.reflect.ConstructorAccessor" to "dev.mobilix.ConstructorAccessor"
    ).map { it.first.toByteArray() to it.second.toByteArray() }
    val tmp = File(jar.path + ".tmp")
    var changed = 0
    java.util.zip.ZipFile(jar).use { z ->
        java.util.zip.ZipOutputStream(tmp.outputStream().buffered()).use { out ->
            for (e in z.entries()) {
                val n = e.name
                if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".DSA") || n.endsWith(".RSA") || n.endsWith(".EC"))) continue
                var data = z.getInputStream(e).readBytes()
                if (n.endsWith(".class")) {
                    for ((from, to) in pairs) {
                        var i = 0
                        while (i <= data.size - from.size) {
                            var j = 0
                            while (j < from.size && data[i + j] == from[j]) j++
                            if (j == from.size) { System.arraycopy(to, 0, data, i, to.size); changed++; i += from.size } else i++
                        }
                    }
                }
                out.putNextEntry(java.util.zip.ZipEntry(n))
                out.write(data)
                out.closeEntry()
            }
        }
    }
    jar.delete()
    tmp.renameTo(jar)
    mark.writeText("ok")
    log("Forge: jar пропатчен под Java 21 ($changed замен)")
}

/** Ставит Forge для 1.8–1.12.2 без запуска установщика: версия из install_profile.json + jar Forge из архива. */
internal fun MainActivity.installLegacyForge(installer: File, root: File): String {
    java.util.zip.ZipFile(installer).use { z ->
        val prof = JSONObject(z.getInputStream(z.getEntry("install_profile.json")).bufferedReader().readText())
        val vi = prof.optJSONObject("versionInfo") ?: throw RuntimeException("в установщике нет versionInfo")
        val inst = prof.getJSONObject("install")
        val id = vi.getString("id")
        val jf = File(root, "versions/$id/$id.json"); jf.parentFile?.mkdirs(); jf.writeText(vi.toString())
        val entry = z.getEntry(inst.getString("filePath")) ?: throw RuntimeException("в установщике нет ${inst.getString("filePath")}")
        val dest = File(File(root, "libraries"), mavenPath(inst.getString("path")))
        dest.parentFile?.mkdirs()
        z.getInputStream(entry).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        return id
    }
}

/** Запускает установщик Forge внутри процесса :game (в нём уже есть встроенная Java). */
/** Файлы, которые постпроцессоры установщика Forge 1.13+ должны создать (srg, extra, пропатченный клиент); пусто, если всё на месте. */
internal fun MainActivity.forgeMissingOutputs(installer: File, root: File, profile: String? = null): List<String> {
    return try {
        java.util.zip.ZipFile(installer).use { z ->
            val prof = JSONObject(z.getInputStream(z.getEntry("install_profile.json")).bufferedReader().readText())
            val data = prof.optJSONObject("data") ?: return emptyList()
            val patched = data.optJSONObject("PATCHED")?.optString("client", "") ?: ""
            // установщик в корне мог остаться от другой версии Forge: тогда ничего не проверяем
            if (profile != null && patched.length > 2) {
                val ver = patched.trim('[', ']').split(":").getOrNull(2) ?: ""
                if (ver.contains("-") && !profile.contains(ver.substringAfter("-"))) return emptyList()
            }
            val missing = ArrayList<String>()
            for (k in listOf("MC_SRG", "MC_EXTRA", "PATCHED")) {
                val v = data.optJSONObject(k)?.optString("client", "") ?: continue
                if (v.startsWith("[") && v.endsWith("]")) {
                    val f = File(File(root, "libraries"), mavenPath(v.substring(1, v.length - 1)))
                    if (!f.exists() || f.length() < 1000) missing.add(f.name)
                }
            }
            missing
        }
    } catch (_: Exception) { emptyList() }
}

/** Копия установщика Forge без контрольных сумм результатов постобработки (и без подписей, иначе правка файла сломает загрузку). */
internal fun MainActivity.installerWithoutHashes(src: File): File {
    val dst = File(src.parentFile, src.nameWithoutExtension + "-nohash.jar")
    java.util.zip.ZipFile(src).use { z ->
        java.util.zip.ZipOutputStream(dst.outputStream().buffered()).use { out ->
            for (e in z.entries()) {
                val n = e.name
                if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))) continue
                out.putNextEntry(java.util.zip.ZipEntry(n))
                if (n == "install_profile.json") {
                    val j = JSONObject(z.getInputStream(e).readBytes().toString(Charsets.UTF_8))
                    j.optJSONArray("processors")?.let { arr -> for (i in 0 until arr.length()) arr.getJSONObject(i).remove("outputs") }
                    out.write(j.toString().toByteArray(Charsets.UTF_8))
                } else if (!e.isDirectory) z.getInputStream(e).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
    return dst
}

/** Куча установщика загрузчика: не меньше, чем у игры + 512 МБ, но не больше ~55% ОЗУ (иначе Android убивает процесс/перезагружает телефон). */
internal fun MainActivity.installerHeapMb(): Int {
    val mi = android.app.ActivityManager.MemoryInfo()
    (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
    val totalMb = (mi.totalMem / 1024 / 1024).toInt()
    val base = if (totalMb >= 6000) 1280 else 768
    return minOf(maxOf(base, gameXmxMb() + 512), totalMb * 55 / 100).coerceAtLeast(512).let { if (is32bit) it.coerceAtMost(1280) else it }
}

internal fun MainActivity.runForgeInstaller(rt: File, installer: File, root: File, installFlag: String = "--installClient") {
    val mainClass = try {
        java.util.jar.JarFile(installer).use { it.manifest?.mainAttributes?.getValue("Main-Class") }
    } catch (_: Exception) { null } ?: "net.minecraftforge.installer.SimpleInstaller"
    val j = JSONObject()
    j.put("javaHome", rt.path)
    j.put("main", mainClass)
    j.put("jvm", JSONArray(listOf("-cp", installer.path, "-Xmx${installerHeapMb()}M", "-XX:+UseSerialGC", "-Djava.awt.headless=true",
        "-Duser.home=${root.path}", "-Djava.io.tmpdir=${cacheDir.path}", "-Duser.dir=${root.path}")))
    j.put("game", JSONArray(listOf(installFlag, root.path)))
    j.put("glLib", "")
    File(root, "installer.json").writeText(j.toString())
    val done = File(getExternalFilesDir(null) ?: filesDir, "installer.done").apply { delete() }
    val ilog = File(getExternalFilesDir(null) ?: filesDir, "installer.log").apply { delete() }
    val dd = getExternalFilesDir(null) ?: filesDir
    File(dd, "installer.started").delete(); File(dd, "installer.restarted").delete()
    // сначала пробуем службу без окна; если она не стартовала за 20 с, запасной путь — окно, как раньше
    var viaService = true
    runOnUiThread {
        try {
            val si = Intent(this, InstallerService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
        } catch (_: Throwable) { viaService = false }
    }
    val ts = System.currentTimeMillis()
    while (viaService && !File(dd, "installer.started").exists() && System.currentTimeMillis() - ts < 20000L) Thread.sleep(300)
    if (!viaService || !File(dd, "installer.started").exists()) {
        log("Forge: служба установки не запустилась, использую окно установщика")
        try { stopService(Intent(this, InstallerService::class.java)) } catch (_: Throwable) {}
        runOnUiThread { startActivity(Intent(this, GameActivity::class.java).putExtra("mode", "installer")) }
    }
    var shown = 0L; var noise = 0
    val t0 = System.currentTimeMillis()
    while (System.currentTimeMillis() - t0 < 30 * 60 * 1000L) {
        Thread.sleep(1500)
        if (File(dd, "installer.restarted").exists()) { log("Forge: процесс установщика был остановлен системой, окно открылось заново; перехожу к следующей попытке"); break }
        try {
            if (ilog.exists() && ilog.length() < shown) shown = 0
            if (ilog.exists() && ilog.length() > shown) {
                java.io.RandomAccessFile(ilog, "r").use { raf ->
                    raf.seek(shown)
                    val buf = ByteArray((raf.length() - shown).toInt()); raf.readFully(buf)
                    shown = raf.length()
                    String(buf).lines().filter { it.isNotBlank() }.forEach {
                        // установщик печатает по строке на каждый класс клиента: не засоряем лог тысячами «Slim …»
                        if (it.startsWith("Slim ") || it.startsWith("Extra ")) {
                            if (++noise % 500 == 0) log("Forge: разбор клиента… обработано классов: $noise")
                        } else log(it)
                    }
                }
            }
        } catch (_: Exception) {}
        if (done.exists()) break
        // процесс :game мог завершиться вместе с установщиком (System.exit)
        if (System.currentTimeMillis() - t0 > 20000) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val alive = am.runningAppProcesses?.any { it.processName == "$packageName:game" } ?: true
            if (!alive) break
        }
    }
    // итог: чем закончился процесс установщика (нужно, чтобы отличить ошибку Forge от того, что систему убила процесс)
    try {
        if (done.exists()) log("Forge: установщик завершился: " + done.readText().trim().take(300))
        else log("Forge: процесс установщика оборвался без результата (вероятно, система остановила его из-за нехватки памяти или нагрева)")
        if (ilog.exists()) ilog.readLines().filter { it.isNotBlank() && !it.startsWith("Slim ") && !it.startsWith("Extra ") }.takeLast(12).forEach { log("  | $it") }
    } catch (_: Exception) {}
}

// ---------- вспомогательное ----------
