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

// Подготовка и запуск игры.
// Функции вынесены из MainActivity как расширения без изменения логики.

/** Последние строки файла: читаем только хвост (game.log бывает в десятки МБ, readLines грузил его целиком при каждом запуске). */
internal fun MainActivity.lastLinesOf(f: File, maxBytes: Int = 65536): List<String> {
    java.io.RandomAccessFile(f, "r").use { r ->
        val start = maxOf(0L, r.length() - maxBytes)
        val buf = ByteArray((r.length() - start).toInt())
        r.seek(start); r.readFully(buf)
        val lines = String(buf, Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }
        return if (start > 0) lines.drop(1) else lines
    }
}

/** Переносит в launcher.log хвост лога прошлой игры, свежие отчёты о крэше и hs_err: так причину вылета видно сразу. */
internal fun MainActivity.logPreviousRun(root: File) {
    val dirs = (root.listFiles()?.toList().orEmpty() + File(root, "builds").listFiles()?.toList().orEmpty()).filter { it.isDirectory }
    try {
        val gl = File(root, "game.log")
        if (gl.exists()) {
            val noise = RUN_LOG_NOISE
            val tail = lastLinesOf(gl).filter { it.isNotBlank() && !noise.containsMatchIn(it) }.takeLast(40)
            log("=== Конец лога прошлого запуска игры ===")
            tail.forEach { log("  $it") }
        }
        dirs.filter { it.name.startsWith("game") || it.parentFile?.name == "builds" }.forEach { d ->
            val cr = File(d, "crash-reports").listFiles()?.maxByOrNull { it.lastModified() }
            if (cr != null && System.currentTimeMillis() - cr.lastModified() < 30 * 60 * 1000) {
                log("=== Отчёт о крэше ${d.name}/${cr.name} ===")
                cr.readLines().take(45).forEach { log("  $it") }
            }
        }
    } catch (_: Exception) {}
    dirs.filter { it.name == "game" || it.name.startsWith("game-") || it.parentFile?.name == "builds" }.forEach { d ->
        val hs = File(d, "hs_err.log")
        if (hs.exists()) {
            log("=== Предыдущий запуск упал (${d.name}/hs_err.log), начало: ===")
            try { hs.readLines().take(40).forEach { log("  $it") } } catch (_: Exception) {}
            hs.renameTo(File(d, "hs_err.old"))
        }
    }
}

private const val NEW_LWJGL = "3.3.3"

/** Общее состояние подготовки запуска: каждый шаг читает нужное и записывает свой результат. */
internal class LaunchPlan(val profile: String, val play: Boolean) {
    lateinit var root: File
    lateinit var assetsDir: File
    lateinit var librariesDir: File
    var child: JSONObject? = null
    lateinit var baseVer: String
    lateinit var natives: File
    lateinit var v: JSONObject
    var javaMajor = 0
    var legacy = false
    lateinit var gameDir: File
    var oldForge = false
    lateinit var realMain: String
    var preAwt = false
    var oldGl = false
    lateinit var clientJar: File
    var lwjglVer = ""
    var jnaVer = ""
    var jnaOrig = ""
    var jnaPath: String? = null
    val classpath = ArrayList<String>()
    lateinit var indexId: String
    lateinit var assetsForGame: File
    lateinit var java: File
    lateinit var rt: File
    var lwjglPath: String? = null
    var useGl4es = false
    var zink = false
    var turnipLib = false
    var mgPath: String? = null
    lateinit var jvmArgs: List<String>
    lateinit var gameArgs: List<String>
    val cmd = ArrayList<String>()
    lateinit var mainName: String
}

/** Подготовка и запуск игры: шаги идут по порядку, общее состояние лежит в LaunchPlan. */
/** Почему умер прошлый процесс игры (Android 11+): причина, сигнал и стек нативного краха (библиотеки и функции из tombstone). Один раз на каждый вылет. */
internal fun MainActivity.logLastNativeExit() {
    if (android.os.Build.VERSION.SDK_INT < 30) return
    try {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val g = am.getHistoricalProcessExitReasons(packageName, 0, 8).filter { it.processName.endsWith(":game") }.maxByOrNull { it.timestamp } ?: return
        if (g.reason != android.app.ApplicationExitInfo.REASON_CRASH_NATIVE && g.reason != android.app.ApplicationExitInfo.REASON_SIGNALED &&
            g.reason != android.app.ApplicationExitInfo.REASON_CRASH && g.reason != android.app.ApplicationExitInfo.REASON_LOW_MEMORY) return
        if (prefs.getLong("exitLogged", 0L) == g.timestamp) return
        prefs.edit().putLong("exitLogged", g.timestamp).apply()
        log("=== Прошлая игра завершилась аварийно: причина ${g.reason}, статус ${g.status}, ${g.description ?: "-"} ===")
        val st = if (g.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) g.traceInputStream else null
        if (st != null) {
            val raw = st.use { it.readBytes().take(2_000_000).toByteArray() }
            val lines = try { Tombstone.describe(raw) } catch (_: Throwable) { emptyList() }
            if (lines.size >= 2) lines.forEach { log("  $it") }
            else {
                // формат tombstone не разобран (другая версия Android): показываем найденные в нём строки
                val keep = Regex("[\\x20-\\x7e]{6,}").findAll(String(raw, Charsets.ISO_8859_1)).map { it.value.trim() }
                    .filter { Regex("\\.so|SIG[A-Z]+|Abort").containsMatchIn(it) }.distinct().take(40).toList()
                if (keep.isEmpty()) log("  (стек краха пуст)") else keep.forEach { log("  $it") }
            }
        } else log("  (стека краха нет: система его не сохранила)")
    } catch (e: Throwable) { log("Причина прошлого вылета недоступна: $e") }
}

