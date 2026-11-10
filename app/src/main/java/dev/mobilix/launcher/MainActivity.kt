package dev.mobilix.launcher

import android.app.Activity
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
import android.widget.EditText
import android.widget.ImageView
import android.graphics.drawable.GradientDrawable
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
    private val menuEntries = ArrayList<Triple<String, Pair<List<String>, Map<Char, Int>>, () -> Unit>>()
    private var ghTab: View? = null
    /** Цвета гаммы для превью: фон, панель, кнопка, акцент, текст, область скина. */
    private fun stylePalette(light: Boolean, classic: Boolean): IntArray {
        val m = Th.builtin(light, classic)
        return paletteOf(m).copyOf(6)   // у встроенных тем кнопка «Играть» прежняя зелёная
    }
    /** Иконка меню: у стандартной палитры цвета зависят от темы и от нажатия на кнопку. */
    private fun menuIcon(ico: Pair<List<String>, Map<Char, Int>>, host: View): PixelIcon {
        val icon = PixelIcon(this, ico.first, ico.second)
        if (ico.second === PixelIcon.MENU_PAL) {
            icon.pal = PixelIcon.menuPal(false); icon.palDown = PixelIcon.menuPal(true)
            host.setOnTouchListener { _, e ->
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> icon.down = true
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> icon.down = false
                }
                false
            }
        }
        return icon
    }
    private fun paletteOf(m: Map<String, Int>) = intArrayOf(m["bg"]!!, m["bar"]!!, m["chip"]!!, m["accent"]!!, m["text"]!!, m["skinBg"]!!, m["accentDk"]!!)

    // ---------- свои темы (до 4): 6 основных цветов, остальное считается автоматически ----------
    private class CTheme(var name: String, val base: LinkedHashMap<String, Int>, val ov: HashMap<String, Int> = HashMap()) {
        fun colors() = ThemeMath.colors(base, ov)
        fun light() = ThemeMath.isLight(base)
    }
    private val maxCustom = 4
    private fun loadCustoms(): MutableList<CTheme> {
        val out = ArrayList<CTheme>()
        try {
            val a = JSONArray(prefs.getString("customThemes", "[]"))
            for (i in 0 until minOf(a.length(), maxCustom)) {
                val o = a.getJSONObject(i)
                val src = o.optJSONObject("base") ?: o.optJSONObject("c") ?: continue   // "c" - формат первой версии редактора
                val fb = Th.builtin(false, false)
                val base = LinkedHashMap<String, Int>()
                for (k in ThemeMath.BASE) base[k] = if (src.has(k)) src.getLong(k).toInt() else fb[k]!!
                val ov = HashMap<String, Int>(); o.optJSONObject("ov")?.let { j -> for (k in j.keys()) ov[k] = j.getLong(k).toInt() }
                out.add(CTheme(o.optString("name", "Тема ${i + 1}"), base, ov))
            }
        } catch (_: Exception) {}
        return out
    }
    private fun saveCustoms(l: List<CTheme>) {
        val a = JSONArray()
        for (t in l) {
            val b = JSONObject(); for ((k, v) in t.base) b.put(k, v.toLong() and 0xFFFFFFFFL)
            val o = JSONObject(); for ((k, v) in t.ov) o.put(k, v.toLong() and 0xFFFFFFFFL)
            a.put(JSONObject().put("name", t.name).put("base", b).put("ov", o))
        }
        prefs.edit().putString("customThemes", a.toString()).apply()
    }
    private fun applySavedCustom() {
        Th.custom = null
        val i = prefs.getInt("customSel", -1)
        if (i >= 0) loadCustoms().getOrNull(i)?.let { Th.custom = it.colors(); Th.light = it.light(); Th.classic = false }
    }
    private fun colorName(k: String) = when (k) {
        "bg" -> tr("Фон", "Background"); "bar" -> tr("Панели", "Bars"); "text" -> tr("Текст", "Text")
        "chip" -> tr("Кнопки", "Buttons"); "line" -> tr("Обводки", "Outlines"); "accent" -> tr("Акцент (выбрано)", "Accent (selected)")
        "drawer" -> tr("Меню", "Menu"); "skinBg" -> tr("Область скина", "Skin area")
        "text2" -> tr("Текст 2", "Text 2"); "dim" -> tr("Приглушённый", "Dim"); "hint" -> tr("Подсказки", "Hints"); "chipText" -> tr("Текст кнопок", "Button text")
        "chip2" -> tr("Кнопка 2", "Button 2"); "line2" -> tr("Обводка 2", "Outline 2")
        "accentDk" -> tr("Выбранная кнопка", "Selected button"); "altBg" -> tr("Выделение", "Highlight"); else -> tr("Текст выдел.", "Highlight text")
    }
    /** Выбор цвета: цветовой круг, ползунок яркости и поле HEX. */
    private fun pickColor(title: String, initial: Int, onPick: (Int) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        val wheel = ColorWheel(this); Color.colorToHSV(initial, wheel.hsv)
        val prev = View(this)
        val hex = EditText(this).apply {
            setSingleLine(); setTextColor(Th.text); filters = arrayOf(android.text.InputFilter.LengthFilter(7))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val bright = SeekBar(this).apply { max = 100; progress = (wheel.hsv[2] * 100).toInt() }
        var busy = false
        fun cur() = Color.HSVToColor(wheel.hsv)
        fun refresh(setHex: Boolean) {
            prev.setBackgroundColor(cur()); wheel.invalidate()
            if (setHex) { busy = true; hex.setText(String.format("#%06X", cur() and 0xFFFFFF)); busy = false }
        }
        wheel.onChange = { refresh(true) }
        bright.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, v: Int, user: Boolean) { if (user) { wheel.hsv[2] = v / 100f; refresh(true) } }
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) {}
        })
        hex.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                if (busy) return
                val t = e.toString().trim().removePrefix("#")
                if (t.length == 6 && t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    Color.colorToHSV(0xFF000000.toInt() or t.toInt(16), wheel.hsv); bright.progress = (wheel.hsv[2] * 100).toInt(); refresh(false)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        box.addView(wheel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)))
        box.addView(TextView(this).apply { text = tr("Яркость", "Brightness"); textSize = 12f; setTextColor(Th.dim); setPadding(dp(8), dp(4), 0, 0) })
        box.addView(bright)
        val low = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        low.addView(prev, LinearLayout.LayoutParams(dp(56), dp(40)).apply { marginEnd = dp(12) })
        low.addView(hex, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(low, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        refresh(true)
        MobDialog(this).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> onPick(cur()) }
            .setNegativeButton(tr("Отмена", "Cancel"), null).show()
    }
    /** Редактор темы на весь экран (со своей прокруткой). idx = -1 создаёт новую из текущих цветов. */
    private fun editCustomTheme(idx: Int) {
        val list = loadCustoms()
        val t = if (idx >= 0 && idx < list.size) list[idx]
        else CTheme(tr("Тема ${list.size + 1}", "Theme ${list.size + 1}"), LinkedHashMap<String, Int>().also { m -> for (k in ThemeMath.BASE) m[k] = Th.get(k) })
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Th.bg); fitsSystemWindows = true }
        // верх: имя и предпросмотр остаются на месте
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundColor(Th.bar) }
        val name = EditText(this).apply { setText(t.name); setSingleLine(); setTextColor(Th.text); filters = arrayOf(android.text.InputFilter.LengthFilter(12)) }
        head.addView(name)
        val card = StyleCard(this, paletteOf(t.colors()), false)
        head.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)).apply { topMargin = dp(6) })
        root.addView(head)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        body.addView(note(tr("Нажмите на большой цвет, чтобы выбрать основной: оттенки под ним пересчитаются сами. Нажмите на оттенок, чтобы поменять его вручную. Светлая или тёмная тема определяется по фону.", "Tap the big colour to pick the main one: shades below recalculate. Tap a shade to override it. Light or dark is detected from the background.")))
        val refreshers = ArrayList<() -> Unit>()
        fun swatchBg(c: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(c); setStroke(dp(1), 0x88888888.toInt()); cornerRadius = dp(6).toFloat() }
        for (bk in ThemeMath.BASE) {
            val grp = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = sel(Th.chip2) }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true }
            val big = View(this)
            val hexT = TextView(this).apply { textSize = 12f; setTextColor(Th.dim) }
            top.addView(big, LinearLayout.LayoutParams(dp(56), dp(40)).apply { marginEnd = dp(12) })
            top.addView(TextView(this).apply { text = colorName(bk); textSize = 16f; setTextColor(Th.text) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(hexT)
            grp.addView(top)
            val shadeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val shades = ThemeMath.SHADES[bk].orEmpty()
            val sViews = HashMap<String, View>()
            for (sk in shades) {
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isClickable = true }
                val sv = View(this); sViews[sk] = sv
                cell.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
                cell.addView(TextView(this).apply { text = colorName(sk); textSize = 10f; setTextColor(Th.dim); gravity = Gravity.CENTER; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
                cell.setOnClickListener { pickColor(colorName(sk), t.colors()[sk]!!) { c -> t.ov[sk] = c; refreshers.forEach { it() } } }
                cell.setOnLongClickListener { t.ov.remove(sk); refreshers.forEach { it() }; Toast.makeText(this, tr("Оттенок снова считается автоматически", "Shade is automatic again"), Toast.LENGTH_SHORT).show(); true }
                shadeRow.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            }
            if (shades.isNotEmpty()) grp.addView(shadeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            if (bk == "chip") grp.addView(note(tr("Кнопка в трёх состояниях: обычная, «Кнопка 2» и выбранная (цвет акцента).", "Buttons have three states: normal, “Button 2” and selected (accent colour).")))
            refreshers.add {
                val all = t.colors()
                big.background = swatchBg(t.base[bk]!!); hexT.text = String.format("#%06X", t.base[bk]!! and 0xFFFFFF)
                for ((sk, v) in sViews) v.background = swatchBg(all[sk]!!)
                card.c = paletteOf(all); card.invalidate()
            }
            top.setOnClickListener { pickColor(colorName(bk), t.base[bk]!!) { c -> t.base[bk] = c; for (sk in shades) t.ov.remove(sk); refreshers.forEach { it() } } }
            body.addView(grp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        refreshers.forEach { it() }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // низ: кнопки всегда на виду
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Th.bar) }
        fun btn(text: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            this.text = text; textSize = 14f; gravity = Gravity.CENTER; isClickable = true
            setTextColor(if (primary) Color.WHITE else Th.text2); setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(if (primary) Th.act else Th.chip); setOnClickListener { onClick() }
        }
        if (idx >= 0) bar.addView(btn(tr("Удалить", "Delete"), false) {
            val l = loadCustoms(); if (idx < l.size) l.removeAt(idx)
            saveCustoms(l)
            val sl = prefs.getInt("customSel", -1)
            prefs.edit().putInt("customSel", if (sl == idx) -1 else if (sl > idx) sl - 1 else sl).apply()
            dlg.dismiss(); recreate()
        })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(btn(tr("Отмена", "Cancel"), false) { dlg.dismiss() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        bar.addView(btn(tr("Сохранить", "Save"), true) {
            t.name = name.text.toString().trim().ifEmpty { t.name }
            val l = loadCustoms()
            val at = if (idx >= 0 && idx < l.size) { l[idx] = t; idx } else { l.add(t); l.size - 1 }
            saveCustoms(l)
            prefs.edit().putInt("customSel", at).apply()
            dlg.dismiss(); recreate()
        })
        root.addView(bar)
        dlg.setContentView(root)
        dlg.show()
    }
    /** Праздничная неделя до и после: Хеллоуин 24 окт – 7 ноя, Новый год 25 дек – 8 янв, день рождения Minecraft 10–24 мая, 1 апреля 25 мар – 8 апр. */
    internal fun season(): String? {
        val c = java.util.Calendar.getInstance(); val m = c.get(java.util.Calendar.MONTH); val d = c.get(java.util.Calendar.DAY_OF_MONTH)
        return when {
            (m == java.util.Calendar.OCTOBER && d >= 24) || (m == java.util.Calendar.NOVEMBER && d <= 7) -> "halloween"
            (m == java.util.Calendar.DECEMBER && d >= 25) || (m == java.util.Calendar.JANUARY && d <= 8) -> "newyear"
            m == java.util.Calendar.MAY && d in 10..24 -> "mcbday"
            (m == java.util.Calendar.MARCH && d >= 25) || (m == java.util.Calendar.APRIL && d <= 8) -> "aprilfools"
            else -> null
        }
    }
    private fun seasonIcon(sn: String): PixelIcon = when (sn) {
        "newyear" -> PixelIcon(this, PixelIcon.SNOWFLAKE, mapOf('w' to 0xFF8FD0FF.toInt()))
        "mcbday" -> PixelIcon(this, PixelIcon.PARTYHAT, PixelIcon.HAT_PAL)
        "aprilfools" -> PixelIcon(this, PixelIcon.CHICKEN, PixelIcon.CHICKEN_PAL)
        else -> PixelIcon(this, PixelIcon.PUMPKIN, PixelIcon.PUMPKIN_PAL)
    }
    /** Фон кнопки: прозрачный в покое (видны эффекты), темнее при нажатии. */
    private fun selT(color: Int): android.graphics.drawable.Drawable {
        fun dk(f: Float): Int = Color.rgb((Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt())
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(dk(0.55f)))
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
    }
    internal fun navBottom() = prefs.getString("nav", "side") == "bottom"
    /** Компактный интерфейс: Версии, Каталог и Сборки объединены в «Загрузки»; раскладка, язык и удаление скачанного ушли в настройки. */
    internal fun compact() = prefs.getBoolean("compact", false)
    private var ghIdx = -1
    private var settingsTab = 0
    private var updateVer: String? = null

    private fun socialPage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        fun asset(name: String, smooth: Boolean): android.graphics.drawable.Drawable? = try {
            assets.open("social/$name").use { android.graphics.BitmapFactory.decodeStream(it) }?.let {
                android.graphics.drawable.BitmapDrawable(resources, it).apply { setFilterBitmap(smooth) }
            }
        } catch (_: Exception) { null }
        fun link(icon: String?, title: String, sub: String, url: String, hi: Boolean = false) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(14), 0)
                background = sel(if (hi) Th.altBg else Th.chip); isClickable = true
                setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {} }
            }
            val ic: View = if (icon != null) ImageView(this).apply { setImageDrawable(asset(icon, icon == "site.png")); scaleType = ImageView.ScaleType.FIT_CENTER }
                else PixelIcon(this, PixelIcon.SHIELD, PixelIcon.SHIELD_PAL)
            row.addView(ic, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(16) })
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(if (hi) Th.altText else Th.chipText) })
            // адрес и пояснения: мелко и светло-зелёным, это второстепенное
            txt.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(if (hi) Th.altText else 0xFF8FD6A3.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { bottomMargin = dp(12) })
        }
        val upd = if (updateVer != null) tr("● Вышло обновление $updateVer", "● Update $updateVer available") else null
        link("site.png", tr("Сайт", "Website"), upd ?: "kumolix.github.io/Mobilix-Launcher-for-Minecraft",
            "https://kumolix.github.io/Mobilix-Launcher-for-Minecraft/", upd != null)
        link("github.png", "GitHub", upd ?: "Kumolix/Mobilix-Launcher-for-Minecraft",
            "https://github.com/Kumolix/Mobilix-Launcher-for-Minecraft", upd != null)
        link("telegram.png", "Telegram", upd ?: "@mobilix_launcher", "https://t.me/mobilix_launcher", upd != null)
        link("tiktok.png", "TikTok", "@mobilix_launcher", "https://www.tiktok.com/@mobilix_launcher")
        link(null, tr("Политика конфиденциальности", "Privacy Policy"), tr("Данные не собираются", "No data is collected"), "https://github.com/Kumolix/Mobilix-Launcher-for-Minecraft/blob/main/PRIVACY.md")
        return col
    }

    private fun openGithub() {
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Kumolix/Mobilix-Launcher-for-Minecraft"))) } catch (_: Exception) {}
    }

    private fun verParts(v: String): List<Int>? = try { v.trim().split(".").map { it.toInt() } } catch (_: Exception) { null }

    /** Сверяет номер версии в репозитории с установленной; при новой показывает метку на главном экране и подсвечивает GitHub в меню. */
    private fun checkUpdate() {
        try {
            // обновление считается только по опубликованному релизу (тег APK-<версия>): тестовые сборки из Actions не в счёт
            val rel = JSONObject(fetchText("https://api.github.com/repos/Kumolix/Mobilix-Launcher-for-Minecraft/releases/latest"))
            if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) return
            val remote = rel.optString("tag_name").removePrefix("APK-").trim()
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
                ghItem.setTextColor(Th.altText); ghItem.background = sel(Th.altBg); ghTab?.background = sel(Th.altBg)
                if (prefs.getString("updToast", "") != remote) {
                    prefs.edit().putString("updToast", remote).apply()
                    Toast.makeText(this, tr("Вышло обновление Mobilix $remote", "Mobilix update $remote is available"), Toast.LENGTH_LONG).show()
                }
            }
        } catch (_: Throwable) {}
    }

    private val prefs get() = getSharedPreferences("launcher", MODE_PRIVATE)
    private var setupHint: View? = null
    internal fun lang() = prefs.getString("lang", "ru") ?: "ru"
    private var zhMap: Map<String, String>? = null
    /** Китайский словарь: файл assets/lang/zh.tsv, строки «русский текст<TAB>перевод»; чего нет в словаре, показывается по-английски. */
    private fun zhDict(): Map<String, String> {
        zhMap?.let { return it }
        val m = HashMap<String, String>()
        try {
            assets.open("lang/zh.tsv").bufferedReader().useLines { ls ->
                for (l in ls) { val i = l.indexOf('\t'); if (i > 0) m[l.substring(0, i).replace("\\n", "\n")] = l.substring(i + 1).replace("\\n", "\n") }
            }
        } catch (_: Exception) {}
        zhMap = m
        return m
    }
    internal fun tr(ru: String, en: String) = when (lang()) { "en" -> en; "zh" -> zhDict()[ru] ?: en; else -> ru }
    internal fun elyOn() = !prefs.getString("ely_token", "").isNullOrEmpty()
    /** Включить, когда Mojang одобрит appID: до этого вход через Microsoft всё равно получает 403. */
    private val MS_APPROVED = false

    internal fun msOn() = !prefs.getString("ms_token", "").isNullOrEmpty()
    internal fun nickName() = if (msOn()) (prefs.getString("ms_name", NICK) ?: NICK) else if (elyOn()) (prefs.getString("ely_name", NICK) ?: NICK) else (prefs.getString("nick", NICK) ?: NICK)
    private fun authUuid(): String = if (msOn()) prefs.getString("ms_uuid", "")!! else if (elyOn()) prefs.getString("ely_uuid", "")!! else skinUuid()
    private fun authToken(): String = if (msOn()) prefs.getString("ms_token", "0")!! else if (elyOn()) prefs.getString("ely_token", "0")!! else "0"

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
        this.text = text; textSize = sizeSp; setTextColor(Th.chipText); gravity = Gravity.CENTER
        background = sel(color); isClickable = true; minHeight = dp(h)
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7) } catch (_: Exception) {}
        }
        Th.light = prefs.getString("theme", "dark") == "light"
        Th.classic = prefs.getString("style", "new") == "old"
        applySavedCustom()
        Fx.kind = season(); Fx.on = prefs.getBoolean("fxOn", true)
        drawerW = resources.displayMetrics.widthPixels - dp(64)

        rootFrame = FrameLayout(this).apply { setBackgroundColor(Th.bg); fitsSystemWindows = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootFrame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // верхняя полоса: название по центру, лог справа (кнопка-меню рисуется поверх всего)
        val top = FxFrame(this).apply { setBackgroundColor(Th.bar) }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        titleRow.addView(TextView(this).apply { text = "Mobilix"; textSize = 22f; setTextColor(Th.accent); gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.12f })
        season()?.let { sn ->
            titleRow.addView(seasonIcon(sn), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(6) })
        }
        titleBox.addView(titleRow)
        verLabel = TextView(this).apply {
            text = versionLabel(); textSize = 11f; setTextColor(Th.dim); gravity = Gravity.CENTER
            setOnClickListener { if (updateVer != null) openGithub() }
        }
        titleBox.addView(verLabel)
        top.addView(titleBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56), Gravity.CENTER))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        // область 3D-скина
        val skinBox = FxFrame(this).apply { setBackgroundColor(Th.skinBg) }
        skinView = SkinView(this)
        skinBox.addView(skinView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        nickText = TextView(this).apply {
            text = nickName(); textSize = 16f; setTextColor(Th.text2); gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        skinBox.addView(nickText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // до первого запуска: заметная подсказка про быстрые настройки графики
        setupHint = TextView(this).apply {
            text = tr("⚙  Перед первым запуском рекомендуем открыть Настройки и выбрать быстрые настройки графики под ваш телефон: так игра пойдёт заметно плавнее.\nНажмите сюда, чтобы открыть.",
                "⚙  Before the first launch we recommend opening Settings and picking a quick graphics preset for your phone: the game will run much smoother.\nTap here to open.")
            textSize = 14f; setTextColor(Th.accent); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Th.drawer); setStroke(dp(2), Th.accent); cornerRadius = dp(12).toFloat() }
            visibility = if (prefs.getBoolean("playedOnce", false)) View.GONE else View.VISIBLE
            setOnClickListener { openPage(tr("Настройки", "Settings"), settingsPage()) }
        }
        skinBox.addView(setupHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            .apply { topMargin = dp(14); marginStart = dp(16); marginEnd = dp(16) })
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
        versionText = bar(verTitle(), Th.chip, 17f, 56) {}
        vrow.addView(versionText, LinearLayout.LayoutParams(0, dp(56), 1f))
        val arrow = bar("▲", Th.chip2, 18f, 56) {}
        arrow.setOnClickListener { showVersions() }
        versionText.setOnClickListener { showVersions() }
        vrow.addView(arrow, LinearLayout.LayoutParams(dp(64), dp(56)))
        root.addView(vrow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        playBtn = Button(this).apply {
            text = tr("Играть", "Play"); textSize = 22f; setTextColor(Color.WHITE)
            background = sel(Th.act); isAllCaps = false
            setOnClickListener { start() }
        }
        root.addView(playBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)))
        if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) {
            playBtn.isEnabled = false
            MobDialog(this)
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
        drawer = FxLinear(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Th.drawer)
            translationX = -drawerW.toFloat(); isClickable = true
        }
        drawer.addView(TextView(this).apply {
            text = "Mobilix"; textSize = 26f; setTextColor(Th.accent); gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.12f; setPadding(dp(72), 0, 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        fun item(ru: String, en: String, ico: Pair<List<String>, Map<Char, Int>>, badge: String? = null, onClick: () -> Unit): TextView {
            menuEntries.add(Triple(tr(ru, en), ico, onClick))
            val t = TextView(this).apply {
                text = tr(ru, en); textSize = 18f; setTextColor(Th.text); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(72), 0, dp(16), 0); isClickable = true; background = selT(Th.drawer)
                setOnClickListener { onClick() }
            }
            drawer.addView(View(this).apply { setBackgroundColor(Th.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            val icon = menuIcon(ico, t)
            val fl = FrameLayout(this)
            fl.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fl.addView(icon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(28) })
            if (badge == null) drawer.addView(fl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
            else {
                fl.addView(TextView(this).apply {
                    text = badge; textSize = 13f; setTextColor(0xCC5BD67B.toInt()); typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = GradientDrawable().apply { setColor((Th.act and 0xFFFFFF) or 0x33000000); setStroke(dp(1), (Th.act and 0xFFFFFF) or 0x66000000); cornerRadius = dp(6).toFloat() }
                    isClickable = false
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(20) })
                drawer.addView(fl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
            }
            return t
        }
        item("Аккаунт", "Account", PixelIcon.I_USER to PixelIcon.MENU_PAL) { openPage(tr("Аккаунт", "Account"), accountPage()) }
        item("Настройки", "Settings", PixelIcon.I_GEAR to PixelIcon.MENU_PAL) { openPage(tr("Настройки", "Settings"), settingsPage()) }
        if (compact()) {
            item("Загрузки", "Downloads", PixelIcon.I_VERS to PixelIcon.MENU_PAL) { openPage(tr("Загрузки", "Downloads"), downloadsPage(0)) }
        } else {
            item("Загрузчик версий", "Version loader", PixelIcon.I_VERS to PixelIcon.MENU_PAL) { openPage(tr("Загрузчик версий", "Version loader"), versionsPage()) }
            item("Каталог", "Catalog", PixelIcon.I_CAT to PixelIcon.MENU_PAL) { openPage(tr("Каталог", "Catalog"), catalogPage()) }
            item("Раскладка управления", "Controls layout", PixelIcon.I_PAD to PixelIcon.MENU_PAL) { startActivity(Intent(this, ControlsEditorActivity::class.java)) }
            menuWipe = item("Удалить скачанное", "Delete downloads", PixelIcon.TRASH to PixelIcon.TRASH_PAL) { confirmWipe() }
        }
        ghIdx = menuEntries.size
        ghItem = item("Соцсети", "Social", PixelIcon.I_CHAT to PixelIcon.MENU_PAL) { openPage(tr("Соцсети", "Social"), socialPage()) }
        if (!compact()) item("Язык", "Language", PixelIcon.I_GLOBE to PixelIcon.MENU_PAL, if (lang() == "zh") "中文" else lang().uppercase()) { chooseLanguage() }
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
        var navBoxRef: View? = null
        if (navBottom()) {
            // нижняя панель только с иконками (как в мессенджерах); боковое меню не используется
            hamb.visibility = View.GONE; drawer.visibility = View.GONE; scrim.visibility = View.GONE
            val nav = FxLinear(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Th.bar) }
            menuEntries.forEachIndexed { i, e ->
                val cell = FrameLayout(this).apply { isClickable = true; background = selT(Th.bar); setOnClickListener { e.third() } }
                cell.contentDescription = e.first
                cell.addView(menuIcon(e.second, cell), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                if (i == ghIdx) ghTab = cell
                nav.addView(cell, LinearLayout.LayoutParams(0, dp(56), 1f))
            }
            val navBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            navBox.addView(View(this).apply { setBackgroundColor(Th.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            navBox.addView(nav)
            root.addView(navBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(57)))
            navBoxRef = navBox
        }
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        lastOrientation = resources.configuration.orientation

        val logBtn = TextView(this).apply {
            text = tr("Лог", "Log"); textSize = 12f; setTextColor(Th.accent); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Th.drawer); setStroke(dp(2), Th.act); cornerRadius = dp(8).toFloat() }
            setOnClickListener { logPanel.visibility = if (logPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        rootFrame.addView(logBtn, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.TOP).apply { topMargin = dp(8); marginEnd = dp(10) })

        pageLayer = FrameLayout(this).apply { visibility = View.GONE }
        rootFrame.addView(pageLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { if (navBottom() && !land) bottomMargin = dp(57) })

        season()?.let { sn ->
            // временная кнопка эффектов у скина: есть только в праздничную неделю
            val btn = FrameLayout(this).apply {
                isClickable = true; alpha = if (Fx.on) 1f else 0.4f
                background = GradientDrawable().apply { setColor(Th.drawer); setStroke(dp(2), Th.accent); cornerRadius = dp(8).toFloat() }
                setOnClickListener {
                    Fx.on = !Fx.on
                    prefs.edit().putBoolean("fxOn", Fx.on).apply()
                    alpha = if (Fx.on) 1f else 0.4f
                    Fx.refresh()
                }
            }
            btn.addView(seasonIcon(sn), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            skinBox.addView(btn, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.BOTTOM).apply { marginEnd = dp(10); bottomMargin = dp(10) })
        }

        // горизонтальный вид: слева скин, справа меню (нижнее) и кнопки; при боковом меню оно стоит слева, а скин и кнопки справа
        if (land) {
            fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
            val MP = ViewGroup.LayoutParams.MATCH_PARENT; val WC = ViewGroup.LayoutParams.WRAP_CONTENT
            root.removeView(skinBox); root.removeView(logPanel); root.removeView(progress); root.removeView(vrow); root.removeView(playBtn)
            navBoxRef?.let { root.removeView(it) }
            val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            if (navBoxRef != null) {
                main.addView(skinBox, lp(0, MP, 1f))
                right.addView(navBoxRef, lp(MP, dp(57)))
                val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0) }
                mid.addView(vrow, lp(MP, dp(52)))
                mid.addView(playBtn, lp(MP, dp(68)).apply { topMargin = dp(10) })
                right.addView(mid, lp(MP, 0, 1f))
                right.addView(progress, lp(MP, dp(6)))
                right.addView(logPanel, lp(MP, WC))
                main.addView(right, lp(0, MP, 1f))
            } else {
                rootFrame.removeView(drawer); rootFrame.removeView(scrim)
                hamb.visibility = View.GONE
                drawer.translationX = 0f
                main.addView(ScrollView(this).apply { addView(drawer, ViewGroup.LayoutParams(MP, WC)) }, lp(dp(250), MP))
                right.addView(skinBox, lp(MP, 0, 1f))
                right.addView(logPanel, lp(MP, WC))
                right.addView(progress, lp(MP, dp(6)))
                right.addView(vrow, lp(MP, dp(52)))
                right.addView(playBtn, lp(MP, dp(64)))
                main.addView(right, lp(0, MP, 1f))
            }
            root.addView(main, lp(MP, 0, 1f))
        }

        setContentView(rootFrame)
        Thread { loadSkin() }.start()
        Thread { ensureGpu(); writeGameSettings() }.start()
        Thread { checkUpdate() }.start()
    }

    private var lastOrientation = 0
    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        if (lastOrientation != 0 && c.orientation != lastOrientation) { lastOrientation = c.orientation; recreate() }
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

    private fun openPage(title: String, content: View, animate: Boolean = true) {
        pageLayer.removeAllViews()
        val pageWrap = FxFrame(this).apply { setBackgroundColor(Th.bg); isClickable = true }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
        pageWrap.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pageLayer.addView(pageWrap, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pageLayer.visibility = View.VISIBLE
        pageOpen = true
        val h = rootFrame.height.toFloat().coerceAtLeast(resources.displayMetrics.heightPixels.toFloat())
        if (animate) {
            pageWrap.translationY = h
            pageWrap.animate().translationY(0f).setDuration(320).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    private fun closePage() {
        if (!pageOpen) return
        pageOpen = false
        catBuild = null
        accountSkin = null
        val page = pageLayer.getChildAt(0) ?: return
        page.animate().translationY(pageLayer.height.toFloat()).setDuration(260)
            .withEndAction { pageLayer.removeAllViews(); pageLayer.visibility = View.GONE }.start()
    }

    override fun onResume() {
        super.onResume()
        handleAuthIntent(intent)
        if (prefs.getBoolean("worldBackup", true)) {
            WorldBackup.checkAfterGame(this, dataRoot()) { n ->
                if (n != null) runOnUiThread { Toast.makeText(this, tr("Резервная копия миров сохранена (миров: $n)", "World backup saved (worlds: $n)"), Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Список копий миров текущей папки игры: по нажатию восстанавливает миры рядом с существующими. */
    private fun restoreWorldsDialog() {
        val gd = curGameDir()
        val copies = WorldBackup.list(dataRoot(), gd)
        if (copies.isEmpty()) {
            Toast.makeText(this, tr("Копий пока нет: они появятся после закрытия игры", "No backups yet: they appear after you close the game"), Toast.LENGTH_LONG).show()
            return
        }
        val labels = copies.map { f -> WorldBackup.label(f) + " · " + WorldBackup.worldNames(f).joinToString(", ") }.toTypedArray()
        MobDialog(this).setTitle(tr("Восстановить миры", "Restore worlds"))
            .setItems(labels) { _, i ->
                Toast.makeText(this, tr("Восстановление…", "Restoring…"), Toast.LENGTH_SHORT).show()
                Thread {
                    val n = try { WorldBackup.restore(copies[i], gd) } catch (_: Throwable) { -1 }
                    runOnUiThread {
                        Toast.makeText(this, if (n >= 0) tr("Восстановлено миров: $n. Они лежат в saves с пометкой «копия»", "Restored worlds: $n. They are in saves marked “copy”")
                            else tr("Не удалось восстановить", "Could not restore"), Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .setNegativeButton(tr("Отмена", "Cancel"), null).show()
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
        val codes = listOf("ru", "en", "zh")
        val names = listOf("Русский", "English", "中文 (简体)")
        val cur = lang()
        MobDialog(this).setTitle(tr("Язык", "Language"))
            .setItems(names.mapIndexed { i, n -> if (codes[i] == cur) "$n  ✓" else n }.toTypedArray()) { _, i ->
                prefs.edit().putString("lang", codes[i]).apply()
                recreate()
            }.setNegativeButton(tr("Закрыть", "Close"), null).show()
    }

    // ---------- настройки ----------
    private fun header(t: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(22), 0, dp(8))
        addView(TextView(context).apply { text = t; textSize = 20f; setTextColor(Th.accent) })
        addView(View(context).apply { setBackgroundColor(Th.act) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(4) })
    }

    /** Кнопки-«чипы» сеткой (когда в одну строку не помещаются). */
    private fun gridChips(labels: List<String>, selected: Int, cols: Int, onPick: (Int) -> Unit): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, t -> t.setBackgroundColor(if (i == sel) Th.act else Th.chip) }
        var row: LinearLayout? = null
        labels.forEachIndexed { i, l ->
            if (i % cols == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = if (i == 0) 0 else dp(6) })
            }
            val t = TextView(this).apply {
                text = l; textSize = 14f; setTextColor(Th.chipText); gravity = Gravity.CENTER; setPadding(dp(10), dp(12), dp(10), dp(12))
                setOnClickListener { paint(i); onPick(i) }
            }
            views.add(t)
            row!!.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = if (i % cols == 0) 0 else dp(6) })
        }
        paint(selected)
        return col
    }

    private fun chipRow(labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, t -> t.setBackgroundColor(if (i == sel) Th.act else Th.chip) }
        labels.forEachIndexed { i, l ->
            val t = TextView(this).apply {
                text = l; textSize = 14f; setTextColor(Th.chipText); gravity = Gravity.CENTER; setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener { paint(i); onPick(i) }
            }
            views.add(t)
            row.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        paint(selected)
        return row
    }

    private fun note(t: String) = TextView(this).apply { text = t; textSize = 12f; setTextColor(Th.dim); setPadding(0, dp(6), 0, 0) }

    /** Рекомендованное разрешение рендера (%) по видеочипу; 100, если чип ещё неизвестен. */
    private fun autoScale(): Int = GpuTier.tier(prefs.getString("gpu", "") ?: "")?.let { GpuTier.scale(it) } ?: 100

    /** Разрешение рендера: 0 в настройках = авто. */
    private fun effectiveScale(): Int = prefs.getInt("renderScale", 0).let { if (it <= 0) autoScale() else it.coerceIn(30, 100) }

    /** Узнаёт видеочип (один раз) коротким офскрин-контекстом EGL, чтобы «Авто» работало уже с первого запуска. */
    private fun ensureGpu() {
        if (!(prefs.getString("gpu", "") ?: "").isEmpty()) return
        var dpy = android.opengl.EGL14.EGL_NO_DISPLAY
        var ctx = android.opengl.EGL14.EGL_NO_CONTEXT
        var surf = android.opengl.EGL14.EGL_NO_SURFACE
        try {
            dpy = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            if (!android.opengl.EGL14.eglInitialize(dpy, v, 0, v, 1)) return
            val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val n = IntArray(1)
            val attr = intArrayOf(android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT, android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT, android.opengl.EGL14.EGL_NONE)
            if (!android.opengl.EGL14.eglChooseConfig(dpy, attr, 0, cfgs, 0, 1, n, 0) || n[0] == 0) return
            ctx = android.opengl.EGL14.eglCreateContext(dpy, cfgs[0], android.opengl.EGL14.EGL_NO_CONTEXT, intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE), 0)
            surf = android.opengl.EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE), 0)
            if (!android.opengl.EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) return
            val r = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)
            if (!r.isNullOrBlank()) prefs.edit().putString("gpu", r.trim()).apply()
        } catch (_: Throwable) {
        } finally {
            try {
                android.opengl.EGL14.eglMakeCurrent(dpy, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT)
                if (surf != android.opengl.EGL14.EGL_NO_SURFACE) android.opengl.EGL14.eglDestroySurface(dpy, surf)
                if (ctx != android.opengl.EGL14.EGL_NO_CONTEXT) android.opengl.EGL14.eglDestroyContext(dpy, ctx)
            } catch (_: Throwable) {}
        }
    }

    /** Настройки для процесса игры (читаются в GameActivity и ControlsView). */
    internal fun writeGameSettings() {
        try {
            File(filesDir, "game_settings.json").writeText(JSONObject()
                .put("renderScale", effectiveScale())
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
        val cmp = compact()
        // в компактном режиме страница делится на вкладки: Оформление, Лаунчер, Игра, Специальное; иначе всё в одной колонке
        fun newCol() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(24)) }
        val one = newCol()
        val colA = if (cmp) newCol() else one; val colL = if (cmp) newCol() else one
        val colG = if (cmp) newCol() else one; val colS = if (cmp) newCol() else one
        var col = colA
        if (!cmp) col.addView(header(tr("Лаунчер", "Launcher")))
        col.addView(TextView(this).apply { text = tr("Оформление", "Appearance"); setTextColor(Th.text); setPadding(0, 0, 0, dp(8)) })
        val cards = listOf(
            Triple(tr("Графит · тёмная", "Graphite · dark"), false, false), Triple(tr("Графит · светлая", "Graphite · light"), true, false),
            Triple(tr("Классика · тёмная", "Classic · dark"), false, true), Triple(tr("Классика · светлая", "Classic · light"), true, true))
        for (rowI in 0..1) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0..1) {
                val (label, lt, cl) = cards[rowI * 2 + k]
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isClickable = true }
                cell.addView(StyleCard(this, stylePalette(lt, cl), Th.custom == null && Th.light == lt && Th.classic == cl), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
                cell.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(Th.text2); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)) })
                cell.setOnClickListener {
                    prefs.edit().putString("theme", if (lt) "light" else "dark").putString("style", if (cl) "old" else "new").putInt("customSel", -1).apply()
                    recreate()
                }
                line.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (k == 0) marginEnd = dp(6) else marginStart = dp(6) })
            }
            col.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
        col.addView(TextView(this).apply { text = tr("Свои темы (до $maxCustom)", "Custom themes (up to $maxCustom)"); setTextColor(Th.text); setPadding(0, dp(10), 0, dp(8)) })
        run {
            val customs = loadCustoms(); val selC = prefs.getInt("customSel", -1)
            val rowC = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until maxCustom) {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3) }
                if (i < customs.size) {
                    val ct = customs[i]
                    val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isClickable = true }
                    cell.addView(StyleCard(this, paletteOf(ct.colors()), Th.custom != null && selC == i), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)))
                    cell.addView(TextView(this).apply { text = ct.name; textSize = 10f; setTextColor(Th.text2); gravity = Gravity.CENTER; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, dp(4)) })
                    cell.setOnClickListener { prefs.edit().putInt("customSel", i).apply(); recreate() }
                    cell.setOnLongClickListener { editCustomTheme(i); true }
                    rowC.addView(cell, lp)
                } else if (i == customs.size) {
                    val plus = TextView(this).apply { text = "+"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(Th.accent); background = sel(Th.chip); isClickable = true }
                    plus.setOnClickListener { editCustomTheme(-1) }
                    rowC.addView(plus, LinearLayout.LayoutParams(0, dp(76), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
                } else rowC.addView(View(this), lp)
            }
            col.addView(rowC)
            col.addView(note(tr("Нажмите, чтобы применить; удерживайте, чтобы изменить. «+» создаёт тему из текущих цветов.", "Tap to apply, long-press to edit. “+” creates a theme from the current colours.")))
            if (Th.custom != null && selC in customs.indices) col.addView(Button(this).apply {
                text = tr("Изменить выбранную тему", "Edit selected theme"); isAllCaps = false; setTextColor(Th.chipText); background = sel(Th.chip)
                setOnClickListener { editCustomTheme(selC) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })
        }
        col.addView(TextView(this).apply { text = tr("Компактный интерфейс", "Compact interface"); setTextColor(Th.text); setPadding(0, dp(16), 0, dp(6)) })
        col.addView(chipRow(listOf(tr("Выкл", "Off"), tr("Вкл", "On")), if (compact()) 1 else 0) { i ->
            prefs.edit().putBoolean("compact", i == 1).apply()
            recreate()
        })
        col.addView(note(tr("Версии, Каталог и Сборки объединяются в один раздел «Загрузки» с вкладками. Раскладка управления, язык и удаление скачанного переезжают в Настройки.", "Versions, Catalog and Builds are merged into one “Downloads” section with tabs. Controls layout, language and delete downloads move to Settings.")))
        col.addView(TextView(this).apply { text = tr("Меню", "Menu"); setTextColor(Th.text); setPadding(0, dp(16), 0, dp(6)) })
        col.addView(chipRow(listOf(tr("Нижняя панель", "Bottom bar"), tr("Боковое", "Side")), if (navBottom()) 0 else 1) { i ->
            prefs.edit().putString("nav", if (i == 0) "bottom" else "side").apply()
            recreate()
        })
        if (cmp) colA.addView(Button(this).apply {
            text = tr("Язык: ", "Language: ") + (if (lang() == "zh") "中文" else lang().uppercase()); isAllCaps = false; setTextColor(Th.chipText); background = sel(Th.chip)
            setOnClickListener { chooseLanguage() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(16) })
        col = colL
        col.addView(TextView(this).apply { text = tr("Многопоточная загрузка", "Multi-threaded download"); setTextColor(Th.text); setPadding(0, dp(16), 0, dp(6)) })
        col.addView(chipRow(listOf(tr("Выкл", "Off"), tr("Вкл", "On")), if (prefs.getBoolean("multiDl", true)) 1 else 0) { i ->
            prefs.edit().putBoolean("multiDl", i == 1).apply()
        })
        col.addView(note(tr("Скачивает файлы игры в 16 потоков вместо 3: намного быстрее. Если интернет нестабилен или появляются ошибки загрузки, выключите.", "Downloads game files in 16 threads instead of 3: much faster. Turn off if your connection is unstable or downloads fail.")))
        col.addView(TextView(this).apply { text = tr("Автообновление модов под загрузчик", "Auto-update mods for the loader"); setTextColor(Th.text); setPadding(0, dp(16), 0, dp(6)) })
        col.addView(chipRow(listOf(tr("Выкл", "Off"), tr("Вкл", "On")), if (prefs.getBoolean("modAuto", true)) 1 else 0) { i ->
            prefs.edit().putBoolean("modAuto", i == 1).apply()
        })
        col.addView(note(tr("Перед запуском Fabric или Forge лаунчер подбирает скачанным из каталога модам файлы под вашу версию игры и загрузчик, докачивает обязательные зависимости и предупреждает, если чего-то не хватает. Моды другого загрузчика отключаются всегда (папка mods-disabled), это не зависит от переключателя.",
            "Before a Fabric or Forge launch, the launcher picks files for mods downloaded from the catalog that match your game version and loader, downloads required dependencies and warns about anything missing. Mods for another loader are always disabled (mods-disabled folder), regardless of this switch.")))
        col = colS
        col.addView(TextView(this).apply { text = tr("Резервные копии миров", "World backups"); setTextColor(Th.text); setPadding(0, dp(16), 0, dp(6)) })
        col.addView(chipRow(listOf(tr("Выкл", "Off"), tr("Вкл", "On")), if (prefs.getBoolean("worldBackup", true)) 1 else 0) { i ->
            prefs.edit().putBoolean("worldBackup", i == 1).apply()
        })
        col.addView(note(tr("После закрытия игры лаунчер сохраняет zip с изменёнными мирами. Хранятся последние ${WorldBackup.KEEP} копии для каждой версии или сборки.", "After you close the game the launcher saves a zip with the worlds that changed. The last ${WorldBackup.KEEP} backups are kept per version or build.")))
        col.addView(Button(this).apply {
            text = tr("Восстановить мир из копии", "Restore a world from backup"); isAllCaps = false; setTextColor(Th.chipText); background = sel(Th.chip)
            setOnClickListener { restoreWorldsDialog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) })
        col = colG
        col.addView(TextView(this).apply { text = tr("Управление движением", "Movement control"); setTextColor(Th.text); setPadding(0, dp(16), 0, dp(6)) })
        col.addView(chipRow(listOf(tr("Крестовина (WASD)", "D-pad (WASD)"), tr("Джойстик", "Joystick")), if (prefs.getBoolean("joystick", false)) 1 else 0) { i ->
            prefs.edit().putBoolean("joystick", i == 1).apply(); writeGameSettings()
        })
        col.addView(note(tr("Джойстик: тяните в сторону, чтобы идти; за край круга — бег.", "Joystick: drag to walk; beyond the ring to sprint.")))
        col = colS
        col.addView(Button(this).apply {
            text = tr("Открыть папку игры", "Open game folder"); isAllCaps = false; setTextColor(Th.chipText); background = sel(Th.chip)
            setOnClickListener { openGameFolder() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(14) })
        col.addView(note(tr("Откроется «Мои файлы» (Samsung) или стандартный проводник. Путь также скопирован в буфер.", "Opens My Files (Samsung) or the system file manager. The path is also copied.")))
        col.addView(Button(this).apply {
            text = tr("Импорт файлов из памяти телефона", "Import files from phone storage"); isAllCaps = false; setTextColor(Th.chipText); background = sel(Th.chip)
            setOnClickListener { importFiles() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) })
        col.addView(note(tr("Выберите тип и файлы в системном окне: моды, шейдеры, ресурспаки, датапаки или карты (zip). Файлы копируются в папку текущей версии или сборки, Shizuku не нужен.", "Pick a type and files in the system picker: mods, shaders, resource packs, datapacks or maps (zip). Files are copied into the current version or build folder; no Shizuku needed.")))
        if (compact()) {
            fun btn(label: String, red: Boolean = false, onClick: () -> Unit) = col.addView(Button(this).apply {
                text = label; isAllCaps = false; setTextColor(if (red) 0xFFE05555.toInt() else Th.chipText); background = sel(Th.chip)
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) })
            btn(tr("Раскладка управления", "Controls layout")) { startActivity(Intent(this, ControlsEditorActivity::class.java)) }
            btn(tr("Удалить скачанное", "Delete downloads"), red = true) { confirmWipe() }
        }
        col = colG
        if (!cmp) col.addView(header(tr("Игра", "Game")))
        // быстрые настройки графики: между двумя линиями
        col.addView(View(this).apply { setBackgroundColor(Th.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(6); bottomMargin = dp(10) })
        col.addView(TextView(this).apply { text = tr("Быстрые настройки графики", "Quick graphics settings"); setTextColor(Th.text); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(6)) })
        col.addView(gridChips(presets.map { tr(it.ru, it.en) }, prefs.getInt("preset", 0) - 1, 2) { i ->
            pickPreset(i)
            Toast.makeText(this, tr("Выбрано: ${presets[i].ru}. Применится при следующем запуске игры.", "Selected: ${presets[i].en}. Applied on next game launch."), Toast.LENGTH_SHORT).show()
            openPage(tr("Настройки", "Settings"), settingsPage(), animate = false)
        })
        col.addView(note(tr("Минимальные: 2 чанка, 30 FPS, 30% разрешения, всё выключено (для самых слабых). Низкие: 6 чанков, 45 FPS, 45%. Средние: 12 чанков, 60 FPS, 70%, плавное освещение. Высокие: 28 чанков, FPS без ограничения, 100%, красивая графика (для мощных, например S24 FE). Настройки записываются один раз, дальше любой параметр можно менять отдельно — выбор не перезаписывается при каждом запуске.",
            "Minimal: 2 chunks, 30 FPS, 30% resolution, everything off (weakest phones). Low: 6 chunks, 45 FPS, 45%. Medium: 12 chunks, 60 FPS, 70%, smooth lighting. High: 28 chunks, unlimited FPS, 100%, fancy graphics (powerful phones). Applied once; any single option can be changed afterwards — your choice is not rewritten on every launch.")))
        col.addView(View(this).apply { setBackgroundColor(Th.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(14); bottomMargin = dp(6) })
        val curR = prefs.getInt("renderScale", 0)
        val rl = TextView(this).apply { setTextColor(Th.text); setPadding(0, 0, 0, dp(4)) }
        fun rText(v: Int) {
            rl.text = if (v == 0) tr("Разрешение рендера: авто (${autoScale()}%)", "Render resolution: auto (${autoScale()}%)")
            else tr("Разрешение рендера: $v%", "Render resolution: $v%")
        }
        rText(curR)
        col.addView(rl)
        val rs = SeekBar(this).apply {
            max = 70; progress = if (curR == 0) 70 else (curR - 30).coerceIn(0, 70)
            visibility = if (curR == 0) View.GONE else View.VISIBLE
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { rText(p + 30) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    prefs.edit().putInt("renderScale", (sb?.progress ?: 70) + 30).apply()
                    writeGameSettings()
                }
            })
        }
        col.addView(chipRow(listOf(tr("Авто", "Auto"), tr("Вручную", "Manual")), if (curR == 0) 0 else 1) { i ->
            if (i == 0) { prefs.edit().putInt("renderScale", 0).apply(); rs.visibility = View.GONE; rText(0) }
            else { val v = rs.progress + 30; prefs.edit().putInt("renderScale", v).apply(); rs.visibility = View.VISIBLE; rText(v) }
            writeGameSettings()
        })
        col.addView(rs)
        col.addView(note(tr("Игра рисуется в меньшем разрешении и растягивается на весь экран: меньше нагрузка на GPU, выше FPS. Размытия нет, это именно снижение числа пикселей. «Авто» подбирает значение по видеочипу (мощные — 100%, слабые — ниже). Применяется при следующем запуске.",
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
        col.addView(note(tr("Мипмапы — уменьшенные копии текстур. На слабых видеочипах их загрузка сильно роняет FPS в мире. «Авто» подбирает ступень по видеочипу: мощные — 4, послабее — 3, затем 2, 1 и 0 для самых слабых (на Adreno 618 из A52 нужен 0). Подбор ставится один раз, потом ваш выбор в самой игре сохраняется. Число: ставится при каждом запуске. Применяется при следующем запуске.",
            "Mipmaps are smaller copies of textures. On weak GPUs uploading them can crush FPS in the world. Auto picks a step from the GPU: strong chips 4, weaker 3, then 2, 1 and 0 for the weakest (the Adreno 618 in the Galaxy A52 needs 0). The pick is applied once, then your in-game choice is kept. A number is applied on every launch. Applied on next launch.")))
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
        col = colS
        if (!cmp) col.addView(header(tr("Специальное", "Special")))
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
            text = tr("Сбросить адрес", "Reset address"); isAllCaps = false; setTextColor(Th.chipText); background = sel(Th.chip)
            setOnClickListener { mirror.setText(DEFAULT_MIRROR); prefs.edit().putString("mirrorApi", DEFAULT_MIRROR).apply() }
        }, LinearLayout.LayoutParams(dp(180), dp(40)).apply { topMargin = dp(6) })
        col.addView(note(tr("Если Modrinth недоступен, выберите Modrinth Black. Если зеркало не отвечает, поправьте адрес API здесь.",
            "If Modrinth is unreachable, choose Modrinth Black. If the mirror does not respond, fix the API address here.")))
        if (!cmp) { sv.addView(one); return sv }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val holder = FrameLayout(this)
        val cols = listOf(colA, colL, colG, colS)
        val cache = arrayOfNulls<View>(4)
        fun show(i: Int) {
            settingsTab = i
            val v = cache[i] ?: ScrollView(this).apply { addView(cols[i]) }.also { cache[i] = it }
            holder.removeAllViews()
            holder.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        box.addView(topTabs(listOf(tr("Оформление", "Look"), tr("Лаунчер", "Launcher"), tr("Игра", "Game"), tr("Специальное", "Special")), settingsTab) { show(it) })
        box.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        show(settingsTab)
        return box
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
    private fun typeDir(type: String) = when (type) { "resourcepack" -> "resourcepacks"; "shader" -> "shaderpacks"; "datapack" -> "datapacks"; else -> "mods" }
    /** Загрузчик и версия игры из самого файла мода (файлы, которых нет в списке скачанного). Пустая строка, если не определилось. */
    private fun modMeta(f: File): Pair<String, String> {
        try {
            java.util.zip.ZipFile(f).use { z ->
                fun text(n: String) = z.getEntry(n)?.let { z.getInputStream(it).bufferedReader().use { r -> r.readText() } }
                text("fabric.mod.json")?.let { t ->
                    val mc = try { JSONObject(t).optJSONObject("depends")?.opt("minecraft")?.toString() ?: "" } catch (_: Exception) { "" }
                    return "fabric" to cleanRange(mc)
                }
                text("quilt.mod.json")?.let { return "quilt" to "" }
                text("META-INF/neoforge.mods.toml")?.let { t -> return "neoforge" to cleanRange(Regex("modId\\s*=\\s*\"minecraft\"[\\s\\S]*?versionRange\\s*=\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: "") }
                text("META-INF/mods.toml")?.let { t -> return "forge" to cleanRange(Regex("modId\\s*=\\s*\"minecraft\"[\\s\\S]*?versionRange\\s*=\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: "") }
                text("mcmod.info")?.let { t ->
                    val v = Regex("\"mcversion\"\\s*:\\s*\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: ""
                    return "forge" to v
                }
            }
        } catch (_: Throwable) {}
        return "" to ""
    }
    private fun cleanRange(s: String) = s.trim().removePrefix("[").removeSuffix(")").removeSuffix("]").replace(",", " – ").replace("~", "").replace(">=", "").replace("^", "").trim().let { if (it.length > 18) it.take(18) else it }

    private fun packFile(type: String, name: String) = File(curGameDir(), typeDir(type) + "/" + name)

    /** Кнопки строки каталога: «Скачать» или «Загружено» + «Удалить». */
    private fun fillActions(box: LinearLayout, id: String, type: String, loader: String, title: String) {
        box.removeAllViews()
        val rec = idx().optJSONObject(id)
        if (rec != null && packFile(rec.optString("t"), rec.optString("f")).exists()) {
            box.addView(Button(this).apply {
                text = tr("Загружено", "Downloaded"); isAllCaps = false; setTextColor(Th.chipText); background = sel(Th.chip); isEnabled = false
            }, LinearLayout.LayoutParams(dp(120), dp(40)))
            box.addView(Button(this).apply {
                text = tr("Удалить", "Delete"); isAllCaps = false; setTextColor(Th.chipText); background = sel(0xFFB3261E.toInt())
                setOnClickListener {
                    packFile(rec.optString("t"), rec.optString("f")).delete()
                    idxSave(idx().apply { remove(id) })
                    fillActions(box, id, type, loader, title)
                }
            }, LinearLayout.LayoutParams(dp(100), dp(40)).apply { marginStart = dp(8) })
        } else {
            val btn = Button(this).apply {
                text = tr("Скачать", "Download"); isAllCaps = false; setTextColor(Th.chipText); background = sel(Th.act)
            }
            btn.setOnClickListener { installProject(id, type, loader, title, btn) { fillActions(box, id, type, loader, title) } }
            box.addView(btn, LinearLayout.LayoutParams(dp(150), dp(40)))
        }
    }

    /** Каталог: вверху переключатель «Каталог» (быстро скачать) / «Сборки» (менеджер). */
    private fun catalogPage(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val holder = FrameLayout(this)
        fun show(i: Int) {
            holder.removeAllViews()
            holder.addView(if (i == 0) modsCatalogPage() else buildsPage(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        box.addView(tabRows(listOf(listOf(tr("Каталог", "Catalog"), tr("Сборки", "Builds"))), 0) { show(it) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12); marginEnd = dp(12); topMargin = dp(8) })
        box.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        show(0)
        return box
    }

    /** Вкладки сверху с подчёркиванием, как в соцсетях. */
    private fun topTabs(labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Th.bar) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val texts = ArrayList<TextView>(); val lines = ArrayList<View>()
        fun paint(sel: Int) {
            texts.forEachIndexed { i, t -> t.setTextColor(if (i == sel) Th.accent else Th.dim); t.typeface = if (i == sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
            lines.forEachIndexed { i, v -> v.setBackgroundColor(if (i == sel) Th.act else 0) }
        }
        labels.forEachIndexed { i, l ->
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; background = selT(Th.bar) }
            val t = TextView(this).apply { text = l; textSize = if (labels.size > 3) 13f else 16f; gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(2), dp(14), dp(2), dp(12)) }
            val ln = View(this)
            texts.add(t); lines.add(ln)
            cell.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            cell.addView(ln, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            cell.setOnClickListener { paint(i); onPick(i) }
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(row)
        box.addView(View(this).apply { setBackgroundColor(Th.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        paint(selected)
        return box
    }

    /** Раздел «Загрузки» (компактный интерфейс): вкладки Версии, Каталог, Сборки. Вкладки создаются один раз и хранят своё состояние. */
    private fun downloadsPage(start: Int): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val holder = FrameLayout(this)
        val cache = arrayOfNulls<View>(3)
        fun show(i: Int) {
            val v = cache[i] ?: when (i) { 0 -> versionsPage(); 1 -> modsCatalogPage(); else -> buildsPage() }.also { cache[i] = it }
            holder.removeAllViews()
            holder.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        box.addView(topTabs(listOf(tr("Версии", "Versions"), tr("Каталог", "Catalog"), tr("Сборки", "Builds")), start) { show(it) })
        box.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        show(start)
        return box
    }

    /** Кнопки-вкладки на всю ширину, несколькими рядами; индекс сквозной по всем рядам. */
    private fun tabRows(rows: List<List<String>>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, t -> t.setBackgroundColor(if (i == sel) Th.act else Th.chip) }
        var n = 0
        rows.forEachIndexed { r, labels ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = if (r == 0) 0 else dp(6) })
            labels.forEachIndexed { c, l ->
                val index = n++
                val t = TextView(this).apply {
                    text = l; textSize = 14f; setTextColor(Th.chipText); gravity = Gravity.CENTER; maxLines = 1
                    setPadding(dp(6), dp(12), dp(6), dp(12))
                    setOnClickListener { paint(index); onPick(index) }
                }
                views.add(t)
                row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = if (c == 0) 0 else dp(6) })
            }
        }
        paint(selected)
        return col
    }

    /** После скачивания датапака предлагает положить его в один из миров (датапаки работают только внутри мира). */
    /** Формат датапака для версии игры (по таблице релизов); null — версия неизвестна. */
    private fun dpFormat(mc: String): Int? {
        val m = Regex("^1\\.(\\d+)(?:\\.(\\d+))?").find(mc) ?: return null
        val mi = m.groupValues[1].toInt(); val pa = m.groupValues[2].ifEmpty { "0" }.toInt()
        return when {
            mi in 13..14 -> 4
            mi == 15 || (mi == 16 && pa <= 1) -> 5
            mi == 16 -> 6
            mi == 17 -> 7
            mi == 18 -> if (pa >= 2) 9 else 8
            mi == 19 -> if (pa >= 4) 12 else 10
            mi == 20 -> when { pa <= 1 -> 15; pa == 2 -> 18; pa <= 4 -> 26; else -> 41 }
            mi == 21 -> when { pa <= 1 -> 48; pa <= 3 -> 57; pa == 4 -> 61; pa == 5 -> 71; pa == 6 -> 80; pa <= 8 -> 81; else -> null }
            else -> null
        }
    }
    /** Версия мира из level.dat (Data → Version → Name); null, если не прочиталось. */
    private fun worldVersion(world: File): String? = try {
        java.io.DataInputStream(java.io.BufferedInputStream(java.util.zip.GZIPInputStream(File(world, "level.dat").inputStream()))).use { d ->
            fun skip(n: Long) { var left = n; while (left > 0) { val k = d.skipBytes(minOf(left, Int.MAX_VALUE.toLong()).toInt()); if (k <= 0) { d.readByte(); left-- } else left -= k } }
            fun skipTag(t: Int) {
                when (t) {
                    1 -> skip(1); 2 -> skip(2); 3, 5 -> skip(4); 4, 6 -> skip(8)
                    7 -> skip(d.readInt().toLong()); 8 -> skip(d.readUnsignedShort().toLong())
                    9 -> { val et = d.readByte().toInt(); val n = d.readInt(); for (i in 0 until n) skipTag(et) }
                    10 -> while (true) { val tt = d.readByte().toInt(); if (tt == 0) break; skip(d.readUnsignedShort().toLong()); skipTag(tt) }
                    11 -> skip(d.readInt() * 4L); 12 -> skip(d.readInt() * 8L)
                }
            }
            fun find(path: List<String>): String? {
                while (true) {
                    val t = d.readByte().toInt(); if (t == 0) return null
                    val key = d.readUTF()
                    if (key == path[0] && path.size == 1 && t == 8) return d.readUTF()
                    if (key == path[0] && path.size > 1 && t == 10) { val r = find(path.drop(1)); if (r != null) return r }
                    else skipTag(t)
                }
            }
            d.readByte(); d.readUTF()
            find(listOf("Data", "Version", "Name"))
        }
    } catch (_: Throwable) { null }
    /** Форматы, на которые рассчитан датапак (pack.mcmeta внутри zip); пусто — не прочиталось. */
    private fun datapackFormats(file: File): Pair<Int, Int>? = try {
        java.util.zip.ZipFile(file).use { z ->
            val e = z.getEntry("pack.mcmeta") ?: return null
            val pack = JSONObject(z.getInputStream(e).readBytes().toString(Charsets.UTF_8)).getJSONObject("pack")
            var lo = pack.optInt("pack_format", -1); var hi = lo
            pack.optJSONObject("supported_formats")?.let { lo = it.optInt("min_inclusive", lo); hi = it.optInt("max_inclusive", hi) }
            pack.optJSONArray("supported_formats")?.let { if (it.length() >= 2) { lo = it.optInt(0, lo); hi = it.optInt(1, hi) } }
            if (lo < 0) null else lo to hi
        }
    } catch (_: Throwable) { null }

    private fun offerDatapackWorld(file: File, next: (() -> Unit)? = null) {
        val worlds = File(curGameDir(), "saves").listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
        if (worlds.isEmpty()) {
            Toast.makeText(this, tr("Датапак ${file.name} в папке datapacks. Создайте мир в игре и добавьте его вручную.", "Datapack ${file.name} is in the datapacks folder. Create a world in the game and add it manually."), Toast.LENGTH_LONG).show()
            next?.invoke(); return
        }
        val fmt = datapackFormats(file)
        // подходящие версии сверху: ✓ формат датапака совпадает с версией мира, ⚠ нет, без значка — версия неизвестна
        class W(val dir: File, val ver: String?, val ok: Boolean?)
        val ws = worlds.map { w ->
            val v = worldVersion(w); val wf = v?.let { dpFormat(it) }
            W(w, v, if (fmt != null && wf != null) wf in fmt.first..fmt.second else null)
        }.sortedWith(compareBy({ when (it.ok) { true -> 0; null -> 1; false -> 2 } }, { it.dir.name }))
        val names = ws.map { (when (it.ok) { true -> "✓ "; false -> "⚠ "; null -> "" }) + it.dir.name + (it.ver?.let { v -> " ($v)" } ?: "") } + tr("Пропустить", "Skip")
        MobDialog(this).setTitle(tr("Куда добавить датапак?", "Add the datapack to which world?"))
            .setMessage(file.name + (fmt?.let { tr("\nФормат датапака: ${it.first}" + (if (it.second != it.first) "–${it.second}" else ""), "\nDatapack format: ${it.first}" + (if (it.second != it.first) "–${it.second}" else "")) } ?: "") +
                tr("\n✓ подходит по версии, ⚠ другая версия игры", "\n✓ matches the world version, ⚠ different game version"))
            .setItems(names.toTypedArray()) { _, i ->
                if (i < ws.size) try {
                    val dir = File(ws[i].dir, "datapacks").apply { mkdirs() }
                    file.copyTo(File(dir, file.name), overwrite = true)
                    Toast.makeText(this, tr("Добавлено в мир ${ws[i].dir.name}", "Added to world ${ws[i].dir.name}"), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { Toast.makeText(this, e.message ?: e.toString(), Toast.LENGTH_LONG).show() }
                next?.invoke()
            }.setNegativeButton(tr("Закрыть", "Close")) { _, _ -> next?.invoke() }.show()
    }


    // ---------- менеджер сборок ----------
    private fun buildsPage(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), 0) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        fun iconBg() = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(0x33FFFFFF))
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        fun render() {
            list.removeAllViews()
            val a = builds()
            val act = activeBuild()?.optString("id")
            if (a.length() == 0) list.addView(TextView(this).apply {
                text = tr("Сборок пока нет. У каждой сборки своя папка: моды, миры и настройки не смешиваются с обычной игрой. Карандаш открывает каталог для сборки, а играть в ней можно, выбрав её внизу списка версий на главном экране.",
                    "No builds yet. Each build has its own folder: mods, worlds and settings stay apart from the regular game. The pencil opens the catalog for that build; to play it, pick it at the bottom of the version list on the main screen.")
                setTextColor(Th.dim); textSize = 14f; setPadding(dp(4), dp(8), dp(4), 0)
            })
            for (i in 0 until a.length()) {
                val b = a.getJSONObject(i)
                val on = b.optString("id") == act
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = sel(if (on) Th.accentDk else Th.chip)
                }
                val bi = buildIcon(b.optString("id"))
                if (bi.exists()) row.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(android.graphics.BitmapFactory.decodeFile(bi.path))
                }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) })
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (bi.exists()) 12 else 16), dp(10), dp(8), dp(10)) }
                col.addView(TextView(this).apply { text = (if (on) "● " else "") + b.optString("name"); textSize = 17f; setTextColor(Th.chipText); typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                col.addView(TextView(this).apply { text = prettyProfile(b.optString("profile")); textSize = 12f; setTextColor(if (on) 0xFFBFE8C9.toInt() else Th.dim) })
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val edit = PixelIcon(this, PixelIcon.PENCIL, PixelIcon.PENCIL_PAL).apply {
                    isClickable = true; background = iconBg(); setPadding(dp(11), dp(11), dp(11), dp(11))
                    setOnClickListener { openPage(tr("Каталог сборки: ", "Build catalog: ") + b.optString("name"), modsCatalogPage(b)) }
                }
                val del = PixelIcon(this, PixelIcon.TRASH, PixelIcon.TRASH_PAL).apply {
                    isClickable = true; background = iconBg(); setPadding(dp(11), dp(11), dp(11), dp(11))
                    setOnClickListener {
                        MobDialog(this@MainActivity).setTitle(tr("Удалить сборку?", "Delete build?"))
                            .setMessage(tr("«${b.optString("name")}»: моды, миры и настройки этой сборки будут удалены.", "“${b.optString("name")}”: its mods, worlds and settings will be deleted."))
                            .setPositiveButton(tr("Удалить", "Delete")) { _, _ ->
                                val id = b.optString("id")
                                val keep = JSONArray()
                                val all = builds()
                                for (k in 0 until all.length()) if (all.getJSONObject(k).optString("id") != id) keep.put(all.getJSONObject(k))
                                buildsSave(keep)
                                if (prefs.getString("build", "") == id) prefs.edit().putString("build", "").apply()
                                versionText.text = verTitle()
                                buildIcon(id).delete(); Thread { buildDir(id).deleteRecursively() }.start()
                                render()
                            }.setNegativeButton(tr("Отмена", "Cancel"), null).show()
                    }
                }
                val share = PixelIcon(this, PixelIcon.I_SHARE, PixelIcon.MENU_PAL).apply {
                    isClickable = true; background = iconBg(); setPadding(dp(11), dp(11), dp(11), dp(11))
                    setOnClickListener { shareBuild(b) }
                }
                row.addView(share, LinearLayout.LayoutParams(dp(48), dp(48)))
                row.addView(edit, LinearLayout.LayoutParams(dp(48), dp(48)))
                row.addView(del, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(6) })
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
        }
        val add = TextView(this).apply {
            text = tr("＋ Добавить сборку", "＋ Add build"); textSize = 16f; setTextColor(Th.chipText); gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14)); background = sel(Th.act); isClickable = true
            setOnClickListener { editBuild(null) { render() } }
        }
        box.addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        val sv = ScrollView(this); sv.addView(list)
        box.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        render()
        return box
    }

    /** Окно создания/правки сборки: название и версия игры. */
    private fun editBuild(existing: JSONObject?, done: () -> Unit) {
        val installed = ArrayList(installedProfiles())
        if (installed.isEmpty()) installed.add(VERSION)
        var profile = existing?.optString("profile")?.takeIf { installed.contains(it) }
            ?: selectedProfile().takeIf { installed.contains(it) } ?: installed[0]
        val pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name = android.widget.EditText(this).apply {
            setText(existing?.optString("name") ?: ""); hint = tr("Название сборки", "Build name")
            setTextColor(Th.text); setHintTextColor(Th.hint); setSingleLine(true); setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        pane.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // фото сборки: показывается слева от названия, хранится 128×128
        var newIcon: android.graphics.Bitmap? = null; var dropIcon = false
        val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Th.chip2) }
        existing?.optString("id")?.let { id -> buildIcon(id).takeIf { it.exists() }?.let { thumb.setImageBitmap(android.graphics.BitmapFactory.decodeFile(it.path)) } }
        val photoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        photoRow.addView(thumb, LinearLayout.LayoutParams(dp(56), dp(56)))
        photoRow.addView(TextView(this).apply {
            text = tr("Выбрать фото", "Choose photo"); textSize = 15f; setTextColor(Th.chipText); gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
            background = sel(Th.chip); isClickable = true
            setOnClickListener {
                iconPick = { bmp -> newIcon = bmp; dropIcon = false; thumb.setImageBitmap(bmp) }
                try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, 80) }
                catch (e: Throwable) { Toast.makeText(this@MainActivity, "Ошибка: $e", Toast.LENGTH_LONG).show() }
            }
        }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(10) })
        photoRow.addView(TextView(this).apply {
            text = tr("Убрать", "Remove"); textSize = 15f; setTextColor(Th.chipText); gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
            background = sel(Th.chip2); isClickable = true
            setOnClickListener { newIcon = null; dropIcon = true; thumb.setImageDrawable(null) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply { marginStart = dp(8) })
        pane.addView(photoRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val verBtn = TextView(this).apply {
            text = tr("Версия: ", "Version: ") + prettyProfile(profile); textSize = 15f; setTextColor(Th.chipText)
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = sel(Th.chip); isClickable = true
            setOnClickListener {
                MobDialog(this@MainActivity).setTitle(tr("Версия для сборки", "Build version"))
                    .setItems(installed.map { prettyProfile(it) }.toTypedArray()) { _, i ->
                        profile = installed[i]; text = tr("Версия: ", "Version: ") + prettyProfile(profile)
                    }.setNegativeButton(tr("Закрыть", "Close"), null).show()
            }
        }
        pane.addView(verBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        MobDialog(this).setTitle(if (existing == null) tr("Новая сборка", "New build") else tr("Правка сборки", "Edit build")).setView(pane)
            .setPositiveButton(tr("Сохранить", "Save")) { _, _ ->
                val n = name.text.toString().trim().ifEmpty { tr("Сборка", "Build") }
                val all = builds()
                val bid = existing?.optString("id") ?: ("b" + System.currentTimeMillis())
                try {
                    if (newIcon != null) { buildIcon(bid).parentFile?.mkdirs(); buildIcon(bid).outputStream().use { newIcon!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                    else if (dropIcon) buildIcon(bid).delete()
                } catch (_: Throwable) {}
                if (existing == null) {
                    all.put(JSONObject().put("id", bid).put("name", n).put("profile", profile))
                } else {
                    for (k in 0 until all.length()) {
                        val b = all.getJSONObject(k)
                        if (b.optString("id") == existing.optString("id")) { b.put("name", n); b.put("profile", profile) }
                    }
                }
                buildsSave(all)
                versionText.text = verTitle()
                done()
            }.setNegativeButton(tr("Отмена", "Cancel"), null).show()
    }

    private fun modsCatalogPage(build: JSONObject? = null): View {
        catBuild = build
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (build != null) {
            val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(4)) }
            hdr.addView(TextView(this).apply {
                text = build.optString("name") + " · " + prettyProfile(build.optString("profile")); textSize = 13f; setTextColor(0xFF8FD6A3.toInt())
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            hdr.addView(TextView(this).apply {
                text = tr("Изменить", "Edit"); textSize = 13f; setTextColor(Th.chipText); setPadding(dp(14), dp(8), dp(14), dp(8))
                background = sel(Th.chip); isClickable = true
                setOnClickListener {
                    editBuild(build) {
                        val a = builds()
                        for (k in 0 until a.length()) if (a.getJSONObject(k).optString("id") == build.optString("id")) {
                            val nb = a.getJSONObject(k)
                            openPage(tr("Каталог сборки: ", "Build catalog: ") + nb.optString("name"), modsCatalogPage(nb), false)
                        }
                    }
                }
            })
            box.addView(hdr)
        }
        var type = "mod"; var loader = loaderOfProfile(build?.optString("profile") ?: selectedProfile())
        var strict = prefs.getBoolean("catStrict", true)     // фильтр «Совместимость версий»
        var hideDl = prefs.getBoolean("catHideDl", false)    // фильтр «Не показывать скачанное»
        var source = if (BuildConfig.CF_KEY.isNotEmpty()) prefs.getString("catSource", "modrinth") ?: "modrinth" else "modrinth"   // modrinth | curseforge
        var searchSeq = 0                                    // номер запроса: устаревший ответ не должен перекрывать список
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
            class Ent(val title: String, val type: String, val file: File, val id: String?, val loader: String, val ver: String)
            val ents = ArrayList<Ent>()
            val keys = index.keys()
            while (keys.hasNext()) {
                val id = keys.next(); val o = index.getJSONObject(id)
                val f = packFile(o.optString("t"), o.optString("f"))
                if (f.exists()) {
                    known.add(f.path)
                    val md = if (o.optString("l").isEmpty() && o.optString("t") == "mod") modMeta(f) else ("" to "")
                    ents.add(Ent(o.optString("n", f.name), o.optString("t"), f, id, o.optString("l").ifEmpty { md.first }, o.optString("v").ifEmpty { md.second }))
                }
            }
            for (t in listOf("mod", "resourcepack", "shader", "datapack")) {
                File(curGameDir(), typeDir(t)).listFiles()?.sortedBy { it.name }?.forEach {
                    if (it.isFile && !known.contains(it.path) && !it.name.endsWith(".part")) {
                        val md = if (t == "mod") modMeta(it) else ("" to "")
                        ents.add(Ent(it.name, t, it, null, md.first, md.second))
                    }
                }
            }
            fun loaderName(l: String) = when (l) { "fabric" -> "Fabric"; "forge" -> "Forge"; "neoforge" -> "NeoForge"; "quilt" -> "Quilt"; else -> "" }
            fun typeName(t: String) = when (t) { "mod" -> tr("Мод", "Mod"); "resourcepack" -> tr("Ресурспак", "Resource pack"); "shader" -> tr("Шейдер", "Shader"); else -> tr("Датапак", "Datapack") }
            fun typeTitle(t: String) = when (t) { "mod" -> tr("Моды", "Mods"); "resourcepack" -> tr("Ресурспаки", "Resource packs"); "shader" -> tr("Шейдеры", "Shaders"); else -> tr("Датапаки", "Datapacks") }
            val order = listOf("mod", "resourcepack", "shader", "datapack")
            ents.sortWith(compareBy<Ent>({ order.indexOf(it.type) }, { loaderName(it.loader).ifEmpty { "~" } }, { it.title.lowercase() }))
            val manyTypes = ents.map { it.type }.toSet().size > 1
            val manyLoaders = ents.filter { it.type == "mod" }.map { it.loader }.toSet().size > 1
            fun header(s: String) = list.addView(TextView(this).apply { text = s; textSize = 13f; setTextColor(Th.accent); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(14), 0, dp(2)) })
            var lastType = ""; var lastLoader = "\u0000"
            for (e in ents) {
                if (e.type != lastType) { lastType = e.type; lastLoader = "\u0000"; if (manyTypes) header(typeTitle(e.type)) }
                if (e.type == "mod" && manyLoaders && e.loader != lastLoader) { lastLoader = e.loader; header("  " + (loaderName(e.loader).ifEmpty { tr("Другое", "Other") })) }
                val sub = (if (e.type == "mod") loaderName(e.loader).ifEmpty { typeName(e.type) } else typeName(e.type)) + (if (e.ver.isNotEmpty()) " • " + e.ver else "")
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply { text = e.title; textSize = 16f; setTextColor(Th.text); typeface = Typeface.DEFAULT_BOLD })
                col.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(Th.dim) })
                r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(Button(this).apply {
                    text = tr("Удалить", "Delete"); isAllCaps = false; setTextColor(Th.chipText); background = sel(0xFFB3261E.toInt())
                    setOnClickListener {
                        e.file.delete()
                        if (e.id != null) idxSave(idx().apply { remove(e.id) })
                        (r.parent as? LinearLayout)?.removeView(r)
                    }
                }, LinearLayout.LayoutParams(dp(100), dp(40)))
                list.addView(r)
                list.addView(View(this).apply { setBackgroundColor(Th.line2) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
            if (ents.isEmpty()) list.addView(TextView(this).apply { text = tr("Пока ничего не скачано", "Nothing downloaded yet"); setTextColor(Th.dim); setPadding(0, dp(16), 0, 0) })
        }
        fun search() {
            val my = ++searchSeq
            if (type == "downloaded") { showDownloaded(); return }
            list.removeAllViews()
            list.addView(TextView(this).apply { text = tr("Загрузка…", "Loading…"); setTextColor(Th.dim); setPadding(0, dp(16), 0, 0) })
            val query = q.text.toString().trim()
            if (source == "curseforge") {
                Thread {
                    try {
                        val data = cfSearch(query, type, loader, strict)
                        runOnUiThread {
                            if (my != searchSeq) return@runOnUiThread
                            list.removeAllViews()
                            var shown = 0
                            for (i in 0 until data.length()) {
                                val h = data.getJSONObject(i)
                                val pid = "cf:" + h.getInt("id")
                                if (hideDl) {
                                    val rec = idx().optJSONObject(pid)
                                    if (rec != null && packFile(rec.optString("t"), rec.optString("f")).exists()) continue
                                }
                                shown++
                                val outer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10)) }
                                val icon = ImageView(this).apply { setBackgroundColor(Th.line2); scaleType = ImageView.ScaleType.CENTER_CROP }
                                outer.addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) })
                                loadIcon(h.optJSONObject("logo")?.optString("thumbnailUrl", "") ?: "", icon)
                                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                                outer.addView(row, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                                row.addView(TextView(this).apply { text = h.optString("name"); textSize = 17f; setTextColor(Th.text); typeface = Typeface.DEFAULT_BOLD })
                                val au = h.optJSONArray("authors")?.optJSONObject(0)?.optString("name") ?: ""
                                row.addView(TextView(this).apply { text = au + "  ·  ⬇ " + h.optLong("downloadCount") + "  ·  CurseForge"; textSize = 12f; setTextColor(Th.accent) })
                                row.addView(TextView(this).apply { text = h.optString("summary"); textSize = 13f; setTextColor(Th.text2); maxLines = 3 })
                                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                                fillActions(actions, pid, type, loader, h.optString("name"))
                                row.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                                list.addView(outer)
                                list.addView(View(this).apply { setBackgroundColor(Th.line2) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                            }
                            if (shown == 0) list.addView(TextView(this).apply {
                                text = tr("Ничего не найдено в CurseForge для ${curBase()} / $loader", "Nothing found on CurseForge for ${curBase()} / $loader")
                                setTextColor(Th.dim); setPadding(0, dp(16), 0, 0)
                            })
                        }
                    } catch (e: Throwable) {
                        runOnUiThread {
                            if (my != searchSeq) return@runOnUiThread
                            list.removeAllViews()
                            list.addView(TextView(this).apply { text = tr("CurseForge недоступен: ", "CurseForge unavailable: ") + e.message; setTextColor(Th.dim); setPadding(0, dp(16), 0, 0) })
                        }
                    }
                }.start()
                return
            }
            Thread {
                try {
                    val facets = JSONArray()
                    if (strict) facets.put(JSONArray().put("versions:${curBase()}"))
                    facets.put(JSONArray().put("project_type:$type"))
                    if (strict && type == "mod") facets.put(JSONArray().apply { loaderCats(loader).forEach { put("categories:$it") } })
                    val path = "/search?query=" + java.net.URLEncoder.encode(query, "UTF-8") +
                        "&facets=" + java.net.URLEncoder.encode(facets.toString(), "UTF-8") + "&limit=25&index=" + (if (query.isEmpty()) "downloads" else "relevance")
                    fun searchPath(q: String, f: JSONArray, limit: Int) = "/search?query=" + java.net.URLEncoder.encode(q, "UTF-8") +
                        "&facets=" + java.net.URLEncoder.encode(f.toString(), "UTF-8") + "&limit=$limit&index=" + (if (q.isEmpty()) "downloads" else "relevance")
                    var hits = JSONObject(apiGet(path)).getJSONArray("hits")
                    // знаки вроде «!» в названии могут мешать поиску: пробуем без них
                    val cleaned = query.replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
                    if (hits.length() == 0 && cleaned.isNotEmpty() && cleaned != query) hits = JSONObject(apiGet(searchPath(cleaned, facets, 25))).getJSONArray("hits")
                    // то, что нашлось по названию, но не прошло фильтры (версия, загрузчик, тип): покажем отдельно с причиной
                    val extra = ArrayList<JSONObject>()
                    val qq = if (cleaned.isNotEmpty()) cleaned else query
                    if (query.isNotEmpty() && strict) {
                        try {
                            val all = JSONObject(apiGet("/search?query=" + java.net.URLEncoder.encode(qq, "UTF-8") + "&limit=10&index=relevance")).getJSONArray("hits")
                            val shown = HashSet<String>()
                            for (k in 0 until hits.length()) shown.add(hits.getJSONObject(k).optString("project_id"))
                            for (k in 0 until all.length()) {
                                val h = all.getJSONObject(k)
                                if (!shown.contains(h.optString("project_id")) && extra.size < 5) extra.add(h)
                            }
                        } catch (_: Throwable) {}
                    }
                    runOnUiThread {
                        if (my != searchSeq) return@runOnUiThread
                        list.removeAllViews()
                        if (hits.length() == 0) list.addView(TextView(this).apply { text = tr("Ничего не найдено для версии ${curBase()} и загрузчика $loader", "Nothing found for version ${curBase()} and loader $loader"); setTextColor(Th.dim); setPadding(0, dp(16), 0, 0) })
                        var shownCount = 0
                        for (i in 0 until hits.length()) {
                            val h = hits.getJSONObject(i)
                            if (hideDl) {
                                val rec = idx().optJSONObject(h.optString("project_id"))
                                if (rec != null && packFile(rec.optString("t"), rec.optString("f")).exists()) continue
                            }
                            shownCount++
                            val outer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10)) }
                            val icon = ImageView(this).apply { setBackgroundColor(Th.line2); scaleType = ImageView.ScaleType.CENTER_CROP }
                            outer.addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) })
                            loadIcon(h.optString("icon_url", ""), icon)
                            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                            outer.addView(row, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            row.addView(TextView(this).apply { text = h.getString("title"); textSize = 17f; setTextColor(Th.text); typeface = Typeface.DEFAULT_BOLD })
                            row.addView(TextView(this).apply { text = h.optString("author") + "  ·  ⬇ " + h.optInt("downloads"); textSize = 12f; setTextColor(Th.accent) })
                            row.addView(TextView(this).apply { text = h.optString("description"); textSize = 13f; setTextColor(Th.text2); maxLines = 3 })
                            if (!strict) {
                                var okv = false
                                h.optJSONArray("versions")?.let { vs2 -> for (k in 0 until vs2.length()) if (vs2.getString(k) == curBase()) okv = true }
                                var okl = type != "mod"
                                h.optJSONArray("categories")?.let { c2 -> for (k in 0 until c2.length()) if (c2.getString(k) == loader) okl = true }
                                if (!okv || !okl) row.addView(TextView(this).apply {
                                    text = "⚠ " + tr("не заявлен для ${curBase()} / $loader", "not listed for ${curBase()} / $loader")
                                    textSize = 12f; setTextColor(0xFFFFB74D.toInt())
                                })
                            }
                            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                            fillActions(actions, h.getString("project_id"), type, loader, h.getString("title"))
                            row.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                            list.addView(outer)
                            list.addView(View(this).apply { setBackgroundColor(Th.line2) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                        }
                        if (hits.length() > 0 && shownCount == 0) list.addView(TextView(this).apply {
                            text = tr("Всё найденное уже скачано (фильтр «Не показывать скачанное»)", "Everything found is already downloaded (hide-downloaded filter)")
                            setTextColor(Th.dim); setPadding(0, dp(16), 0, 0)
                        })
                        if (extra.isNotEmpty()) {
                            list.addView(TextView(this).apply {
                                text = tr("Найдено, но не подходит под фильтры (версия ${curBase()}, загрузчик $loader, тип «$type»):", "Found, but filtered out (version ${curBase()}, loader $loader, type \"$type\"):")
                                setTextColor(Th.text2); textSize = 13f; setPadding(0, dp(18), 0, dp(6))
                            })
                            for (h in extra) {
                                val vs = h.optJSONArray("versions")
                                var has = false
                                var range = "?"
                                if (vs != null && vs.length() > 0) {
                                    range = vs.getString(0) + (if (vs.length() > 1) " – " + vs.getString(vs.length() - 1) else "")
                                    for (k in 0 until vs.length()) if (vs.getString(k) == curBase()) has = true
                                }
                                val cats = h.optJSONArray("categories")
                                val ld = ArrayList<String>()
                                if (cats != null) for (k in 0 until cats.length()) { val c = cats.getString(k); if (c in listOf("fabric", "forge", "neoforge", "quilt")) ld.add(c) }
                                val okLoader = type != "mod" || ld.contains(loader)
                                val okType = h.optString("project_type") == type
                                val reasons = ArrayList<String>()
                                if (!okType) reasons.add(tr("другой тип: ${h.optString("project_type")}", "different type: ${h.optString("project_type")}"))
                                if (!has) reasons.add(tr("версии ${curBase()} нет в списке мода", "${curBase()} is not in the mod's version list"))
                                if (!okLoader) reasons.add(tr("нет загрузчика $loader", "no $loader loader"))
                                list.addView(TextView(this).apply {
                                    text = h.optString("title") + "\n" + tr("Версии: ", "Versions: ") + range + "  ·  " + tr("Загрузчики: ", "Loaders: ") + (if (ld.isEmpty()) "?" else ld.joinToString(", ")) +
                                        "\n" + tr("Причина: ", "Reason: ") + (if (reasons.isEmpty()) "?" else reasons.joinToString("; "))
                                    setTextColor(Th.text); textSize = 14f; setPadding(0, dp(6), 0, dp(6))
                                })
                                val pid = h.optString("project_id"); val ptype = h.optString("project_type", type); val ptitle = h.optString("title")
                                list.addView(Button(this).apply {
                                    text = tr("Скачать вручную…", "Download manually…"); isAllCaps = false; setTextColor(Th.chipText); background = sel(Th.chip)
                                    setOnClickListener { openManualDownload(pid, ptype, ptitle) {} }
                                }, LinearLayout.LayoutParams(dp(200), dp(40)).apply { bottomMargin = dp(8) })
                            }
                        }
                    }
                } catch (e: Throwable) {
                    runOnUiThread {
                        if (my != searchSeq) return@runOnUiThread
                        list.removeAllViews()
                        list.addView(TextView(this).apply { text = tr("Не удалось загрузить каталог: ", "Could not load the catalog: ") + e.message + tr("\nПроверьте источник в Настройки → Специальное.", "\nCheck the source in Settings → Special."); setTextColor(Th.dim); setPadding(0, dp(16), 0, 0) })
                    }
                }
            }.start()
        }
        // порядок вкладок: ряд 1 — моды, шейдеры, скачанные; ряд 2 — ресурспаки, датапаки
        val types = listOf("mod" to tr("Моды", "Mods"), "shader" to tr("Шейдеры", "Shaders"), "downloaded" to tr("Скачанные", "Downloaded"),
            "resourcepack" to tr("Ресурспаки", "Resource packs"), "datapack" to tr("Датапаки", "Datapacks"))
        val loaders = listOf("fabric", "forge", "neoforge", "quilt")
        fun loaderLabel() = (if (loader == "neoforge") "NeoForge" else loader.replaceFirstChar { c -> c.uppercase() }) + " ▾"
        fun smallBtn(txt: String, onClick: () -> Unit) = TextView(this).apply {
            text = txt; textSize = 13f; setTextColor(Th.chipText); gravity = Gravity.CENTER; maxLines = 1
            setPadding(dp(10), dp(8), dp(10), dp(8)); isClickable = true; background = sel(Th.chip); setOnClickListener { onClick() }
        }
        val verBtn = smallBtn(curBase() + " ▾") {}
        val loadBtn = smallBtn(loaderLabel()) {}
        verBtn.setOnClickListener {
            if (build != null) Toast.makeText(this, tr("Версия сборки меняется в её настройках («Изменить»)", "A build's version is changed in its settings (“Edit”)"), Toast.LENGTH_SHORT).show()
            else showVersions {
                verBtn.text = curBase() + " ▾"
                loader = loaderOfProfile(selectedProfile()); loadBtn.text = loaderLabel()
                search()
            }
        }
        loadBtn.setOnClickListener {
            MobDialog(this).setTitle(tr("Загрузчик для поиска", "Loader for search"))
                .setItems(loaders.map { (if (it == "neoforge") "NeoForge" else it.replaceFirstChar { c -> c.uppercase() }) + (if (it == loader) "  ✓" else "") }.toTypedArray()) { _, i ->
                    loader = loaders[i]; loadBtn.text = loaderLabel(); if (type == "mod") search()
                }.setNegativeButton(tr("Закрыть", "Close"), null).show()
        }
        val loaderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        loaderRow.addView(verBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        loaderRow.addView(loadBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        val typeRow = tabRows(listOf(types.subList(0, 3).map { it.second }, types.subList(3, 5).map { it.second }), 0) { i ->
            type = types[i].first
            loaderRow.visibility = if (type == "mod") View.VISIBLE else View.GONE
            q.visibility = if (type == "downloaded" || prefs.getBoolean("catCollapsed", false)) View.GONE else View.VISIBLE
            search()
        }
        box.addView(typeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12); marginEnd = dp(12); topMargin = dp(4); bottomMargin = dp(8) })
        // источник каталога: Modrinth / CurseForge (CurseForge виден, только если в сборку вшит ключ)
        if (BuildConfig.CF_KEY.isNotEmpty()) {
            val srcBtn = smallBtn("") {}
            fun srcLabel() = tr("Источник: ", "Source: ") + (if (source == "curseforge") "CurseForge" else "Modrinth") + " ▾"
            srcBtn.text = srcLabel()
            srcBtn.setOnClickListener {
                val names = arrayOf("Modrinth" + (if (source == "modrinth") "  ✓" else ""), "CurseForge" + (if (source == "curseforge") "  ✓" else ""))
                MobDialog(this).setTitle(tr("Источник каталога", "Catalog source")).setItems(names) { _, i ->
                    source = if (i == 1) "curseforge" else "modrinth"
                    prefs.edit().putString("catSource", source).apply()
                    srcBtn.text = srcLabel(); search()
                }.setNegativeButton(tr("Закрыть", "Close"), null).show()
            }
            box.addView(srcBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12); bottomMargin = dp(6) })
        }
        // кнопка фильтров справа от Fabric/Forge: иконка с ползунками и числом включённых фильтров
        val filt = FilterView(this)
        fun refreshFilt() { filt.count = (if (strict) 1 else 0) + (if (hideDl) 1 else 0) }
        refreshFilt()
        filt.setOnClickListener {
            val pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun chip(label: String, note: String, on: Boolean, onToggle: (Boolean) -> Unit) {
                var state = on
                val t = TextView(this).apply {
                    text = label; textSize = 14f; setTextColor(Th.chipText); gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    fun paint() { setBackgroundColor(if (state) Th.act else Th.chip); text = (if (state) "✓  " else "") + label }
                    paint()
                    setOnClickListener { state = !state; paint(); onToggle(state) }
                }
                pane.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                pane.addView(TextView(this).apply { text = note; textSize = 12f; setTextColor(Th.dim); setPadding(0, dp(4), 0, dp(12)) })
            }
            chip(tr("Совместимость версий", "Version compatibility"),
                tr("Включено: только то, что подходит под вашу версию игры и загрузчик. Выключено: показывать всё найденное, несовместимое можно скачать вручную (на свой риск).",
                    "On: only items that fit your game version and loader. Off: show everything found; incompatible items can be downloaded manually (at your own risk)."), strict) { v ->
                strict = v; prefs.edit().putBoolean("catStrict", v).apply(); refreshFilt(); search()
            }
            chip(tr("Не показывать скачанное", "Hide downloaded"),
                tr("Скрывает в результатах то, что уже скачано.", "Hides items you have already downloaded."), hideDl) { v ->
                hideDl = v; prefs.edit().putBoolean("catHideDl", v).apply(); refreshFilt(); search()
            }
            MobDialog(this).setTitle(tr("Фильтры", "Filters")).setView(pane)
                .setPositiveButton(tr("Готово", "Done"), null).show()
        }
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // слева кнопка-стрелка: сворачивает поиск и вкладки, чтобы список занимал больше места
        val caret = CaretView(this)
        fun applyCollapse() {
            val c = prefs.getBoolean("catCollapsed", false)
            caret.up = !c
            typeRow.visibility = if (c) View.GONE else View.VISIBLE
            q.visibility = if (c || type == "downloaded") View.GONE else View.VISIBLE
        }
        caret.setOnClickListener { prefs.edit().putBoolean("catCollapsed", !prefs.getBoolean("catCollapsed", false)).apply(); applyCollapse() }
        applyCollapse()
        val caretBox = FrameLayout(this)
        caretBox.addView(caret, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.START or Gravity.CENTER_VERTICAL))
        // Fabric/Forge по центру строки, кнопка фильтров справа
        filterRow.addView(caretBox, LinearLayout.LayoutParams(0, dp(42), 1f))
        val centerBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        centerBox.addView(loaderRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // «Импорт» рядом с Fabric/Forge: копирует файлы из памяти телефона в папку выбранного типа
        val impBtn = TextView(this).apply {
            text = "＋ " + tr("Импорт", "Import"); textSize = 14f; setTextColor(Th.accent); gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8)); isClickable = true
            background = GradientDrawable().apply { setColor(Th.chip2); setStroke(dp(2), Th.act) }
            setOnClickListener {
                importFiles { search() }
            }
        }
        centerBox.addView(impBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        filterRow.addView(centerBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val filtBox = FrameLayout(this)
        filtBox.addView(filt, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.END or Gravity.CENTER_VERTICAL))
        filterRow.addView(filtBox, LinearLayout.LayoutParams(0, dp(42), 1f))
        box.addView(filterRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(6) })
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

    // ---------- CurseForge ----------
    private fun cfGet(path: String): JSONObject {
        val c = URL("https://api.curseforge.com/v1" + path).openConnection() as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 25000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("x-api-key", BuildConfig.CF_KEY)
        c.setRequestProperty("User-Agent", "Kumolix/Mobilix (Android launcher)")
        if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    }
    private fun cfLoaderType(l: String) = when (l) { "forge" -> 1; "fabric", "quilt" -> 4; "neoforge" -> 6; else -> 0 }
    private fun cfClass(type: String) = when (type) { "resourcepack" -> 12; "shader" -> 6552; "datapack" -> 6945; else -> 6 }

    private fun cfSearch(query: String, type: String, loader: String, strict: Boolean): JSONArray {
        var p = "/mods/search?gameId=432&classId=${cfClass(type)}&pageSize=25&sortOrder=desc&sortField=" + (if (query.isEmpty()) 2 else 1)
        if (query.isNotEmpty()) p += "&searchFilter=" + java.net.URLEncoder.encode(query, "UTF-8")
        if (strict) {
            p += "&gameVersion=" + java.net.URLEncoder.encode(curBase(), "UTF-8")
            if (type == "mod" && cfLoaderType(loader) != 0) p += "&modLoaderType=" + cfLoaderType(loader)
        }
        return cfGet(p).getJSONArray("data")
    }

    /** Скачивает файл CurseForge под текущую версию/загрузчик. Возвращает имя файла или бросает исключение. */
    private fun cfDownloadFile(modId: Int, type: String, loader: String): String {
        var p = "/mods/$modId/files?pageSize=30&gameVersion=" + java.net.URLEncoder.encode(curBase(), "UTF-8")
        if (type == "mod" && cfLoaderType(loader) != 0) p += "&modLoaderType=" + cfLoaderType(loader)
        val files = cfGet(p).getJSONArray("data")
        if (files.length() == 0) throw RuntimeException(tr("нет файла под ${curBase()} / $loader", "no file for ${curBase()} / $loader"))
        var f: JSONObject? = null
        var best = -1L
        for (i in 0 until files.length()) {   // самый свежий релиз; релизы (1) важнее бета/альфа (2,3)
            val o = files.getJSONObject(i)
            val score = (4 - o.optInt("releaseType", 3)) * 1_000_000_000_000L + (o.optString("fileDate").filter { it.isDigit() }.take(14).toLongOrNull() ?: 0L) / 1000
            if (score > best) { best = score; f = o }
        }
        val file = f!!
        val name = file.getString("fileName")
        val url = if (file.isNull("downloadUrl")) "" else file.optString("downloadUrl")
        if (url.isEmpty()) throw RuntimeException("__cfsite")
        var sha: String? = null
        file.optJSONArray("hashes")?.let { hs -> for (i in 0 until hs.length()) if (hs.getJSONObject(i).optInt("algo") == 1) sha = hs.getJSONObject(i).optString("value").ifEmpty { null } }
        val dest = File(curGameDir(), typeDir(type) + "/" + name)
        if (!download(url, dest, sha)) throw RuntimeException(tr("ошибка загрузки", "download failed"))
        // обязательные зависимости (relationType 3): ставим, что получится
        if (type == "mod") {
            val deps = file.optJSONArray("dependencies")
            if (deps != null) for (i in 0 until deps.length()) {
                val d = deps.getJSONObject(i)
                if (d.optInt("relationType") != 3) continue
                val did = d.optInt("modId"); val key = "cf:$did"
                val rec = idx().optJSONObject(key)
                if (rec != null && packFile(rec.optString("t"), rec.optString("f")).exists()) continue
                try {
                    val dn = cfDownloadFile(did, "mod", loader)
                    idxSave(idx().apply { put(key, JSONObject().put("t", "mod").put("f", dn).put("n", try { cfGet("/mods/$did").getJSONObject("data").optString("name", "CF $did") } catch (_: Throwable) { "CF $did" }).put("l", loader).put("v", curBase())) })
                } catch (_: Throwable) {}
            }
        }
        return name
    }

    private fun cfInstall(modId: Int, type: String, loader: String, title: String, btn: Button, onDone: () -> Unit) {
        Thread {
            var res = ""
            var destFile: File? = null
            var site = ""
            try {
                val name = cfDownloadFile(modId, type, loader)
                destFile = File(curGameDir(), typeDir(type) + "/" + name)
                idxSave(idx().apply { put("cf:$modId", JSONObject().put("t", type).put("f", name).put("n", title).put("l", if (type == "mod") loader else "").put("v", curBase())) })
                res = "ok"
            } catch (e: Throwable) {
                res = e.message ?: e.toString()
                if (res == "__cfsite") try { site = cfGet("/mods/$modId").getJSONObject("data").getJSONObject("links").optString("websiteUrl") } catch (_: Throwable) {}
            }
            runOnUiThread {
                if (res == "ok") { onDone(); if (type == "datapack") destFile?.let { offerDatapackWorld(it) } }
                else {
                    btn.isEnabled = true; btn.alpha = 1f; btn.text = tr("Скачать", "Download")
                    if (res == "__cfsite") {
                        MobDialog(this).setTitle(title)
                            .setMessage(tr("Автор отключил скачивание через сторонние лаунчеры. Файл можно скачать на сайте CurseForge и добавить через «Импорт».", "The author disabled downloads via third-party launchers. Download the file on the CurseForge website and add it with “Import”."))
                            .setPositiveButton(tr("Открыть сайт", "Open site")) { _, _ ->
                                if (site.isNotEmpty()) try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(site + "/files"))) } catch (_: Throwable) {}
                            }.setNegativeButton(tr("Закрыть", "Close"), null).show()
                    } else Toast.makeText(this, res, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun installProject(id: String, type: String, loader: String, title: String, btn: Button, onDone: () -> Unit) {
        btn.isEnabled = false; btn.alpha = 0.6f; btn.text = tr("Скачивание…", "Downloading…")
        if (id.startsWith("cf:")) { cfInstall(id.substring(3).toInt(), type, loader, title, btn, onDone); return }
        Thread {
            var res = ""
            var destFile: File? = null
            try {
                val g = "?game_versions=" + java.net.URLEncoder.encode("[\"${curBase()}\"]", "UTF-8") +
                    (if (type == "mod") "&loaders=" + java.net.URLEncoder.encode(JSONArray(loaderCats(loader)).toString(), "UTF-8") else "")
                val vers = JSONArray(apiGet("/project/$id/version$g"))
                if (vers.length() == 0) throw RuntimeException("__manual")
                val files = vers.getJSONObject(0).getJSONArray("files")
                var f = files.getJSONObject(0)
                for (i in 0 until files.length()) if (files.getJSONObject(i).optBoolean("primary")) f = files.getJSONObject(i)
                val dest = File(curGameDir(), typeDir(type) + "/" + f.getString("filename"))
                destFile = dest
                val ok = download(f.getString("url"), dest, f.optJSONObject("hashes")?.optString("sha1")?.ifEmpty { null })
                if (!ok) throw RuntimeException(tr("ошибка загрузки", "download failed"))
                idxSave(idx().apply { put(id, JSONObject().put("t", type).put("f", f.getString("filename")).put("n", title).put("l", if (type == "mod") loader else "").put("v", curBase())) })
                res = "ok"
            } catch (e: Throwable) { res = e.message ?: e.toString() }
            runOnUiThread {
                if (res == "ok") { onDone(); if (type == "datapack") destFile?.let { offerDatapackWorld(it) } }
                else {
                    btn.isEnabled = true; btn.alpha = 1f; btn.text = tr("Скачать", "Download")
                    // нет файла под вашу версию/загрузчик: предлагаем выбрать любой файл вручную
                    if (res == "__manual") openManualDownload(id, type, title, onDone)
                    else Toast.makeText(this, res, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Окно ручной загрузки: все файлы проекта без фильтров, совместимые сверху; на свой риск. */
    private fun openManualDownload(id: String, type: String, title: String, onDone: () -> Unit) {
        Toast.makeText(this, tr("Нет файла под ${curBase()}. Загружаю все версии…", "No file for ${curBase()}. Loading all versions…"), Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val vers = JSONArray(apiGet("/project/$id/version"))
                runOnUiThread {
                    if (vers.length() == 0) { Toast.makeText(this, tr("У проекта нет файлов", "The project has no files"), Toast.LENGTH_LONG).show(); return@runOnUiThread }
                    val all = ArrayList<JSONObject>()
                    for (i in 0 until vers.length()) all.add(vers.getJSONObject(i))
                    fun fits(v: JSONObject): Boolean {
                        val g = v.optJSONArray("game_versions") ?: return false
                        for (k in 0 until g.length()) if (g.getString(k) == curBase()) return true
                        return false
                    }
                    val sorted = all.sortedByDescending { if (fits(it)) 1 else 0 }.take(80)
                    val items = ArrayList<String>()
                    for (v in sorted) {
                        val g = v.optJSONArray("game_versions"); val l = v.optJSONArray("loaders")
                        val gs = if (g != null && g.length() > 0) g.getString(0) + (if (g.length() > 1) " – " + g.getString(g.length() - 1) else "") else "?"
                        val ls = ArrayList<String>()
                        if (l != null) for (k in 0 until l.length()) ls.add(l.getString(k))
                        items.add((if (fits(v)) "✓ " else "✗ ") + v.optString("name", v.optString("version_number")) + "\n" + gs + "  ·  " + ls.joinToString(", "))
                    }
                    MobDialog(this)
                        .setTitle(title + "\n" + tr("выберите файл (на свой риск)", "pick a file (at your own risk)"))
                        .setItems(items.toTypedArray()) { _, i -> downloadVersion(id, type, title, sorted[i], onDone) }
                        .setNegativeButton(tr("Отмена", "Cancel"), null).show()
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, e.message ?: e.toString(), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun downloadVersion(id: String, type: String, title: String, v: JSONObject, onDone: () -> Unit) {
        Toast.makeText(this, tr("Скачивание…", "Downloading…"), Toast.LENGTH_SHORT).show()
        Thread {
            var res = ""
            var dl: File? = null
            try {
                val files = v.getJSONArray("files")
                var f = files.getJSONObject(0)
                for (i in 0 until files.length()) if (files.getJSONObject(i).optBoolean("primary")) f = files.getJSONObject(i)
                val dest = File(curGameDir(), typeDir(type) + "/" + f.getString("filename"))
                dl = dest
                val ok = download(f.getString("url"), dest, f.optJSONObject("hashes")?.optString("sha1")?.ifEmpty { null })
                if (!ok) throw RuntimeException(tr("ошибка загрузки", "download failed"))
                val ldv = v.optJSONArray("loaders"); var ld = ""
                if (type == "mod" && ldv != null) for (k in 0 until ldv.length()) if (ldv.getString(k) in listOf("fabric", "forge", "neoforge", "quilt")) { ld = ldv.getString(k); break }
                val gvs = v.optJSONArray("game_versions"); var gv = curBase()
                if (gvs != null && gvs.length() > 0) { var has = false; for (k in 0 until gvs.length()) if (gvs.getString(k) == gv) has = true; if (!has) gv = gvs.getString(gvs.length() - 1) }
                idxSave(idx().apply { put(id, JSONObject().put("t", type).put("f", f.getString("filename")).put("n", title).put("l", ld).put("v", gv)) })
                res = "ok"
            } catch (e: Throwable) { res = e.message ?: e.toString() }
            runOnUiThread {
                if (res == "ok") {
                    if (type == "datapack") dl?.let { offerDatapackWorld(it) }
                    Toast.makeText(this, tr("Скачано: $title. Версия может не подойти к вашей игре.", "Downloaded: $title. The version may not fit your game."), Toast.LENGTH_LONG).show()
                    onDone()
                } else Toast.makeText(this, res, Toast.LENGTH_LONG).show()
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
        fun btn(t: String, bg: Int, fg: Int = Th.chipText, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setTextColor(fg); background = sel(bg); setOnClickListener { onClick() }
        }
        skin.slim = prefs.getBoolean("slim", false)
        val modelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val modelBtns = ArrayList<TextView>()
        fun paintModel() = modelBtns.forEachIndexed { i, t ->
            val on = (i == 1) == prefs.getBoolean("slim", false)
            t.background = sel(if (on) Th.accentDk else Th.act)
        }
        for ((i, label) in listOf(tr("Широкий", "Wide"), tr("Стройный", "Slim")).withIndex()) {
            val t = TextView(this).apply {
                text = label; textSize = 15f; setTextColor(Th.chipText); gravity = Gravity.CENTER; isClickable = true
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
        col.addView(btn(tr("Загрузить скин (PNG)", "Upload skin (PNG)"), Th.chip) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/png"), 77)
        })
        col.addView(btn(tr("Сбросить на стандартный", "Reset to default"), Th.chip2) {
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
        impRow.addView(btn("Ely.by", Th.chip) { importSkin("ely", skinNick.text.toString().trim()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        impRow.addView(btn("Mojang", Th.chip) { importSkin("mojang", skinNick.text.toString().trim()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
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
        col.addView(btn(tr("Сохранить ник", "Save nickname"), Th.act) {
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
            col.addView(btn(tr("Войти через Ely.by", "Sign in with Ely.by"), Th.chip) {
                elyDialog { openPage(tr("Аккаунт", "Account"), accountPage()) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(24) })
        }
        // кнопка Microsoft по их правилам: белый фон, рамка, логотип слева
        val ms = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1), 0xFF8C8C8C.toInt()) }
            isClickable = true
            alpha = if (MS_APPROVED) 1f else 0.55f
            setOnClickListener {
                if (MS_APPROVED) msBrowserLogin()
                else MobDialog(this@MainActivity).setTitle(tr("Вход через Microsoft", "Sign in with Microsoft"))
                    .setMessage(tr("Пока недоступен: Mojang не одобрил приложение Mobilix для официального входа. Мы подадим заявку ещё раз позже. Играть можно с ником или через Ely.by.",
                        "Not available yet: Mojang has not approved Mobilix for official sign-in. We will apply again later. You can play with a nickname or via Ely.by."))
                    .setPositiveButton("OK", null).show()
            }
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

    // ---------- Вход через Microsoft в браузере (Authorization Code + PKCE, без пароля в лаунчере) ----------
    private val MS_REDIRECT = "mobilix-launcher://auth"
    private fun b64u(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    private fun msBrowserLogin() {
        val rnd = java.security.SecureRandom()
        val vb = ByteArray(48).also { rnd.nextBytes(it) }; val verifier = b64u(vb)
        val challenge = b64u(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = b64u(ByteArray(16).also { rnd.nextBytes(it) })
        prefs.edit().putString("ms_pkce_v", verifier).putString("ms_pkce_s", state).apply()
        fun e(x: String) = java.net.URLEncoder.encode(x, "UTF-8")
        val url = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?client_id=${e(MS_CLIENT)}&response_type=code" +
            "&redirect_uri=${e(MS_REDIRECT)}&scope=${e(MS_SCOPE)}&code_challenge=$challenge&code_challenge_method=S256&state=$state&prompt=select_account"
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        catch (_: Exception) { msDeviceLogin() }   // нет браузера: вход по коду
    }
    /** Возврат из браузера: mobilix-launcher://auth?code=...&state=... */
    private fun handleAuthIntent(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "mobilix-launcher" || d.host != "auth") return
        intent = Intent()
        val err = d.getQueryParameter("error")
        val code = d.getQueryParameter("code")
        val verifier = prefs.getString("ms_pkce_v", "") ?: ""
        val okState = !d.getQueryParameter("state").isNullOrEmpty() && d.getQueryParameter("state") == prefs.getString("ms_pkce_s", null)
        prefs.edit().remove("ms_pkce_v").remove("ms_pkce_s").apply()
        if (err != null || code == null || !okState || verifier.isEmpty()) {
            Toast.makeText(this, tr("Вход не удался: ", "Sign-in failed: ") + (d.getQueryParameter("error_description") ?: err ?: tr("неверный ответ", "bad response")), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, tr("Проверяем вход…", "Checking sign-in…"), Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val (c, t) = postForm(MS_TOKEN_URL, mapOf("client_id" to MS_CLIENT, "grant_type" to "authorization_code", "code" to code,
                    "redirect_uri" to MS_REDIRECT, "code_verifier" to verifier, "scope" to MS_SCOPE))
                if (c !in 200..299) throw RuntimeException("Microsoft: HTTP $c " + (try { JSONObject(t).optString("error_description").take(150) } catch (_: Exception) { "" }))
                val j = JSONObject(t)
                val (mc, id, name) = msMinecraftLogin(j.getString("access_token"))
                elyLogout(false)
                msSave(mc, id, name, j.optString("refresh_token", ""))
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
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i) }

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
                    MobDialog(this).setTitle(tr("Вход через Microsoft", "Sign in with Microsoft"))
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
        MobDialog(this).setTitle(tr("Вход через Ely.by", "Sign in with Ely.by")).setView(box)
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

    private fun httpBytes(url0: String): ByteArray {
        // Mojang отдаёт ссылки на скины по http://, а Android не пускает незашифрованный трафик: переходим на https
        val url = if (url0.startsWith("http://") && URL(url0).host.endsWith("minecraft.net")) "https://" + url0.removePrefix("http://") else url0
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Mobilix/1.8")
        c.setRequestProperty("Accept", "*/*"); c.setRequestProperty("Cache-Control", "no-cache")
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
            val props = JSONObject(String(httpBytes("https://sessionserver.mojang.com/session/minecraft/profile/$id?t=${System.currentTimeMillis()}"))).getJSONArray("properties")
            val value = String(android.util.Base64.decode(props.getJSONObject(0).getString("value"), android.util.Base64.DEFAULT))
            fromTextureJson(JSONObject(value).getJSONObject("textures"))
        }
        // свой аккаунт Microsoft: профиль по токену, без кэша и ограничений публичных серверов (обновляется сразу)
        val mojangAuth = "Mojang (аккаунт)" to {
            if (!msOn() || !msEnsureSession()) throw RuntimeException("нет входа Microsoft")
            val (c, t) = getBearer("https://api.minecraftservices.com/minecraft/profile", prefs.getString("ms_token", "") ?: "")
            if (c !in 200..299) throw RuntimeException("профиль: HTTP $c")
            val skins = JSONObject(t).getJSONArray("skins")
            var pick: JSONObject? = null
            for (i in 0 until skins.length()) { val s = skins.getJSONObject(i); if (s.optString("state") == "ACTIVE") pick = s }
            val pk = pick ?: throw RuntimeException("скин не задан")
            Triple(httpBytes(pk.getString("url")), pk.optString("variant").equals("SLIM", true), true)
        }
        val ashcon = "Ashcon" to {
            val sk = JSONObject(String(httpBytes("https://api.ashcon.app/mojang/v2/user/$nickIn"))).getJSONObject("textures").getJSONObject("skin")
            Triple(httpBytes(sk.getString("url")), sk.optBoolean("slim"), true)
        }
        val minotar = "Minotar" to { Triple(httpBytes("https://minotar.net/skin/$nickIn"), false, false) }
        val mcheads = "MC-Heads" to { Triple(httpBytes("https://mc-heads.net/skin/$nickIn"), false, false) }
        if (strict) tries.add(ely)
        else if (source == "ely") { tries.add(ely); tries.add(mojang); tries.add(minotar) } else { if (msOn() && source == "mojang" && nickIn == nickName()) tries.add(mojangAuth); tries.add(mojang); tries.add(ashcon); tries.add(minotar); tries.add(mcheads); tries.add(ely) }
        var lastErr = ""
        val errs = ArrayList<String>()
        for ((name, act) in tries) {
            try {
                // копии сайтов (Minotar, MC-Heads) бывают устаревшими: при автообновлении ими не заменяем текущий скин
                if (quiet && (name == "Minotar" || name == "MC-Heads")) { log("Скин: Mojang недоступен (" + errs.joinToString("; ") + "), копии сайтов не используются"); throw RuntimeException("только копия сайта") }
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
                    if (!quiet) {
                        if (name == "Minotar" || name == "MC-Heads") {
                            val why = errs.joinToString("\n")
                            log("Скин: Mojang не ответил: " + why.replace("\n", "; "))
                            MobDialog(this).setTitle(tr("Скин взят с копии сайта", "Skin taken from a site copy"))
                                .setMessage(tr("Скин загружен с $name, это копия, она может быть старой. Mojang не ответил:\n\n$why", "Skin loaded from $name, a copy that may be outdated. Mojang did not answer:\n\n$why"))
                                .setPositiveButton("OK", null).show()
                        } else Toast.makeText(this, tr("Скин загружен ($name)", "Skin loaded ($name)") + (if (slim) tr(", стройный", ", slim") else ""), Toast.LENGTH_SHORT).show()
                    }
                }
                return
            } catch (e: Exception) {
                lastErr = "$name: ${e.message ?: e.javaClass.simpleName}"
                errs.add(lastErr)
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
    private var importAfter: (() -> Unit)? = null
    /** Импорт: тип каждого файла определяется сам (мод, шейдер, ресурспак, датапак, карта, сборка Mobilix). */
    private fun importFiles(after: (() -> Unit)? = null) {
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

    private fun uriName(u: android.net.Uri): String {
        var name = "file"
        contentResolver.query(u, null, null, null, null)?.use { c ->
            val ix = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && ix >= 0) name = c.getString(ix) ?: name
        }
        return name.replace('/', '_')
    }
    private fun zipClean(n: String) = n.replace('\\', '/').trimStart('/')

    /** Читает оглавление zip (и mobilix-build.json, если он есть). */
    private fun zipScan(u: android.net.Uri): Pair<List<String>, String?> {
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
    private fun zipKind(names: List<String>, hasManifest: Boolean): String? {
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

    private fun extractWorlds(u: android.net.Uri, names: List<String>, base: String, dest: File) {
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

    private fun importUris(uris: List<android.net.Uri>) {
        val gameDir = curGameDir()
        Toast.makeText(this, tr("Копирование…", "Copying…"), Toast.LENGTH_SHORT).show()
        Thread {
            val counts = LinkedHashMap<String, Int>(); var fail = 0
            val builds = ArrayList<Pair<android.net.Uri, JSONObject>>()
            val dps = ArrayList<File>()
            val mrpacks = ArrayList<Pair<android.net.Uri, JSONObject>>()
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
                if (fail > 0) parts.add(tr("не распознано: $fail", "unrecognized: $fail"))
                Toast.makeText(this, (if (parts.isEmpty()) tr("Ничего не импортировано", "Nothing imported") else tr("Импорт: ", "Imported: ") + parts.joinToString(", ")), Toast.LENGTH_LONG).show()
                importAfter?.invoke()
                builds.forEach { (u, m) -> importBuild(u, m) }
                mrpacks.forEach { (u, m) -> importMrpack(u, m) }
                // датапаки: по очереди спрашиваем, в какой мир положить
                fun offer(i: Int) { if (i < dps.size) offerDatapackWorld(dps[i]) { offer(i + 1) } }
                offer(0)
            }
        }.start()
    }

    /** Сборка Mobilix из zip: создаёт новую сборку, распаковывает файлы и ставит нужную версию игры, если её ещё нет. */
    private fun importBuild(u: android.net.Uri, m: JSONObject) {
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

    /** Читает modrinth.index.json из .mrpack; null, если это не сборка Modrinth. */
    private fun readMrpackIndex(u: android.net.Uri): JSONObject? {
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
    private fun importMrpack(u: android.net.Uri, idx: JSONObject) {
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

    private fun profileKindMc(p: String): Pair<String, String> = when {
        p.startsWith("fabric-loader-") -> "fabric" to p.substringAfterLast("-")
        p.startsWith("quilt-loader-") -> "quilt" to p.substringAfterLast("-")
        p.startsWith("neoforge-") -> "neoforge" to (neoMc(p.removePrefix("neoforge-")) ?: p)
        p.contains("-forge-") -> "forge" to p.substringBefore("-forge-")
        Regex("^[\\d.]+-forge[\\d.]+-.+").matches(p) -> "forge" to p.substringBefore("-forge")
        else -> "vanilla" to p
    }

    /** Экспорт сборки в zip и отправка в любое приложение (Telegram и т.п.). */
    private fun shareBuild(b: JSONObject) {
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 80 && resultCode == RESULT_OK && data?.data != null) {
            try {
                val bytes = contentResolver.openInputStream(data.data!!)!!.use { it.readBytes() }
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = maxOf(1, minOf(o.outWidth, o.outHeight) / 256) }
                val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)!!
                val side = minOf(src.width, src.height)
                val sq = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                val out = if (side > 128) android.graphics.Bitmap.createScaledBitmap(sq, 128, 128, true) else sq
                iconPick?.invoke(out)
            } catch (e: Throwable) { Toast.makeText(this, tr("Не удалось открыть картинку", "Could not open the image"), Toast.LENGTH_LONG).show() }
            return
        }
        if (requestCode == 78 && resultCode == RESULT_OK && data != null) {
            val list = ArrayList<android.net.Uri>()
            data.clipData?.let { cd -> for (i in 0 until cd.itemCount) list.add(cd.getItemAt(i).uri) }
            if (list.isEmpty()) data.data?.let { list.add(it) }
            if (list.isNotEmpty()) importUris(list)
            return
        }
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
    /** Версия NeoForge «21.1.172» -> версия игры «1.21.1»; для нумерации 26+ версия игры совпадает с первыми двумя числами. */
    private fun neoMc(v: String): String? {
        val p = v.substringBefore("-").split("."); val a = p.getOrNull(0)?.toIntOrNull() ?: return null; val b = p.getOrNull(1)?.toIntOrNull() ?: return null
        return if (a < 26) "1.$a" + (if (b > 0) ".$b" else "") else "$a.$b"
    }
    private fun verKey(v: String) = v.split(".", "-").map { it.toIntOrNull() ?: -1 }
    private val verCmp = Comparator<String> { a, b ->
        val x = verKey(a); val y = verKey(b); var r = 0
        for (i in 0 until maxOf(x.size, y.size)) { r = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 }); if (r != 0) break }
        r
    }
    /** Версии NeoForge для игры mc: сначала стабильные, затем бета; новые первыми. */
    private fun neoforgeCandidates(mc: String): List<String> {
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
    private fun loaderOfProfile(p: String) = when {
        p.startsWith("neoforge-") -> "neoforge"; p.startsWith("quilt-loader-") -> "quilt"; p.contains("forge", true) -> "forge"; else -> "fabric"
    }
    /** Категории Modrinth для загрузчика: Quilt запускает и моды Fabric. */
    private fun loaderCats(l: String) = if (l == "quilt") listOf("quilt", "fabric") else listOf(l)
    private fun kindLabel(kind: String, mc: String) = when (kind) { "fabric" -> "Fabric $mc"; "forge" -> "Forge $mc"; "quilt" -> "Quilt $mc"; "neoforge" -> "NeoForge $mc"; else -> mc }

    /** Установленный профиль нужного типа для версии игры, либо null. */
    private fun findProfile(kind: String, mc: String): String? {
        val ids = installedProfiles()
        return when (kind) {
            "vanilla" -> ids.firstOrNull { it == mc && File(dataRoot(), "versions/$it/$it.jar").exists() }
            "fabric" -> ids.firstOrNull { it.startsWith("fabric-loader-") && it.endsWith("-$mc") }
            "quilt" -> ids.firstOrNull { it.startsWith("quilt-loader-") && it.endsWith("-$mc") }
            "neoforge" -> ids.firstOrNull { it.startsWith("neoforge-") && (neoMc(it.removePrefix("neoforge-")) == mc) }
            else -> ids.firstOrNull { it.startsWith("$mc-forge-") || it.startsWith("$mc-forge$mc-") }
        }
    }

    /** Скачивает версию (ванильную, Fabric или Forge) и выбирает её. Идёт в фоне, пока работает сервис. */
    // ---------- OptiFine: ставится как мод в профиль Forge (источник - зеркало BMCLAPI) ----------
    private fun gameDirFor(profile: String): File {
        val base = try { JSONObject(File(dataRoot(), "versions/$profile/$profile.json").readText()).let { it.optString("inheritsFrom", "").ifEmpty { it.optString("jar", "") }.ifEmpty { profile } } } catch (_: Exception) { profile }
        val legacy = try { JSONObject(File(dataRoot(), "versions/$base/$base.json").readText()).optJSONObject("arguments") == null } catch (_: Exception) { false }
        return File(dataRoot(), if (legacy) "game-$base" else "game")
    }
    private fun optifineFlow(mc: String, onDone: () -> Unit) {
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
    private fun optifineInstall(mc: String, o: JSONObject, onDone: () -> Unit) {
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
        val quilt = ArrayList<String>(); val neoforge = ArrayList<String>()
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
                list.addView(View(this).apply { setBackgroundColor(Th.act) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
                for (v in f) {
                    val pid = findProfile(key, v)
                    val sel = pid != null && pid == selectedProfile()
                    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
                    r.addView(TextView(this).apply { text = v; textSize = 17f; setTextColor(Th.text) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (key == "forge") r.addView(TextView(this).apply {
                        text = "OF"; textSize = 13f; setTextColor(Th.chipText); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                        background = sel(Th.chip2); isClickable = true; contentDescription = "OptiFine"
                        setOnClickListener { optifineFlow(v) { runOnUiThread { render() } } }
                    }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) })
                    r.addView(Button(this).apply {
                        text = when { sel -> tr("✓ Выбрана", "✓ Selected"); pid != null -> tr("Выбрать", "Select"); else -> tr("Скачать", "Download") }
                        isAllCaps = false; textSize = 12f; setTextColor(Th.chipText)
                        background = sel(if (pid != null) Th.chip else Th.act)
                        setOnClickListener {
                            if (pid != null) {
                                prefs.edit().putString("profile", pid).apply()
                                versionText.text = verTitle()
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
            section("Vanilla", vanilla, "vanilla"); section("Fabric", fabric, "fabric"); section("Quilt", quilt, "quilt"); section("Forge", forge, "forge"); section("NeoForge", neoforge, "neoforge")
            if (loader == "all" || loader == "downloaded") {
                val mine = installedProfiles().filter { q.isEmpty() || it.contains(q) || prettyProfile(it).contains(q) }
                if (mine.isNotEmpty()) {
                    list.addView(TextView(this).apply { text = tr("Скачанные", "Downloaded"); textSize = 20f; setTextColor(Th.accent); setPadding(0, dp(18), 0, dp(6)) })
                    list.addView(View(this).apply { setBackgroundColor(Th.act) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
                    for (id in mine) {
                        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
                        r.addView(TextView(this).apply { text = prettyProfile(id) + if (id == selectedProfile()) "  ✓" else ""; textSize = 17f; setTextColor(Th.text) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        r.addView(Button(this).apply {
                            text = tr("Удалить", "Delete"); isAllCaps = false; textSize = 12f; setTextColor(Th.chipText); background = sel(0xFFB3261E.toInt())
                            setOnClickListener {
                                if (busy) { Toast.makeText(this@MainActivity, tr("Подождите: идёт загрузка", "Wait: a download is in progress"), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                                MobDialog(this@MainActivity)
                                    .setTitle(tr("Удалить версию?", "Delete version?"))
                                    .setMessage(prettyProfile(id))
                                    .setNegativeButton(tr("Отмена", "Cancel"), null)
                                    .setPositiveButton(tr("Удалить", "Delete")) { _, _ ->
                                        File(dataRoot(), "versions/$id").deleteRecursively()
                                        if (selectedProfile() == id) prefs.edit().remove("profile").apply()
                                        versionText.text = verTitle()
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
            t.setBackgroundColor(if (k == loader) Th.act else Th.chip)
        }
        for ((k, label) in listOf("all" to tr("Все", "All"), "vanilla" to "Vanilla", "fabric" to "Fabric", "quilt" to "Quilt", "forge" to "Forge", "neoforge" to "NeoForge", "downloaded" to tr("Скачанные", "Downloaded"))) {
            val t = TextView(this).apply {
                text = label; textSize = 14f; setTextColor(Th.chipText); gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8))
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
                    else if (o.getString("type") == "old_alpha" && id.startsWith("c0.")) vanilla.add(id)   // Minecraft Classic
                    if (id == "1.0") cut = true   // 1.0–1.5.2 запускаются через слой без окна AWT (OldMain)
                }
                try {
                    val fa = JSONArray(fetchText("https://meta.fabricmc.net/v2/versions/game"))
                    for (i in 0 until fa.length()) {
                        val o = fa.getJSONObject(i)
                        if (o.optBoolean("stable") && order.containsKey(o.getString("version"))) fabric.add(o.getString("version"))
                    }
                } catch (_: Throwable) {}
                try {
                    val qa = JSONArray(fetchText("https://meta.quiltmc.org/v3/versions/game"))
                    for (i in 0 until qa.length()) {
                        val o = qa.getJSONObject(i)
                        if (o.optBoolean("stable") && order.containsKey(o.getString("version"))) quilt.add(o.getString("version"))
                    }
                } catch (_: Throwable) {}
                try {
                    val xml = fetchText("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
                    val set = LinkedHashSet<String>()
                    Regex("<version>([^<]+)</version>").findAll(xml).forEach { r -> neoMc(r.groupValues[1])?.let { mc -> if (order.containsKey(mc)) set.add(mc) } }
                    neoforge.addAll(set.sortedBy { order[it] })
                } catch (_: Throwable) {}
                try {
                    val set = HashSet<String>()
                    try {
                        val pr = JSONObject(fetchText("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")).getJSONObject("promos")
                        val it = pr.keys()
                        while (it.hasNext()) set.add(it.next().substringBefore("-"))
                    } catch (_: Throwable) {
                        // запасной источник списка: maven-metadata (с зеркалом BMCLAPI)
                        val xml = fetchText("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml")
                        Regex("<version>([\\d.]+)-[^<]+</version>").findAll(xml).forEach { set.add(it.groupValues[1]) }
                    }
                    val idx18 = order["1.8"] ?: Int.MAX_VALUE   // индекс в манифесте растёт к старым версиям
                    forge.addAll(set.filter { vanilla.contains(it) && (order[it] ?: 0) <= idx18 }.sortedBy { order[it] })
                } catch (_: Throwable) {}
            } catch (e: Throwable) {
                log("Список версий: $e")
            }
            loaded = true
            runOnUiThread { render() }
        }.start()
        return box
    }

    private fun showVersions(onPicked: (() -> Unit)? = null) {
        val installed = ArrayList(installedProfiles())
        if (installed.isEmpty()) installed.add(VERSION)
        val cur = selectedProfile()
        val names = ArrayList<String>()
        installed.forEach { names.add(prettyProfile(it) + if (it == cur) "  ✓" else "") }
        names.add(tr("＋ Загрузить другую версию…", "＋ Download another version…"))
        val bl = builds()
        val act = activeBuild()?.optString("id")
        val buildList = (0 until bl.length()).map { bl.getJSONObject(it) }
        if (buildList.isNotEmpty()) {
            names.add("\u0001" + tr("Сборки", "Builds"))
            buildList.forEach { names.add(it.optString("name") + "  ·  " + prettyProfile(it.optString("profile")) + if (it.optString("id") == act) "  ✓" else "") }
        }
        val buildStart = installed.size + 2   // после версий, «Загрузить другую» и заголовка раздела
        MobDialog(this).setTitle(tr("Версия для игры", "Game version")).setItems(names.toTypedArray()) { _, i ->
            if (i == installed.size) { if (compact()) openPage(tr("Загрузки", "Downloads"), downloadsPage(0)) else openPage(tr("Загрузчик версий", "Version loader"), versionsPage()) }
            else if (i >= buildStart) {
                val b = buildList[i - buildStart]
                prefs.edit().putString("profile", b.optString("profile")).putString("build", b.optString("id")).apply()
                versionText.text = verTitle(); onPicked?.invoke()
            } else if (i < installed.size) {
                prefs.edit().putString("profile", installed[i]).putString("build", "").apply()
                versionText.text = verTitle(); onPicked?.invoke()
            }
        }.setNegativeButton(tr("Закрыть", "Close"), null).show()
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
            MobDialog(this)
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
        MobDialog(this)
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
                runOnUiThread { versionText.text = verTitle() }
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
    /** Сборка, для которой открыт каталог (пока открыта страница «Каталог сборки»); иначе null. */
    private var catBuild: JSONObject? = null

    internal fun curGameDir(): File {
        catBuild?.let { return buildDir(it.optString("id")).apply { mkdirs() } }
        activeBuild()?.let { return buildDir(it.optString("id")).apply { mkdirs() } }
        val base = curBase()
        val legacy = try { JSONObject(File(dataRoot(), "versions/$base/$base.json").readText()).optJSONObject("arguments") == null } catch (_: Exception) { false }
        return File(dataRoot(), if (legacy) "game-$base" else "game")
    }

    internal fun selectedProfile(): String = prefs.getString("profile", VERSION) ?: VERSION

    // ---------- сборки: у каждой своя папка игры (моды, миры, настройки) и своя версия ----------
    private fun builds(): JSONArray = try { JSONArray(prefs.getString("builds", "[]")) } catch (_: Exception) { JSONArray() }
    private fun buildsSave(a: JSONArray) { prefs.edit().putString("builds", a.toString()).apply() }
    private fun buildDir(id: String) = File(dataRoot(), "builds/$id")
    private fun buildIcon(id: String) = File(dataRoot(), "build-icons/$id.png")
    private var iconPick: ((android.graphics.Bitmap) -> Unit)? = null

    /** Выбранная сборка. Если после неё сменили версию игры, сборка считается снятой. */
    internal fun activeBuild(): JSONObject? {
        val id = prefs.getString("build", "") ?: ""
        if (id.isEmpty()) return null
        val a = builds()
        for (i in 0 until a.length()) {
            val b = a.getJSONObject(i)
            if (b.optString("id") == id) return if (b.optString("profile") == selectedProfile()) b else null
        }
        return null
    }

    internal fun verTitle(): String = activeBuild()?.let { tr("Сборка: ", "Build: ") + it.optString("name") }
        ?: (tr("Версия: ", "Version: ") + prettyProfile(selectedProfile()))

    /** Базовая версия игры выбранного профиля (для Fabric/Forge берётся из inheritsFrom). */
    internal fun curBase(): String {
        val id = catBuild?.optString("profile") ?: selectedProfile()
        return try {
            val j = JSONObject(File(dataRoot(), "versions/$id/$id.json").readText())
            j.optString("inheritsFrom", "").ifEmpty { j.optString("jar", "") }.ifEmpty { id }
        } catch (_: Exception) { id }
    }

    internal fun prettyProfile(id: String): String = when {
        id.startsWith("quilt-loader-") -> "Quilt " + id.substringAfterLast("-") + " (" + id.removePrefix("quilt-loader-").substringBeforeLast("-") + ")"
        id.startsWith("neoforge-") -> "NeoForge " + (neoMc(id.removePrefix("neoforge-")) ?: "") + " (" + id.removePrefix("neoforge-") + ")"
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
        if (play) {
            prefs.edit().putBoolean("playedOnce", true).apply()
            runOnUiThread { setupHint?.visibility = View.GONE }
        }
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
            (root.listFiles()?.toList().orEmpty() + File(root, "builds").listFiles()?.toList().orEmpty()).filter { it.isDirectory && (it.name.startsWith("game") || it.parentFile?.name == "builds") }.forEach { d ->
                val cr = File(d, "crash-reports").listFiles()?.maxByOrNull { it.lastModified() }
                if (cr != null && System.currentTimeMillis() - cr.lastModified() < 30 * 60 * 1000) {
                    log("=== Отчёт о крэше ${d.name}/${cr.name} ===")
                    cr.readLines().take(45).forEach { log("  $it") }
                }
            }
        } catch (_: Exception) {}
        (root.listFiles()?.toList().orEmpty() + File(root, "builds").listFiles()?.toList().orEmpty()).filter { it.isDirectory && (it.name == "game" || it.name.startsWith("game-") || it.parentFile?.name == "builds") }.forEach { d ->
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
        applyMipmap(gameDir, oldGl)
        applyPreset(gameDir, baseVer)
        val modLoader = when { profile.startsWith("fabric-loader-") -> "fabric"; profile.startsWith("quilt-loader-") -> "quilt"; profile.startsWith("neoforge-") -> "neoforge"; profile.contains("forge", true) -> "forge"; else -> null }
        syncMods(gameDir, modLoader)
        autoUpdateMods(gameDir, modLoader, baseVer)
        if (preAwt) log("Очень старая версия (до 1.6): слой LWJGL 2 + запуск без окна AWT")
        else if (legacy) log("Старая версия (формат запуска до 1.13): слой LWJGL 2 + gl4es")
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
            // Forge 1.17+: ванильный jar игры не должен попасть в модульный слой, иначе «Module minecraft contains package …»
            jvmArgs = collectArgs(args.optJSONArray("jvm"), sub).map {
                if (it.startsWith("-DignoreList=") && !it.contains("$baseVer.jar")) "$it,$baseVer.jar" else it
            }
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
        val mainName = if (preAwt) "dev.mobilix.OldMain" else realMain
        cmd.add(mainName)
        if (preAwt) cmd.add(realMain)
        cmd.addAll(gameArgs)
        File(root, "launch_command.txt").writeText(cmd.joinToString("\n"))
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
            if (prefs.getBoolean("worldBackup", true)) WorldBackup.begin(dataRoot(), curGameDir())
            startActivity(Intent(this, GameActivity::class.java).putExtra("mode", "game"))
        }
    }

    // ---------- быстрые настройки графики ----------
    private class Preset(
        val ru: String, val en: String, val render: Int, val fps: Int, val chunks: Int,
        val ao: Boolean, val fancy: Boolean, val mip: Int, val vbo: Boolean, val clouds: Boolean, val particles: Int
    )

    private val presets = listOf(
        Preset("Минимальные", "Minimal", 30, 30, 2, false, false, 0, false, false, 2),
        Preset("Низкие", "Low", 45, 45, 6, false, false, 0, false, false, 2),
        Preset("Средние", "Medium", 70, 60, 12, true, false, 2, false, true, 1),
        Preset("Высокие", "High", 100, 0, 28, true, true, 4, true, true, 0)
    )

    private fun mcMinor(ver: String): Int = ver.split('.').getOrNull(1)?.toIntOrNull() ?: 12

    /** Выбор пресета: настройки лаунчера меняются сразу, options.txt — при ближайшем запуске каждой версии (один раз). */
    private fun pickPreset(i: Int) {
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

    /** Записывает выбранный пресет в options.txt этой папки игры один раз; дальше игрок может менять параметры сам. */
    /** Какие загрузчики поддерживает мод (по файлам описания внутри jar). */
    private fun modLoaders(jar: File): Set<String> = try {
        java.util.zip.ZipFile(jar).use { z ->
            val r = HashSet<String>()
            if (z.getEntry("fabric.mod.json") != null) r.add("fabric")
            if (z.getEntry("quilt.mod.json") != null) { r.add("quilt"); r.add("fabric") }
            if (z.getEntry("META-INF/neoforge.mods.toml") != null) r.add("neoforge")
            if (z.getEntry("META-INF/mods.toml") != null || z.getEntry("mcmod.info") != null) r.add("forge")
            r
        }
    } catch (_: Exception) { emptySet() }

    /** Моды не под текущий загрузчик уходят в mods-disabled, подходящие возвращаются обратно: чужой мод не должен ломать запуск. */
    private fun syncMods(gameDir: File, loader: String?) {
        if (loader == null) return
        try {
            val mods = File(gameDir, "mods"); val off = File(gameDir, "mods-disabled")
            val moved = ArrayList<String>()
            mods.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.forEach { f ->
                val ls = modLoaders(f)
                if (ls.isNotEmpty() && !ls.contains(loader) && !(loader == "quilt" && ls.contains("fabric"))) { off.mkdirs(); if (f.renameTo(File(off, f.name))) moved.add(f.name) }
            }
            off.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.forEach { f ->
                val lz = modLoaders(f)
                if (lz.contains(loader) || (loader == "quilt" && lz.contains("fabric"))) { mods.mkdirs(); f.renameTo(File(mods, f.name)) }
            }
            if (moved.isNotEmpty()) {
                log("Моды не под $loader отключены (папка mods-disabled): " + moved.joinToString(", "))
                runOnUiThread { Toast.makeText(this, tr("Отключено модов не под этот загрузчик: ${moved.size}", "Mods for another loader disabled: ${moved.size}"), Toast.LENGTH_LONG).show() }
            }
        } catch (e: Throwable) { log("Проверка загрузчиков модов: $e") }
    }

    /** Подтягивает файлы скачанных модов под текущую версию игры и загрузчик, докачивает обязательные зависимости, предупреждает о нехватке. */
    private fun autoUpdateMods(gameDir: File, loader: String?, baseVer: String) {
        if (loader == null || !prefs.getBoolean("modAuto", true)) return
        val t0 = System.currentTimeMillis()
        try {
            val index = idx()
            val ids = ArrayList<String>(); val keys = index.keys(); while (keys.hasNext()) ids.add(keys.next())
            fun enc(x: String) = java.net.URLEncoder.encode(x, "UTF-8")
            fun versionsFor(id: String) = JSONArray(apiGet("/project/$id/version?game_versions=" + enc("[\"$baseVer\"]") + "&loaders=" + enc(JSONArray(loaderCats(loader)).toString())))
            fun fetch(id: String, v: JSONObject, title: String): String? {
                val files = v.getJSONArray("files"); var f = files.getJSONObject(0)
                for (i in 0 until files.length()) if (files.getJSONObject(i).optBoolean("primary")) f = files.getJSONObject(i)
                val name = f.getString("filename")
                val dest = File(gameDir, "mods/$name")
                if (!dest.exists() && !download(f.getString("url"), dest, f.optJSONObject("hashes")?.optString("sha1")?.ifEmpty { null })) return null
                index.put(id, JSONObject().put("t", "mod").put("f", name).put("n", title).put("l", loader).put("v", baseVer))
                return name
            }
            var updated = 0; var added = 0
            val noVersion = ArrayList<String>(); val noDeps = ArrayList<String>()
            for (id in ids.take(40)) {
                if (System.currentTimeMillis() - t0 > 40000) { log("Автообновление модов: остановлено по времени"); break }
                val rec = index.optJSONObject(id) ?: continue
                if (rec.optString("t") != "mod") continue
                val cur = File(gameDir, "mods/" + rec.optString("f"))
                if (!cur.exists()) continue
                val title = rec.optString("n", id)
                try {
                    val vers = versionsFor(id)
                    if (vers.length() == 0) { noVersion.add(title); continue }
                    val v = vers.getJSONObject(0)
                    val old = rec.optString("f")
                    val nn = fetch(id, v, title)
                    if (nn != null && nn != old) { cur.delete(); updated++ }
                    val deps = v.optJSONArray("dependencies")
                    if (deps != null) for (k in 0 until deps.length()) {
                        val d = deps.getJSONObject(k)
                        val pid = d.optString("project_id", "")
                        if (d.optString("dependency_type") != "required" || pid.isEmpty()) continue
                        val have = index.optJSONObject(pid)
                        if (have != null && File(gameDir, "mods/" + have.optString("f")).exists()) continue
                        try {
                            val dv = versionsFor(pid)
                            if (dv.length() == 0) { noDeps.add(title); continue }
                            val pt = try { JSONObject(apiGet("/project/$pid")).optString("title", pid) } catch (_: Exception) { pid }
                            if (fetch(pid, dv.getJSONObject(0), pt) != null) added++ else noDeps.add("$pt ($title)")
                        } catch (_: Exception) { noDeps.add(title) }
                    }
                } catch (_: Exception) {}
            }
            idxSave(index)
            if (updated > 0 || added > 0) log("Автообновление модов: обновлено $updated, докачано зависимостей $added")
            val warn = ArrayList<String>()
            if (noVersion.isNotEmpty()) warn.add(tr("нет версии под $baseVer/$loader: ", "no build for $baseVer/$loader: ") + noVersion.joinToString(", "))
            if (noDeps.isNotEmpty()) warn.add(tr("не хватает зависимостей: ", "missing dependencies: ") + noDeps.joinToString(", "))
            if (warn.isNotEmpty() || updated > 0 || added > 0) {
                val msg = (if (updated + added > 0) tr("Моды обновлены: $updated, зависимостей: $added. ", "Mods updated: $updated, dependencies: $added. ") else "") +
                    warn.joinToString(". ") + (if (warn.isNotEmpty()) tr(". Автообновление можно отключить в Настройках.", ". You can turn auto-update off in Settings.") else "")
                log("Автообновление модов: $msg")
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }
        } catch (e: Throwable) { log("Автообновление модов: $e") }
    }

    private fun applyPreset(gameDir: File, ver: String) {
        try {
            val id = prefs.getInt("preset", 0)
            if (id <= 0 || id > presets.size) return
            val stamp = prefs.getInt("presetStamp", 0).toString()
            val marker = "presetApplied:" + gameDir.name
            if (prefs.getString(marker, "") == stamp) return
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
            kv["entityShadows"] = pr.fancy.toString()
            if (minor >= 15) kv["biomeBlendRadius"] = if (pr.fancy) "2" else if (pr.ao) "1" else "0"
            val opt = File(gameDir, "options.txt")
            val keep = if (opt.exists()) opt.readLines().filter { l -> kv.keys.none { l.startsWith("$it:") } } else emptyList()
            opt.writeText((keep + kv.map { "${it.key}:${it.value}" }).joinToString("\n") + "\n")
            prefs.edit().putString(marker, stamp).apply()
            log("Быстрые настройки графики: ${pr.ru} (${gameDir.name})")
        } catch (_: Throwable) {}
    }

    /**
     * Уровень мипмапов в options.txt. Число из настроек ставится при каждом запуске.
     * «Авто»: видеочип берём из game.log прошлого запуска; для Adreno 6xx и старше (медленная загрузка текстур)
     * один раз ставим 0 для этой папки игры, потом выбор игрока в самой игре не трогаем.
     */
    private fun applyMipmap(gameDir: File, oldGl: Boolean) {
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
    /** Файлы, которые постпроцессоры установщика Forge 1.13+ должны создать (srg, extra, пропатченный клиент); пусто, если всё на месте. */
    private fun forgeMissingOutputs(installer: File, root: File, profile: String? = null): List<String> {
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
    private fun installerWithoutHashes(src: File): File {
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

    private fun installerHeapMb(): Int {
        val mi = android.app.ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        return if (mi.totalMem / 1024 / 1024 >= 6000) 1280 else 768
    }

    private fun runForgeInstaller(rt: File, installer: File, root: File, installFlag: String = "--installClient") {
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
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
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

    /** Версии Forge для игры $mc, от лучшей к худшей: рекомендуемая, последняя, затем свежие из maven-metadata и BMCLAPI. */
    private fun forgeCandidates(mc: String): List<String> {
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
    private fun mirrorsFor(url: String): List<String> {
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
