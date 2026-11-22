package dev.mobilix.launcher

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Макрос: именованная последовательность нажатий (например, быстрая смена предмета в руках). */
class Macro(val id: Int, var name: String, var text: String)

/**
 * Хранилище и разбор макросов. Лежат в файле macros.json в папке приложения: его читают и лаунчер, и процесс игры.
 * Запись: слова через пробел. `F` — нажать и отпустить, `+Shift` — зажать, `-Shift` — отпустить, `w100` — пауза 100 мс.
 */
object Macros {
    private var appCtx: Context? = null
    private var cache: MutableList<Macro>? = null

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "macros.json")

    @Synchronized fun load(ctx: Context): MutableList<Macro> {
        appCtx = ctx.applicationContext
        cache?.let { return it }
        val out = ArrayList<Macro>()
        try {
            val a = JSONArray(file(ctx).readText())
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(Macro(o.getInt("id"), o.optString("n"), o.optString("t"))) }
        } catch (_: Exception) {}
        cache = out
        return out
    }

    @Synchronized fun save(ctx: Context, list: List<Macro>) {
        cache = list.toMutableList()
        try {
            val a = JSONArray()
            list.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("t", it.text)) }
            file(ctx).writeText(a.toString())
        } catch (_: Exception) {}
    }

    fun find(ctx: Context, id: Int): Macro? = load(ctx).firstOrNull { it.id == id }
    fun nextId(list: List<Macro>) = (list.maxOfOrNull { it.id } ?: 0) + 1
    fun nameOf(id: Int): String = appCtx?.let { find(it, id)?.name } ?: "?"

    private val NAMED = mapOf(
        "lmb" to (K_MOUSE to 0), "rmb" to (K_MOUSE to 1), "mmb" to (K_MOUSE to 2),
        "wheelup" to (K_SCROLL to 1), "wheeldown" to (K_SCROLL to -1),
        "space" to (K_KEY to 32), "esc" to (K_KEY to 256), "enter" to (K_KEY to 257), "tab" to (K_KEY to 258),
        "shift" to (K_KEY to 340), "ctrl" to (K_KEY to 341), "alt" to (K_KEY to 342),
        "up" to (K_KEY to 265), "down" to (K_KEY to 264), "left" to (K_KEY to 263), "right" to (K_KEY to 262),
        "pgup" to (K_KEY to 266), "pgdn" to (K_KEY to 267), "/" to (K_KEY to 47)
    )

    private fun key(t: String): Pair<Int, Int>? {
        NAMED[t]?.let { return it }
        if (t.length == 1 && t[0] in 'a'..'z') return K_KEY to t[0].uppercaseChar().code
        if (t.length == 1 && t[0] in '0'..'9') return K_KEY to t[0].code
        if (t.length in 2..3 && t[0] == 'f') t.drop(1).toIntOrNull()?.let { if (it in 1..12) return K_KEY to (289 + it) }
        return null
    }

    /** Шаг: [тип, код, действие], действие: 0 — нажать и отпустить, 1 — зажать, 2 — отпустить, 3 — пауза (код — миллисекунды). */
    fun parse(text: String): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (raw in text.lowercase().split(Regex("[\\s,;]+"))) {
            if (raw.isEmpty()) continue
            val wait = Regex("^(?:wait|w)(\\d{1,5})$").find(raw)
            if (wait != null) { out.add(intArrayOf(0, wait.groupValues[1].toInt().coerceAtMost(10000), 3)); continue }
            val act = when { raw.startsWith("+") -> 1; raw.startsWith("-") && raw.length > 1 -> 2; else -> 0 }
            val k = key(if (act == 0) raw else raw.drop(1)) ?: continue
            out.add(intArrayOf(k.first, k.second, act))
        }
        return out
    }


    /** Слово записи для клавиши/кнопки (обратное к разбору). */
    fun token(kind: Int, code: Int): String? =
        NAMED.entries.firstOrNull { it.value.first == kind && it.value.second == code }?.key
            ?: when {
                kind == K_KEY && code in 65..90 -> code.toChar().lowercase()
                kind == K_KEY && code in 48..57 -> code.toChar().toString()
                kind == K_KEY && code in 290..301 -> "f" + (code - 289)
                else -> null
            }

    private val NAMES_RU = mapOf("lmb" to "ЛКМ", "rmb" to "ПКМ", "mmb" to "СКМ", "wheelup" to "Колесо ↑", "wheeldown" to "Колесо ↓",
        "space" to "Пробел", "up" to "↑", "down" to "↓", "left" to "←", "right" to "→", "pgup" to "PgUp", "pgdn" to "PgDn")

    /** Подпись плитки. */
    fun label(kind: Int, code: Int): String { val t = token(kind, code) ?: return "?"; return NAMES_RU[t] ?: (if (t.length <= 3) t.uppercase() else t.replaceFirstChar { it.uppercase() }) }

    /** Все клавиши для выбора. */
    val ALL: List<Pair<Int, Int>> by lazy {
        val l = ArrayList<Pair<Int, Int>>()
        for (c in 'A'..'Z') l.add(K_KEY to c.code)
        for (c in '0'..'9') l.add(K_KEY to c.code)
        for (i in 1..12) l.add(K_KEY to 289 + i)
        listOf("space", "shift", "ctrl", "alt", "tab", "enter", "esc", "up", "down", "left", "right", "lmb", "rmb", "mmb", "wheelup", "wheeldown").forEach { NAMED[it]?.let { p -> l.add(p) } }
        l
    }

    fun serialize(steps: List<IntArray>): String = steps.mapNotNull { s ->
        if (s[2] == 3) "w" + s[1] else token(s[0], s[1])?.let { (when (s[2]) { 1 -> "+"; 2 -> "-"; else -> "" }) + it }
    }.joinToString(" ")

    /** Длительность макроса, мс (как при настоящем проигрывании). */
    fun duration(steps: List<IntArray>): Int = steps.sumOf { when (it[2]) { 3 -> it[1]; 0 -> 60; else -> 20 } }

    /** Заготовки: название, запись. */
    val TEMPLATES = listOf(
        Triple("Свап: рука ↔ слот 2", "f w50 2 w50 f", ""),
        Triple("Съесть еду из слота 2 и вернуть слот 1", "2 w50 +rmb w1700 -rmb w50 1", ""),
        Triple("Бросить весь стак", "+ctrl w30 q w30 -ctrl", ""),
        Triple("Блок щитом 0,3 с", "+rmb w300 -rmb", ""),
        Triple("Двойной удар", "lmb w80 lmb", ""),
        Triple("Пустой макрос", "", ""))

    /** Слова, которые не распознаны (для подсказки при сохранении). */
    fun unknown(text: String): List<String> = text.lowercase().split(Regex("[\\s,;]+")).filter { w ->
        val core = if (w.startsWith("+") || (w.startsWith("-") && w.length > 1)) w.drop(1) else w
        w.isNotEmpty() && !Regex("^(?:wait|w)\\d{1,5}$").matches(w) && key(core) == null
    }
}

