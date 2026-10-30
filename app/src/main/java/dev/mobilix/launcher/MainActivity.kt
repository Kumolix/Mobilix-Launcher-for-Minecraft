package dev.mobilix.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar
import android.widget.ImageView
import android.widget.FrameLayout
import android.view.Gravity
import android.graphics.Color
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
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

/**
 * Mobilix Launcher.
 * Ник Player (офлайн), версия 1.21.3.
 * Скачивает файлы игры, ставит Java-рантайм, проверяет `java -version` и пробует запустить игру.
 * Графики и ввода (GLFW/LWJGL под Android) пока нет, поэтому игра дойдёт до ошибки окна: это ожидаемо.
 */
class MainActivity : Activity() {

    companion object {
        const val NICK = "Player"
        const val AGREEMENT_URL = "https://github.com/Kumolix/Mobilix-Launcher-for-Minecraft/blob/main/PRIVACY.md"
        const val DEFAULT_MIRROR = "https://api.modrinth.black/v2"
        const val VERSION = "1.21.3"
        const val MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val ASSETS_URL = "https://resources.download.minecraft.net/"

        // Готовые сборки OpenJDK 21 для Android arm64 (открытый код, GPLv2 + Classpath Exception).
        // Лаунчер ищет в релизах первый подходящий архив; если не вышло, пробует следующий источник.
        // Свой архив можно положить в папку данных как runtime.tar.xz или runtime.tar.gz.
        // Нативные библиотеки LWJGL под Android arm64 (подбираются по версии LWJGL из описания игры).
        val LWJGL_REPOS = listOf(
            "Unxkki/nlauncher-android-natives",
            "MDTCopper/android-lwjgl3",
            "yuyi2439/AndroidLWJGL"
        )
        val RUNTIME_REPOS = listOf(
            "metinjakupi/openjdk-android-arm64",
            "feeldev12/nerion-android-jdk-build",
            "AstralNext/aml-android-jre",
            "vhynlain/nlauncher-jre-android"
        )
    }

    private class Task(val url: String, val dest: File, val sha1: String?)

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var playBtn: Button
    private var logFile: File? = null

    private lateinit var skinView: SkinView
    private lateinit var logPanel: LinearLayout
    private lateinit var versionText: TextView
    private lateinit var nickText: TextView
    private lateinit var hamb: HamburgerView
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private lateinit var pageLayer: FrameLayout
    private lateinit var rootFrame: FrameLayout
    private lateinit var menuWipe: TextView
    private var drawerW = 0
    private var drawerOpen = false
    private var pageOpen = false
    private var accountSkin: SkinView? = null
    private lateinit var verLabel: TextView
    private lateinit var ghItem: TextView
    private var updateVer: String? = null