internal fun MainActivity.prepareAndRun(profile: String = selectedProfile(), play: Boolean = true) {
    val p = LaunchPlan(profile, play)
    prepareInputs(p)        // папки, профиль, версия игры
    loadVersionInfo(p)      // json версии, режим (старая/новая), папка игры, настройки, моды
    downloadClient(p)       // jar клиента
    collectLibraries(p)     // библиотеки, classpath, LWJGL
    downloadAssets(p)       // ресурсы
    if (!play) { log("Файлы версии скачаны."); return }
    prepareRuntime(p)       // Java, LWJGL, рендерер
    refreshAccounts(p)      // сессии Ely.by / Microsoft, скин
    buildLaunchArgs(p)      // аргументы JVM и игры из json версии
    buildJvmCommand(p)      // итоговая команда JVM
    writeLaunchConfig(p)    // launch.json для нативного загрузчика
    // Проверка Java (запускать программы из папки приложения нельзя, проверяем наличие файлов)
    val rt = p.rt
    if (!File(rt, "lib/server/libjvm.so").exists() && !File(rt, "lib/aarch64/server/libjvm.so").exists()) {
        log("Java установлена неполностью (нет libjvm.so). Удалите скачанное и попробуйте снова.")
        return
    }
    log("Java работает.")

    log("Открываю окно игры...")
    runOnUiThread {
        if (prefs.getBoolean("worldBackup", true)) WorldBackup.begin(dataRoot(), curGameDir())
        startActivity(Intent(this, GameActivity::class.java).putExtra("mode", "game"))
    }
}

/** Шаг 0: папки данных, профиль, базовая версия, лог. */
private fun MainActivity.prepareInputs(p: LaunchPlan) {
    val profile = p.profile
    val play = p.play
    if (play) {
        prefs.edit().putBoolean("playedOnce", true).apply()
        runOnUiThread { setupHint?.visibility = View.GONE }
    }
    val root = dataRoot()
    File(root, "game").mkdirs()
    val assetsDir = File(root, "assets")
    val librariesDir = File(root, "libraries")
    archiveRunLogs(root)
    logFile = File(root, "launcher.log").also { it.writeText("") }
    runOnUiThread { logView.text = "" }
    val profFile = File(root, "versions/$profile/$profile.json")
    val child: JSONObject? = if (profFile.exists()) JSONObject(profFile.readText()).takeIf { it.has("inheritsFrom") || (it.has("jar") && it.optString("jar") != profile) } else null
    val baseVer = child?.let { it.optString("inheritsFrom", "").ifEmpty { it.optString("jar", "") } }?.ifEmpty { null } ?: profile
    val natives = File(filesDir, "natives/$baseVer").apply { mkdirs() }
    log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MODEL}, ${Build.SUPPORTED_ABIS.joinToString()}")
    log("Папка данных: ${root.path}")
    log("Профиль $profile (игра $baseVer), ник ${nickName()}")
    logPreviousRun(root)
    logLastNativeExit()
    p.root = root
    p.assetsDir = assetsDir
    p.librariesDir = librariesDir
    p.child = child
    p.baseVer = baseVer
    p.natives = natives
}

