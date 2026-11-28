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

// Установка версий и профилей: Forge/Fabric/Quilt/NeoForge, OptiFine, слияние профилей.
// Функции вынесены из MainActivity как расширения без изменения логики.

internal fun MainActivity.profileKindMc(p: String): Pair<String, String> = when {
    p.startsWith("corelix-") -> "corelix" to p.removePrefix("corelix-")
    p.startsWith("fabric-loader-") -> "fabric" to p.substringAfterLast("-")
    p.startsWith("quilt-loader-") -> "quilt" to p.substringAfterLast("-")
    p.startsWith("neoforge-") -> "neoforge" to (neoMc(p.removePrefix("neoforge-")) ?: p)
    p.contains("-forge-") -> "forge" to p.substringBefore("-forge-")
    Regex("^[\\d.]+-forge[\\d.]+-.+").matches(p) -> "forge" to p.substringBefore("-forge")
    else -> "vanilla" to p
}

/** Версии NeoForge для игры mc: сначала стабильные, затем бета; новые первыми. */
internal fun MainActivity.neoforgeCandidates(mc: String): List<String> {
    val all = LinkedHashSet<String>()
    try {
        val xml = fetchText("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
        Regex("<version>([^<]+)</version>").findAll(xml).forEach { all.add(it.groupValues[1]) }
    } catch (_: Throwable) {}
    var vs = all.filter { neoMc(it) == mc }
    if (vs.isEmpty()) try {
        val arr = JSONArray(fetchText("https://bmclapi2.bangbang93.com/neoforge/list/$mc"))
        vs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> o.optString("version", "").ifEmpty { o.optString("rawVersion", "").removePrefix("neoforge-") }.takeIf { s -> s.isNotEmpty() } } }
    } catch (_: Throwable) {}
    val (beta, stable) = vs.partition { it.contains("-") }
    return (stable.sortedWith(verCmp).reversed() + beta.sortedWith(verCmp).reversed()).take(6)
}

