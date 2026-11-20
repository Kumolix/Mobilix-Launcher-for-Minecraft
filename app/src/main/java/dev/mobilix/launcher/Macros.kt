package dev.mobilix.launcher

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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

    fun edit(m: Macro?, refresh: () -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(4)) }
        val name = EditText(this).apply { setText(m?.name ?: ""); hint = tr("Название", "Name"); setSingleLine(true); setTextColor(Th.text) }
        val body = EditText(this).apply {
            setText(m?.text ?: ""); hint = tr("Например: f w50 1 w50 f", "E.g.: f w50 1 w50 f"); setTextColor(Th.text)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3
        }
        box.addView(name); box.addView(body)
        box.addView(TextView(this).apply {
            text = tr("Слова через пробел: буквы и цифры (f, 1), f1–f12, space, esc, enter, tab, shift, ctrl, alt, up/down/left/right, lmb/rmb/mmb (мышь), wheelup/wheeldown. " +
                "w100 — пауза 100 мс, +shift — зажать, -shift — отпустить. Пример свапа: f w50 1 w50 f.",
                "Words separated by spaces: letters and digits (f, 1), f1-f12, space, esc, enter, tab, shift, ctrl, alt, up/down/left/right, lmb/rmb/mmb (mouse), wheelup/wheeldown. " +
                "w100 is a 100 ms pause, +shift holds, -shift releases. Swap example: f w50 1 w50 f.")
            textSize = 12f; setTextColor(Th.dim); setPadding(0, dp(8), 0, 0)
        })
        MobDialog(this).setTitle(if (m == null) tr("Новый макрос", "New macro") else tr("Макрос", "Macro")).setView(box)
            .setPositiveButton(tr("Сохранить", "Save")) { _, _ ->
                val all = Macros.load(this)
                val n = name.text.toString().trim().ifEmpty { tr("Макрос", "Macro") + " " + (all.size + 1) }
                val t = body.text.toString().trim()
                if (m == null) all.add(Macro(Macros.nextId(all), n, t)) else { m.name = n; m.text = t }
                Macros.save(this, all)
                val bad = Macros.unknown(t)
                if (bad.isNotEmpty()) android.widget.Toast.makeText(this, tr("Не распознано (пропущено): ", "Not recognized (skipped): ") + bad.joinToString(" "), android.widget.Toast.LENGTH_LONG).show()
                refresh()
            }
            .apply { if (m != null) setNeutralButton(tr("Удалить", "Delete")) { _, _ -> Macros.save(this@macrosSection, Macros.load(this@macrosSection).filter { it.id != m.id }); refresh() } }
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .show()
    }

    fun refresh() {
        list.removeAllViews()
        Macros.load(this).forEach { m ->
            list.addView(Button(this).apply {
                text = m.name + (if (m.text.isNotEmpty()) "   " + m.text.take(28) else ""); isAllCaps = false; gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setTextColor(Th.chipText); background = sel(Th.chip); setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { edit(m) { refresh() } }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })
        }
        list.addView(Button(this).apply {
            text = tr("+ Новый макрос", "+ New macro"); isAllCaps = false; setTextColor(Th.chipText); background = sel(Th.act)
            setOnClickListener { edit(null) { refresh() } }
        }, LinearLayout.LayoutParams(dp(200), dp(42)).apply { topMargin = dp(8) })
    }
    refresh()
}