/** Шаг 1: json версии, режим запуска, папка игры, пресет, моды. */
private fun MainActivity.loadVersionInfo(p: LaunchPlan) {
    val root = p.root
    val profile = p.profile
    val baseVer = p.baseVer
    val child = p.child
    // 1. json ванильной версии
    val versionJsonFile = File(root, "versions/$baseVer/$baseVer.json")
    if (!versionJsonFile.exists()) {
        val versions = JSONObject(fetchText(MANIFEST_URL)).getJSONArray("versions")
        var entry: JSONObject? = null
        for (i in 0 until versions.length()) {
            val o = versions.getJSONObject(i)
            if (o.getString("id") == baseVer) { entry = o; break }
        }
        val found = entry ?: throw RuntimeException("Версия $baseVer не найдена")
        if (!download(found.getString("url"), versionJsonFile, found.getString("sha1"))) {
            throw RuntimeException("Не удалось скачать описание версии")
        }
    }
    val v = mergeProfile(JSONObject(versionJsonFile.readText()), child)
    val javaMajor = v.optJSONObject("javaVersion")?.optInt("majorVersion", 0) ?: 0
    log("Нужна Java: $javaMajor")
    val legacy = JSONObject(versionJsonFile.readText()).optJSONObject("arguments") == null
    if (!legacy && profile.contains("forge")) {
        val inst = File(root, "forge-installer.jar")
        val miss = if (inst.exists()) forgeMissingOutputs(inst, root, profile) else emptyList()
        if (miss.isNotEmpty()) {
            File(root, "versions/$profile").deleteRecursively()
            throw RuntimeException("Forge установлен не полностью (нет ${miss.joinToString(", ")}). Профиль сброшен: установите Forge заново в разделе «Загрузчик версий».")
        }
    }
    // старые версии играют в своей папке: настройки и миры 1.13+ ломают их (options.txt и т.п.)
    val gameDir = (activeBuild()?.let { buildDir(it.optString("id")) }
        ?: if (legacy) File(root, "game-$baseVer") else File(root, "game")).apply { mkdirs() }
    if (legacy && child != null && !profile.contains("forge")) throw RuntimeException("Fabric и другие загрузчики для версий 1.12.2 и ниже не поддерживаются, только Forge")
    val oldForge = legacy && child != null
    // 1.0–1.5.2 и Classic стартуют через Frame/Canvas: запускаем их через dev.mobilix.OldMain (без окна AWT)
    val realMain = v.getString("mainClass")
    val preAwt = legacy && child == null && !realMain.startsWith("net.minecraft.client.main.")   // у 1.0–1.5.2 и Classic главный класс — launchwrapper, он на Java 9+ не работает
    val oldGl = legacy || javaMajor < 16
    if (is32bit && !oldGl && javaMajor >= 21) throw RuntimeException(tr("На 32-битных устройствах работают версии до 1.20.4: для новее не хватает адресного пространства (память).", "32-bit devices run versions up to 1.20.4: newer ones need more address space than a 32-bit process has."))
    applyMipmap(gameDir, oldGl)
    applyPreset(gameDir, baseVer)
    val modLoader = when { profile.startsWith("fabric-loader-") -> "fabric"; profile.startsWith("quilt-loader-") -> "quilt"; profile.startsWith("neoforge-") -> "neoforge"; profile.contains("forge", true) -> "forge"; else -> null }
    syncMods(gameDir, modLoader)
    autoUpdateMods(gameDir, modLoader, baseVer)
    if (preAwt) log("Очень старая версия (до 1.6): слой LWJGL 2 + запуск без окна AWT")
    else if (legacy) log("Старая версия (формат запуска до 1.13): слой LWJGL 2 + gl4es")
    else if (oldGl) log("Версия 1.13–1.16.5: LWJGL 3.3.3 + gl4es")
    p.v = v
    p.javaMajor = javaMajor
    p.legacy = legacy
    p.gameDir = gameDir
    p.oldForge = oldForge
    p.realMain = realMain
    p.preAwt = preAwt
    p.oldGl = oldGl
}

/** Шаг 2: клиентский jar. */
private fun MainActivity.downloadClient(p: LaunchPlan) {
    val root = p.root
    val baseVer = p.baseVer
    val v = p.v
    val clientInfo = v.getJSONObject("downloads").getJSONObject("client")
    val clientJar = File(root, "versions/$baseVer/$baseVer.jar")
    downloadAll(
        "Клиент игры",
        listOf(Task(clientInfo.getString("url"), clientJar, clientInfo.getString("sha1")))
    )
    Thread { loadSkin() }.start()
    p.clientJar = clientJar
}