internal fun MainActivity.optifineFlow(mc: String, onDone: () -> Unit) {
    if (busy) { Toast.makeText(this, tr("Подождите: идёт другая загрузка", "Wait: another download is in progress"), Toast.LENGTH_SHORT).show(); return }
    Toast.makeText(this, tr("Ищем OptiFine для $mc…", "Looking for OptiFine for $mc…"), Toast.LENGTH_SHORT).show()
    Thread {
        try {
            val arr = JSONArray(fetchText("https://bmclapi2.bangbang93.com/optifine/$mc"))
            val items = (0 until arr.length()).map { arr.getJSONObject(it) }
            if (items.isEmpty()) { runOnUiThread { Toast.makeText(this, tr("Для $mc нет OptiFine", "No OptiFine for $mc"), Toast.LENGTH_LONG).show() }; return@Thread }
            // новые сверху: HD_U_I7 новее HD_U_I6 и HD_U_H9 (буква, затем номер)
            fun rank(o: JSONObject): Int { val p = o.optString("patch"); return (p.firstOrNull()?.code ?: 0) * 100 + (p.drop(1).toIntOrNull() ?: 0) }
            val sorted = items.sortedByDescending { rank(it) }.take(8)
            runOnUiThread {
                MobDialog(this).setTitle("OptiFine $mc")
                    .setMessage(tr("OptiFine ставится как мод в профиль Forge для этой версии. Нужен подходящий Forge: если игра не запустится, попробуйте другой Forge или другую сборку OptiFine.", "OptiFine is installed as a mod into the Forge profile for this version. It needs a matching Forge: if the game does not start, try another Forge or another OptiFine build."))
                    .setItems(sorted.map { it.optString("type") + " " + it.optString("patch") }.toTypedArray()) { _, i -> optifineInstall(mc, sorted[i], onDone) }
                    .setNegativeButton(tr("Отмена", "Cancel"), null).show()
            }
        } catch (e: Throwable) {
            runOnUiThread { Toast.makeText(this, tr("Не удалось получить список OptiFine: ", "Could not load the OptiFine list: ") + (e.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }.start()
}

internal fun MainActivity.optifineInstall(mc: String, o: JSONObject, onDone: () -> Unit) {
    fun fetchJar() {
        val pid = findProfile("forge", mc)
        if (pid == null) { runOnUiThread { Toast.makeText(this, tr("Forge для $mc не установился", "Forge for $mc was not installed"), Toast.LENGTH_LONG).show() }; return }
        Thread {
            try {
                val mods = File(gameDirFor(pid), "mods").apply { mkdirs() }
                mods.listFiles()?.filter { it.name.startsWith("OptiFine", true) }?.forEach { it.delete() }
                val name = o.optString("filename").ifEmpty { "OptiFine_${mc}_${o.optString("type")}_${o.optString("patch")}.jar" }
                val dest = File(mods, name)
                val ok = download("https://bmclapi2.bangbang93.com/optifine/$mc/${o.optString("type")}/${o.optString("patch")}", dest, null)
                if (!ok || dest.length() < 100000) { dest.delete(); throw RuntimeException(tr("ошибка загрузки", "download failed")) }
                prefs.edit().putString("profile", pid).putString("build", "").apply()
                runOnUiThread {
                    versionText.text = verTitle()
                    Toast.makeText(this, tr("OptiFine установлен и выбран профиль Forge $mc", "OptiFine installed and Forge $mc selected"), Toast.LENGTH_LONG).show()
                    onDone()
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, tr("Не удалось поставить OptiFine: ", "Could not install OptiFine: ") + (e.message ?: ""), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
    if (findProfile("forge", mc) != null) fetchJar() else installProfile("forge", mc) { fetchJar() }
}

internal fun MainActivity.installProfile(kind: String, mc: String, done: () -> Unit) {
    setBusy(true)
    Thread {
        try {
            val root = dataRoot()
            logFile = File(root, "launcher.log").also { it.writeText("") }
            var id = mc
            when (kind) {
                "corelix" -> {
                    if (mc !in corelixSupported()) throw RuntimeException("Corelix не поддерживает $mc")
                    id = corelixProfileId(mc)
                    val prof = JSONObject().put("id", id).put("inheritsFrom", mc).put("type", "release")
                        .put("mainClass", "net.minecraft.client.main.Main").put("libraries", JSONArray())
                        .put("arguments", JSONObject().put("jvm", JSONArray()).put("game", JSONArray()))
                    val f = File(root, "versions/$id/$id.json"); f.parentFile?.mkdirs(); f.writeText(prof.toString())
                    prepareAndRun(id, false)
                    ensureCorelix()   // заранее скачиваем загрузчик, чтобы первый запуск не ждал
                }
                "fabric" -> {
                    log("Fabric $mc: поиск загрузчика…")
                    val ls = JSONArray(fetchText("https://meta.fabricmc.net/v2/versions/loader/$mc"))
                    var pick: String? = null
                    for (i in 0 until ls.length()) {
                        val l = ls.getJSONObject(i).getJSONObject("loader")
                        if (l.optBoolean("stable")) { pick = l.getString("version"); break }
                    }
                    if (pick == null && ls.length() > 0) pick = ls.getJSONObject(0).getJSONObject("loader").getString("version")
                    if (pick == null) throw RuntimeException("Fabric не поддерживает $mc")
                    val prof = fetchText("https://meta.fabricmc.net/v2/versions/loader/$mc/$pick/profile/json")
                    id = JSONObject(prof).getString("id")
                    val f = File(root, "versions/$id/$id.json"); f.parentFile?.mkdirs(); f.writeText(prof)
                    prepareAndRun(id, false)
                }
                "quilt" -> {
                    log("Quilt $mc: поиск загрузчика…")
                    val ls = JSONArray(fetchText("https://meta.quiltmc.org/v3/versions/loader/$mc"))
                    var pick: String? = null
                    for (i in 0 until ls.length()) {
                        val v = ls.getJSONObject(i).getJSONObject("loader").getString("version")
                        if (!v.contains("beta", true) && !v.contains("rc", true) && !v.contains("alpha", true)) { pick = v; break }
                    }
                    if (pick == null && ls.length() > 0) pick = ls.getJSONObject(0).getJSONObject("loader").getString("version")
                    if (pick == null) throw RuntimeException("Quilt не поддерживает $mc")
                    val prof = fetchText("https://meta.quiltmc.org/v3/versions/loader/$mc/$pick/profile/json")
                    id = JSONObject(prof).getString("id")
                    val f = File(root, "versions/$id/$id.json"); f.parentFile?.mkdirs(); f.writeText(prof)
                    prepareAndRun(id, false)
                }
                "neoforge" -> {
                    prepareAndRun(mc, false)
                    val cands = neoforgeCandidates(mc)
                    if (cands.isEmpty()) throw RuntimeException("NeoForge для $mc не найден")
                    val installer = File(root, "forge-installer.jar")
                    installer.delete()
                    var got = false; var lastErr: Exception? = null
                    for (nv in cands) {
                        log("NeoForge $nv: скачивание установщика…")
                        val u0 = "https://maven.neoforged.net/releases/net/neoforged/neoforge/$nv/neoforge-$nv-installer.jar"
                        for (u in listOf(u0) + mirrorsFor(u0) + listOf("https://bmclapi2.bangbang93.com/neoforge/version/$nv/download/installer")) {
                            try {
                                downloadBig(u, installer)
                                if (installer.length() > 100000) { got = true; break }
                                installer.delete()
                            } catch (e: Exception) { lastErr = e; installer.delete() }
                        }
                        if (got) break
                    }
                    if (!got) throw RuntimeException("не удалось скачать установщик NeoForge: $lastErr")
                    val java = ensureRuntime(root, 21)
                    val rt = java.parentFile!!.parentFile!!
                    val lp = File(root, "launcher_profiles.json")
                    if (!lp.exists()) lp.writeText("{\"profiles\":{}}")
                    var found: String? = null
                    var inst = installer
                    val ilogF = File(getExternalFilesDir(null) ?: filesDir, "installer.log")
                    for (attempt in 1..6) {
                        log("NeoForge: установка, попытка $attempt из 6 (это долго, потерпите)…")
                        runOnUiThread { Toast.makeText(this, tr(
                            "NeoForge ставится в фоне: не закрывайте лаунчер, это может занять несколько минут (попытка $attempt из 6).",
                            "NeoForge is installing in the background: do not close the launcher, it may take a few minutes (attempt $attempt of 6)."), Toast.LENGTH_LONG).show() }
                        // у разных версий установщика ключ называется по-разному: чередуем «--installClient» и «--install-client»
                        runForgeInstaller(rt, inst, root, if (attempt % 2 == 1) "--installClient" else "--install-client")
                        found = findProfile("neoforge", mc)
                        val miss = if (found != null) forgeMissingOutputs(installer, root, found) else emptyList()
                        if (found != null && miss.isEmpty()) break
                        if (miss.isNotEmpty()) log("NeoForge: установка не завершилась, нет файлов: ${miss.joinToString(", ")}")
                        found = null
                        if (inst == installer && try { ilogF.readText().let { it.contains("Expected:") && it.contains("Actual:") } } catch (_: Exception) { false }) {
                            try { inst = installerWithoutHashes(installer); log("NeoForge: контрольные суммы постобработки не совпали; продолжаю без их проверки") }
                            catch (e: Exception) { log("NeoForge: не удалось подготовить установщик без проверки сумм: $e") }
                        }
                        Thread.sleep(2500)
                    }
                    id = found ?: throw RuntimeException("NeoForge не установился до конца. Попробуйте ещё раз или пришлите лог (кнопка «Лог» и файл installer.log).")
                }
                "forge" -> {
                    prepareAndRun(mc, false)
                    val cands = forgeCandidates(mc)
                    if (cands.isEmpty()) throw RuntimeException("Forge для $mc не найден")
                    val installer = File(root, "forge-installer.jar")
                    installer.delete()
                    var got = false; var lastErr: Exception? = null
                    // Рекомендуемая сборка может не иметь установщика на сервере (HTTP 404): пробуем следующие и зеркала
                    for (fv in cands) {
                        log("Forge $mc-$fv: скачивание установщика…")
                        for (suf in listOf("", "-$mc", "-$mc.0")) {
                            val u0 = "https://maven.minecraftforge.net/net/minecraftforge/forge/$mc-$fv$suf/forge-$mc-$fv$suf-installer.jar"
                            for (u in listOf(u0) + mirrorsFor(u0)) {
                                try {
                                    downloadBig(u, installer)
                                    if (installer.length() > 100000) { got = true; break }
                                    installer.delete()
                                } catch (e: Exception) { lastErr = e; installer.delete() }
                            }
                            if (got) break
                        }
                        if (got) break
                    }
                    if (!got) throw RuntimeException("не удалось скачать установщик Forge: $lastErr")
                    val legacyMc = try { JSONObject(File(root, "versions/$mc/$mc.json").readText()).optJSONObject("arguments") == null } catch (_: Exception) { false }
                    var legacyId: String? = null
                    if (legacyMc) {
                        // Старые версии: установщик не запускаем, берём из него описание версии и файл Forge
                        log("Forge: распаковка установщика…")
                        try { legacyId = installLegacyForge(installer, root) }
                        catch (e: RuntimeException) {
                            // у части сборок 1.12.2 в установщике нет versionInfo: тогда запускаем установщик целиком
                            if (e.message?.contains("versionInfo") == true) log("Forge: в установщике нет versionInfo, запускаю его целиком")
                            else throw e
                        }
                    }
                    if (legacyId != null) {
                        id = legacyId
                        log("Профиль $id создан, докачиваю библиотеки…")
                        prepareAndRun(id, false)
                    } else {
                        val java = ensureRuntime(root, 21)
                        val rt = java.parentFile!!.parentFile!!
                        val lp = File(root, "launcher_profiles.json")
                        if (!lp.exists()) lp.writeText("{\"profiles\":{}}")
                        var found: String? = null
                        var inst = installer
                        val ilogF = File(getExternalFilesDir(null) ?: filesDir, "installer.log")
                        for (attempt in 1..6) {
                            log("Forge: установка, попытка $attempt из 6 (это долго, потерпите)…")
                            runOnUiThread { Toast.makeText(this, tr(
                                "Forge ставится в отдельном окне с чёрным экраном: не закрывайте его, оно может мигать. Если установка прервётся, лаунчер продолжит с того места (попытка $attempt из 6).",
                                "Forge installs in a separate black window: do not close it, it may flicker. If the install is interrupted the launcher resumes where it stopped (attempt $attempt of 6)."), Toast.LENGTH_LONG).show() }
                            runForgeInstaller(rt, inst, root)
                            found = findProfile("forge", mc)
                            // профиль появляется раньше, чем постпроцессоры создадут srg/extra/client: проверяем сами файлы
                            val miss = if (found != null) forgeMissingOutputs(installer, root, found) else emptyList()
                            if (found != null && miss.isEmpty()) break
                            if (miss.isNotEmpty()) log("Forge: установка не завершилась, нет файлов: ${miss.joinToString(", ")}")
                            found = null
                            // контрольные суммы постобработки не совпали (на некоторых телефонах zip сжимается иначе, содержимое то же):
                            // дальше ставим без проверки этих сумм — Forge при запуске их не сверяет
                            if (inst == installer && try { ilogF.readText().let { it.contains("Expected:") && it.contains("Actual:") } } catch (_: Exception) { false }) {
                                try {
                                    inst = installerWithoutHashes(installer)
                                    log("Forge: контрольные суммы постобработки не совпали (на этом телефоне иначе сжимается zip); продолжаю без их проверки")
                                } catch (e: Exception) { log("Forge: не удалось подготовить установщик без проверки сумм: $e") }
                            }
                            Thread.sleep(2500)
                        }
                        id = found ?: throw RuntimeException("Forge не установился до конца (не созданы файлы постобработки). Попробуйте ещё раз, закрыв другие приложения.")
                        if (legacyMc) { log("Профиль $id создан, докачиваю библиотеки…"); prepareAndRun(id, false) }
                    }
                }
                else -> prepareAndRun(mc, false)
            }
            prefs.edit().putString("profile", id).apply()
            runOnUiThread { versionText.text = verTitle() }
            log("Готово: $id")
        } catch (e: Throwable) {
            log("ОШИБКА установки: $e")
            runOnUiThread { Toast.makeText(this, tr("Ошибка установки: ", "Install error: ") + e.message, Toast.LENGTH_LONG).show() }
        } finally {
            setBusy(false)
            done()
        }
    }.start()
}

internal fun MainActivity.mavenPath(name: String): String {
    val ext = if (name.contains("@")) name.substringAfter("@") else "jar"
    val p = name.substringBefore("@").split(":")
    val g = p[0].replace('.', '/'); val a = p[1]; val ver = p[2]
    return "$g/$a/$ver/$a-$ver" + (if (p.size > 3) "-" + p[3] else "") + ".$ext"
}

internal fun MainActivity.mergeProfile(base: JSONObject, child: JSONObject?): JSONObject {
    if (child == null) return base
    val m = JSONObject(base.toString())
    m.put("mainClass", child.getString("mainClass"))
    val libs = JSONArray()
    for (src in listOf(base, child)) {
        val a = src.optJSONArray("libraries") ?: continue
        for (i in 0 until a.length()) libs.put(a.get(i))
    }
    m.put("libraries", libs)
    val args = JSONObject()
    for (k in listOf("jvm", "game")) {
        val arr = JSONArray()
        for (src in listOf(base, child)) {
            val a = src.optJSONObject("arguments")?.optJSONArray(k) ?: continue
            for (i in 0 until a.length()) arr.put(a.get(i))
        }
        args.put(k, arr)
    }
    m.put("arguments", args)
    if (child.has("minecraftArguments")) { m.put("minecraftArguments", child.getString("minecraftArguments")); m.remove("arguments") }
    return m
}