/** Раздел «Макросы» в самом конце настроек. */
internal fun MainActivity.macrosSection(col: LinearLayout) {
    col.addView(TextView(this).apply {
        text = tr("Макросы", "Macros"); textSize = 15f; setTextColor(Th.accent)
        typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, dp(18), 0, 0)
    })
    col.addView(TextView(this).apply {
        text = tr("Последовательность нажатий на одну кнопку, например быстрая смена предмета. Готовый макрос назначается кнопке в редакторе управления: Назначить клавишу → Макрос.",
            "A sequence of key presses on one button, e.g. a quick item swap. Assign a finished macro to a button in the controls editor: Assign key → Macro.")
        textSize = 12f; setTextColor(Th.dim); setPadding(0, dp(6), 0, 0)
    })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(list)

    fun refresh() {
        list.removeAllViews()
        Macros.load(this).forEach { m ->
            list.addView(Button(this).apply {
                text = m.name + (if (m.text.isNotEmpty()) "   " + m.text.take(28) else ""); isAllCaps = false; gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setTextColor(Th.chipText); background = sel(Th.chip); setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { macroEditor(m) { refresh() } }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })
        }
        list.addView(Button(this).apply {
            text = tr("+ Новый макрос", "+ New macro"); isAllCaps = false; setTextColor(Th.chipText); background = sel(Th.act)
            setOnClickListener {
                MobDialog(this@macrosSection).setTitle(tr("Новый макрос", "New macro"))
                    .setItems(Macros.TEMPLATES.map { it.first }.toTypedArray()) { _, i -> val t = Macros.TEMPLATES[i]; macroEditor(null, if (t.second.isEmpty()) "" else t.first, t.second) { refresh() } }
                    .setNegativeButton(tr("Отмена", "Cancel"), null).show()
            }
        }, LinearLayout.LayoutParams(dp(200), dp(42)).apply { topMargin = dp(8) })
    }
    refresh()
}