/** Шаг 3: библиотеки, classpath, версия LWJGL. */
private fun MainActivity.collectLibraries(p: LaunchPlan) {
    val v = p.v
    val oldGl = p.oldGl
    val legacy = p.legacy
    val oldForge = p.oldForge
    val librariesDir = p.librariesDir
    val clientJar = p.clientJar
    val newLwjgl = NEW_LWJGL
    var lwjglVer = ""
    var jnaVer = ""
    var jnaOrig = ""
    val libTasks = ArrayList<Task>()
    val cpMap = LinkedHashMap<String, String>()
    val libs = v.getJSONArray("libraries")
    // Игры с LWJGL 3.1–3.3.x (1.17–1.20.x, Forge) тоже получают jar 3.3.3: готовых нативных библиотек 3.2.2 под Android нет,
    // а 3.3.3 совпадает с тем, что уже работает для 1.13–1.16.5
    val gameLwjgl = (0 until libs.length()).map { libs.getJSONObject(it).optString("name") }
        .firstOrNull { it.startsWith("org.lwjgl:lwjgl:") }?.split(":")?.getOrNull(2) ?: ""
    val swap3 = oldGl || gameLwjgl.startsWith("3.2") || gameLwjgl.startsWith("3.1") ||
        gameLwjgl.startsWith("3.3")   // 3.3.1 (1.20.x, Forge): полного набора нативных библиотек нет, подходит 3.3.3
    for (i in 0 until libs.length()) {
        val lib = libs.getJSONObject(i)
        if (!allowed(lib)) continue
        val name = lib.optString("name")
        val art = lib.optJSONObject("downloads")?.optJSONObject("artifact")
        if (name.startsWith("net.java.dev.jna:jna:") && name.split(":").size == 3) { jnaVer = name.split(":")[2]; jnaOrig = jnaVer }
        if (oldGl) {
            // нативные jar и LWJGL 2 нам не нужны: LWJGL 3.3.3 ставится отдельно
            if (name.startsWith("org.lwjgl.lwjgl:lwjgl:") || name.startsWith("org.lwjgl.lwjgl:lwjgl-platform:") ||
                name.startsWith("net.java.jinput:") || name.startsWith("net.java.jutils:")) continue
            if (lib.has("natives") && art == null) continue
            if (name.split(":").size > 3 && name.split(":")[3].startsWith("natives")) continue
        }
        if (swap3 && name.startsWith("org.lwjgl:")) {
            val pp = name.split(":")
            val a = pp[1]
            val path3 = "org/lwjgl/$a/$newLwjgl/$a-$newLwjgl.jar"
            val dest3 = File(librariesDir, path3)
            libTasks.add(Task("https://repo1.maven.org/maven2/$path3", dest3, null))
            cpMap["org.lwjgl:$a"] = dest3.path
            continue
        }
        if (name.startsWith("org.lwjgl:lwjgl:")) lwjglVer = name.split(":")[2]
        var path: String; var url: String?; var sha: String? = null
        if (art != null) {
            path = art.getString("path"); url = art.optString("url", ""); sha = art.optString("sha1", "").ifEmpty { null }
        } else if (name.isNotEmpty()) {
            path = mavenPath(name)
            url = lib.optString("url", "https://libraries.minecraft.net/").trimEnd('/')
                .replace("http://", "https://").replace("files.minecraftforge.net/maven", "maven.minecraftforge.net") + "/" + path
        } else continue
        // тихая подмена JNA: прошлый запуск с этой версией JNA упал из-за неё (см. CrashDoctor.jnaFailure) — берём 5.13.0, как Amethyst/Pojav
        if (name == "net.java.dev.jna:jna:$jnaOrig" && jnaOrig.isNotEmpty() && jnaOrig != "5.13.0" && prefs.getString("jnaSwap", "") == jnaOrig) {
            path = "net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar"
            url = "https://repo1.maven.org/maven2/$path"; sha = "1200e7ebeedbe0d10062093f32925a912020e747"
            jnaVer = "5.13.0"
            log("JNA: $jnaOrig заменена на 5.13.0 (прошлый запуск упал из-за JNA)")
        }
        val dest = File(librariesDir, path)
        if (!url.isNullOrEmpty() && !(oldForge && art == null && dest.exists())) libTasks.add(Task(url, dest, sha)) else if (!dest.exists()) continue
        val parts = name.split(":")
        val key = if (parts.size >= 2) parts[0] + ":" + parts[1] + (if (parts.size > 3) ":" + parts[3] else "") else path
        cpMap[key] = dest.path
    }
    if (swap3) lwjglVer = newLwjgl
    log("LWJGL: в описании игры '$gameLwjgl', ставим '$lwjglVer'" + (if (swap3) " (jar 3.3.3)" else ""))
    if (legacy) {
        for (a in listOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-openal")) {
            val path3 = "org/lwjgl/$a/$newLwjgl/$a-$newLwjgl.jar"
            val dest3 = File(librariesDir, path3)
            libTasks.add(Task("https://repo1.maven.org/maven2/$path3", dest3, null))
            cpMap["org.lwjgl:$a"] = dest3.path
        }
    }
    if (oldForge) {
        // Pack200 удалён из Java 14+: старый Forge распаковывает им патчи, подставляем распаковщик из Commons Compress
        val ccPath = "org/apache/commons/commons-compress/1.21/commons-compress-1.21.jar"
        val ccFile = File(librariesDir, ccPath)
        libTasks.add(Task("https://repo1.maven.org/maven2/$ccPath", ccFile, null))
        cpMap["org.apache.commons:commons-compress"] = ccFile.path
    }
    if (oldForge) {
        cpMap.values.firstOrNull { it.contains("/net/minecraftforge/forge/") && it.endsWith(".jar") }?.let { patchForgeJar(File(it)) }
    }
    val classpath = p.classpath
    if (legacy) {
        val compat = File(filesDir, "lwjgl2compat.jar")
        try {
            assets.open("lwjgl2compat.jar").use { i -> compat.outputStream().use { o -> i.copyTo(o) } }
        } catch (e: Exception) {
            throw RuntimeException("В этой сборке нет слоя LWJGL 2 (lwjgl2compat.jar): старые версии недоступны")
        }
        classpath.add(compat.path)
    }
    classpath.addAll(cpMap.values)
    classpath.add(clientJar.path)
    downloadAll("Библиотеки", libTasks)
    p.lwjglVer = lwjglVer
    p.jnaVer = jnaVer
    p.jnaOrig = jnaOrig
}

/** Шаг 4: ресурсы (звуки, языки), копирование для старых версий. */
private fun MainActivity.downloadAssets(p: LaunchPlan) {
    val v = p.v
    val assetsDir = p.assetsDir
    val gameDir = p.gameDir
    val preAwt = p.preAwt
    val idx = v.getJSONObject("assetIndex")
    val indexId = idx.getString("id")
    val indexFile = File(assetsDir, "indexes/$indexId.json")
    downloadAll("Индекс ресурсов", listOf(Task(idx.getString("url"), indexFile, idx.optString("sha1", "").ifEmpty { null })))
    val idxJson = JSONObject(indexFile.readText())
    val objects = idxJson.getJSONObject("objects")
    val assetTasks = ArrayList<Task>()
    val keys = objects.keys()
    while (keys.hasNext()) {
        val hash = objects.getJSONObject(keys.next()).getString("hash")
        val p = hash.substring(0, 2) + "/" + hash
        assetTasks.add(Task(ASSETS_URL + p, File(assetsDir, "objects/$p"), hash))
    }
    downloadAll("Ресурсы", assetTasks, fatal = false)
    val virtualAssets = idxJson.optBoolean("virtual", false) || idxJson.optBoolean("map_to_resources", false)
    val mapToResources = idxJson.optBoolean("map_to_resources", false)
    val assetsForGame: File = when {
        mapToResources -> File(gameDir, if (preAwt) ".minecraft/resources" else "resources")
        virtualAssets -> File(assetsDir, "virtual/$indexId")
        else -> assetsDir
    }
    if (virtualAssets) {
        log("Копирование ресурсов в ${assetsForGame.path}")
        val ks = objects.keys()
        while (ks.hasNext()) {
            val k = ks.next()
            val hash = objects.getJSONObject(k).getString("hash")
            val src = File(assetsDir, "objects/${hash.substring(0, 2)}/$hash")
            val dst = File(assetsForGame, k)
            if (src.exists() && !dst.exists()) { dst.parentFile?.mkdirs(); src.copyTo(dst) }
        }
    }
    p.indexId = indexId
    p.assetsForGame = assetsForGame
}