    private fun socialPage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        fun link(title: String, sub: String, url: String, hi: Boolean = false) {
            val t = TextView(this).apply {
                text = title + "\n" + sub; textSize = 16f; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0)
                setTextColor(if (hi) Th.altText else Color.WHITE)
                background = sel(if (hi) Th.altBg else 0xFF14301D.toInt()); isClickable = true
                setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {} }
            }
            col.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { bottomMargin = dp(12) })
        }
        link("GitHub", if (updateVer != null) tr("● Вышло обновление $updateVer", "● Update $updateVer available") else "Kumolix/Mobilix-Launcher-for-Minecraft",
            "https://github.com/Kumolix/Mobilix-Launcher-for-Minecraft", updateVer != null)
        link("TikTok", "@mobilix_launcher", "https://www.tiktok.com/@mobilix_launcher")
        link("Telegram", "@mobilix_launcher", "https://t.me/mobilix_launcher")
        link(tr("Политика конфиденциальности", "Privacy Policy"), tr("Данные не собираются", "No data is collected"), "https://github.com/Kumolix/Mobilix-Launcher-for-Minecraft/blob/main/PRIVACY.md")
        return col
    }

    private fun openGithub() {
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Kumolix/Mobilix-Launcher-for-Minecraft"))) } catch (_: Exception) {}
    }

    private fun verParts(v: String): List<Int>? = try { v.trim().split(".").map { it.toInt() } } catch (_: Exception) { null }

    /** Сверяет номер версии в репозитории с установленной; при новой показывает метку на главном экране и подсвечивает GitHub в меню. */
    private fun checkUpdate() {
        try {
            val remote = fetchText("https://raw.githubusercontent.com/Kumolix/Mobilix-Launcher-for-Minecraft/main/VERSION").trim()
            val cur = packageManager.getPackageInfo(packageName, 0).versionName ?: return
            val r = verParts(remote) ?: return
            val c = verParts(cur) ?: return
            var newer = false
            for (i in 0 until maxOf(r.size, c.size)) {
                val a = r.getOrElse(i) { 0 }; val b = c.getOrElse(i) { 0 }
                if (a != b) { newer = a > b; break }
            }
            if (!newer) return
            updateVer = remote
            runOnUiThread {
                verLabel.text = "v-$cur  ·  ⬆ " + tr("Вышло обновление $remote", "Update $remote available")
                verLabel.setTextColor(Th.accent); verLabel.typeface = Typeface.DEFAULT_BOLD
                ghItem.text = "● " + tr("Соцсети: обновление $remote", "Social: update $remote")
                ghItem.setTextColor(Th.altText); ghItem.background = sel(Th.altBg)
                if (prefs.getString("updToast", "") != remote) {
                    prefs.edit().putString("updToast", remote).apply()
                    Toast.makeText(this, tr("Вышло обновление Mobilix $remote", "Mobilix update $remote is available"), Toast.LENGTH_LONG).show()
                }
            }
        } catch (_: Throwable) {}
    }

    private val prefs get() = getSharedPreferences("launcher", MODE_PRIVATE)
    internal fun lang() = prefs.getString("lang", "ru") ?: "ru"
    internal fun tr(ru: String, en: String) = if (lang() == "en") en else ru
    internal fun elyOn() = !prefs.getString("ely_token", "").isNullOrEmpty()
    internal fun msOn() = !prefs.getString("ms_token", "").isNullOrEmpty()
    internal fun nickName() = if (msOn()) (prefs.getString("ms_name", NICK) ?: NICK) else if (elyOn()) (prefs.getString("ely_name", NICK) ?: NICK) else (prefs.getString("nick", NICK) ?: NICK)
    private fun authUuid(): String = if (msOn()) prefs.getString("ms_uuid", "")!! else if (elyOn()) prefs.getString("ely_uuid", "")!! else skinUuid()
    private fun authToken(): String = if (msOn()) prefs.getString("ms_token", "0")!! else if (elyOn()) prefs.getString("ely_token", "0")!! else "0"
    internal fun dpi(v: Int) = dp(v)

    /** Фон кнопки: при нажатии и в выключенном состоянии темнеет. */
    internal fun sel(color: Int): android.graphics.drawable.Drawable {
        fun dk(f: Float): Int = Color.rgb((Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt())
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), android.graphics.drawable.ColorDrawable(dk(0.45f)))
            addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(dk(0.55f)))
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(color))
        }
    }

    private fun bar(text: String, color: Int, sizeSp: Float, h: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = sizeSp; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = sel(color); isClickable = true; minHeight = dp(h)
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7) } catch (_: Exception) {}
        }
        Th.light = prefs.getString("theme", "dark") == "light"
        drawerW = resources.displayMetrics.widthPixels - dp(64)

        rootFrame = FrameLayout(this).apply { setBackgroundColor(Th.bg); fitsSystemWindows = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootFrame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // верхняя полоса: название по центру, лог справа (кнопка-меню рисуется поверх всего)
        val top = FrameLayout(this).apply { setBackgroundColor(Th.bar) }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        titleBox.addView(TextView(this).apply { text = "Mobilix"; textSize = 22f; setTextColor(Th.accent); gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.12f })
        verLabel = TextView(this).apply {
            text = versionLabel(); textSize = 11f; setTextColor(Th.dim); gravity = Gravity.CENTER
            setOnClickListener { if (updateVer != null) openGithub() }
        }
        titleBox.addView(verLabel)
        top.addView(titleBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56), Gravity.CENTER))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        // область 3D-скина
        val skinBox = FrameLayout(this).apply { setBackgroundColor(Th.skinBg) }
        skinView = SkinView(this)
        skinBox.addView(skinView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        nickText = TextView(this).apply {
            text = nickName(); textSize = 16f; setTextColor(Th.text2); gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        skinBox.addView(nickText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(skinBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // скин Ely.by обновляется при каждом открытии лаунчера (если есть интернет)
        if (elyOn()) Thread { try { importSkinNow("ely", nickName(), quiet = true, strict = true) } catch (_: Exception) {} }.start()
        else if (msOn()) Thread { try { importSkinNow("mojang", nickName(), quiet = true) } catch (_: Exception) {} }.start()

        // панель лога (скрыта по умолчанию)
        logPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setBackgroundColor(Th.bar) }
        scroll = ScrollView(this)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Th.text2)
            setTextIsSelectable(true)
            setPadding(dp(8), 0, dp(8), 0)
        }
        scroll.addView(logView)
        logPanel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))
        root.addView(logPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))

        // полоса версии + выпадающий вверх список
        val vrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        versionText = bar(tr("Версия: ", "Version: ") + prettyProfile(selectedProfile()), 0xFF14301D.toInt(), 17f, 56) {}
        vrow.addView(versionText, LinearLayout.LayoutParams(0, dp(56), 1f))
        val arrow = bar("▲", 0xFF0F2416.toInt(), 18f, 56) {}
        arrow.setOnClickListener { showVersions(arrow) }
        versionText.setOnClickListener { showVersions(arrow) }
        vrow.addView(arrow, LinearLayout.LayoutParams(dp(64), dp(56)))
        root.addView(vrow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        playBtn = Button(this).apply {
            text = tr("Играть", "Play"); textSize = 22f; setTextColor(Color.WHITE)
            background = sel(0xFF2EA84F.toInt()); isAllCaps = false
            setOnClickListener { start() }
        }
        root.addView(playBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)))
        if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) {
            playBtn.isEnabled = false
            android.app.AlertDialog.Builder(this)
                .setTitle(tr("Устройство не поддерживается", "Device not supported"))
                .setMessage(tr("Нужна 64-битная система (arm64). Здесь работает 32-битная, поэтому Minecraft (Java 21) запустить нельзя. Лаунчер откроется, но кнопка «Играть» отключена.",
                    "A 64-bit (arm64) system is required. This device runs a 32-bit one, so Minecraft (Java 21) cannot start. The launcher opens, but Play is disabled."))
                .setPositiveButton("OK", null).show()
        } else {
            val es = (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).deviceConfigurationInfo.reqGlEsVersion
            if (es < 0x30002) Toast.makeText(this, tr("Видеочип поддерживает OpenGL ES ниже 3.2: игра может не запуститься или работать плохо.", "GPU supports OpenGL ES below 3.2: the game may not run or may run poorly."), Toast.LENGTH_LONG).show()
        }

        // затемнение + боковое меню
        scrim = View(this).apply {
            setBackgroundColor(0xFF000000.toInt()); alpha = 0f; visibility = View.GONE
            setOnClickListener { setDrawer(false) }
        }
        rootFrame.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Th.drawer)
            translationX = -drawerW.toFloat(); isClickable = true
        }
        drawer.addView(TextView(this).apply {
            text = "Mobilix"; textSize = 26f; setTextColor(Th.accent); gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.12f; setPadding(dp(72), 0, 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        fun item(ru: String, en: String, onClick: () -> Unit): TextView {
            val t = TextView(this).apply {
                text = tr(ru, en); textSize = 18f; setTextColor(Th.text); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(28), 0, dp(16), 0); isClickable = true; background = sel(Th.drawer)
                setOnClickListener { onClick() }
            }
            drawer.addView(View(this).apply { setBackgroundColor(Th.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            drawer.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
            return t
        }
        item("Аккаунт", "Account") { openPage(tr("Аккаунт", "Account"), accountPage()) }
        item("Настройки", "Settings") { openPage(tr("Настройки", "Settings"), settingsPage()) }
        item("Загрузчик версий", "Version loader") { openPage(tr("Загрузчик версий", "Version loader"), versionsPage()) }
        item("Каталог", "Catalog") { openPage(tr("Каталог", "Catalog"), catalogPage()) }
        item("Раскладка управления", "Controls layout") { startActivity(Intent(this, ControlsEditorActivity::class.java)) }
        menuWipe = item("Удалить скачанное", "Delete downloads") { confirmWipe() }
        ghItem = item("Соцсети", "Social") { openPage(tr("Соцсети", "Social"), socialPage()) }
        item("Язык", "Language") { chooseLanguage() }
        drawer.addView(View(this).apply { setBackgroundColor(Th.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        drawer.addView(TextView(this).apply {
            text = tr("Пользовательское соглашение", "User agreement"); textSize = 13f; setTextColor(Th.dim)
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(28), 0, dp(16), 0); isClickable = true
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(AGREEMENT_URL))) } catch (_: Exception) {}
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        rootFrame.addView(drawer, FrameLayout.LayoutParams(drawerW, ViewGroup.LayoutParams.MATCH_PARENT))

        hamb = HamburgerView(this).apply { setOnClickListener { setDrawer(!drawerOpen) } }
        rootFrame.addView(hamb, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.TOP))

        val logBtn = TextView(this).apply {
            text = tr("Лог", "Log"); textSize = 12f; setTextColor(Th.accent); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Th.drawer); setStroke(dp(2), 0xFF2EA84F.toInt()); cornerRadius = dp(8).toFloat() }
            setOnClickListener { logPanel.visibility = if (logPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        rootFrame.addView(logBtn, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.TOP).apply { topMargin = dp(8); marginEnd = dp(10) })

        pageLayer = FrameLayout(this).apply { visibility = View.GONE }
        rootFrame.addView(pageLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(rootFrame)
        Thread { loadSkin() }.start()
        Thread { checkUpdate() }.start()
    }

    // ---------- меню и страницы ----------
    private fun setDrawer(open: Boolean) {
        drawerOpen = open
        val from = 1f - (-drawer.translationX / drawerW)
        scrim.visibility = View.VISIBLE
        android.animation.ValueAnimator.ofFloat(from, if (open) 1f else 0f).apply {
            duration = 300
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                drawer.translationX = -(1f - p) * drawerW
                scrim.alpha = 0.6f * p
                hamb.progress = p
                if (p == 0f) scrim.visibility = View.GONE
            }
            start()
        }
    }

    private fun openPage(title: String, content: View) {
        pageLayer.removeAllViews()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Th.bg); isClickable = true }
        val head = FrameLayout(this).apply { setBackgroundColor(Th.bar) }
        head.addView(TextView(this).apply {
            text = "‹"; textSize = 34f; setTextColor(Th.accent); gravity = Gravity.CENTER
            setOnClickListener { closePage() }
        }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START))
        head.addView(TextView(this).apply {
            text = title; textSize = 19f; setTextColor(Th.accent); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56), Gravity.CENTER))
        page.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        page.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pageLayer.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pageLayer.visibility = View.VISIBLE
        pageOpen = true
        val h = rootFrame.height.toFloat().coerceAtLeast(resources.displayMetrics.heightPixels.toFloat())
        page.translationY = h
        page.animate().translationY(0f).setDuration(320).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun closePage() {
        if (!pageOpen) return
        pageOpen = false
        accountSkin = null
        val page = pageLayer.getChildAt(0) ?: return
        page.animate().translationY(pageLayer.height.toFloat()).setDuration(260)
            .withEndAction { pageLayer.removeAllViews(); pageLayer.visibility = View.GONE }.start()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            pageOpen -> closePage()
            drawerOpen -> setDrawer(false)
            else -> super.onBackPressed()
        }
    }

    private fun chooseLanguage() {
        AlertDialog.Builder(this).setTitle(tr("Язык", "Language"))
            .setItems(arrayOf("Русский", "English")) { _, i ->
                prefs.edit().putString("lang", if (i == 1) "en" else "ru").apply()
                recreate()
            }.show()
    }

    // ---------- настройки ----------
    private fun header(t: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(22), 0, dp(8))
        addView(TextView(context).apply { text = t; textSize = 20f; setTextColor(Th.accent) })
        addView(View(context).apply { setBackgroundColor(0xFF2EA84F.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(4) })
    }

    private fun chipRow(labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, t -> t.setBackgroundColor(if (i == sel) 0xFF2EA84F.toInt() else 0xFF14301D.toInt()) }
        labels.forEachIndexed { i, l ->
            val t = TextView(this).apply {
                text = l; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener { paint(i); onPick(i) }
            }
            views.add(t)
            row.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        paint(selected)
        return row
    }

    private fun note(t: String) = TextView(this).apply { text = t; textSize = 12f; setTextColor(Th.dim); setPadding(0, dp(6), 0, 0) }

    /** Настройки для процесса игры (читаются в GameActivity и ControlsView). */
    internal fun writeGameSettings() {
        try {
            File(filesDir, "game_settings.json").writeText(JSONObject()
                .put("renderScale", prefs.getInt("renderScale", 100))
                .put("joystick", prefs.getBoolean("joystick", false))
                .put("fps", prefs.getInt("fps", 0))
                .put("fullscreen", prefs.getBoolean("fullscreen", false)).toString())
        } catch (_: Exception) {}
    }

    private fun openGameFolder() {
        val dir = curGameDir().apply { mkdirs() }
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("path", dir.path))
        } catch (_: Exception) {}
        try {
            startActivity(Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES").setPackage("com.sec.android.app.myfiles")
                .putExtra("samsung.myfiles.intent.extra.START_PATH", dir.path))
            return
        } catch (_: Exception) {}
        try {
            val rel = dir.path.substringAfter("/storage/emulated/0/")
            val uri = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.document/directory").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {}
        Toast.makeText(this, tr("Не удалось открыть проводник. Путь скопирован: ", "Could not open the file manager. Path copied: ") + dir.path, Toast.LENGTH_LONG).show()
    }

    private fun settingsPage(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(24)) }
        col.addView(header(tr("Лаунчер", "Launcher")))
        col.addView(TextView(this).apply { text = tr("Тема", "Theme"); setTextColor(Th.text); setPadding(0, 0, 0, dp(6)) })
        col.addView(chipRow(listOf(tr("Тёмная", "Dark"), tr("Светлая", "Light")), if (Th.light) 1 else 0) { i ->
            prefs.edit().putString("theme", if (i == 1) "light" else "dark").apply()
            recreate()
        })
        col.addView(TextView(this).apply { text = tr("Многопоточная загрузка", "Multi-threaded download"); setTextColor(Th.text); setPadding(0, dp(16), 0, dp(6)) })
        col.addView(chipRow(listOf(tr("Выкл", "Off"), tr("Вкл", "On")), if (prefs.getBoolean("multiDl", true)) 1 else 0) { i ->
            prefs.edit().putBoolean("multiDl", i == 1).apply()
        })
        col.addView(note(tr("Скачивает файлы игры в 16 потоков вместо 3: намного быстрее. Если интернет нестабилен или появляются ошибки загрузки, выключите.", "Downloads game files in 16 threads instead of 3: much faster. Turn off if your connection is unstable or downloads fail.")))
        col.addView(TextView(this).apply { text = tr("Управление движением", "Movement control"); setTextColor(Th.text); setPadding(0, dp(16), 0, dp(6)) })
        col.addView(chipRow(listOf(tr("Крестовина (WASD)", "D-pad (WASD)"), tr("Джойстик", "Joystick")), if (prefs.getBoolean("joystick", false)) 1 else 0) { i ->
            prefs.edit().putBoolean("joystick", i == 1).apply(); writeGameSettings()
        })
        col.addView(note(tr("Джойстик: тяните в сторону, чтобы идти; за край круга — бег.", "Joystick: drag to walk; beyond the ring to sprint.")))
        col.addView(Button(this).apply {
            text = tr("Открыть папку игры", "Open game folder"); isAllCaps = false; setTextColor(Color.WHITE); background = sel(0xFF14301D.toInt())
            setOnClickListener { openGameFolder() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(14) })
        col.addView(note(tr("Откроется «Мои файлы» (Samsung) или стандартный проводник. Путь также скопирован в буфер.", "Opens My Files (Samsung) or the system file manager. The path is also copied.")))
        col.addView(header(tr("Игра", "Game")))
        val cur = prefs.getInt("renderScale", 100)
        val lbl = TextView(this).apply { text = tr("Разрешение рендера: $cur%", "Render resolution: $cur%"); setTextColor(Th.text) }
        col.addView(lbl)
        col.addView(SeekBar(this).apply {
            max = 70; progress = cur - 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { lbl.text = tr("Разрешение рендера: ${p + 30}%", "Render resolution: ${p + 30}%") }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val v = (sb?.progress ?: 70) + 30
                    prefs.edit().putInt("renderScale", v).apply()
                    writeGameSettings()
                }
            })
        })
        col.addView(note(tr("Игра рисуется в меньшем разрешении и растягивается на весь экран: меньше нагрузка на GPU, выше FPS. Размытия нет, это именно снижение числа пикселей. Применяется при следующем запуске.",
            "The game renders at a lower resolution and is scaled to the full screen: lower GPU load, higher FPS. Applied on next launch.")))
        col.addView(TextView(this).apply { text = tr("Стабилизация производительности", "Performance stabilization"); setTextColor(Th.text); setPadding(0, dp(18), 0, dp(6)) })
        val fpsOpts = listOf(0, 60, 45, 30)
        col.addView(chipRow(listOf(tr("Выкл", "Off"), "60", "45", "30"), fpsOpts.indexOf(prefs.getInt("fps", 0)).coerceAtLeast(0)) { i ->
            prefs.edit().putInt("fps", fpsOpts[i]).apply(); writeGameSettings()
        })
        col.addView(note(tr("Ограничивает FPS: меньше нагрев и расход батареи, но и частота кадров ниже. Применяется при запуске.", "Caps FPS: less heat and battery use, but lower frame rate. Applied on launch.")))
        col.addView(TextView(this).apply { text = tr("Мипмапы (уровни текстур)", "Mipmap levels"); setTextColor(Th.text); setPadding(0, dp(18), 0, dp(6)) })
        col.addView(chipRow(listOf(tr("Авто", "Auto"), "0", "1", "2", "3", "4"), prefs.getInt("mip", -1) + 1) { i ->
            prefs.edit().putInt("mip", i - 1).apply()
        })
        col.addView(note(tr("На старых видеочипах (Adreno 6xx и ниже) мипмапы сильно роняют FPS в мире: анимации воды и лавы грузятся по 5 раз за кадр. «Авто»: после первого запуска лаунчер узнаёт видеочип и один раз ставит 0 для такого, дальше ваш выбор в самой игре сохраняется. Число: ставится при каждом запуске. Применяется при следующем запуске.",
            "On old GPUs (Adreno 6xx and older) mipmaps can crush FPS in the world: water and lava animations upload 5 times per frame. Auto: after the first launch the launcher learns the GPU and sets 0 once for such devices; your in-game choice is kept afterwards. A number is applied on every launch. Applied on next launch.")))
        col.addView(TextView(this).apply { text = tr("Полноэкранный режим", "Full screen mode"); setTextColor(Th.text); setPadding(0, dp(18), 0, dp(6)) })
        col.addView(chipRow(listOf(tr("Выкл", "Off"), tr("Вкл", "On")), if (prefs.getBoolean("fullscreen", false)) 1 else 0) { i ->
            prefs.edit().putBoolean("fullscreen", i == 1).apply(); writeGameSettings()
        })
        col.addView(note(tr("Игра рисуется и под вырезом фронтальной камеры. Если появится серая полоса — выключите.", "The game draws under the front camera cutout. If a grey bar appears, turn it off.")))
        val mi = android.app.ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        val totalMb = (mi.totalMem / 1024 / 1024).toInt()
        val maxX = (totalMb * 6 / 10).coerceAtLeast(1024) / 128 * 128
        val curX = prefs.getInt("xmx", 0)
        val xl = TextView(this).apply { setTextColor(Th.text); setPadding(0, dp(18), 0, dp(4)) }
        fun xText(v: Int) { xl.text = if (v == 0) tr("Память игры: авто", "Game memory: auto") else tr("Память игры: $v МБ", "Game memory: $v MB") }
        xText(curX)
        col.addView(xl)
        val xs = SeekBar(this).apply {
            max = (maxX - 512) / 128; progress = if (curX == 0) 0 else ((curX - 512) / 128).coerceIn(0, max)
            visibility = if (curX == 0) View.GONE else View.VISIBLE
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { xText(512 + p * 128) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { prefs.edit().putInt("xmx", 512 + (sb?.progress ?: 0) * 128).apply() }
            })
        }
        col.addView(chipRow(listOf(tr("Авто", "Auto"), tr("Вручную", "Manual")), if (curX == 0) 0 else 1) { i ->
            if (i == 0) { prefs.edit().putInt("xmx", 0).apply(); xs.visibility = View.GONE; xText(0) }
            else { val v = 512 + xs.progress * 128; prefs.edit().putInt("xmx", v).apply(); xs.visibility = View.VISIBLE; xText(v) }
        })
        col.addView(xs)
        col.addView(note(tr("Больше памяти полезно для модов, но слишком большое значение система может «убить». Всего ОЗУ: $totalMb МБ. Применяется при следующем запуске.",
            "More memory helps with mods, but too large a value may get the game killed. Total RAM: $totalMb MB. Applied on next launch.")))
        col.addView(TextView(this).apply { text = tr("Настройки JVM", "JVM settings"); setTextColor(Th.text); setPadding(0, dp(18), 0, dp(6)) })
        val gcs = listOf("Serial", "Parallel", "G1")
        col.addView(chipRow(gcs, gcs.indexOf(prefs.getString("gc", "Serial")).coerceAtLeast(0)) { i -> prefs.edit().putString("gc", gcs[i]).apply() })
        col.addView(android.widget.EditText(this).apply {
            hint = "-XX:+UseG1GC -Dfoo=bar"; setHintTextColor(Th.hint); setTextColor(Th.text); setSingleLine(true)
            setText(prefs.getString("jvmArgs", ""))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { prefs.edit().putString("jvmArgs", e.toString().trim()).apply() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        col.addView(note(tr("Сборщик мусора и дополнительные аргументы (через пробел). Неверный аргумент может сломать запуск.", "Garbage collector and extra arguments (space-separated). A wrong argument can break launch.")))
        col.addView(header(tr("Специальное", "Special")))
        col.addView(TextView(this).apply { text = tr("Источник каталога", "Catalog source"); setTextColor(Th.text); setPadding(0, 0, 0, dp(6)) })
        col.addView(chipRow(listOf("Modrinth", "Modrinth Black"), prefs.getInt("modSource", 2) - 1) { i -> prefs.edit().putInt("modSource", i + 1).apply() })
        col.addView(TextView(this).apply { text = tr("Адрес API зеркала", "Mirror API address"); setTextColor(Th.text); setPadding(0, dp(14), 0, 0) })
        val mirror = android.widget.EditText(this).apply {
            setText(prefs.getString("mirrorApi", DEFAULT_MIRROR)); setTextColor(Th.text); setSingleLine(true)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { prefs.edit().putString("mirrorApi", e.toString().trim()).apply() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        col.addView(mirror)
        col.addView(Button(this).apply {
            text = tr("Сбросить адрес", "Reset address"); isAllCaps = false; setTextColor(Color.WHITE); background = sel(0xFF14301D.toInt())
            setOnClickListener { mirror.setText(DEFAULT_MIRROR); prefs.edit().putString("mirrorApi", DEFAULT_MIRROR).apply() }
        }, LinearLayout.LayoutParams(dp(180), dp(40)).apply { topMargin = dp(6) })
        col.addView(note(tr("Если Modrinth недоступен, выберите Modrinth Black. Если зеркало не отвечает, поправьте адрес API здесь.",
            "If Modrinth is unreachable, choose Modrinth Black. If the mirror does not respond, fix the API address here.")))
        sv.addView(col)
        return sv
    }

    // ---------- каталог (Modrinth) ----------
    private fun apiBases(): List<String> {
        val official = "https://api.modrinth.com/v2"
        val mirror = (prefs.getString("mirrorApi", "https://api.modrinth.black/v2") ?: official).trimEnd('/')
        return if (prefs.getInt("modSource", 2) == 2) listOf(mirror, official) else listOf(official, mirror)
    }

    private fun apiGet(path: String): String {
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

    // индекс скачанного: projectId -> {t: тип, f: имя файла, n: название}
    private fun idx(): JSONObject = try { JSONObject(prefs.getString("installed", "{}")) } catch (_: Exception) { JSONObject() }
    private fun idxSave(o: JSONObject) { prefs.edit().putString("installed", o.toString()).apply() }
    private fun typeDir(type: String) = when (type) { "resourcepack" -> "resourcepacks"; "shader" -> "shaderpacks"; else -> "mods" }
    private fun packFile(type: String, name: String) = File(curGameDir(), typeDir(type) + "/" + name)

    /** Кнопки строки каталога: «Скачать» или «Загружено» + «Удалить». */
    private fun fillActions(box: LinearLayout, id: String, type: String, loader: String, title: String) {
        box.removeAllViews()
        val rec = idx().optJSONObject(id)
        if (rec != null && packFile(rec.optString("t"), rec.optString("f")).exists()) {
            box.addView(Button(this).apply {
                text = tr("Загружено", "Downloaded"); isAllCaps = false; setTextColor(Color.WHITE); background = sel(0xFF14301D.toInt()); isEnabled = false
            }, LinearLayout.LayoutParams(dp(120), dp(40)))
            box.addView(Button(this).apply {
                text = tr("Удалить", "Delete"); isAllCaps = false; setTextColor(Color.WHITE); background = sel(0xFFB3261E.toInt())
                setOnClickListener {
                    packFile(rec.optString("t"), rec.optString("f")).delete()
                    idxSave(idx().apply { remove(id) })
                    fillActions(box, id, type, loader, title)
                }
            }, LinearLayout.LayoutParams(dp(100), dp(40)).apply { marginStart = dp(8) })
        } else {
            val btn = Button(this).apply {
                text = tr("Скачать", "Download"); isAllCaps = false; setTextColor(Color.WHITE); background = sel(0xFF2EA84F.toInt())
            }
            btn.setOnClickListener { installProject(id, type, loader, title, btn) { fillActions(box, id, type, loader, title) } }
            box.addView(btn, LinearLayout.LayoutParams(dp(150), dp(40)))
        }
    }

    private fun catalogPage(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var type = "mod"; var loader = if (selectedProfile().contains("forge")) "forge" else "fabric"
        val q = android.widget.EditText(this).apply {
            hint = tr("Поиск", "Search"); setHintTextColor(Th.hint); setTextColor(Th.text); setSingleLine(true)
            setPadding(dp(20), dp(12), dp(20), dp(12)); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        box.addView(q)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(24)) }
        fun showDownloaded() {
            list.removeAllViews()
            val index = idx()
            val known = HashSet<String>()
            var count = 0
            fun row(title: String, sub: String, type: String, file: File, id: String?) {
                count++
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(Th.text); typeface = Typeface.DEFAULT_BOLD })
                col.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(Th.dim) })
                r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(Button(this).apply {
                    text = tr("Удалить", "Delete"); isAllCaps = false; setTextColor(Color.WHITE); background = sel(0xFFB3261E.toInt())
                    setOnClickListener {
                        file.delete()
                        if (id != null) idxSave(idx().apply { remove(id) })
                        (r.parent as? LinearLayout)?.removeView(r)
                    }
                }, LinearLayout.LayoutParams(dp(100), dp(40)))
                list.addView(r)
                list.addView(View(this).apply { setBackgroundColor(Th.line2) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
            val keys = index.keys()
            while (keys.hasNext()) {
                val id = keys.next(); val o = index.getJSONObject(id)
                val f = packFile(o.optString("t"), o.optString("f"))
                if (f.exists()) { known.add(f.path); row(o.optString("n", f.name), typeDir(o.optString("t")) + " · " + f.name, o.optString("t"), f, id) }
            }
            for (t in listOf("mod", "resourcepack", "shader")) {
                File(curGameDir(), typeDir(t)).listFiles()?.sortedBy { it.name }?.forEach {
                    if (it.isFile && !known.contains(it.path) && !it.name.endsWith(".part")) row(it.name, typeDir(t), t, it, null)
                }
            }
            if (count == 0) list.addView(TextView(this).apply { text = tr("Пока ничего не скачано", "Nothing downloaded yet"); setTextColor(Th.dim); setPadding(0, dp(16), 0, 0) })
        }
        fun search() {
            if (type == "downloaded") { showDownloaded(); return }
            list.removeAllViews()
            list.addView(TextView(this).apply { text = tr("Загрузка…", "Loading…"); setTextColor(Th.dim); setPadding(0, dp(16), 0, 0) })
            val query = q.text.toString().trim()
            Thread {
                try {
                    val facets = JSONArray().put(JSONArray().put("versions:${curBase()}")).put(JSONArray().put("project_type:$type"))
                    if (type == "mod") facets.put(JSONArray().put("categories:$loader"))
                    val path = "/search?query=" + java.net.URLEncoder.encode(query, "UTF-8") +
                        "&facets=" + java.net.URLEncoder.encode(facets.toString(), "UTF-8") + "&limit=25&index=" + (if (query.isEmpty()) "downloads" else "relevance")
                    val hits = JSONObject(apiGet(path)).getJSONArray("hits")
                    runOnUiThread {
                        list.removeAllViews()
                        if (hits.length() == 0) list.addView(TextView(this).apply { text = tr("Ничего не найдено", "Nothing found"); setTextColor(Th.dim); setPadding(0, dp(16), 0, 0) })
                        for (i in 0 until hits.length()) {
                            val h = hits.getJSONObject(i)
                            val outer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10)) }
                            val icon = ImageView(this).apply { setBackgroundColor(Th.line2); scaleType = ImageView.ScaleType.CENTER_CROP }
                            outer.addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) })
                            loadIcon(h.optString("icon_url", ""), icon)
                            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                            outer.addView(row, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            row.addView(TextView(this).apply { text = h.getString("title"); textSize = 17f; setTextColor(Th.text); typeface = Typeface.DEFAULT_BOLD })
                            row.addView(TextView(this).apply { text = h.optString("author") + "  ·  ⬇ " + h.optInt("downloads"); textSize = 12f; setTextColor(Th.accent) })
                            row.addView(TextView(this).apply { text = h.optString("description"); textSize = 13f; setTextColor(Th.text2); maxLines = 3 })
                            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                            fillActions(actions, h.getString("project_id"), type, loader, h.getString("title"))
                            row.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                            list.addView(outer)
                            list.addView(View(this).apply { setBackgroundColor(Th.line2) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                        }
                    }
                } catch (e: Throwable) {
                    runOnUiThread {
                        list.removeAllViews()
                        list.addView(TextView(this).apply { text = tr("Не удалось загрузить каталог: ", "Could not load the catalog: ") + e.message + tr("\nПроверьте источник в Настройки → Специальное.", "\nCheck the source in Settings → Special."); setTextColor(Th.dim); setPadding(0, dp(16), 0, 0) })
                    }
                }
            }.start()
        }
        val types = listOf("mod" to tr("Моды", "Mods"), "resourcepack" to tr("Ресурспаки", "Resource packs"), "shader" to tr("Шейдеры", "Shaders"), "downloaded" to tr("Скачанные", "Downloaded"))
        val loaders = listOf("fabric", "forge")
        val loaderRow = chipRow(loaders.map { it.replaceFirstChar { c -> c.uppercase() } }, loaders.indexOf(loader).coerceAtLeast(0)) { i -> loader = loaders[i]; if (type == "mod") search() }
        val typeRow = chipRow(types.map { it.second }, 0) { i ->
            type = types[i].first
            loaderRow.visibility = if (type == "mod") View.VISIBLE else View.GONE
            q.visibility = if (type == "downloaded") View.GONE else View.VISIBLE
            search()
        }
        box.addView(typeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12); bottomMargin = dp(6) })
        box.addView(loaderRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12); bottomMargin = dp(6) })
        val sv = ScrollView(this); sv.addView(list)
        box.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        q.setOnEditorActionListener { _, _, _ -> search(); true }
        search()
        return box
    }

    private val iconCache = HashMap<String, android.graphics.Bitmap>()
    private fun loadIcon(url: String, iv: ImageView) {
        if (url.isEmpty()) return
        iconCache[url]?.let { iv.setImageBitmap(it); return }
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 15000
                c.setRequestProperty("User-Agent", "Kumolix/Mobilix/1.4")
                val bytes = c.inputStream.use { it.readBytes() }
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = maxOf(1, o.outWidth / 160) }
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
                if (bmp != null) { iconCache[url] = bmp; runOnUiThread { iv.setImageBitmap(bmp) } }
            } catch (_: Throwable) {}
        }.start()
    }

    private fun installProject(id: String, type: String, loader: String, title: String, btn: Button, onDone: () -> Unit) {
        btn.isEnabled = false; btn.alpha = 0.6f; btn.text = tr("Скачивание…", "Downloading…")
        Thread {
            var res = ""
            try {
                val g = "?game_versions=" + java.net.URLEncoder.encode("[\"${curBase()}\"]", "UTF-8") +
                    (if (type == "mod") "&loaders=" + java.net.URLEncoder.encode("[\"$loader\"]", "UTF-8") else "")
                val vers = JSONArray(apiGet("/project/$id/version$g"))
                if (vers.length() == 0) throw RuntimeException(tr("нет файла для ${curBase()}", "no file for ${curBase()}"))
                val files = vers.getJSONObject(0).getJSONArray("files")
                var f = files.getJSONObject(0)
                for (i in 0 until files.length()) if (files.getJSONObject(i).optBoolean("primary")) f = files.getJSONObject(i)
                val dir = when (type) { "resourcepack" -> "resourcepacks"; "shader" -> "shaderpacks"; else -> "mods" }
                val root = getExternalFilesDir(null) ?: filesDir
                val dest = File(curGameDir(), "$dir/" + f.getString("filename"))
                val ok = download(f.getString("url"), dest, f.optJSONObject("hashes")?.optString("sha1")?.ifEmpty { null })
                if (!ok) throw RuntimeException(tr("ошибка загрузки", "download failed"))
                idxSave(idx().apply { put(id, JSONObject().put("t", type).put("f", f.getString("filename")).put("n", title)) })
                res = "ok"
            } catch (e: Throwable) { res = e.message ?: e.toString() }
            runOnUiThread {
                if (res == "ok") { onDone() }
                else { btn.isEnabled = true; btn.alpha = 1f; btn.text = tr("Скачать", "Download"); Toast.makeText(this, res, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // ---------- аккаунт ----------
    private fun accountPage(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24)) }
        val skin = SkinView(this)
        accountSkin = skin
        skinFile()?.let { skin.setSkin(android.graphics.BitmapFactory.decodeFile(it.path)) }
        col.addView(skin, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
        fun btn(t: String, bg: Int, fg: Int = Color.WHITE, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setTextColor(fg); background = sel(bg); setOnClickListener { onClick() }
        }
        skin.slim = prefs.getBoolean("slim", false)
        val modelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val modelBtns = ArrayList<TextView>()
        fun paintModel() = modelBtns.forEachIndexed { i, t ->
            val on = (i == 1) == prefs.getBoolean("slim", false)
            t.background = sel(if (on) 0xFF1B6E34.toInt() else 0xFF2EA84F.toInt())
        }
        for ((i, label) in listOf(tr("Широкий", "Wide"), tr("Стройный", "Slim")).withIndex()) {
            val t = TextView(this).apply {
                text = label; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; isClickable = true
                setOnClickListener {
                    prefs.edit().putBoolean("slim", i == 1).apply()
                    skin.slim = i == 1; skinView.slim = i == 1; paintModel()
                }
            }
            modelBtns.add(t)
            modelRow.addView(t, LinearLayout.LayoutParams(dp(130), dp(44)).apply { marginStart = dp(6); marginEnd = dp(6) })
        }
        paintModel()
        col.addView(modelRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(8) })
        col.addView(btn(tr("Загрузить скин (PNG)", "Upload skin (PNG)"), 0xFF14301D.toInt()) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/png"), 77)
        })
        col.addView(btn(tr("Сбросить на стандартный", "Reset to default"), 0xFF0F2416.toInt()) {
            File(filesDir, "skin_custom.png").delete()
            Thread { loadSkin() }.start()
            skinFile()?.let { accountSkin?.setSkin(android.graphics.BitmapFactory.decodeFile(it.path)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        col.addView(TextView(this).apply {
            text = tr("Скин по нику (Ely.by или Mojang)", "Skin by nickname (Ely.by or Mojang)"); textSize = 14f; setTextColor(Th.dim); setPadding(0, dp(20), 0, dp(4))
        })
        val skinNick = android.widget.EditText(this).apply {
            hint = tr("Ник игрока", "Player nickname"); setHintTextColor(Th.hint); setTextColor(Th.text); setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(16))
        }
        col.addView(skinNick)
        val impRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        impRow.addView(btn("Ely.by", 0xFF14301D.toInt()) { importSkin("ely", skinNick.text.toString().trim()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        impRow.addView(btn("Mojang", 0xFF14301D.toInt()) { importSkin("mojang", skinNick.text.toString().trim()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        col.addView(impRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        col.addView(TextView(this).apply {
            text = tr("Импорт по нику отправляет ник на сервер выбранного сервиса.", "Import by nickname sends the nickname to the chosen service.")
            textSize = 12f; setTextColor(Th.dim); setPadding(0, dp(6), 0, 0)
        })
        col.addView(TextView(this).apply {
            text = tr("Ник (офлайн-режим)", "Nickname (offline mode)"); textSize = 14f; setTextColor(Th.dim); setPadding(0, dp(24), 0, dp(4))
        })
        val nick = android.widget.EditText(this).apply {
            setText(nickName()); setTextColor(Th.text); setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(16))
        }
        col.addView(nick)
        col.addView(btn(tr("Сохранить ник", "Save nickname"), 0xFF2EA84F.toInt()) {
            val n = nick.text.toString().trim()
            if (!Regex("[A-Za-z0-9_]{3,16}").matches(n)) {
                Toast.makeText(this, tr("Ник: 3–16 символов, латиница, цифры и _", "Nick: 3–16 chars, letters, digits and _"), Toast.LENGTH_LONG).show()
            } else {
                prefs.edit().putString("nick", n).apply()
                nickText.text = n
                Toast.makeText(this, tr("Ник сохранён", "Nickname saved"), Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        if (elyOn()) {
            col.addView(TextView(this).apply {
                text = tr("Ely.by: ", "Ely.by: ") + nickName(); textSize = 15f; setTextColor(Th.accent); setPadding(0, dp(24), 0, dp(4))
            })
            col.addView(Button(this).apply {
                text = tr("Выйти", "Sign out"); isAllCaps = false; setTextColor(0xFFFF6B6B.toInt()); background = sel(0xFF4A0D0D.toInt())
                setOnClickListener { elyLogout(true); nickText.text = nickName(); openPage(tr("Аккаунт", "Account"), accountPage()) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(4) })
        } else {
            col.addView(btn(tr("Войти через Ely.by", "Sign in with Ely.by"), 0xFF14301D.toInt()) {
                elyDialog { openPage(tr("Аккаунт", "Account"), accountPage()) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(24) })
        }
        // кнопка Microsoft по их правилам: белый фон, рамка, логотип слева
        val ms = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1), 0xFF8C8C8C.toInt()) }
            isClickable = true
            setOnClickListener { msDeviceLogin() }
        }
        ms.addView(MsLogoView(this), LinearLayout.LayoutParams(dp(21), dp(21)))
        ms.addView(TextView(this).apply { text = tr("Войти через Microsoft", "Sign in with Microsoft"); textSize = 15f; setTextColor(0xFF5E5E5E.toInt()); setPadding(dp(12), 0, 0, 0) })
        col.addView(ms, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
        if (msOn()) {
            ms.visibility = View.GONE
            col.addView(TextView(this).apply {
                text = "Microsoft: " + nickName(); textSize = 15f; setTextColor(Th.accent); setPadding(0, dp(24), 0, dp(4))
            })
            col.addView(Button(this).apply {
                text = tr("Выйти", "Sign out"); isAllCaps = false; setTextColor(0xFFFF6B6B.toInt()); background = sel(0xFF4A0D0D.toInt())
                setOnClickListener { msLogout(true); nickText.text = nickName(); openPage(tr("Аккаунт", "Account"), accountPage()) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(4) })
        }
        col.addView(TextView(this).apply {
            text = tr("Скин применяется и в игре: лаунчер подставляет его как стандартный скин (ресурспак Mobilix-skin). Ширина рук в игре зависит от UUID игрока.",
                "The skin is also applied in-game as the default skin (Mobilix-skin resource pack). Arm width in-game depends on the player UUID.")
            textSize = 12f; setTextColor(Th.dim); setPadding(0, dp(16), 0, 0)
        })
        sv.addView(col)
        return sv
    }

    // ---------- Microsoft ----------
    private val MS_CLIENT = "f5cd701a-c346-422c-bd11-dfd604cb0c68"
    private val MS_TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
    private val MS_SCOPE = "XboxLive.signin offline_access"

    private fun postForm(url: String, p: Map<String, String>): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); c.setRequestProperty("User-Agent", "Mobilix/1.8")
        val body = p.entries.joinToString("&") { java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8") }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    private fun getBearer(url: String, token: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer $token"); c.setRequestProperty("User-Agent", "Mobilix/1.8")
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    /** Токен Microsoft -> Xbox Live -> XSTS -> токен Minecraft и профиль. Возвращает (токен, uuid, ник). */
    private fun msMinecraftLogin(msAccess: String): Triple<String, String, String> {
        val (c1, t1) = postJson("https://user.auth.xboxlive.com/user/authenticate", JSONObject()
            .put("Properties", JSONObject().put("AuthMethod", "RPS").put("SiteName", "user.auth.xboxlive.com").put("RpsTicket", "d=$msAccess"))
            .put("RelyingParty", "http://auth.xboxlive.com").put("TokenType", "JWT"))
        if (c1 !in 200..299) throw RuntimeException("Xbox Live: HTTP $c1")
        val xbl = JSONObject(t1)
        val xblToken = xbl.getString("Token")
        val uhs = xbl.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs")
        val (c2, t2) = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", JSONObject()
            .put("Properties", JSONObject().put("SandboxId", "RETAIL").put("UserTokens", JSONArray().put(xblToken)))
            .put("RelyingParty", "rp://api.minecraftservices.com/").put("TokenType", "JWT"))
        if (c2 !in 200..299) {
            val xe = try { JSONObject(t2).optLong("XErr") } catch (_: Exception) { 0L }
            throw RuntimeException(when (xe) {
                2148916233L -> tr("у аккаунта нет профиля Xbox", "the account has no Xbox profile")
                2148916238L -> tr("детский аккаунт: его нужно добавить в семью Microsoft", "child account: add it to a Microsoft family")
                else -> "XSTS: HTTP $c2"
            })
        }
        val xsts = JSONObject(t2).getString("Token")
        val (c3, t3) = postJson("https://api.minecraftservices.com/authentication/login_with_xbox", JSONObject().put("identityToken", "XBL3.0 x=$uhs;$xsts"))
        if (c3 == 403) throw RuntimeException(tr("Mojang ещё не одобрил приложение (Invalid app registration)", "Mojang has not approved the app yet (Invalid app registration)"))
        if (c3 !in 200..299) throw RuntimeException("Minecraft: HTTP $c3")
        val mc = JSONObject(t3).getString("access_token")
        val (c4, t4) = getBearer("https://api.minecraftservices.com/minecraft/profile", mc)
        if (c4 == 404) throw RuntimeException(tr("на аккаунте нет Minecraft Java Edition", "this account does not own Minecraft Java Edition"))
        if (c4 !in 200..299) throw RuntimeException("Профиль: HTTP $c4")
        val pj = JSONObject(t4)
        return Triple(mc, pj.getString("id"), pj.getString("name"))
    }

    private fun msSave(mc: String, uuid: String, name: String, refresh: String) {
        prefs.edit().putString("ms_token", mc).putString("ms_uuid", uuid).putString("ms_name", name)
            .putString("ms_refresh", refresh).putLong("ms_exp", System.currentTimeMillis() + 23L * 3600 * 1000).apply()
    }

    private fun msLogout(tell: Boolean) {
        prefs.edit().remove("ms_token").remove("ms_uuid").remove("ms_name").remove("ms_refresh").remove("ms_exp").apply()
        if (tell) Toast.makeText(this, tr("Вы вышли из Microsoft", "Signed out of Microsoft"), Toast.LENGTH_SHORT).show()
    }

    /** Токен Minecraft живёт сутки: при необходимости обновляем через refresh-токен. false: нужен новый вход. */
    private fun msEnsureSession(): Boolean {
        if (System.currentTimeMillis() < prefs.getLong("ms_exp", 0L) - 120000L) return true
        val rt = prefs.getString("ms_refresh", "") ?: ""
        return try {
            val (c, t) = postForm(MS_TOKEN_URL, mapOf("client_id" to MS_CLIENT, "grant_type" to "refresh_token", "refresh_token" to rt, "scope" to MS_SCOPE))
            if (c !in 200..299) return false
            val j = JSONObject(t)
            val (mc, id, name) = msMinecraftLogin(j.getString("access_token"))
            msSave(mc, id, name, j.optString("refresh_token", rt))
            true
        } catch (_: java.io.IOException) { true }   // нет сети: пробуем запуститься с текущим токеном
        catch (_: Exception) { false }
    }

    /** Вход через Microsoft по коду устройства: пользователь открывает страницу и вводит код. */
    private fun msDeviceLogin() {
        Toast.makeText(this, tr("Подключение к Microsoft…", "Connecting to Microsoft…"), Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val (c, t) = postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", mapOf("client_id" to MS_CLIENT, "scope" to MS_SCOPE))
                if (c !in 200..299) throw RuntimeException("Microsoft: HTTP $c " + (try { JSONObject(t).optString("error_description").take(150) } catch (_: Exception) { "" }))
                val j = JSONObject(t)
                val userCode = j.getString("user_code")
                val uri = j.getString("verification_uri")
                val dev = j.getString("device_code")
                var interval = j.optInt("interval", 5)
                val until = System.currentTimeMillis() + j.optInt("expires_in", 900) * 1000L
                val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle(tr("Вход через Microsoft", "Sign in with Microsoft"))
                        .setMessage(tr("1. Откройте $uri\n2. Введите код: $userCode\n3. Войдите в аккаунт с Minecraft и вернитесь сюда.\n\nЛаунчер сам заметит вход.",
                            "1. Open $uri\n2. Enter the code: $userCode\n3. Sign in with the account that owns Minecraft and come back.\n\nThe launcher will notice the sign-in by itself."))
                        .setPositiveButton(tr("Открыть и скопировать код", "Open and copy code")) { _, _ ->
                            try {
                                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("code", userCode))
                                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
                            } catch (_: Exception) {}
                            Toast.makeText(this, tr("Ждём подтверждения…", "Waiting for confirmation…"), Toast.LENGTH_LONG).show()
                        }
                        .setNegativeButton(tr("Отмена", "Cancel")) { _, _ -> cancelled.set(true) }
                        .setOnCancelListener { cancelled.set(true) }
                        .show()
                }
                var access: String? = null; var refresh = ""
                while (!cancelled.get() && System.currentTimeMillis() < until && access == null) {
                    Thread.sleep(interval * 1000L)
                    if (cancelled.get()) break
                    val (c2, t2) = postForm(MS_TOKEN_URL, mapOf("client_id" to MS_CLIENT, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to dev))
                    val j2 = JSONObject(t2)
                    if (c2 in 200..299) { access = j2.getString("access_token"); refresh = j2.optString("refresh_token", "") }
                    else when (j2.optString("error")) {
                        "authorization_pending" -> {}
                        "slow_down" -> interval += 5
                        else -> throw RuntimeException(j2.optString("error_description").take(150).ifEmpty { j2.optString("error") })
                    }
                }
                if (access == null) return@Thread
                val (mc, id, name) = msMinecraftLogin(access)
                elyLogout(false)
                msSave(mc, id, name, refresh)
                try { importSkinNow("mojang", name, quiet = true) } catch (_: Exception) {}
                runOnUiThread {
                    nickText.text = nickName()
                    Toast.makeText(this, tr("Вы вошли как $name", "Signed in as $name"), Toast.LENGTH_LONG).show()
                    openPage(tr("Аккаунт", "Account"), accountPage())
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, tr("Вход не удался: ", "Sign-in failed: ") + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // ---------- Ely.by ----------
    private val ELY_AUTH = "https://authserver.ely.by/auth"

    private fun postJson(url: String, body: JSONObject): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "Mobilix/1.8")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    private fun clientToken(): String = prefs.getString("ely_client", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString("ely_client", it).apply() }

    /** Проверяет токен Ely.by, при необходимости обновляет. false: нужен новый вход. */
    private fun elyEnsureSession(): Boolean {
        return try {
            val tok = prefs.getString("ely_token", "") ?: ""
            if (postJson("$ELY_AUTH/validate", JSONObject().put("accessToken", tok)).first in 200..299) return true
            val (c, t) = postJson("$ELY_AUTH/refresh", JSONObject().put("accessToken", tok).put("clientToken", clientToken()))
            if (c !in 200..299) return false
            prefs.edit().putString("ely_token", JSONObject(t).getString("accessToken")).apply()
            true
        } catch (_: Exception) { true } // нет сети: пробуем запуститься с текущим токеном
    }

    private fun elyLogout(tell: Boolean) {
        prefs.edit().remove("ely_token").remove("ely_uuid").remove("ely_name").apply()
        if (tell) Toast.makeText(this, tr("Вы вышли из Ely.by", "Signed out of Ely.by"), Toast.LENGTH_SHORT).show()
    }

    private fun elyLogin(user: String, pass: String, onDone: (String?) -> Unit) {
        Thread {
            var err: String? = null
            try {
                val (code, text) = postJson("$ELY_AUTH/authenticate", JSONObject().put("username", user).put("password", pass)
                    .put("clientToken", clientToken()).put("requestUser", true))
                if (code in 200..299) {
                    val j = JSONObject(text)
                    val prof = j.getJSONObject("selectedProfile")
                    msLogout(false)
                    prefs.edit().putString("ely_token", j.getString("accessToken")).putString("ely_uuid", prof.getString("id"))
                        .putString("ely_name", prof.getString("name")).apply()
                    // скин аккаунта становится скином в лаунчере
                    try { importSkinNow("ely", prof.getString("name")) } catch (_: Exception) {}
                } else {
                    val m = try { JSONObject(text).optString("errorMessage") } catch (_: Exception) { "" }
                    err = if (m.contains("two factor", true)) "2FA" else if (code == 401 || code == 403) tr("Неверный логин или пароль", "Wrong login or password") else (m.ifEmpty { "HTTP $code" })
                }
            } catch (e: Exception) { err = tr("Нет связи с Ely.by", "Cannot reach Ely.by") }
            runOnUiThread { onDone(err) }
        }.start()
    }

    private fun elyDialog(after: () -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0) }
        val u = android.widget.EditText(this).apply { hint = tr("Ник или email Ely.by", "Ely.by nickname or email"); setSingleLine(true) }
        val p = android.widget.EditText(this).apply {
            hint = tr("Пароль", "Password"); setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val f = android.widget.EditText(this).apply { hint = tr("Код двухфакторной защиты (если включена)", "2FA code (if enabled)"); setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        box.addView(u); box.addView(p); box.addView(f)
        android.app.AlertDialog.Builder(this).setTitle(tr("Вход через Ely.by", "Sign in with Ely.by")).setView(box)
            .setPositiveButton(tr("Войти", "Sign in")) { _, _ ->
                val pass = p.text.toString() + (if (f.text.isNotEmpty()) ":" + f.text else "")
                elyLogin(u.text.toString().trim(), pass) { err ->
                    if (err == null) { Toast.makeText(this, tr("Вход выполнен", "Signed in"), Toast.LENGTH_SHORT).show(); nickText.text = nickName(); after() }
                    else if (err == "2FA") Toast.makeText(this, tr("Включена двухфакторная защита: введите код в третье поле", "2FA is on: enter the code in the third field"), Toast.LENGTH_LONG).show()
                    else Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(tr("Отмена", "Cancel"), null).show()
    }

    /** Можно ли подключать -javaagent: в рантайме есть модуль java.instrument и libinstrument.so, и она загружается. */
    private fun agentUsable(rt: File): Boolean {
        val so = File(rt, "lib/libinstrument.so")
        if (!so.exists()) { log("  libinstrument.so не найдена в рантайме"); return false }
        val rel = try { File(rt, "release").readText() } catch (_: Exception) { null }
        if (rel != null && !rel.contains("java.instrument")) { log("  в рантайме нет модуля java.instrument"); return false }
        // Файл есть и модуль подключён; нужные библиотеки (libjli, libjava) подгружает сам процесс игры до запуска JVM.
        return true
    }

    /** authlib-injector (открытый, AGPL-3.0): подменяет серверы авторизации и скинов на Ely.by. */
    private fun ensureAuthlib(): File? {
        val f = File(filesDir, "authlib-injector.jar")
        if (f.exists() && f.length() > 10000) return f
        return try {
            val rel = JSONObject(fetchText("https://api.github.com/repos/yushijinhun/authlib-injector/releases/latest"))
            val assets = rel.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".jar")) { url = a.getString("browser_download_url"); break }
            }
            if (url == null) null else { downloadBig(url, f); f }
        } catch (e: Exception) { log("authlib-injector: не скачался ($e)"); null }
    }

    /** Скачивает скин игрока по нику: source = "ely" или "mojang". */
    private fun importSkin(source: String, nickIn: String) {
        if (!Regex("[A-Za-z0-9_]{3,16}").matches(nickIn)) {
            Toast.makeText(this, tr("Введите ник: 3–16 символов, латиница, цифры и _", "Enter a nickname: 3–16 chars, letters, digits and _"), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, tr("Загрузка скина…", "Downloading skin…"), Toast.LENGTH_SHORT).show()
        Thread {
            try {
                importSkinNow(source, nickIn)
            } catch (e: Exception) {
                val why = e.message ?: e.javaClass.simpleName
                runOnUiThread { Toast.makeText(this, tr("Скин не найден: ", "Skin not found: ") + why, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun httpBytes(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Mobilix/1.8")
        c.setRequestProperty("Accept", "*/*")
        val code = c.responseCode
        if (code !in 200..299) throw RuntimeException("HTTP $code (${URL(url).host})")
        return c.inputStream.use { it.readBytes() }
    }

    /** Скин по нику: несколько источников по очереди (на мобильной сети часть адресов может быть недоступна). */
    private fun importSkinNow(source: String, nickIn: String, quiet: Boolean = false, strict: Boolean = false) {
        val tries = ArrayList<Pair<String, () -> Triple<ByteArray, Boolean, Boolean>>>()  // название, действие -> (png, slim?, slimKnown)
        fun fromTextureJson(js: JSONObject): Triple<ByteArray, Boolean, Boolean> {
            val t = js.getJSONObject("SKIN")
            return Triple(httpBytes(t.getString("url")), t.optJSONObject("metadata")?.optString("model") == "slim", true)
        }
        val ely = "Ely.by" to {
            try {
                fromTextureJson(JSONObject(String(httpBytes("https://skinsystem.ely.by/textures/$nickIn"))))
            } catch (e: Exception) {
                Triple(httpBytes("https://skinsystem.ely.by/skins/$nickIn.png"), false, false)
            }
        }
        val mojangId = {
            try { JSONObject(String(httpBytes("https://api.minecraftservices.com/minecraft/profile/lookup/name/$nickIn"))).getString("id") }
            catch (e: Exception) { JSONObject(String(httpBytes("https://api.mojang.com/users/profiles/minecraft/$nickIn"))).getString("id") }
        }
        val mojang = "Mojang" to {
            val id = mojangId()
            val props = JSONObject(String(httpBytes("https://sessionserver.mojang.com/session/minecraft/profile/$id"))).getJSONArray("properties")
            val value = String(android.util.Base64.decode(props.getJSONObject(0).getString("value"), android.util.Base64.DEFAULT))
            fromTextureJson(JSONObject(value).getJSONObject("textures"))
        }
        val minotar = "Minotar" to { Triple(httpBytes("https://minotar.net/skin/$nickIn"), false, false) }
        val mcheads = "MC-Heads" to { Triple(httpBytes("https://mc-heads.net/skin/$nickIn"), false, false) }
        if (strict) tries.add(ely)
        else if (source == "ely") { tries.add(ely); tries.add(mojang); tries.add(minotar) } else { tries.add(mojang); tries.add(minotar); tries.add(mcheads); tries.add(ely) }
        var lastErr = ""
        for ((name, act) in tries) {
            try {
                val (bytes, slimIn, known) = act()
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp == null || bmp.width != 64 || (bmp.height != 64 && bmp.height != 32)) { lastErr = "$name: not a 64x64 skin"; continue }
                // если модель неизвестна, определяем по прозрачности руки (у «стройного» скина правая рука 3 пикселя)
                val slim = if (known) slimIn else (bmp.height == 64 && android.graphics.Color.alpha(bmp.getPixel(54, 20)) == 0 && android.graphics.Color.alpha(bmp.getPixel(55, 25)) == 0)
                File(filesDir, "skin_custom.png").writeBytes(bytes)
                prefs.edit().putBoolean("slim", slim).apply()
                runOnUiThread {
                    skinView.slim = slim; skinView.setSkin(bmp)
                    accountSkin?.slim = slim; accountSkin?.setSkin(bmp)
                    if (!quiet) Toast.makeText(this, tr("Скин загружен ($name)", "Skin loaded ($name)") + (if (slim) tr(", стройный", ", slim") else ""), Toast.LENGTH_SHORT).show()
                }
                return
            } catch (e: Exception) {
                lastErr = "$name: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw RuntimeException(lastErr)
    }

    private fun skinFile(): File? {
        val c = File(filesDir, "skin_custom.png")
        if (c.exists()) return c
        val d = File(filesDir, "skin_default.png")
        return if (d.exists()) d else null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 77 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp == null || bmp.width != 64 || (bmp.height != 64 && bmp.height != 32)) {
                Toast.makeText(this, tr("Нужен скин PNG 64×64", "Need a 64×64 PNG skin"), Toast.LENGTH_LONG).show()
                return
            }
            File(filesDir, "skin_custom.png").writeBytes(bytes)
            skinView.setSkin(bmp)
            accountSkin?.setSkin(bmp)
        } catch (e: Throwable) {
            Toast.makeText(this, "Ошибка: $e", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- установка версий ----------
    /** Установленный профиль нужного типа для версии игры, либо null. */
    private fun findProfile(kind: String, mc: String): String? {
        val ids = installedProfiles()
        return when (kind) {
            "vanilla" -> ids.firstOrNull { it == mc && File(dataRoot(), "versions/$it/$it.jar").exists() }
            "fabric" -> ids.firstOrNull { it.startsWith("fabric-loader-") && it.endsWith("-$mc") }
            else -> ids.firstOrNull { it.startsWith("$mc-forge-") || it.startsWith("$mc-forge$mc-") }
        }
    }

    /** Скачивает версию (ванильную, Fabric или Forge) и выбирает её. Идёт в фоне, пока работает сервис. */
    private fun installProfile(kind: String, mc: String, done: () -> Unit) {
        setBusy(true)
        Thread {
            try {
                val root = dataRoot()
                logFile = File(root, "launcher.log").also { it.writeText("") }
                var id = mc
                when (kind) {
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
                    "forge" -> {
                        prepareAndRun(mc, false)
                        val promos = JSONObject(fetchText("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")).getJSONObject("promos")
                        val fv = promos.optString("$mc-recommended", "").ifEmpty { promos.optString("$mc-latest", "") }
                        if (fv.isEmpty()) throw RuntimeException("Forge для $mc не найден")
                        val installer = File(root, "forge-installer.jar")
                        installer.delete()
                        log("Forge $mc-$fv: скачивание установщика…")
                        var got = false; var lastErr: Exception? = null
                        for (suf in listOf("", "-$mc", "-$mc.0")) {
                            try {
                                downloadBig("https://maven.minecraftforge.net/net/minecraftforge/forge/$mc-$fv$suf/forge-$mc-$fv$suf-installer.jar", installer)
                                if (installer.length() > 100000) { got = true; break }
                            } catch (e: Exception) { lastErr = e; installer.delete() }
                        }
                        if (!got) throw RuntimeException("не удалось скачать установщик Forge: $lastErr")
                        val legacyMc = try { JSONObject(File(root, "versions/$mc/$mc.json").readText()).optJSONObject("arguments") == null } catch (_: Exception) { false }
                        if (legacyMc) {
                            // Старые версии: установщик не запускаем, берём из него описание версии и файл Forge
                            log("Forge: распаковка установщика…")
                            id = installLegacyForge(installer, root)
                            log("Профиль $id создан, докачиваю библиотеки…")
                            prepareAndRun(id, false)
                        } else {
                            val java = ensureRuntime(root, 21)
                            val rt = java.parentFile!!.parentFile!!
                            val lp = File(root, "launcher_profiles.json")
                            if (!lp.exists()) lp.writeText("{\"profiles\":{}}")
                            var found: String? = null
                            for (attempt in 1..3) {
                                log("Forge: установка, попытка $attempt (это долго, потерпите)…")
                                runForgeInstaller(rt, installer, root)
                                found = findProfile("forge", mc)
                                if (found != null) break
                            }
                            id = found ?: throw RuntimeException("Forge установился, но профиль не найден")
                        }
                    }
                    else -> prepareAndRun(mc, false)
                }
                prefs.edit().putString("profile", id).apply()
                runOnUiThread { versionText.text = tr("Версия: ", "Version: ") + prettyProfile(id) }
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

    // ---------- загрузчик версий ----------
    private fun versionsPage(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val filter = android.widget.EditText(this).apply {
            hint = tr("Нужная версия (например 1.21)", "Needed version (e.g. 1.21)")
            setHintTextColor(Th.hint); setTextColor(Th.text); setSingleLine(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        box.addView(filter)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        box.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        val sv = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(24)) }
        sv.addView(list)
        box.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val vanilla = ArrayList<String>(); val fabric = ArrayList<String>(); val forge = ArrayList<String>()
        var loader = "all"
        val root = getExternalFilesDir(null) ?: filesDir
        var loaded = false

        fun render() {
            list.removeAllViews()
            if (!loaded) {
                list.addView(TextView(this).apply { text = tr("Загрузка списка…", "Loading list…"); setTextColor(Th.dim); setPadding(0, dp(16), 0, 0) })
                return
            }
            val q = filter.text.toString().trim()
            fun section(name: String, items: List<String>, key: String) {
                if (loader == "downloaded" || (loader != "all" && loader != key)) return
                val f = items.filter { q.isEmpty() || it.contains(q) }
                if (f.isEmpty()) return
                list.addView(TextView(this).apply { text = name; textSize = 20f; setTextColor(Th.accent); setPadding(0, dp(18), 0, dp(6)) })
                list.addView(View(this).apply { setBackgroundColor(0xFF2EA84F.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
                for (v in f) {
                    val pid = findProfile(key, v)
                    val sel = pid != null && pid == selectedProfile()
                    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
                    r.addView(TextView(this).apply { text = v; textSize = 17f; setTextColor(Th.text) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    r.addView(Button(this).apply {
                        text = when { sel -> tr("✓ Выбрана", "✓ Selected"); pid != null -> tr("Выбрать", "Select"); else -> tr("Скачать", "Download") }
                        isAllCaps = false; textSize = 12f; setTextColor(Color.WHITE)
                        background = sel(if (pid != null) 0xFF14301D.toInt() else 0xFF2EA84F.toInt())
                        setOnClickListener {
                            if (pid != null) {
                                prefs.edit().putString("profile", pid).apply()
                                versionText.text = tr("Версия: ", "Version: ") + prettyProfile(pid)
                                render()
                            } else if (busy) {
                                Toast.makeText(this@MainActivity, tr("Подождите: идёт другая загрузка", "Wait: another download is in progress"), Toast.LENGTH_SHORT).show()
                            } else {
                                isEnabled = false; alpha = 0.6f; text = tr("Скачивание…", "Downloading…")
                                installProfile(key, v) { runOnUiThread { render() } }
                            }
                        }
                    }, LinearLayout.LayoutParams(dp(130), dp(40)))
                    list.addView(r)
                    list.addView(View(this).apply { setBackgroundColor(Th.line2) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                }
            }
            section("Vanilla", vanilla, "vanilla"); section("Fabric", fabric, "fabric"); section("Forge", forge, "forge")
            if (loader == "all" || loader == "downloaded") {
                val mine = installedProfiles().filter { q.isEmpty() || it.contains(q) || prettyProfile(it).contains(q) }
                if (mine.isNotEmpty()) {
                    list.addView(TextView(this).apply { text = tr("Скачанные", "Downloaded"); textSize = 20f; setTextColor(Th.accent); setPadding(0, dp(18), 0, dp(6)) })
                    list.addView(View(this).apply { setBackgroundColor(0xFF2EA84F.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
                    for (id in mine) {
                        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
                        r.addView(TextView(this).apply { text = prettyProfile(id) + if (id == selectedProfile()) "  ✓" else ""; textSize = 17f; setTextColor(Th.text) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        r.addView(Button(this).apply {
                            text = tr("Удалить", "Delete"); isAllCaps = false; textSize = 12f; setTextColor(Color.WHITE); background = sel(0xFFB3261E.toInt())
                            setOnClickListener {
                                if (busy) { Toast.makeText(this@MainActivity, tr("Подождите: идёт загрузка", "Wait: a download is in progress"), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle(tr("Удалить версию?", "Delete version?"))
                                    .setMessage(prettyProfile(id))
                                    .setNegativeButton(tr("Отмена", "Cancel"), null)
                                    .setPositiveButton(tr("Удалить", "Delete")) { _, _ ->
                                        File(dataRoot(), "versions/$id").deleteRecursively()
                                        if (selectedProfile() == id) prefs.edit().remove("profile").apply()
                                        versionText.text = tr("Версия: ", "Version: ") + prettyProfile(selectedProfile())
                                        render()
                                    }.show()
                            }
                        }, LinearLayout.LayoutParams(dp(130), dp(40)))
                        list.addView(r)
                        list.addView(View(this).apply { setBackgroundColor(Th.line2) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                    }
                }
            }
            if (list.childCount == 0) list.addView(TextView(this).apply { text = tr("Ничего не найдено", "Nothing found"); setTextColor(Th.dim); setPadding(0, dp(16), 0, 0) })
        }

        val chipViews = ArrayList<Pair<String, TextView>>()
        fun refreshChips() = chipViews.forEach { (k, t) ->
            t.setBackgroundColor(if (k == loader) 0xFF2EA84F.toInt() else 0xFF14301D.toInt())
        }
        for ((k, label) in listOf("all" to tr("Все", "All"), "vanilla" to "Vanilla", "fabric" to "Fabric", "forge" to "Forge", "downloaded" to tr("Скачанные", "Downloaded"))) {
            val t = TextView(this).apply {
                text = label; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { loader = k; refreshChips(); render() }
            }
            chipViews.add(k to t)
            chips.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        refreshChips()
        filter.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { render() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        render()

        Thread {
            try {
                val man = JSONObject(fetchText(MANIFEST_URL)).getJSONArray("versions")
                val order = HashMap<String, Int>()
                var cut = false
                for (i in 0 until man.length()) {
                    val o = man.getJSONObject(i); val id = o.getString("id")
                    order[id] = i
                    if (!cut && o.getString("type") == "release") vanilla.add(id)
                    if (id == "1.8") cut = true
                }
                try {
                    val fa = JSONArray(fetchText("https://meta.fabricmc.net/v2/versions/game"))
                    for (i in 0 until fa.length()) {
                        val o = fa.getJSONObject(i)
                        if (o.optBoolean("stable") && order.containsKey(o.getString("version"))) fabric.add(o.getString("version"))
                    }
                } catch (_: Throwable) {}
                try {
                    val pr = JSONObject(fetchText("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")).getJSONObject("promos")
                    val set = HashSet<String>()
                    val it = pr.keys()
                    while (it.hasNext()) set.add(it.next().substringBefore("-"))
                    forge.addAll(set.filter { vanilla.contains(it) }.sortedBy { order[it] })
                } catch (_: Throwable) {}
            } catch (e: Throwable) {
                log("Список версий: $e")
            }
            loaded = true
            runOnUiThread { render() }
        }.start()
        return box
    }

    private fun showVersions(anchor: View) {
        val installed = ArrayList(installedProfiles())
        if (installed.isEmpty()) installed.add(VERSION)
        val cur = selectedProfile()
        val pm = android.widget.PopupMenu(this, anchor, Gravity.END)
        installed.forEachIndexed { i, v -> pm.menu.add(0, i, i, prettyProfile(v) + if (v == cur) "  ✓" else "") }
        pm.menu.add(1, 1000, 1000, tr("＋ Загрузить другую версию…", "＋ Download another version…"))
        pm.setOnMenuItemClickListener {
            if (it.itemId == 1000) openPage(tr("Загрузчик версий", "Version loader"), versionsPage())
            else {
                prefs.edit().putString("profile", installed[it.itemId]).apply()
                versionText.text = tr("Версия: ", "Version: ") + prettyProfile(installed[it.itemId])
            }
            true
        }
        pm.show()
    }

    /** Достаёт стандартный скин (Стив) из клиентского jar и показывает его в 3D. */
    private fun loadSkin() {
        try {
            val root = getExternalFilesDir(null) ?: filesDir
            val cache = File(filesDir, "skin_default.png")
            if (!cache.exists()) {
                val bv = curBase(); val jar = File(root, "versions/$bv/$bv.jar")
                if (!jar.exists()) return
                java.util.zip.ZipFile(jar).use { z ->
                    val e = listOf(
                        "assets/minecraft/textures/entity/player/wide/steve.png",
                        "assets/minecraft/textures/entity/steve.png"
                    ).firstNotNullOfOrNull { z.getEntry(it) } ?: return
                    z.getInputStream(e).use { i -> cache.outputStream().use { o -> i.copyTo(o) } }
                }
            }
            val custom = File(filesDir, "skin_custom.png")
            val bmp = android.graphics.BitmapFactory.decodeFile((if (custom.exists()) custom else cache).path)
            runOnUiThread { skinView.slim = prefs.getBoolean("slim", false); skinView.setSkin(bmp) }
        } catch (_: Throwable) {
        }
    }

    private fun versionLabel(): String = try {
        "v-" + packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: Exception) {
        ""
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun log(s: String) {
        try {
            logFile?.appendText(s + "\n")
        } catch (_: Exception) {
        }
        runOnUiThread {
            logView.append(s + "\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    @Volatile internal var busy = false

    private fun setBusy(b: Boolean) = runOnUiThread {
        busy = b
        try {
            if (b) DownloadService.start(this) else DownloadService.stop(this)
        } catch (_: Throwable) {
        }
        playBtn.isEnabled = !b
        playBtn.alpha = if (b) 0.6f else 1f
        if (::menuWipe.isInitialized) menuWipe.isEnabled = !b
    }

    private fun start() {
        if (findJava(runtimeDir()) == null) {
            android.app.AlertDialog.Builder(this)
                .setTitle(tr("Первый запуск", "First launch"))
                .setMessage(tr("Первый запуск долгий: скачиваются Java, библиотеки и ресурсы игры (около 400 МБ), это займёт несколько минут (примерно 3–5). Лучше подключиться к Wi‑Fi и не закрывать лаунчер. Дальше игра будет запускаться быстро.",
                    "The first launch is slow: Java, libraries and game assets (about 400 MB) are downloaded, which takes a few minutes (roughly 3–5). Use Wi‑Fi and keep the launcher open. Later launches are fast."))
                .setPositiveButton(tr("Продолжить", "Continue")) { _, _ -> startNow() }
                .setNegativeButton(tr("Отмена", "Cancel"), null).show()
            return
        }
        startNow()
    }

    private fun startNow() {
        setBusy(true)
        Thread {
            try {
                prepareAndRun()
            } catch (e: Throwable) {
                log("ОШИБКА: $e")
            } finally {
                setBusy(false)
            }
        }.start()
    }

    // ---------- каталоги ----------

    /** Данные игры (скачанное). Внешняя папка приложения: оттуда запускать программы нельзя. */
    private fun dataRoot(): File = getExternalFilesDir(null) ?: filesDir

    /** Java-рантайм обязан лежать во внутренней памяти: из внешней Android не даст запустить файлы. */
    private fun runtimeDir(): File = File(filesDir, "runtime")

    /** UUID: при своём скине подбираем такой, у которого стандартный скин нужной ширины. */
    private fun skinUuid(): String {
        val n = nickName()
        if (!File(filesDir, "skin_custom.png").exists()) return offlineUuid(n)
        val slim = prefs.getBoolean("slim", false)
        for (k in 0 until 500) {
            val u = UUID.nameUUIDFromBytes("OfflinePlayer:$n#$k".toByteArray())
            val idx = Math.floorMod(u.hashCode(), 18)
            if ((idx < 9) == slim) return u.toString()
        }
        return offlineUuid(n)
    }

    /** Кладёт ресурспак, подменяющий все стандартные скины, и включает его в options.txt. */
    private fun applySkinPack(gameDir: File, ver: String = "") {
        val minor = Regex("^1\\.(\\d+)").find(ver)?.groupValues?.get(1)?.toIntOrNull() ?: 21
        val patch = Regex("^1\\.\\d+\\.(\\d+)").find(ver)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val fmt = when { minor <= 8 -> 1; minor <= 10 -> 2; minor <= 12 -> 3; minor <= 14 -> 4; minor == 15 -> 5; minor == 16 -> if (patch >= 2) 6 else 5; minor == 17 -> 7; minor == 18 -> 8; minor == 19 -> 9; else -> 42 }
        val oldNames = minor <= 12   // до 1.13 в options.txt имена паков без «file/»
        val packName = "Mobilix-skin.zip"
        val packFile = File(gameDir, "resourcepacks/$packName")
        val custom = File(filesDir, "skin_custom.png")
        val opt = File(gameDir, "options.txt")
        try {
            val lines = if (opt.exists()) opt.readLines().toMutableList() else ArrayList()
            val i = lines.indexOfFirst { it.startsWith("resourcePacks:") }
            val arr = try { JSONArray(if (i >= 0) lines[i].substringAfter(":") else "[]") } catch (_: Exception) { JSONArray() }
            val list = ArrayList<String>()
            for (k in 0 until arr.length()) list.add(arr.getString(k))
            val entry = if (oldNames) packName else "file/$packName"
            if (custom.exists()) {
                packFile.parentFile?.mkdirs()
                val png = custom.readBytes()
                java.util.zip.ZipOutputStream(packFile.outputStream()).use { z ->
                    z.putNextEntry(java.util.zip.ZipEntry("pack.mcmeta"))
                    z.write("{\"pack\":{\"pack_format\":$fmt,\"description\":\"Mobilix skin\"}}".toByteArray())
                    z.closeEntry()
                    for (n in listOf("steve", "alex")) {   // 1.8–1.19.2
                        z.putNextEntry(java.util.zip.ZipEntry("assets/minecraft/textures/entity/$n.png"))
                        z.write(png); z.closeEntry()
                    }
                    for (n in listOf("alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"))
                        for (d in listOf("wide", "slim")) {
                            z.putNextEntry(java.util.zip.ZipEntry("assets/minecraft/textures/entity/player/$d/$n.png"))
                            z.write(png); z.closeEntry()
                        }
                }
                if (!oldNames && !list.contains("vanilla")) list.add(0, "vanilla")
                if (!list.contains(entry)) list.add(entry)
            } else {
                list.remove(entry)
                packFile.delete()
            }
            val newLine = "resourcePacks:" + JSONArray(list).toString()
            if (i >= 0) lines[i] = newLine else lines.add(newLine)
            opt.writeText(lines.joinToString("\n") + "\n")
        } catch (e: Throwable) {
            log("Скин в игре: $e")
        }
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle(tr("Удалить скачанное?", "Delete downloads?"))
            .setMessage(tr("Будут удалены клиент игры, библиотеки, ресурсы, Java-рантайм, моды и паки. Миры (папка saves) можно оставить.",
                "The game client, libraries, assets, Java runtime, mods and packs will be deleted. You can keep your worlds (saves)."))
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .setNeutralButton(tr("Вообще всё", "Everything")) { _, _ -> wipe(true) }
            .setPositiveButton(tr("Всё, кроме миров", "All except worlds")) { _, _ -> wipe(false) }
            .show()
    }

    private fun wipe(everything: Boolean) {
        setBusy(true)
        Thread {
            try {
                val root = dataRoot()
                var freed = 0L
                val targets = listOf(
                    File(root, "assets"), File(root, "libraries"), File(root, "versions"),
                    File(root, "natives"), File(root, "launch_command.txt"), File(root, "launch.json"),
                    File(root, "runtime.tar.xz"), File(root, "runtime.tar.gz"), File(root, "forge-installer.jar"),
                    File(root, "launcher_profiles.json"),
                    runtimeDir(), cacheDir, File(filesDir, "natives"), File(filesDir, "lwjgl"), File(filesDir, "mg"),
                    File(filesDir, "skin_default.png"), File(root, "mg-plugin.apk")
                ) + (root.listFiles { f -> f.name.startsWith("lwjgl-natives") }?.toList() ?: emptyList())
                for (t in targets) {
                    freed += sizeOf(t)
                    t.deleteRecursively()
                }
                val game = File(root, "game")
                if (everything) {
                    freed += sizeOf(game); game.deleteRecursively()
                } else {
                    game.listFiles()?.forEach { if (it.name != "saves") { freed += sizeOf(it); it.deleteRecursively() } }
                }
                prefs.edit().remove("profile").remove("installed").apply()
                cacheDir.mkdirs()
                runOnUiThread { versionText.text = tr("Версия: ", "Version: ") + prettyProfile(selectedProfile()) }
                log("Удалено: ${freed / 1024 / 1024} МБ. " + if (everything) "Миры тоже удалены." else "Папка saves сохранена.")
            } catch (e: Throwable) {
                log("ОШИБКА удаления: $e")
            } finally {
                setBusy(false)
            }
        }.start()
    }

    private fun sizeOf(f: File): Long =
        if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---------- основной сценарий ----------

    /** Папка игры выбранного профиля: старые версии (формат до 1.13) играют в своей папке. */
    internal fun curGameDir(): File {
        val base = curBase()
        val legacy = try { JSONObject(File(dataRoot(), "versions/$base/$base.json").readText()).optJSONObject("arguments") == null } catch (_: Exception) { false }
        return File(dataRoot(), if (legacy) "game-$base" else "game")
    }

    internal fun selectedProfile(): String = prefs.getString("profile", VERSION) ?: VERSION

    /** Базовая версия игры выбранного профиля (для Fabric/Forge берётся из inheritsFrom). */
    internal fun curBase(): String {
        val id = selectedProfile()
        return try {
            val j = JSONObject(File(dataRoot(), "versions/$id/$id.json").readText())
            j.optString("inheritsFrom", "").ifEmpty { j.optString("jar", "") }.ifEmpty { id }
        } catch (_: Exception) { id }
    }

    internal fun prettyProfile(id: String): String = when {
        id.startsWith("fabric-loader-") -> "Fabric " + id.substringAfterLast("-") + " (" + id.removePrefix("fabric-loader-").substringBeforeLast("-") + ")"
        id.contains("-forge-") -> "Forge " + id.substringBefore("-forge-") + " (" + id.substringAfter("-forge-") + ")"
        Regex("^[\\d.]+-forge[\\d.]+-.+").matches(id) -> "Forge " + id.substringBefore("-forge") + " (" + id.substringAfter("-forge").removePrefix(id.substringBefore("-forge")).removePrefix("-") + ")"
        else -> id
    }

    internal fun installedProfiles(): List<String> {
        val out = ArrayList<String>()
        File(dataRoot(), "versions").listFiles()?.sortedBy { it.name }?.forEach { if (File(it, it.name + ".json").exists()) out.add(it.name) }
        return out
    }

    private fun mavenPath(name: String): String {
        val ext = if (name.contains("@")) name.substringAfter("@") else "jar"
        val p = name.substringBefore("@").split(":")
        val g = p[0].replace('.', '/'); val a = p[1]; val ver = p[2]
        return "$g/$a/$ver/$a-$ver" + (if (p.size > 3) "-" + p[3] else "") + ".$ext"
    }

    private fun mergeProfile(base: JSONObject, child: JSONObject?): JSONObject {
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

    private fun prepareAndRun(profile: String = selectedProfile(), play: Boolean = true) {
        val root = dataRoot()
        File(root, "game").mkdirs()
        val assetsDir = File(root, "assets")
        val librariesDir = File(root, "libraries")
        logFile = File(root, "launcher.log").also { it.writeText("") }
        val profFile = File(root, "versions/$profile/$profile.json")
        val child: JSONObject? = if (profFile.exists()) JSONObject(profFile.readText()).takeIf { it.has("inheritsFrom") || (it.has("jar") && it.optString("jar") != profile) } else null
        val baseVer = child?.let { it.optString("inheritsFrom", "").ifEmpty { it.optString("jar", "") } }?.ifEmpty { null } ?: profile
        val natives = File(filesDir, "natives/$baseVer").apply { mkdirs() }
        log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MODEL}, ${Build.SUPPORTED_ABIS.joinToString()}")
        log("Папка данных: ${root.path}")
        log("Профиль $profile (игра $baseVer), ник ${nickName()}")
        try {
            val gl = File(root, "game.log")
            if (gl.exists()) {
                val noise = Regex("^\\s*(Possible|Possible solutions|a\\)|b\\)|\\[LWJGL\\]|Module:|Loaded from|JRE:|JVM:|OS:)")
                val tail = gl.readLines().filter { it.isNotBlank() && !noise.containsMatchIn(it) && !it.contains("Incompatible Java and native") }.takeLast(40)
                log("=== Конец лога прошлого запуска игры ===")
                tail.forEach { log("  $it") }
            }
            root.listFiles()?.filter { it.isDirectory && it.name.startsWith("game") }?.forEach { d ->
                val cr = File(d, "crash-reports").listFiles()?.maxByOrNull { it.lastModified() }
                if (cr != null && System.currentTimeMillis() - cr.lastModified() < 30 * 60 * 1000) {
                    log("=== Отчёт о крэше ${d.name}/${cr.name} ===")
                    cr.readLines().take(45).forEach { log("  $it") }
                }
            }
        } catch (_: Exception) {}
        root.listFiles()?.filter { it.isDirectory && (it.name == "game" || it.name.startsWith("game-")) }?.forEach { d ->
            val hs = File(d, "hs_err.log")
            if (hs.exists()) {
                log("=== Предыдущий запуск упал (${d.name}/hs_err.log), начало: ===")
                try { hs.readLines().take(40).forEach { log("  $it") } } catch (_: Exception) {}
                hs.renameTo(File(d, "hs_err.old"))
            }
        }

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
        // старые версии играют в своей папке: настройки и миры 1.13+ ломают их (options.txt и т.п.)
        val gameDir = (if (legacy) File(root, "game-$baseVer") else File(root, "game")).apply { mkdirs() }
        if (legacy && child != null && !profile.contains("forge")) throw RuntimeException("Fabric и другие загрузчики для версий 1.12.2 и ниже не поддерживаются, только Forge")
        val oldForge = legacy && child != null
        val oldGl = legacy || javaMajor < 16
        applyMipmap(gameDir, oldGl)
        if (legacy) log("Старая версия (формат запуска до 1.13): слой LWJGL 2 + gl4es")
        else if (oldGl) log("Версия 1.13–1.16.5: LWJGL 3.3.3 + gl4es")
        val newLwjgl = "3.3.3"

        // 2. Клиентский jar
        val clientInfo = v.getJSONObject("downloads").getJSONObject("client")
        val clientJar = File(root, "versions/$baseVer/$baseVer.jar")
        downloadAll(
            "Клиент игры",
            listOf(Task(clientInfo.getString("url"), clientJar, clientInfo.getString("sha1")))
        )
        Thread { loadSkin() }.start()

        // 3. Библиотеки
        var lwjglVer = ""
        val libTasks = ArrayList<Task>()
        val cpMap = LinkedHashMap<String, String>()
        val libs = v.getJSONArray("libraries")
        for (i in 0 until libs.length()) {
            val lib = libs.getJSONObject(i)
            if (!allowed(lib)) continue
            val name = lib.optString("name")
            val art = lib.optJSONObject("downloads")?.optJSONObject("artifact")
            if (oldGl) {
                // нативные jar и LWJGL 2 нам не нужны: LWJGL 3.3.3 ставится отдельно
                if (name.startsWith("org.lwjgl.lwjgl:lwjgl:") || name.startsWith("org.lwjgl.lwjgl:lwjgl-platform:") ||
                    name.startsWith("net.java.jinput:") || name.startsWith("net.java.jutils:")) continue
                if (lib.has("natives") && art == null) continue
                if (name.split(":").size > 3 && name.split(":")[3].startsWith("natives")) continue
            }
            if (oldGl && name.startsWith("org.lwjgl:")) {
                val pp = name.split(":")
                val a = pp[1]
                val path3 = "org/lwjgl/$a/$newLwjgl/$a-$newLwjgl.jar"
                val dest3 = File(librariesDir, path3)
                libTasks.add(Task("https://repo1.maven.org/maven2/$path3", dest3, null))
                cpMap["org.lwjgl:$a"] = dest3.path
                continue
            }
            if (name.startsWith("org.lwjgl:lwjgl:")) lwjglVer = name.split(":")[2]
            val path: String; var url: String?; var sha: String? = null
            if (art != null) {
                path = art.getString("path"); url = art.optString("url", ""); sha = art.optString("sha1", "").ifEmpty { null }
            } else if (name.isNotEmpty()) {
                path = mavenPath(name)
                url = lib.optString("url", "https://libraries.minecraft.net/").trimEnd('/')
                    .replace("http://", "https://").replace("files.minecraftforge.net/maven", "maven.minecraftforge.net") + "/" + path
            } else continue
            val dest = File(librariesDir, path)
            if (!url.isNullOrEmpty() && !(oldForge && art == null && dest.exists())) libTasks.add(Task(url, dest, sha)) else if (!dest.exists()) continue
            val parts = name.split(":")
            val key = if (parts.size >= 2) parts[0] + ":" + parts[1] + (if (parts.size > 3) ":" + parts[3] else "") else path
            cpMap[key] = dest.path
        }
        if (oldGl) lwjglVer = newLwjgl
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
        val classpath = ArrayList<String>()
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

        // 4. Ресурсы (звуки, языки и т.д.)
        val idx = v.getJSONObject("assetIndex")
        val indexId = idx.getString("id")
        val indexFile = File(assetsDir, "indexes/$indexId.json")
        downloadAll("Индекс ресурсов", listOf(Task(idx.getString("url"), indexFile, idx.optString("sha1", "").ifEmpty { null })))
        val objects = JSONObject(indexFile.readText()).getJSONObject("objects")
        val assetTasks = ArrayList<Task>()
        val keys = objects.keys()
        while (keys.hasNext()) {
            val hash = objects.getJSONObject(keys.next()).getString("hash")
            val p = hash.substring(0, 2) + "/" + hash
            assetTasks.add(Task(ASSETS_URL + p, File(assetsDir, "objects/$p"), hash))
        }
        downloadAll("Ресурсы", assetTasks, fatal = false)
        val idxJson = JSONObject(indexFile.readText())
        val virtualAssets = idxJson.optBoolean("virtual", false) || idxJson.optBoolean("map_to_resources", false)
        val mapToResources = idxJson.optBoolean("map_to_resources", false)
        val assetsForGame: File = when {
            mapToResources -> File(gameDir, "resources")
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
        if (!play) { log("Файлы версии скачаны."); return }

        // 5. Java-рантайм
        val java = ensureRuntime(root, javaMajor)
        val rt = java.parentFile!!.parentFile!!
        val lwjglPath = ensureLwjgl(lwjglVer)
        val gl4es = File(applicationInfo.nativeLibraryDir, "libgl4es.so").takeIf { it.exists() }?.path
        if (oldGl && gl4es == null) log("ВНИМАНИЕ: gl4es нет в этой сборке, для старой версии используется MobileGlues (скорее всего не заработает).")
        val mgPath = if (oldGl && gl4es != null) gl4es else ensureMobileGlues()

        // 6. Команда запуска
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
        val repl = mapOf(
            "auth_player_name" to nickName(),
            "version_name" to profile,
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
            "classpath" to classpath.joinToString(":")
        )
        val sub = { s: String ->
            Regex("\\$\\{(\\w+)\\}").replace(s) { m -> repl[m.groupValues[1]] ?: m.value }
        }
        val jvmArgs: List<String>
        val gameArgs: List<String>
        if (legacy) {
            jvmArgs = listOf("-Djava.library.path=${natives.path}", "-cp", classpath.joinToString(":"))
            val dm = resources.displayMetrics
            val sw = maxOf(dm.widthPixels, dm.heightPixels); val sh = minOf(dm.widthPixels, dm.heightPixels)
            val base = v.getString("minecraftArguments").trim().split(Regex("\\s+")).map(sub)
            val g0 = if (base.contains("--width")) base else base + listOf("--width", sw.toString(), "--height", sh.toString())
            gameArgs = if (oldForge) g0 + listOf("--tweakClass", "dev.mobilix.loader.MobilixTweaker") else g0
        } else {
            val args = v.getJSONObject("arguments")
            jvmArgs = collectArgs(args.optJSONArray("jvm"), sub)
            gameArgs = collectArgs(args.optJSONArray("game"), sub)
        }
        val cmd = ArrayList<String>()
        cmd.add(java.path)
        val mi = android.app.ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        val totalMb = mi.totalMem / 1024 / 1024
        val manualX = prefs.getInt("xmx", 0)
        val modern = !oldGl   // 1.17+ тяжелее: даём больше памяти
        val xmx = if (manualX > 0) manualX else when { totalMb < 5000 -> if (modern) 1280 else 1024; totalMb < 10000 -> if (modern) 2048 else 1536; else -> 2048 }
        log("ОЗУ устройства: $totalMb МБ, память игры: $xmx МБ")
        cmd.add("-Xmx${xmx}M")
        cmd.add(when (prefs.getString("gc", "Serial")) { "G1" -> "-XX:+UseG1GC"; "Parallel" -> "-XX:+UseParallelGC"; else -> "-XX:+UseSerialGC" })
        prefs.getString("jvmArgs", "")?.split(Regex("\\s+"))?.filter { it.startsWith("-") }?.forEach { cmd.add(it) }
        cmd.add("-XX:ErrorFile=${File(gameDir, "hs_err.log").path}")
        cmd.add("-Xlog:gc:file=${File(gameDir, "gc.log").path}:uptime:filecount=1,filesize=2m")
        cmd.add("-Djava.io.tmpdir=${cacheDir.path}")
        cmd.add("-Duser.home=${gameDir.path}")
        cmd.add("-Djava.awt.headless=true")
        val libPath = (if (lwjglPath != null) "$lwjglPath:" else "") + applicationInfo.nativeLibraryDir
        cmd.add("-Dorg.lwjgl.librarypath=$libPath")
        if (lwjglPath != null) {
            cmd.add("-Djna.boot.library.path=$lwjglPath")
            cmd.add("-Djna.nosys=true")
        }
        if (mgPath != null) cmd.add("-Dorg.lwjgl.opengl.libname=$mgPath")
        if (oldGl) {
            cmd.add("-Dlog4j2.formatMsgNoLookups=true")
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
        cmd.add("-Dorg.lwjgl.util.Debug=true")
        cmd.add("-Dorg.lwjgl.util.DebugLoader=true")
        if (elyOn()) {
            val agent = ensureAuthlib()
            if (agent != null && agentUsable(rt)) cmd.add("-javaagent:${agent.path}=https://authserver.ely.by/api/authlib-injector")
            else log("ВНИМАНИЕ: в Java-рантайме нет рабочего java.instrument, поэтому authlib-injector не подключён. Игра запустится с ником и UUID из Ely.by, скин взят из Ely.by, но вход на серверы с проверкой Ely.by работать не будет.")
        }
        cmd.addAll(jvmArgs)
        cmd.add(v.getString("mainClass"))
        cmd.addAll(gameArgs)
        File(root, "launch_command.txt").writeText(cmd.joinToString("\n"))
        val mainName = v.getString("mainClass")
        val mainIdx = cmd.indexOf(mainName)
        val launch = JSONObject()
        launch.put("javaHome", rt.path)
        launch.put("main", mainName)
        launch.put("jvm", JSONArray(cmd.subList(1, mainIdx)))
        launch.put("game", JSONArray(cmd.subList(mainIdx + 1, cmd.size)))
        launch.put("glLib", mgPath ?: "")
        if (oldGl && gl4es != null) {
            launch.put("env", JSONObject().put("LIBGL_ES", "2").put("LIBGL_NOBANNER", "1").put("LIBGL_NOERROR", "1"))
        }
        File(root, "launch.json").writeText(launch.toString())
        log("Команда запуска собрана (${cmd.size} аргументов).")

        // 7. Проверка Java (запускать программы из папки приложения нельзя, проверяем наличие файлов)
        if (!File(rt, "lib/server/libjvm.so").exists() && !File(rt, "lib/aarch64/server/libjvm.so").exists()) {
            log("Java установлена неполностью (нет libjvm.so). Удалите скачанное и попробуйте снова.")
            return
        }
        log("Java работает.")

        log("Открываю окно игры...")
        runOnUiThread {
            startActivity(Intent(this, GameActivity::class.java).putExtra("mode", "game"))
        }
    }

    /**
     * Уровень мипмапов в options.txt. Число из настроек ставится при каждом запуске.
     * «Авто»: видеочип берём из game.log прошлого запуска; для Adreno 6xx и старше (медленная загрузка текстур)
     * один раз ставим 0 для этой папки игры, потом выбор игрока в самой игре не трогаем.
     */
    private fun applyMipmap(gameDir: File, oldGl: Boolean) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            try {
                val lg = File(dir, "game.log")
                if (lg.exists()) {
                    val buf = ByteArray(300000)
                    val n = java.io.FileInputStream(lg).use { it.read(buf) }
                    if (n > 0) {
                        val m = Regex("\\[Mobilix\\] GPU: (.+?) \\|").find(String(buf, 0, n))
                        if (m != null) prefs.edit().putString("gpu", m.groupValues[1].trim()).apply()
                    }
                }
            } catch (_: Throwable) {}
            val gpu = prefs.getString("gpu", "") ?: ""
            val adreno = Regex("Adreno \\(TM\\) (\\d+)").find(gpu)?.groupValues?.get(1)?.toIntOrNull()
            val slow = adreno != null && adreno < 700
            val pref = prefs.getInt("mip", -1)
            val marker = "mipAuto:" + gameDir.name
            val want = when {
                pref >= 0 -> pref
                slow && oldGl && prefs.getString(marker, "") != gpu -> 0
                else -> return
            }
            val opt = File(gameDir, "options.txt")
            val lines = if (opt.exists()) opt.readLines().filter { !it.startsWith("mipmapLevels:") } else emptyList()
            opt.writeText((lines + "mipmapLevels:$want").joinToString("\n") + "\n")
            if (pref < 0) prefs.edit().putString(marker, gpu).apply()
            log("Мипмапы: $want" + (if (pref < 0) " (авто, видеочип: $gpu)" else " (из настроек)"))
        } catch (_: Throwable) {}
    }

    // ---------- MobileGlues (OpenGL поверх OpenGL ES) ----------

    /** Берёт libmobileglues.so из плагина MobileGlues (LGPL-2.1). Возвращает путь к файлу или null. */
    private fun ensureMobileGlues(): String? {
        val dir = File(filesDir, "mg")
        fun find() = if (!dir.exists()) null else dir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "libmobileglues.so" && it.path.contains("arm64") }
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
    private fun ensureLwjgl(version: String): String? {
        if (version.isEmpty()) {
            log("Версия LWJGL не определена, пропуск.")
            return null
        }
        val dir = File(filesDir, "lwjgl")
        fun hasMain() = dir.exists() && dir.walkTopDown().any { it.isFile && it.name == "liblwjgl.so" }
        if (!hasMain()) {
            log("Установка LWJGL $version для Android...")
            val archive = downloadLwjgl(version) ?: return null
            dir.deleteRecursively()
            dir.mkdirs()
            try {
                if (archive.name.endsWith(".zip")) extractZip(archive, dir) else extractTar(archive, dir)
            } catch (e: Exception) {
                log("  ошибка распаковки: $e")
                return null
            }
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

    private fun downloadLwjgl(version: String): File? {
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

    private fun extractZip(archive: File, dest: File) {
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

    private fun findJava(rt: File): File? {
        if (!rt.exists()) return null
        return rt.walkTopDown().maxDepth(4)
            .firstOrNull { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
    }

    private fun ensureRuntime(root: File, javaMajor: Int): File {
        val rt = runtimeDir()
        findJava(rt)?.let {
            log("Java-рантайм уже установлен.")
            return it
        }
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

    private fun downloadRuntime(root: File): File {
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

    /** Большой файл с прогрессом; пишется во временный файл и переименовывается после полной загрузки. */
    private fun downloadBig(url: String, dest: File) {
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

    private fun extractTar(archive: File, dest: File) {
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
    private fun patchForgeJar(jar: File) {
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
    private fun installLegacyForge(installer: File, root: File): String {
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
    private fun runForgeInstaller(rt: File, installer: File, root: File) {
        val mainClass = try {
            java.util.jar.JarFile(installer).use { it.manifest?.mainAttributes?.getValue("Main-Class") }
        } catch (_: Exception) { null } ?: "net.minecraftforge.installer.SimpleInstaller"
        val j = JSONObject()
        j.put("javaHome", rt.path)
        j.put("main", mainClass)
        j.put("jvm", JSONArray(listOf("-cp", installer.path, "-Xmx768M", "-XX:+UseSerialGC", "-Djava.awt.headless=true",
            "-Duser.home=${root.path}", "-Djava.io.tmpdir=${cacheDir.path}", "-Duser.dir=${root.path}")))
        j.put("game", JSONArray(listOf("--installClient", root.path)))
        j.put("glLib", "")
        File(root, "installer.json").writeText(j.toString())
        val done = File(getExternalFilesDir(null) ?: filesDir, "installer.done").apply { delete() }
        val ilog = File(getExternalFilesDir(null) ?: filesDir, "installer.log").apply { delete() }
        runOnUiThread { startActivity(Intent(this, GameActivity::class.java).putExtra("mode", "installer")) }
        var shown = 0L
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 30 * 60 * 1000L) {
            Thread.sleep(1500)
            try {
                if (ilog.exists() && ilog.length() > shown) {
                    java.io.RandomAccessFile(ilog, "r").use { raf ->
                        raf.seek(shown)
                        val buf = ByteArray((raf.length() - shown).toInt()); raf.readFully(buf)
                        shown = raf.length()
                        String(buf).lines().filter { it.isNotBlank() }.forEach { log(it) }
                    }
                }
            } catch (_: Exception) {}
            if (done.exists()) break
            // процесс :game мог завершиться вместе с установщиком (System.exit)
            if (System.currentTimeMillis() - t0 > 20000) {
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                val alive = am.runningAppProcesses?.any { it.processName == "$packageName:game" } ?: true
                if (!alive) break
            }
        }
    }

    // ---------- вспомогательное ----------

    /** Правила Mojang. Для Android считаем систему linux, фичи (demo и т.п.) отключены. */
    private fun allowed(o: JSONObject): Boolean {
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

    private fun collectArgs(arr: JSONArray?, sub: (String) -> String): List<String> {
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

    private fun offlineUuid(name: String): String {
        val b = MessageDigest.getInstance("MD5").digest("OfflinePlayer:$name".toByteArray())
        b[6] = ((b[6].toInt() and 0x0f) or 0x30).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val bb = ByteBuffer.wrap(b)
        return UUID(bb.long, bb.long).toString()
    }

    internal fun fetchText(url: String): String {
        var err: Exception? = null
        for (u in listOf(url) + mirrorsFor(url)) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "Mobilix/1.4")
                return c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                err = e
            }
        }
        throw err ?: RuntimeException("нет адреса")
    }

    private fun sha1Of(f: File): String {
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

    @Volatile
    private var lastDownloadError = ""

    /** Зеркало BMCLAPI: подстановка для хостов Mojang. Файлы всё равно проверяются по sha1. */
    private fun mirrorsFor(url: String): List<String> {
        val m = "https://bmclapi2.bangbang93.com"
        return when {
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
            else -> emptyList()
        }
    }

    internal fun download(url: String, dest: File, sha1: String?): Boolean {
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
    private fun downloadAll(label: String, tasks: List<Task>, fatal: Boolean = true) {
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
}
