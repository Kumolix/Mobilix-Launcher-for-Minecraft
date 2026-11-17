package dev.mobilix.launcher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Button
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Мост к нативному коду. Загружается только в процессе игры. */
object Native {
    init {
        System.loadLibrary("mobilix")
    }

    @JvmStatic external fun setFpsCap(fps: Int)
    @JvmStatic external fun setSurface(surface: Surface, w: Int, h: Int)
    @JvmStatic external fun pushEvent(type: Int, a: Double, b: Double, c: Int)
    @JvmStatic external fun setGlLib(path: String)
    @JvmStatic external fun redirectOutput(path: String)
    @JvmStatic external fun chdir(path: String)
    @JvmStatic external fun surfaceLost()
    @JvmStatic external fun cursorMode(): Int
    @JvmStatic external fun runMain(javaHome: String, options: Array<String>, mainClass: String, args: Array<String>): String
}

/**
 * Экран игры (отдельный процесс :game).
 * mode=test: проверка EGL и запуска Java.
 * mode=game: запуск Minecraft внутри процесса.
 */
open class GameActivity : Activity(), SurfaceHolder.Callback {
    /** Тихий пробный запуск (подбор модов): окно 1×1 без управления, игра закрывается сама, когда дошла до меню. */
    private val probe get() = this is ProbeActivity
    private lateinit var logView: TextView
    private var started = false
    private var mode = "game"
    private val handler = Handler(Looper.getMainLooper())
    private var gameLog: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode") ?: "game"
        if (mode == "installer") {
            // окно установщика открывается один раз за попытку. Если систему убила процесс, она может открыть окно заново:
            // повторно ничего не запускаем, а сообщаем лаунчеру, чтобы он перешёл к следующей попытке (иначе чёрный экран мигает по кругу)
            val dd = getExternalFilesDir(null) ?: filesDir
            val started = File(dd, "installer.started")
            if (started.exists()) {
                try { File(dd, "installer.restarted").writeText("1") } catch (_: Exception) {}
                finish(); return
            }
            try { started.writeText(System.currentTimeMillis().toString()) } catch (_: Exception) {}
        }
        if (probe) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            window.setLayout(1, 1)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        if (fullscreen() && !probe) {
            // рисуем и под вырезом фронтальной камеры
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (if (fullscreen()) View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE else 0) or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this)
        val sv = SurfaceView(this)
        sv.holder.addCallback(this)
        root.addView(sv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val controls = ControlsView(this)
        run {
            val sc = renderScale()
            controls.gameScale = sc / 100f
        }
        root.addView(controls, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        logView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            typeface = Typeface.MONOSPACE
            textSize = 8f
            setPadding(12, 8, 12, 8)
            maxLines = 45
        }
        root.addView(
            logView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        logView.visibility = View.GONE
        val copy = Button(this).apply {
            text = "Копировать лог"
            textSize = 11f
            visibility = View.GONE
            setOnClickListener { copyLog() }
        }
        val toggle = Button(this).apply {
            text = "Показать лог"
            textSize = 11f
            setOnClickListener {
                val show = logView.visibility != View.VISIBLE
                logView.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "Скрыть лог" else "Показать лог"
                copy.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        val editBtns = ArrayList<Button>()
        fun eb(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 11f; visibility = View.GONE
            setOnClickListener { f() }
            editBtns.add(this)
        }
        val layoutBtn = Button(this).apply {
            text = "Раскладка"
            textSize = 11f
            setOnClickListener {
                val on = !controls.editing
                controls.editing = on
                text = if (on) "Готово" else "Раскладка"
                editBtns.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
            }
        }
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(layoutBtn)
            addView(toggle)
        }
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(row)
            addView(copy)
        }
        root.addView(
            bar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        )
        // MobiLabs: кнопка ↘ вверху по центру делится адресом ZeroTier и портом открытого для сети мира
        if (BuildConfig.LABS) {
            val d = resources.displayMetrics.density
            val shareBtn = TextView(this).apply {
                text = "↘"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; includeFontPadding = false
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x99000000.toInt()); setStroke((1 * d).toInt(), 0x66FFFFFF) }
                setOnClickListener { shareIp() }
            }
            root.addView(shareBtn, FrameLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (6 * d).toInt() })
        }
        // «Скрыть системные кнопки в игре»: кнопки «Раскладка» и «Лог» прячутся, раскладка открывается удержанием Esc (Bedrock: «II») 5 с
        if (gs().optBoolean("hideSys", false)) bar.visibility = View.GONE
        controls.onHoldEsc = { layoutBtn.performClick() }
        val editBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(eb("+ Кнопка") { controls.newButton() })
            addView(eb("Камера") { controls.cameraDialog() })
            addView(eb("Сброс") { controls.reset() })
            addView(eb("Готово") { layoutBtn.performClick() })
        }
        root.addView(
            editBar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = (56 * resources.displayMetrics.density).toInt() }
        )
        setContentView(root)
    }

    private fun copyLog() {
        val sb = StringBuilder()
        try {
            val f = gameLog
            if (f != null && f.exists()) {
                val t = f.readText()
                sb.append(if (t.length > 30000) t.takeLast(30000) else t)
            }
        } catch (_: Exception) {
        }
        sb.append("\n--- экран ---\n").append(logView.text)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Mobilix log", sb.toString()))
        say("Лог скопирован в буфер обмена.")
    }

    private fun say(s: String) = runOnUiThread { logView.append(s + "\n") }

    // ---------- поверхность ----------
    private fun gs(): JSONObject = try { JSONObject(File(filesDir, "game_settings.json").readText()) } catch (_: Throwable) { JSONObject() }
    private fun renderScale(): Int = gs().optInt("renderScale", 100).coerceIn(30, 100)
    private fun fullscreen(): Boolean = gs().optBoolean("fullscreen", false)

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (probe) { holder.setFixedSize(854, 480); return }
        val sc = renderScale()
        if (sc < 100) {
            val dm = android.util.DisplayMetrics()
            if (fullscreen()) windowManager.defaultDisplay.getRealMetrics(dm) else dm.setTo(resources.displayMetrics)
            holder.setFixedSize(maxOf(dm.widthPixels, dm.heightPixels) * sc / 100, minOf(dm.widthPixels, dm.heightPixels) * sc / 100)
        }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (mode == "game" && started) {
            try {
                Native.surfaceLost()
            } catch (_: Throwable) {
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (mode == "game") {
            try {
                Native.setSurface(holder.surface, width, height)
            } catch (e: Throwable) {
                say("ОШИБКА setSurface: $e")
            }
        }
        if (started) return
        started = true
        say("Экран: ${width}x$height")
        Thread(null, { runGame() }, "game-main", 32L * 1024 * 1024).start()
    }

    private var probeGameDir: File? = null

    /** Следит за логом пробного запуска: дошла до главного меню — пишет OK и закрывает игру; не дошла за 4 минуты — TIMEOUT. */
    private fun probeWatch(dataDir: File, gd: File?) {
        val result = File(dataDir, "probe.result")
        val ok = Regex("Sound engine started|Created: \\d+x\\d+x\\d+ minecraft:textures/atlas/blocks")
        val t0 = System.currentTimeMillis()
        var seen = 0L
        while (System.currentTimeMillis() - t0 < 240_000) {
            try { Thread.sleep(700) } catch (_: InterruptedException) { return }
            val text = listOfNotNull(File(dataDir, "game.log"), gd?.let { File(it, "logs/latest.log") }).joinToString("\n") { f ->
                try { RandomAccessFile(f, "r").use { raf -> val n = minOf(raf.length(), 300_000L).toInt(); raf.seek(raf.length() - n); ByteArray(n).also { raf.readFully(it) }.toString(Charsets.UTF_8) } } catch (_: Exception) { "" }
            }
            if (ok.containsMatchIn(text)) { if (seen == 0L) seen = System.currentTimeMillis() else if (System.currentTimeMillis() - seen > 5000) break }
        }
        try { result.writeText(if (seen > 0L) "OK" else "TIMEOUT") } catch (_: Exception) {}
        // JVM в этом процессе второй раз не создать: процесс игры просто завершаем
        runOnUiThread { finish() }
        handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 1200)
    }

    // ---------- игра ----------
    private fun runGame() {
        try {
            val dataDir = getExternalFilesDir(null) ?: filesDir
            val launchFile = File(dataDir, if (mode == "installer") "installer.json" else "launch.json")
            if (!launchFile.exists()) {
                say("Нет launch.json. Сначала нажмите «Играть» в главном окне.")
                return
            }
            val j = JSONObject(launchFile.readText())
            val jvm = j.getJSONArray("jvm")
            val opts = ArrayList<String>()
            var i = 0
            while (i < jvm.length()) {
                val a = jvm.getString(i)
                if ((a == "-cp" || a == "-classpath") && i + 1 < jvm.length()) {
                    opts.add("-Djava.class.path=" + jvm.getString(i + 1))
                    i += 2
                    continue
                }
                val eq = setOf("--add-modules", "--add-opens", "--add-exports", "--add-reads", "--limit-modules", "--patch-module", "--upgrade-module-path", "--module-path")
                if ((a == "-p" || a in eq) && i + 1 < jvm.length()) {
                    opts.add((if (a == "-p") "--module-path" else a) + "=" + jvm.getString(i + 1))
                    i += 2
                    continue
                }
                opts.add(a)
                i++
            }
            if (!opts.contains("-Xrs")) opts.add("-Xrs")
            val game = j.getJSONArray("game")
            val args = Array(game.length()) { game.getString(it) }
            for (k in 0 until game.length() - 1) {
                if (game.getString(k) == "--gameDir") {
                    val gd = File(game.getString(k + 1)).apply { mkdirs() }
                    probeGameDir = gd
                    opts.add("-Duser.dir=" + gd.path)
                    File(gd, "logs").mkdirs()
                    Native.chdir(gd.path)
                }
            }

            val log = File(dataDir, if (mode == "installer") "installer.log" else "game.log")
            gameLog = log
            Native.redirectOutput(log.path)
            runOnUiThread { handler.post(tailer) }

            try { Native.setFpsCap(gs().optInt("fps", 0)) } catch (_: Throwable) {}
            j.optJSONObject("env")?.let { e ->
                val it2 = e.keys()
                while (it2.hasNext()) { val k = it2.next(); try { android.system.Os.setenv(k, e.getString(k), true) } catch (_: Throwable) {} }
            }
            val gl = j.optString("glLib", "")
            if (gl.isNotEmpty()) Native.setGlLib(gl)
            if (probe) Thread { probeWatch(dataDir, probeGameDir) }.apply { isDaemon = true }.start()
            say("Запуск Minecraft в процессе...")
            val r = Native.runMain(j.getString("javaHome"), opts.toTypedArray(), j.getString("main"), args)
            say(r)
            if (probe) {
                val res = File(dataDir, "probe.result")
                if (!res.exists()) try { res.writeText("EXIT") } catch (_: Exception) {}
                runOnUiThread { finish() }
                handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 1200)
                return
            }
            if (mode == "game") { try { WorldBackup.endedMarker(getExternalFilesDir(null) ?: filesDir).writeText("1") } catch (_: Exception) {} }
            if (mode == "installer") {
                try { File(dataDir, "installer.done").writeText(r) } catch (_: Exception) {}
                runOnUiThread { finish() }
            }
        } catch (e: Throwable) {
            say("ОШИБКА игры: $e")
        }
    }

    private var lastLanPort = -1
    /** «Поделиться IP»: копирует «[ZeroTier] IP: адрес:порт» и открывает системное окно отправки. */
    private fun shareIp() {
        // встроенный ZeroTier: лаунчер пишет «адрес:порт» в zt-share.txt, когда мир раздаётся друзьям
        val shared = try { File(getExternalFilesDir(null) ?: filesDir, "zt-share.txt").takeIf { it.exists() }?.readText()?.trim() } catch (_: Exception) { null }
        val ips = ZeroTierNet.myIps(this)
        if (shared.isNullOrEmpty() && ips.isEmpty()) { Toast.makeText(this, "ZeroTier не подключён: включите сеть во вкладке «Сеть» лаунчера", Toast.LENGTH_LONG).show(); return }
        val text = if (!shared.isNullOrEmpty()) "[ZeroTier] IP: $shared" else ZeroTierNet.shareText(ips[0].second, ZeroTierNet.lanPort(gameLog))
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("ZeroTier", text))
        Toast.makeText(this, "$text (скопировано)", Toast.LENGTH_LONG).show()
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Поделиться IP").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    /** Показывает последние строки лога игры на экране. */
    private val tailer = object : Runnable {
        override fun run() {
            try {
                val f = gameLog
                if (f != null && f.exists()) {
                    RandomAccessFile(f, "r").use { raf ->
                        val len = raf.length()
                        val start = maxOf(0L, len - 7000)
                        raf.seek(start)
                        val buf = ByteArray((len - start).toInt())
                        raf.readFully(buf)
                        val lines = String(buf).lines().takeLast(45)
                        logView.text = lines.joinToString("\n")
                        if (BuildConfig.LABS) {
                            val p = Regex("Started serving on (\\d+)").findAll(String(buf)).lastOrNull()?.groupValues?.get(1)?.toInt()
                            if (p != null && p != lastLanPort) {
                                lastLanPort = p
                                val shared = try { File(getExternalFilesDir(null) ?: filesDir, "zt-share.txt").takeIf { it.exists() }?.readText()?.trim() } catch (_: Exception) { null }
                                val ip = shared?.substringBefore(':')
                                if (!ip.isNullOrEmpty()) {
                                    val t = "[ZeroTier] IP: $ip:$p"
                                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("ZeroTier", t))
                                    Toast.makeText(this@GameActivity, "$t (скопировано)", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 700)
        }
    }

    // ---------- ввод ----------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode != "game") {
            super.onBackPressed()
            return
        }
        try {
            Native.pushEvent(1, 256.0, 0.0, 1) // Esc нажата
            Native.pushEvent(1, 256.0, 0.0, 0) // Esc отпущена
        } catch (_: Throwable) {
        }
    }
}

/** Невидимое окно для пробного запуска игры (подбор модов): тот же экран игры, но 1×1 и прозрачный. */
class ProbeActivity : GameActivity()