/** Шаг 5: Java-рантайм, LWJGL, выбор рендерера (gl4es / MobileGlues / ANGLE / Zink / Turnip). */
private fun MainActivity.prepareRuntime(p: LaunchPlan) {
    val root = p.root
    val javaMajor = p.javaMajor
    val lwjglVer = p.lwjglVer
    val oldGl = p.oldGl
    val java = ensureRuntime(root, javaMajor)
    val rt = java.parentFile!!.parentFile!!
    // 26.x (LWJGL 3.4.x): нативные 3.3.3 не годятся (нет ThreadLocalUtil.setupEnvData), а в архиве 3.4.1 лишь 2 библиотеки.
    // Берём полный набор 3.4.3: минорные версии 3.4.x совместимы по JNI
    val nativesVer = if (lwjglVer.startsWith("3.4")) "3.4.3" else lwjglVer
    if (nativesVer != lwjglVer) log("LWJGL: для игры $lwjglVer берём нативные библиотеки $nativesVer")
    // 26.1/26.2 (LWJGL 3.4.x): сначала набор из yuyi2439/AndroidLWJGL, при отсутствии нужной версии — остальные источники как раньше
    val lwjglPath = ensureLwjgl(nativesVer, if (lwjglVer.startsWith("3.4")) "yuyi2439/AndroidLWJGL" else null)
    // В наборе 3.4.x свой libopenal.so зависит от libc++_shared.so, которой нет в системе. В APK есть собственный libopenal.so
    // (c++_static), поэтому лишний убираем: игра подхватит библиотеку из папки приложения
    if (lwjglPath != null && File(applicationInfo.nativeLibraryDir, "libopenal.so").exists()) {
        lwjglPath.split(":").forEach { d ->
            val f = File(d, "libopenal.so")
            if (f.exists()) { if (f.renameTo(File(d, "libopenal.so.unused"))) log("LWJGL: libopenal.so набора убран, используется встроенная") else f.delete() }
        }
    }
    val gl4es = File(applicationInfo.nativeLibraryDir, "libgl4es.so").takeIf { it.exists() }?.path
    if (oldGl && gl4es == null) log("ВНИМАНИЕ: gl4es нет в этой сборке, для старой версии используется MobileGlues (скорее всего не заработает).")
    // Рендерер: 0 авто, 1 MobileGlues, 2 MobileGlues + ANGLE, 3 gl4es. Для 1.17+ gl4es не подходит (нужен GL 3.2 core), для <1.13 нужен gl4es (слой LWJGL 2).
    val rendPref = prefs.getInt("renderer", 0)
    val useGl4es = gl4es != null && oldGl   // 1.13–1.16.5 на MobileGlues падают: нет glAlphaFunc (нужен compatibility-профиль)
    if (!oldGl) ensureAngle()
    if (!oldGl && rendPref == 3) log("Рендерер gl4es не работает с 1.17+, используется MobileGlues.")
    if (oldGl && (rendPref == 1 || rendPref == 2)) log("Версии до 1.17 работают только через gl4es, выбор рендерера игнорируется.")
    // Zink (только MobiLabs): Mesa OSMesa + Vulkan-драйвер телефона, рисует в буфер окна; пока для 1.17+ (OpenGL core)
    val turnip = turnipOk && rendPref == 5 && !oldGl
    val zink = zinkOk && (rendPref == 4 || turnip) && !oldGl
    if (zinkOk && rendPref == 4 && oldGl) log("Zink пока только для 1.17+, для этой версии используется gl4es.")
    val osmesaLib = File(applicationInfo.nativeLibraryDir, "libOSMesa.so")
    if (zink && !osmesaLib.exists()) throw RuntimeException("В этой сборке нет libOSMesa.so")
    if (zink) log("Рендерер: Zink (OSMesa → Vulkan)" + (if (turnip) " + драйвер Turnip" else ""))
    var turnipLib = false
    if (turnip) try {
        val td = File(filesDir, "turnip"); td.mkdirs()
        val drv = File(td, "vulkan.ad07xx.so")
        if (!drv.exists() || drv.length() < 1_000_000) { log("  скачивание драйвера Turnip (17 МБ)"); downloadBig(MIRROR + "turnip/libvulkan_freedreno.so", drv) }
        drv.setReadable(true, false); drv.setExecutable(true, false)
        turnipLib = drv.exists()
    } catch (e: Exception) { log("  Turnip недоступен ($e), используется системный Vulkan-драйвер") }
    val mgPath = if (zink) osmesaLib.path else if (useGl4es) gl4es else ensureMobileGlues()
    p.java = java
    p.rt = rt
    p.lwjglPath = lwjglPath
    // JNA из jar игры содержит только glibc-библиотеку, на Android (bionic) она не грузится: oshi и системная информация падают
    // с «Could not initialize class com.sun.jna.Native». Как в Amethyst/Pojav, берём libjnidispatch.so из AAR той же версии
    p.jnaPath = ensureJna(p.jnaVer)
    // что запускали: по этим данным checkLastCrash решает, нужна ли подмена, и не крутится по кругу, если подмена уже была
    prefs.edit().putString("jnaVerLast", p.jnaOrig).putBoolean("jnaRunSwapped", p.jnaVer != p.jnaOrig).apply()
    p.useGl4es = useGl4es
    p.zink = zink
    p.turnipLib = turnipLib
    p.mgPath = mgPath
}