/** Редактор макроса: плитки шагов, запись нажатиями, заготовки и пробный прогон. */
internal fun MainActivity.macroEditor(m: Macro?, presetName: String = "", presetText: String = "", refresh: () -> Unit) {
    val steps = ArrayList<IntArray>(Macros.parse(m?.text ?: presetText))
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)) }
    val name = EditText(this).apply { setText(m?.name ?: presetName); hint = tr("Название", "Name"); setSingleLine(true); setTextColor(Th.text) }
    root.addView(name)
    val tilesScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(6)) }
    tilesScroll.addView(tiles)
    root.addView(tilesScroll)
    val info = TextView(this).apply { textSize = 12f; setTextColor(Th.dim) }
    root.addView(info)
    var active = -1
    var rebuild: () -> Unit = {}
    var tileMenu: (Int) -> Unit = {}

    fun tileText(s: IntArray) = if (s[2] == 3) "⏱ ${s[1]} " + tr("мс", "ms") else (when (s[2]) { 1 -> "▼ "; 2 -> "▲ "; else -> "" }) + Macros.label(s[0], s[1])
    rebuild = {
        tiles.removeAllViews()
        if (steps.isEmpty()) tiles.addView(TextView(this).apply { text = tr("Пусто. Нажмите «● Записать» или «+ Клавиша».", "Empty. Tap “● Record” or “+ Key”."); setTextColor(Th.dim); textSize = 13f })
        steps.forEachIndexed { i, s ->
            tiles.addView(TextView(this).apply {
                text = tileText(s); textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(12), dp(10), dp(12), dp(10))
                setTextColor(Th.chipText); background = rr(if (i == active) Th.accent else if (s[2] == 3) Th.chip2 else Th.chip, 10)
                setOnClickListener { tileMenu(i) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        val ms = Macros.duration(steps)
        info.text = tr("Шагов: ${steps.size}, длительность ≈ ${ms / 1000}.${(ms % 1000) / 100} с", "Steps: ${steps.size}, duration ≈ ${ms / 1000}.${(ms % 1000) / 100} s")
    }

    fun pickKey(onPick: (Pair<Int, Int>) -> Unit) {
        MobDialog(this).setTitle(tr("Клавиша", "Key"))
            .setItems(Macros.ALL.map { Macros.label(it.first, it.second) }.toTypedArray()) { _, i -> onPick(Macros.ALL[i]) }
            .setNegativeButton(tr("Отмена", "Cancel"), null).show()
    }
    fun askWait(cur: Int, onOk: (Int) -> Unit) {
        val et = EditText(this).apply { setText(cur.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(Th.text); setSingleLine(true) }
        val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        MobDialog(this).setTitle(tr("Пауза, мс", "Pause, ms")).setView(b)
            .setPositiveButton(tr("Готово", "Done")) { _, _ -> onOk((et.text.toString().toIntOrNull() ?: cur).coerceIn(10, 10000)) }
            .setNegativeButton(tr("Отмена", "Cancel"), null).show()
    }
    fun move(i: Int, d: Int) { val j = i + d; if (j in steps.indices) { val t = steps[i]; steps[i] = steps[j]; steps[j] = t; rebuild() } }
    tileMenu = menu@{ i ->
        val s = steps[i]
        val acts = ArrayList<Pair<String, () -> Unit>>()
        if (s[2] == 3) acts.add(tr("Изменить паузу", "Change pause") to { askWait(s[1]) { v -> s[1] = v; rebuild() } })
        else {
            acts.add(tr("Сменить клавишу", "Change key") to { pickKey { k -> s[0] = k.first; s[1] = k.second; rebuild() } })
            acts.add(tr("Режим: ${when (s[2]) { 1 -> "зажать"; 2 -> "отпустить"; else -> "нажать и отпустить" }} → сменить", "Mode: ${when (s[2]) { 1 -> "hold"; 2 -> "release"; else -> "tap" }} → change") to {
                MobDialog(this).setTitle(tr("Режим", "Mode"))
                    .setItems(arrayOf(tr("Нажать и отпустить", "Tap"), tr("Зажать", "Hold"), tr("Отпустить", "Release"))) { _, k -> s[2] = k; rebuild() }.show()
            })
        }
        acts.add(tr("← Влево", "← Left") to { move(i, -1) })
        acts.add(tr("Вправо →", "Right →") to { move(i, 1) })
        acts.add(tr("Удалить", "Delete") to { steps.removeAt(i); rebuild() })
        MobDialog(this).setTitle(tileText(s)).setItems(acts.map { it.first }.toTypedArray()) { _, k -> acts[k].second() }.show()
    }

    // ---- запись нажатиями ----
    var lastT = 0L
    val recPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(6), 0, dp(4)) }
    recPanel.addView(TextView(this).apply { text = tr("● Запись: нажимайте кнопки — порядок и паузы запомнятся.", "● Recording: tap the keys — order and pauses are remembered."); textSize = 12f; setTextColor(Th.accent) })
    fun addStep(k: Pair<Int, Int>, timed: Boolean) {
        if (timed) {
            val now = android.os.SystemClock.uptimeMillis()
            if (lastT > 0) { val gap = ((now - lastT - 60).coerceIn(0, 2000) / 10 * 10).toInt(); if (gap >= 20) steps.add(intArrayOf(0, gap, 3)) }
            lastT = now
        }
        steps.add(intArrayOf(k.first, k.second, 0)); rebuild()
        tilesScroll.post { tilesScroll.fullScroll(View.FOCUS_RIGHT) }
    }
    fun keyOf(t: String): Pair<Int, Int> = Macros.parse(t).first().let { it[0] to it[1] }
    val pad = listOf(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9"), listOf("f", "e", "q", "r", "t", "f5"), listOf("space", "shift", "ctrl", "lmb", "rmb"), listOf("wheelup", "wheeldown"))
    for (row in pad) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in row) {
            val k = keyOf(t)
            r.addView(TextView(this).apply {
                text = Macros.label(k.first, k.second); textSize = 13f; gravity = Gravity.CENTER; setTextColor(Th.chipText); background = sel(Th.chip2)
                setOnClickListener { addStep(k, true) }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { margin(dp(2)) })
        }
        recPanel.addView(r)
    }
    recPanel.addView(TextView(this).apply {
        text = tr("Другие клавиши…", "More keys…"); textSize = 13f; gravity = Gravity.CENTER; setTextColor(Th.chipText); background = sel(Th.chip2)
        setOnClickListener { pickKey { k -> addStep(k, true) } }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { margin(dp(2)) })

    // ---- пробный прогон ----
    var running = false
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    fun runStep(i: Int) {
        if (!running || i >= steps.size) { running = false; active = -1; rebuild(); return }
        active = i; rebuild()
        tiles.getChildAt(i)?.let { c -> tilesScroll.post { tilesScroll.smoothScrollTo((c.left - dp(40)).coerceAtLeast(0), 0) } }
        val s = steps[i]
        handler.postDelayed({ runStep(i + 1) }, if (s[2] == 3) s[1].coerceAtMost(800).toLong() else 160L)
    }

    fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Th.chipText); background = sel(Th.chip); textSize = 13f; setOnClickListener { onClick() }
    }
    val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    row1.addView(btn(tr("+ Клавиша", "+ Key")) { pickKey { k -> addStep(k, false) } }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { margin(dp(2)) })
    row1.addView(btn(tr("+ Пауза", "+ Pause")) { steps.add(intArrayOf(0, 50, 3)); rebuild() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { margin(dp(2)) })
    val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    lateinit var recBtn: Button
    recBtn = btn(tr("● Записать", "● Record")) {
        val on = recPanel.visibility != View.VISIBLE
        recPanel.visibility = if (on) View.VISIBLE else View.GONE; lastT = 0L
        recBtn.text = if (on) tr("■ Готово", "■ Stop") else tr("● Записать", "● Record")
    }
    row2.addView(recBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { margin(dp(2)) })
    row2.addView(btn(tr("▶ Проверить", "▶ Test")) { if (running) { running = false } else if (steps.isNotEmpty()) { running = true; runStep(0) } }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { margin(dp(2)) })
    root.addView(row1); root.addView(row2); root.addView(recPanel)
    root.addView(TextView(this).apply {
        text = tr("Нажмите плитку, чтобы изменить, сдвинуть или удалить шаг. «Проверить» показывает порядок и время шагов; в игре макрос выполняет кнопка, назначенная в редакторе управления.",
            "Tap a tile to edit, move or delete a step. “Test” shows the order and timing of the steps; in the game the macro runs from a button assigned in the controls editor.")
        textSize = 12f; setTextColor(Th.dim); setPadding(0, dp(8), 0, 0)
    })
    rebuild()
    val sv = ScrollView(this).apply { addView(root) }
    val dlg = MobDialog(this).setTitle(if (m == null) tr("Новый макрос", "New macro") else tr("Макрос", "Macro")).setView(sv)
        .setPositiveButton(tr("Сохранить", "Save")) { _, _ ->
            running = false
            val all = Macros.load(this)
            val n = name.text.toString().trim().ifEmpty { tr("Макрос", "Macro") + " " + (all.size + 1) }
            val t = Macros.serialize(steps)
            if (m == null) all.add(Macro(Macros.nextId(all), n, t)) else { m.name = n; m.text = t }
            Macros.save(this, all); refresh()
        }
        .setNegativeButton(tr("Отмена", "Cancel")) { _, _ -> running = false }
        .setOnCancelListener { running = false }
    if (m != null) dlg.setNeutralButton(tr("Удалить", "Delete")) { _, _ -> running = false; Macros.save(this, Macros.load(this).filter { it.id != m.id }); refresh() }
    dlg.show()
}

private fun LinearLayout.LayoutParams.margin(v: Int) { setMargins(v, v, v, v) }
