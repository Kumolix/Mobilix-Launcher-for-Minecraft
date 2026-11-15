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

// Пресеты графики: выбор по видеочипу и памяти, запись настроек, мипмапы.
// Функции вынесены из MainActivity как расширения без изменения логики.

internal fun MainActivity.mcMinor(ver: String): Int = ver.split('.').getOrNull(1)?.toIntOrNull() ?: 12

/** Выбор пресета: настройки лаунчера меняются сразу, options.txt — при ближайшем запуске каждой версии (один раз). */
internal fun MainActivity.pickPreset(i: Int) {
    val pr = presets[i]
    prefs.edit()
        .putInt("preset", i + 1)
        .putInt("presetStamp", prefs.getInt("presetStamp", 0) + 1)
        .putInt("renderScale", pr.render)
        .putInt("fps", pr.fps)
        .putInt("mip", -1)       // мипмапы ставит пресет один раз, а не при каждом запуске
        .apply()
    writeGameSettings()
}

/** Первый запуск без выбранного пресета: берём более слабое из двух — видеочип или память (у телефонов видеопамять общая с ОЗУ). Выше «Средних» сами не ставим. */
internal fun MainActivity.autoPreset(): Int {
    val tier = GpuTier.tier(prefs.getString("gpu", "") ?: "")
    val byGpu = when (tier) { null -> 3; 0 -> 1; 1 -> 2; else -> 3 }
    val mi = android.app.ActivityManager.MemoryInfo()
    (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
    val totalMb = mi.totalMem / 1024 / 1024
    val byMem = when { totalMb < 3000 -> 1; totalMb < 4500 -> 2; else -> 3 }
    log("Авто-пресет: видеочип «${prefs.getString("gpu", "")}» (уровень ${tier ?: "?"}) → $byGpu, память $totalMb МБ → $byMem")
    return minOf(byGpu, byMem)
}

internal fun MainActivity.applyPreset(gameDir: File, ver: String) {
    try {
        val chosen = prefs.getInt("preset", 0)
        val auto = chosen <= 0
        val stamp = if (auto) "auto" else prefs.getInt("presetStamp", 0).toString()
        val marker = (if (auto) "presetAuto:" else "presetApplied:") + gameDir.name
        if (prefs.getString(marker, "") == stamp) return
        if (auto) {
            // игрок пресет не выбирал: подбираем один раз, и только для папки, где игра ещё не писала свои настройки
            val o = File(gameDir, "options.txt")
            if (o.exists() && o.readText().contains("renderDistance:")) { prefs.edit().putString(marker, stamp).apply(); return }
        } else if (chosen > presets.size) return
        val id = if (auto) autoPreset() else chosen
        val pr = presets[id - 1]
        val minor = mcMinor(ver)
        val kv = LinkedHashMap<String, String>()
        kv["renderDistance"] = (if (minor <= 8) pr.chunks.coerceAtMost(16) else pr.chunks).toString()
        kv["enableVsync"] = "false"
        kv["maxFps"] = if (pr.fps <= 0) "260" else pr.fps.toString()
        kv["mipmapLevels"] = pr.mip.toString()
        kv["ao"] = if (minor >= 14) pr.ao.toString() else if (pr.ao) "2" else "0"
        kv["fancyGraphics"] = pr.fancy.toString()
        if (minor >= 16) kv["graphicsMode"] = if (pr.fancy) "1" else "0"
        if (minor <= 15) kv["useVbo"] = pr.vbo.toString()
        if (minor <= 8) kv["clouds"] = pr.clouds.toString() else kv["renderClouds"] = pr.clouds.toString()
        kv["particles"] = pr.particles.toString()
        // нагрузка на процессор: дальность симуляции и сущностей (сильно влияет на слабых телефонах)
        val weak = pr.chunks <= 12
        if (minor >= 18) kv["simulationDistance"] = (if (pr.chunks <= 6) 5 else if (weak) 8 else 12).toString()
        if (minor >= 16) kv["entityDistanceScaling"] = (if (pr.chunks <= 6) "0.5" else if (weak) "0.75" else "1.0")
        if (minor >= 21) kv["chunkSectionFadeInTime"] = if (weak) "0.0" else "0.75"
        kv["entityShadows"] = pr.fancy.toString()
        if (minor >= 15) kv["biomeBlendRadius"] = if (pr.fancy) "2" else if (pr.ao) "1" else "0"
        val opt = File(gameDir, "options.txt")
        val keep = if (opt.exists()) opt.readLines().filter { l -> kv.keys.none { l.startsWith("$it:") } } else emptyList()
        opt.writeText((keep + kv.map { "${it.key}:${it.value}" }).joinToString("\n") + "\n")
        prefs.edit().putString(marker, stamp).apply()
        log("Быстрые настройки графики: ${pr.ru}" + (if (auto) " (авто по видеочипу и памяти)" else "") + " (${gameDir.name})")
    } catch (_: Throwable) {}
}

/**
 * Уровень мипмапов в options.txt. Число из настроек ставится при каждом запуске.
 * «Авто»: видеочип берём из game.log прошлого запуска; для Adreno 6xx и старше (медленная загрузка текстур)
 * один раз ставим 0 для этой папки игры, потом выбор игрока в самой игре не трогаем.
 */

internal fun MainActivity.applyMipmap(gameDir: File, oldGl: Boolean) {
    try {
        ensureGpu()
        writeGameSettings()
        val dir = getExternalFilesDir(null) ?: filesDir
        try {
            val lg = File(dir, "game.log")
            if ((prefs.getString("gpu", "") ?: "").isEmpty() && lg.exists()) {
                val buf = ByteArray(300000)
                val n = java.io.FileInputStream(lg).use { it.read(buf) }
                if (n > 0) {
                    val m = Regex("\\[Mobilix\\] GPU: (.+?) \\|").find(String(buf, 0, n))
                    if (m != null) prefs.edit().putString("gpu", m.groupValues[1].trim()).apply()
                }
            }
        } catch (_: Throwable) {}
        val gpu = prefs.getString("gpu", "") ?: ""
        val tier = GpuTier.tier(gpu)
        val pref = prefs.getInt("mip", -1)
        val marker = "mipAuto:" + gameDir.name
        val want = when {
            pref >= 0 -> pref
            tier != null && tier < 4 && prefs.getString(marker, "") != gpu -> tier
            else -> return
        }
        val opt = File(gameDir, "options.txt")
        val lines = if (opt.exists()) opt.readLines().filter { !it.startsWith("mipmapLevels:") } else emptyList()
        opt.writeText((lines + "mipmapLevels:$want").joinToString("\n") + "\n")
        if (pref < 0) prefs.edit().putString(marker, gpu).apply()
        log("Мипмапы: $want" + (if (pref < 0) " (авто, видеочип: $gpu, уровень $tier из 4)" else " (из настроек)"))
    } catch (_: Throwable) {}
}

// ---------- MobileGlues (OpenGL поверх OpenGL ES) ----------