/** Шаг 6а: сессии Ely.by и Microsoft, скин. */
private fun MainActivity.refreshAccounts(p: LaunchPlan) {
    val gameDir = p.gameDir
    val baseVer = p.baseVer
    if (elyOn() && !elyEnsureSession()) {
        log("Сессия Ely.by истекла: игра запускается в офлайн-режиме. Войдите снова в разделе «Аккаунт».")
        elyLogout(false)
    }
    if (msOn() && !msEnsureSession()) {
        log("Сессия Microsoft истекла: игра запускается в офлайн-режиме. Войдите снова в разделе «Аккаунт».")
        msLogout(false)
    }
    if (elyOn()) try { importSkinNow("ely", nickName(), quiet = true, strict = true) } catch (_: Exception) {}
    else if (msOn()) try { importSkinNow("mojang", nickName(), quiet = true) } catch (_: Exception) {}
    applySkinPack(gameDir, baseVer)
}

/** Шаг 6б: подстановка аргументов JVM и игры из json версии. */
private fun MainActivity.buildLaunchArgs(p: LaunchPlan) {
    val profile = p.profile
    val librariesDir = p.librariesDir
    val gameDir = p.gameDir
    val assetsDir = p.assetsDir
    val indexId = p.indexId
    val natives = p.natives
    val assetsForGame = p.assetsForGame
    val legacy = p.legacy
    val oldForge = p.oldForge
    val v = p.v
    val baseVer = p.baseVer
    val repl = mapOf(
        "auth_player_name" to nickName(),
        "version_name" to profile,
        "version_id" to profile,
        "library_directory" to librariesDir.path,
        "classpath_separator" to ":",
        "game_directory" to gameDir.path,
        "assets_root" to assetsDir.path,
        "assets_index_name" to indexId,
        "auth_uuid" to authUuid(),
        "auth_access_token" to authToken(),
        "clientid" to "0",
        "auth_xuid" to "0",
        "user_type" to (if (msOn()) "msa" else if (elyOn()) "mojang" else "legacy"),
        "version_type" to "release",
        "natives_directory" to natives.path,
        "auth_session" to "token:" + authToken() + ":" + authUuid(),
        "game_assets" to assetsForGame.path,
        "user_properties" to "{}",
        "launcher_name" to "Mobilix",
        "launcher_version" to "0.2",
        "classpath" to p.classpath.joinToString(":")
    )
    val sub = { s: String ->
        Regex("\\$\\{(\\w+)\\}").replace(s) { m -> repl[m.groupValues[1]] ?: m.value }
    }
    val jvmArgs: List<String>
    val gameArgs: List<String>
    if (legacy) {
        jvmArgs = listOf("-Djava.library.path=${natives.path}", "-cp", p.classpath.joinToString(":"))
        // размер окна для игры = размер поверхности в GameActivity (с учётом «разрешения рендера» и полного экрана),
        // иначе 1.12.2 строит интерфейс под полный экран и рисует в меньшую поверхность: меню уезжает вправо вверх
        val dm = if (prefs.getBoolean("fullscreen", false)) android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) } else resources.displayMetrics
        val rsc = effectiveScale().coerceIn(30, 100)
        val sw = maxOf(dm.widthPixels, dm.heightPixels) * rsc / 100; val sh = minOf(dm.widthPixels, dm.heightPixels) * rsc / 100
        val base = v.getString("minecraftArguments").trim().split(Regex("\\s+")).map(sub)
        val g0 = if (base.contains("--width")) base else base + listOf("--width", sw.toString(), "--height", sh.toString())
        gameArgs = if (oldForge) g0 + listOf("--tweakClass", "dev.mobilix.loader.MobilixTweaker") else g0
    } else {
        val args = v.getJSONObject("arguments")
        // Forge 1.17+: ванильный jar игры не должен попасть в модульный слой, иначе «Module minecraft contains package …»
        jvmArgs = collectArgs(args.optJSONArray("jvm"), sub).map {
            if (it.startsWith("-DignoreList=") && !it.contains("$baseVer.jar")) "$it,$baseVer.jar" else it
        }
        gameArgs = collectArgs(args.optJSONArray("game"), sub)
    }
    // конфиг log4j из описания версии (как в официальном лаунчере): для 1.7–1.12 без него в консоль идут только ошибки,
    // а конфиги Mojang закрывают уязвимость Log4Shell (в 1.7–1.11 её закрывает только этот конфиг)
    val logArgs = ArrayList<String>()
    try {
        val lc = v.optJSONObject("logging")?.optJSONObject("client")
        val lf = lc?.optJSONObject("file")
        // только 1.7–1.16.5: у них нет встроенной защиты, а конфиги Mojang пишут консоль в XML (в 1.17+ родной текстовый лог лучше)
        val minor = baseVer.split(".").getOrNull(1)?.toIntOrNull() ?: 99
        if (lc != null && lf != null && baseVer.startsWith("1.") && minor <= 16 && jvmArgs.none { it.startsWith("-Dlog4j.configurationFile") }) {
            val f = File(assetsDir, "log_configs/" + lf.getString("id"))
            if (!f.exists() && !download(lf.getString("url"), f, lf.optString("sha1").ifEmpty { null })) log("log4j: конфиг не скачался, продолжаю без него")
            if (f.exists()) logArgs.add(lc.optString("argument", "-Dlog4j.configurationFile=\${path}").replace("\${path}", f.path))
        }
    } catch (e: Exception) { log("log4j: конфиг пропущен ($e)") }
    p.jvmArgs = logArgs + jvmArgs
    p.gameArgs = gameArgs
}

/** Шаг 6в: итоговая команда JVM (память, GC, LWJGL, режимы старых версий и Forge, Ely.by). */
private fun MainActivity.buildJvmCommand(p: LaunchPlan) {
    val java = p.java
    val gameDir = p.gameDir
    val oldGl = p.oldGl
    val oldForge = p.oldForge
    val preAwt = p.preAwt
    val realMain = p.realMain
    val lwjglPath = p.lwjglPath
    val mgPath = p.mgPath
    val zink = p.zink
    val rt = p.rt
    val jvmArgs = p.jvmArgs
    val gameArgs = p.gameArgs
    val cmd = p.cmd
    cmd.add(java.path)
    val mi = android.app.ActivityManager.MemoryInfo()
    (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
    val totalMb = mi.totalMem / 1024 / 1024
    val modern = !oldGl   // 1.17+ тяжелее: даём больше памяти
    val xmx = gameXmxMb(modern)
    log("ОЗУ устройства: $totalMb МБ, память игры: $xmx МБ")
    cmd.add("-Xmx${xmx}M")
    // не-кучевая память (метаспейс, кэш кода) тоже ограничена: при загрузке меню с модами она растёт и может убить игру
    val lowMem = totalMb < 3500
    cmd.add("-XX:MaxMetaspaceSize=${if (lowMem) 192 else if (totalMb < 5000) 384 else 640}m")
    cmd.add("-XX:ReservedCodeCacheSize=${if (lowMem) 48 else if (totalMb < 5000) 96 else 160}m")
    // слабые устройства: только быстрый компилятор C1 и один поток компиляции. Меньше нативной памяти (код, компилятор), чуть ниже пик скорости
    if (lowMem) { cmd.add("-XX:TieredStopAtLevel=1"); cmd.add("-XX:CICompilerCount=1") }
    cmd.add(when (prefs.getString("gc", "Serial")) { "G1" -> "-XX:+UseG1GC"; "Parallel" -> "-XX:+UseParallelGC"; else -> "-XX:+UseSerialGC" })
    prefs.getString("jvmArgs", "")?.split(Regex("\\s+"))?.filter { it.startsWith("-") }?.forEach { cmd.add(it) }
    cmd.add("-XX:ErrorFile=${File(gameDir, "hs_err.log").path}")
    cmd.add("-Xlog:gc:file=${File(gameDir, "gc.log").path}:uptime:filecount=1,filesize=2m")
    cmd.add("-Djava.io.tmpdir=${cacheDir.path}")
    cmd.add("-Duser.home=${gameDir.path}")
    cmd.add("-Djava.awt.headless=true")
    // как в Amethyst/Pojav: Forge 1.14+ открывает раннее окно через GLFW (у нас его нет), Fabric/Quilt при ошибке запускают отдельный
    // процесс с окном, posix_spawn на Android не работает (нет jspawnhelper), число ядер берём у системы
    cmd.add("-XX:+IgnoreUnrecognizedVMOptions")   // на случай старого Java 8 без -XX:ActiveProcessorCount
    cmd.add("-Dfml.earlyprogresswindow=false")
    cmd.add("-Dloader.disable_forked_guis=true")
    cmd.add("-Djdk.lang.Process.launchMechanism=FORK")
    cmd.add("-XX:ActiveProcessorCount=" + Runtime.getRuntime().availableProcessors())
    cmd.add("-Dlog4j2.formatMsgNoLookups=true")
    val libPath = (if (lwjglPath != null) "$lwjglPath:" else "") + applicationInfo.nativeLibraryDir
    cmd.add("-Dorg.lwjgl.librarypath=$libPath")
    if (lwjglPath != null) {
        cmd.add("-Djna.boot.library.path=" + (p.jnaPath?.let { "$it:" } ?: "") + lwjglPath)
        cmd.add("-Djna.nosys=true")
    }
    // для Zink функции GL ищутся через glXGetProcAddress из нашей libglfw.so (она перенаправляет в OSMesaGetProcAddress)
    if (mgPath != null) cmd.add("-Dorg.lwjgl.opengl.libname=" + (if (zink) File(applicationInfo.nativeLibraryDir, "libglfw.so").path else mgPath))
    if (oldGl) {
        for (p in listOf("java.lang", "java.util", "java.io", "java.net", "java.nio", "sun.nio.ch")) { cmd.add("--add-opens"); cmd.add("java.base/$p=ALL-UNNAMED") }
    }
    if (oldForge) {
        cmd.add("-Djava.system.class.loader=dev.mobilix.loader.MobilixLoader")
        cmd.add("-Djava.security.manager=allow")  // Forge ставит свой SecurityManager, в Java 21 это запрещено без этого ключа
        for (p in listOf("java.lang.reflect", "java.lang.invoke", "java.util.jar", "java.util.zip", "java.security", "java.text", "java.util.concurrent", "jdk.internal.loader", "jdk.internal.misc", "sun.security.util")) { cmd.add("--add-opens"); cmd.add("java.base/$p=ALL-UNNAMED") }
        cmd.add("-Dfml.ignoreInvalidMinecraftCertificates=true")
        cmd.add("-Dfml.ignorePatchDiscrepancies=true")
        // заставка Forge использует LWJGL2-контексты в потоке, у нас этого нет: отключаем
        try {
            val cfg = File(gameDir, "config").apply { mkdirs() }
            val sp = File(cfg, "splash.properties")
            val lines = if (sp.exists()) sp.readLines().filter { !it.startsWith("enabled=") } else emptyList()
            sp.writeText((lines + "enabled=false").joinToString("\n") + "\n")
        } catch (_: Exception) {}
    }
    cmd.add("-Dorg.lwjgl.util.DebugLoader=true")   // пути загрузки библиотек в лог; полный режим Debug выключен: он добавляет проверки в каждый вызов LWJGL
    if (elyOn()) {
        val agent = ensureAuthlib()
        if (agent != null && agentUsable(rt)) cmd.add("-javaagent:${agent.path}=https://authserver.ely.by/api/authlib-injector")
        else log("ВНИМАНИЕ: в Java-рантайме нет рабочего java.instrument, поэтому authlib-injector не подключён. Игра запустится с ником и UUID из Ely.by, скин взят из Ely.by, но вход на серверы с проверкой Ely.by работать не будет.")
    }
    cmd.addAll(jvmArgs)
    val mainName = if (preAwt) "dev.mobilix.OldMain" else realMain
    cmd.add(mainName)
    if (preAwt) cmd.add(realMain)
    cmd.addAll(gameArgs)
    p.mainName = mainName
}

/** Шаг 6г: launch_command.txt и launch.json (JVM, игра, библиотека GL, окружение рендерера). */
private fun MainActivity.writeLaunchConfig(p: LaunchPlan) {
    val root = p.root
    val mainName = p.mainName
    val rt = p.rt
    val mgPath = p.mgPath
    val useGl4es = p.useGl4es
    val zink = p.zink
    val turnipLib = p.turnipLib
    val cmd = p.cmd
    File(root, "launch_command.txt").writeText(cmd.joinToString("\n"))
    val mainIdx = cmd.indexOf(mainName)
    val launch = JSONObject()
    launch.put("javaHome", rt.path)
    launch.put("main", mainName)
    launch.put("jvm", JSONArray(cmd.subList(1, mainIdx)))
    launch.put("game", JSONArray(cmd.subList(mainIdx + 1, cmd.size)))
    launch.put("glLib", mgPath ?: "")
    if (useGl4es) {
        launch.put("env", JSONObject().put("LIBGL_ES", "2").put("LIBGL_NOBANNER", "1").put("LIBGL_NOERROR", "1"))
    } else if (zink) {
        launch.put("env", JSONObject()
            .put("MESA_LOADER_DRIVER_OVERRIDE", "zink").put("GALLIUM_DRIVER", "zink")
            .put("MESA_GL_VERSION_OVERRIDE", "4.6").put("MESA_GLSL_VERSION_OVERRIDE", "460")
            .put("MESA_GLSL_CACHE_DIR", cacheDir.path).put("MESA_NO_ERROR", "1")
            .put("force_glsl_extensions_warn", "true").put("allow_higher_compat_version", "true")
            .put("allow_glsl_extension_directive_midshader", "true").put("ZINK_DESCRIPTORS", "lazy")
            .also { if (turnipLib) it.put("MOBILIX_TURNIP_DIR", File(filesDir, "turnip").path).put("MOBILIX_TURNIP_LIB", "vulkan.ad07xx.so").put("MOBILIX_NATIVE_DIR", applicationInfo.nativeLibraryDir).put("MOBILIX_TURNIP_TMP", cacheDir.path) })
    } else {
        // Renderlix: профиль настроек MobileGlues. Без MG_DIR_PATH MobileGlues считает лаунчер «неподдерживаемым» и игнорирует конфиг.
        try {
            val env = JSONObject().put("MG_DIR_PATH", writeRenderConfig().path)
            if (angleActive() != null) env.put("MG_ANGLE_DIR", angleActive()!!.path)
            launch.put("env", env)
        } catch (e: Throwable) { log("Профиль рендера не записан: $e") }
    }
    // зависимости библиотек LWJGL (libc++_shared.so для libopenal.so и др.) ищутся динамическим загрузчиком Android по LD_LIBRARY_PATH
    if (p.lwjglPath != null) {
        val e = launch.optJSONObject("env") ?: JSONObject().also { launch.put("env", it) }
        e.put("MOBILIX_LWJGL_DIRS", p.lwjglPath)
    }
    File(root, "launch.json").writeText(launch.toString())
    log("Команда запуска собрана (${cmd.size} аргументов).")
}
