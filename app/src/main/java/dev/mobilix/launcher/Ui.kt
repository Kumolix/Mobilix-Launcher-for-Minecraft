package dev.mobilix.launcher

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Три полоски: при progress=1 повёрнуты в вертикальное положение. */
class HamburgerView(ctx: Context) : View(ctx) {
    var progress = 0f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Th.act }   // цвет как у ненажатой кнопки «Играть»
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f; val cy = height / 2f
        c.save()
        c.rotate(90f * progress, cx, cy)
        for (i in -1..1) {
            val y = cy + i * 7f * d
            c.drawRoundRect(RectF(cx - 12f * d, y - 1.5f * d, cx + 12f * d, y + 1.5f * d), 2f * d, 2f * d, p)
        }
        c.restore()
    }
}

/** Логотип Microsoft: четыре цветных квадрата. */
class MsLogoView(ctx: Context) : View(ctx) {
    private val p = Paint()
    override fun onDraw(c: Canvas) {
        val s = width / 2f - 1f; val g = 2f
        fun sq(x: Float, y: Float, col: Int) { p.color = col; c.drawRect(x, y, x + s, y + s, p) }
        sq(0f, 0f, 0xFFF25022.toInt()); sq(s + g, 0f, 0xFF7FBA00.toInt())
        sq(0f, s + g, 0xFF00A4EF.toInt()); sq(s + g, s + g, 0xFFFFB900.toInt())
    }
}

/** Цвета светлой/тёмной темы лаунчера (зелёный акцент в обеих). */
object Th {
    var light = false
    /** Пользовательская тема: ключ цвета -> ARGB; null = встроенные темы. */
    var custom: Map<String, Int>? = null
    /** true = старое зелёное оформление; false = новое графитовое. */
    var classic = false
    private fun c(d: Long, l: Long) = (if (light) l else d).toInt()
    private fun n(cd: Long, cl: Long, nd: Long, nl: Long) = c(if (classic) cd else nd, if (classic) cl else nl)
    val bg get() = custom?.get("bg") ?: n(0xFF0B0F0C, 0xFFF1F6F2, 0xFF0C0D0F, 0xFFF4F5F7)
    val bar get() = custom?.get("bar") ?: n(0xFF121A15, 0xFFE2EDE5, 0xFF141518, 0xFFE9EBEE)
    val drawer get() = custom?.get("drawer") ?: n(0xFF0F1712, 0xFFFFFFFF, 0xFF111214, 0xFFFFFFFF)
    val skinBg get() = custom?.get("skinBg") ?: n(0xFF0E1F14, 0xFFDCEBDF, 0xFF131519, 0xFFE3E6EA)
    val accent get() = custom?.get("accent") ?: n(0xFF43E06A, 0xFF1B7F3A, 0xFF4ADE80, 0xFF1B7F3A)
    val text get() = custom?.get("text") ?: n(0xFFFFFFFF, 0xFF10200F, 0xFFF2F4F7, 0xFF14171B)
    val text2 get() = custom?.get("text2") ?: n(0xFFB8E6C4, 0xFF2B4B33, 0xFFC9CED6, 0xFF2F353D)
    val dim get() = custom?.get("dim") ?: n(0xFF7FA08A, 0xFF5A7A62, 0xFF8A919C, 0xFF626A75)
    val line get() = custom?.get("line") ?: n(0xFF22332A, 0xFFCADBCE, 0xFF25282D, 0xFFD6DAE0)
    val line2 get() = custom?.get("line2") ?: n(0xFF1B2A20, 0xFFDCE8DF, 0xFF1B1D21, 0xFFE3E6EB)
    val chip get() = custom?.get("chip") ?: n(0xFF14301D, 0xFFCFE3D4, 0xFF24282E, 0xFFDADDE3)
    val chip2 get() = custom?.get("chip2") ?: n(0xFF0F2416, 0xFFBFD6C5, 0xFF1B1E23, 0xFFCBCFD6)
    val chipText get() = custom?.get("chipText") ?: if (light) 0xFF10200F.toInt() else 0xFFFFFFFF.toInt()
    val accentDk get() = custom?.get("accentDk") ?: n(0xFF1B6E34, 0xFF1B6E34, 0xFF1F7A45, 0xFF1F7A45)
    val altBg get() = custom?.get("altBg") ?: (if (light) (if (classic) 0xFF0B0F0C else 0xFF0C0D0F) else (if (classic) 0xFFF1F6F2 else 0xFFF4F5F7)).toInt()
    val altText get() = custom?.get("altText") ?: (if (light) 0xFF4ADE80 else 0xFF14171B).toInt()
    /** Зелёный цвет кнопок «Играть», выбранных вкладок и рамок: у встроенных тем прежний, у своих это «Акцент тёмный». */
    val act get() = custom?.get("accentDk") ?: 0xFF2EA84F.toInt()
    val hint get() = custom?.get("hint") ?: n(0xFF58705F, 0xFF8AA391, 0xFF5C636E, 0xFF8E96A1)

    val KEYS = listOf("bg", "bar", "drawer", "skinBg", "accent", "accentDk", "text", "text2", "dim", "hint", "line", "line2", "chip", "chip2", "chipText", "altBg", "altText")
    fun get(k: String): Int = when (k) {
        "bg" -> bg; "bar" -> bar; "drawer" -> drawer; "skinBg" -> skinBg; "accent" -> accent; "accentDk" -> accentDk
        "text" -> text; "text2" -> text2; "dim" -> dim; "hint" -> hint; "line" -> line; "line2" -> line2
        "chip" -> chip; "chip2" -> chip2; "chipText" -> chipText; "altBg" -> altBg; else -> altText
    }
    /** Цвета встроенной темы (без пользовательской). */
    fun builtin(lt: Boolean, cl: Boolean): LinkedHashMap<String, Int> {
        val ol = light; val oc = classic; val cu = custom
        custom = null; light = lt; classic = cl
        val m = LinkedHashMap<String, Int>(); for (k in KEYS) m[k] = get(k)
        light = ol; classic = oc; custom = cu
        return m
    }
}

/** Квадратная кнопка фильтров: три ползунка (вверх, вниз, вверх) с квадратными ручками и число включённых фильтров. */
class FilterView(ctx: Context) : View(ctx) {
    var count = 0
        set(v) { field = v; invalidate() }
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Th.chip }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Th.act }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE; color = Th.accent }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Th.accent }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    init { isClickable = true }
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()
        val box = RectF(1f * d, 1f * d, w - 1f * d, h - 1f * d)
        c.drawRect(box, back)
        frame.strokeWidth = 2f * d
        c.drawRect(box, frame)
        line.strokeWidth = 2f * d
        val xs = floatArrayOf(0.30f, 0.50f, 0.70f)
        val ys = floatArrayOf(0.38f, 0.64f, 0.38f)
        for (i in 0..2) {
            val x = w * xs[i]
            c.drawLine(x, h * 0.26f, x, h * 0.74f, line)
            val y = h * ys[i]
            c.drawRect(x - 3.6f * d, y - 2.6f * d, x + 3.6f * d, y + 2.6f * d, fill)   // квадратная ручка
        }
        if (count > 0) {
            val r = 7.5f * d
            c.drawRect(w - 2 * r, 0f, w, 2 * r, fill)
            num.textSize = 11f * d
            c.drawText(count.toString(), w - r, r + 4f * d, num)
        }
    }
}

private class MaxHeightScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }
}

/** Окно в стиле лаунчера вместо системного AlertDialog: тот же набор методов, зелёная рамка, плавное появление. */
class MobDialog(private val ctx: Context) {
    private class B(val text: CharSequence, val l: DialogInterface.OnClickListener?)
    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var content: View? = null
    private var items: Array<out CharSequence>? = null
    private var itemListener: DialogInterface.OnClickListener? = null
    private var pos: B? = null
    private var neg: B? = null
    private var neu: B? = null
    private var cancelListener: DialogInterface.OnCancelListener? = null
    private var cancelable = true

    fun setTitle(t: CharSequence?): MobDialog { title = t; return this }
    fun setMessage(m: CharSequence?): MobDialog { message = m; return this }
    fun setView(v: View?): MobDialog { content = v; return this }
    fun setItems(arr: Array<out CharSequence>, l: DialogInterface.OnClickListener?): MobDialog { items = arr; itemListener = l; return this }
    fun setPositiveButton(t: CharSequence, l: DialogInterface.OnClickListener?): MobDialog { pos = B(t, l); return this }
    fun setNegativeButton(t: CharSequence, l: DialogInterface.OnClickListener?): MobDialog { neg = B(t, l); return this }
    fun setNeutralButton(t: CharSequence, l: DialogInterface.OnClickListener?): MobDialog { neu = B(t, l); return this }
    fun setOnCancelListener(l: DialogInterface.OnCancelListener?): MobDialog { cancelListener = l; return this }
    fun setCancelable(c: Boolean): MobDialog { cancelable = c; return this }

    fun show(): Dialog {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dm = ctx.resources.displayMetrics
        val den = dm.density
        fun dp(v: Int) = (v * den).toInt()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = GradientDrawable().apply { setColor(Th.drawer); setStroke(dp(2), Th.act); cornerRadius = dp(6).toFloat() }
        }
        title?.let { t ->
            card.addView(TextView(ctx).apply { text = t; textSize = 19f; setTextColor(Th.accent); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(10)) })
        }
        message?.let { m ->
            card.addView(MaxHeightScroll(ctx, (dm.heightPixels * 0.55f).toInt()).apply {
                addView(TextView(ctx).apply { text = m; textSize = 14f; setTextColor(Th.text2); setLineSpacing(0f, 1.15f) })
            })
        }
        content?.let { v -> card.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        items?.let { arr ->
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            arr.forEachIndexed { i, label ->
                // строка, начинающаяся с \u0001, — заголовок раздела: зелёный отчерк и подпись, не нажимается
                if (label.startsWith("\u0001")) {
                    col.addView(View(ctx).apply { setBackgroundColor(Th.act) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(8) })
                    col.addView(TextView(ctx).apply { text = label.substring(1); textSize = 13f; setTextColor(0xFF8FD6A3.toInt()); setPadding(dp(10), dp(8), dp(10), dp(2)) })
                    return@forEachIndexed
                }
                if (i > 0 && !arr[i - 1].startsWith("\u0001")) col.addView(View(ctx).apply { setBackgroundColor(Th.line2) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                col.addView(TextView(ctx).apply {
                    text = label; textSize = 15f; setTextColor(Th.text); setPadding(dp(10), dp(12), dp(10), dp(12))
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Th.chip))
                        addState(intArrayOf(), ColorDrawable(0))
                    }
                    isClickable = true
                    setOnClickListener { d.dismiss(); itemListener?.onClick(d, i) }
                })
            }
            card.addView(MaxHeightScroll(ctx, (dm.heightPixels * 0.6f).toInt()).apply { addView(col) })
        }
        if (pos != null || neg != null || neu != null) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            fun button(b: B, which: Int, primary: Boolean) = TextView(ctx).apply {
                text = b.text; textSize = 14f; gravity = Gravity.CENTER; isClickable = true
                setTextColor(if (primary) Color.WHITE else Th.text2)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setBackgroundColor(if (primary) Th.act else Th.chip)
                setOnClickListener { d.dismiss(); b.l?.onClick(d, which) }
            }
            neu?.let { row.addView(button(it, DialogInterface.BUTTON_NEUTRAL, false)) }
            row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            neg?.let { row.addView(button(it, DialogInterface.BUTTON_NEGATIVE, false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }) }
            pos?.let { row.addView(button(it, DialogInterface.BUTTON_POSITIVE, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }) }
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }
        val w = minOf((dm.widthPixels * 0.9f).toInt(), dp(440))
        d.setContentView(card, ViewGroup.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        d.setCancelable(cancelable)
        d.setCanceledOnTouchOutside(cancelable)
        d.setOnCancelListener(cancelListener)
        card.alpha = 0f; card.translationY = dp(24).toFloat()
        d.show()
        card.animate().alpha(1f).translationY(0f).setDuration(180).start()
        return d
    }
}


/** Пиксельная иконка из строк-символов: масштаб целый, без сглаживания. */
class PixelIcon(ctx: Context, private val rows: List<String>, var pal: Map<Char, Int>) : View(ctx) {
    private val p = Paint()
    /** Палитра для нажатого состояния (иконки меню: нажатая темнее) и признак нажатия. */
    var palDown: Map<Char, Int>? = null
    var down = false
        set(v) { if (field != v) { field = v; invalidate() } }
    override fun onDraw(c: Canvas) {
        val pal = if (down) (palDown ?: pal) else pal
        val h = rows.size; val w = rows.maxOf { it.length }
        val s = minOf(width / w, height / h).coerceAtLeast(1)
        val ox = (width - w * s) / 2; val oy = (height - h * s) / 2
        for (y in rows.indices) for (x in rows[y].indices) {
            val col = pal[rows[y][x]] ?: continue
            p.color = col
            c.drawRect((ox + x * s).toFloat(), (oy + y * s).toFloat(), (ox + (x + 1) * s).toFloat(), (oy + (y + 1) * s).toFloat(), p)
        }
    }

    companion object {
        val PENCIL = listOf(".......pp", "......mpp", ".....Ymm.", "....YyY..", "...YyY...", "..YyY....", "..wY.....", ".k.......", ".........")
        val PENCIL_PAL = mapOf('p' to 0xFFF096AA.toInt(), 'm' to 0xFFBEC4CE.toInt(), 'y' to 0xFFF5C846.toInt(), 'Y' to 0xFFC8961E.toInt(), 'w' to 0xFFEBCDA0.toInt(), 'k' to 0xFF463C32.toInt())
        val TRASH = listOf("...rrr...", "rrrrrrrrr", ".RRRRRRR.", ".RrRrRrR.", ".RrRrRrR.", ".RrRrRrR.", ".RrRrRrR.", ".RrRrRrR.", ".RRRRRRR.")
        val SHIELD = listOf(".sssssss.", "sSSSSSSSs", "sSSSSSSSs", "sSSSSSSSs", "sSSSSSSSs", ".sSSSSSs.", "..sSSSs..", "...sSs...", "....s....")
        val SHIELD_PAL = mapOf('s' to 0xFF2EA84F.toInt(), 'S' to 0xFF5BD67B.toInt())
        /** Палитра иконок меню: у встроенных тем прежняя; у своих считается от цветов темы (не нажатая светлая, нажатая тёмная). */
        fun menuPal(pressed: Boolean): Map<Char, Int> {
            if (Th.custom == null) return MENU_PAL
            return if (!pressed) mapOf('g' to Th.accent, 'G' to ThemeMath.mix(Th.accent, 0xFFFFFFFF.toInt(), 0.4f), 'W' to Th.text)
            else mapOf('g' to Th.act, 'G' to ThemeMath.mix(Th.act, Th.bg, 0.25f), 'W' to ThemeMath.mix(Th.text, Th.bg, 0.4f))
        }
        val MENU_PAL = mapOf('g' to 0xFF2EA84F.toInt(), 'G' to 0xFF5BD67B.toInt(), 'W' to 0xFFE8F5EC.toInt())
        val I_USER = listOf("....gggg....", "...gGGGGg...", "...gGGGGg...", "...gGGGGg...", "....gggg....", "............", "..gggggggg..", ".gGGGGGGGGg.", ".gGGGGGGGGg.", ".gGGGGGGGGg.", ".gggggggggg.")
        val I_GEAR = listOf(".....ggg.....", "..gg.gGg.gg..", ".gGGgGGGgGGg.", "..gGGgggGGg..", "..gGg...gGg..", "ggGg.....gGgg", "gGGg.....gGGg", "ggGg.....gGgg", "..gGg...gGg..", "..gGGgggGGg..", ".gGGgGGGgGGg.", "..gg.gGg.gg..", ".....ggg.....")
        val I_VERS = listOf("....g....", "....G....", "....G....", ".gggGggg.", "..gGGGg..", "...gGg...", "....g....", "g.......g", "ggggggggg")
        val I_CAT = listOf("gggg.gggg", "gGGg.gGGg", "gGGg.gGGg", "gggg.gggg", ".........", "gggg.gggg", "gGGg.gGGg", "gGGg.gGGg", "gggg.gggg")
        val I_PAD = listOf("....g....", "...gGg...", ".........", ".g.GgG.g.", "gG.ggg.Gg", ".g.GgG.g.", ".........", "...gGg...", "....g....")
        val I_CHAT = listOf(".ggggggg.", "gGGGGGGGg", "gGWGWGWGg", "gGGGGGGGg", ".ggggggg.", "..gG.....", "..g......")
        val I_GLOBE = listOf(".....GGG.....", "...GGGGGGG...", "..G.GG.GG.G..", ".G.GG...GG.G.", ".G.G.....G.G.", "G..G.....G..G", "GGGGGGGGGGGGG", "G..G.....G..G", ".G.G.....G.G.", ".G.GG...GG.G.", "..G.GG.GG.G..", "...GGGGGGG...", ".....GGG.....")
        val PUMPKIN = listOf("....uu...", "..oouoo..", ".ooooooo.", "ookoookoo", "ookkokkoo", "ooooooooo", "okokokoko", ".okkkkko.", "..OOOOO..")
        val SNOWFLAKE = listOf("...w...", ".w.w.w.", "..www..", "wwwwwww", "..www..", ".w.w.w.", "...w...")
        val BAT = listOf("k.......k", "kk.k.k.kk", "kkkkkkkkk", ".kk.k.kk.")
        val PUMPKIN_PAL = mapOf('o' to 0xFFF28A1E.toInt(), 'O' to 0xFFC25A0A.toInt(), 'u' to 0xFF4C9A2A.toInt(), 'k' to 0xFF2A1608.toInt())
        val SNOW_PAL = mapOf('w' to 0xFFDCEBFF.toInt())
        val BAT_PAL = mapOf('k' to 0xFF8A6BD6.toInt())
        val I_SHARE = listOf("....G....", "...GGG...", "..G.G.G..", "....G....", "....G....", "g.......g", "g.......g", "ggggggggg")
        val CREEPER = listOf("aaaaaaaa", "aAaaaaAa", "akkaakka", "akkaakka", "aaakkaaa", "aakkkkaa", "aakkkkaa", "aakaakaa")
        val CHICKEN = listOf("...rr...", "..wkwo..", "..wwwo..", ".wwww...", "wwwww...", "wwwww...", ".wwww...", "..o.o...")
        val PARTYHAT = listOf("....y....", "...rrr...", "...bbb...", "..rrrrr..", "..bbbbb..", ".rrrrrrr.", ".yyyyyyy.")
        val CREEPER_PAL = mapOf('a' to 0xFF4CAF50.toInt(), 'A' to 0xFF2E7D32.toInt(), 'k' to 0xFF0B0B0B.toInt())
        val CHICKEN_PAL = mapOf('w' to 0xFFF2F2F2.toInt(), 'r' to 0xFFE5484D.toInt(), 'o' to 0xFFF5A623.toInt(), 'k' to 0xFF222222.toInt())
        val HAT_PAL = mapOf('r' to 0xFFE5484D.toInt(), 'b' to 0xFF3FA7F5.toInt(), 'y' to 0xFFF5C846.toInt())
        val TRASH_PAL = mapOf('r' to 0xFFE5484D.toInt(), 'R' to 0xFFA3231C.toInt())
    }
}


/** Квадратная кнопка в стиле фильтров: пиксельная стрелка вверх (панель открыта) или вниз (свёрнута). */
class CaretView(ctx: Context) : View(ctx) {
    var up = true
        set(v) { field = v; invalidate() }
    private val back = Paint().apply { color = Th.chip }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Th.act }
    private val fill = Paint().apply { color = Th.act }
    init { isClickable = true }
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()
        val box = RectF(1f * d, 1f * d, w - 1f * d, h - 1f * d)
        c.drawRect(box, back)
        frame.strokeWidth = 2f * d
        c.drawRect(box, frame)
        val s = 3f * d
        val cx = w / 2f; val cy = h / 2f
        for (r in 0..3) {
            val y = if (up) cy - 2f * s + r * s else cy + s - r * s
            c.drawRect(cx - r * s - s / 2, y, cx - r * s + s / 2, y + s, fill)
            if (r > 0) c.drawRect(cx + r * s - s / 2, y, cx + r * s + s / 2, y + s, fill)
        }
    }

}


/** Праздничный режим: kind = halloween / newyear / mcbday / aprilfools или null; on = включены ли эффекты. */
object Fx {
    var kind: String? = null
    var on = true
    val hosts: MutableSet<View> = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
    fun refresh() { hosts.forEach { it.invalidate() } }
}

/** Рисует праздничные частицы. Хозяин (FxFrame/FxLinear) вызывает draw() из onDraw: после фона и до детей, поэтому текст и кнопки лежат поверх. */
class FxPainter(private val host: View) {
    private class P(var x: Float, var y: Float, var vx: Float, var vy: Float, var ph: Float, val bmp: android.graphics.Bitmap, val size: Float, val bat: Boolean)
    private val ps = ArrayList<P>()
    private val paint = Paint().apply { isFilterBitmap = false }
    private val rnd = java.util.Random()
    private var last = 0L; private var lw = 0; private var lh = 0; private var lk: String? = null
    private val d = host.resources.displayMetrics.density
    private fun bmp(rows: List<String>, pal: Map<Char, Int>): android.graphics.Bitmap {
        val w = rows.maxOf { it.length }
        val b = android.graphics.Bitmap.createBitmap(w, rows.size, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in rows.indices) for (x in rows[y].indices) pal[rows[y][x]]?.let { b.setPixel(x, y, it) }
        return b
    }
    private val pump by lazy { bmp(PixelIcon.PUMPKIN, PixelIcon.PUMPKIN_PAL) }
    private val snow by lazy { bmp(PixelIcon.SNOWFLAKE, PixelIcon.SNOW_PAL) }
    private val bat by lazy { bmp(PixelIcon.BAT, PixelIcon.BAT_PAL) }
    private val creeper by lazy { bmp(PixelIcon.CREEPER, PixelIcon.CREEPER_PAL) }
    private val chicken by lazy { bmp(PixelIcon.CHICKEN, PixelIcon.CHICKEN_PAL) }
    private fun spawn(kind: String, top: Boolean): P {
        val w = lw.toFloat().coerceAtLeast(1f); val h = lh.toFloat().coerceAtLeast(1f)
        val x = rnd.nextFloat() * w; val y = if (top) -24f * d else rnd.nextFloat() * h
        val ph = rnd.nextFloat() * 6f
        return when (kind) {
            "newyear" -> { val big = rnd.nextInt(4) == 0
                P(x, y, (rnd.nextFloat() - 0.5f) * 14f * d, (22f + rnd.nextFloat() * 28f) * d, ph, snow, (if (big) 14f else 8f + rnd.nextFloat() * 3f) * d, false) }
            "mcbday" -> P(x, y, (rnd.nextFloat() - 0.5f) * 8f * d, (70f + rnd.nextFloat() * 60f) * d, ph, creeper, (13f + rnd.nextFloat() * 7f) * d, false)
            "aprilfools" -> P(x, y, (rnd.nextFloat() - 0.5f) * 20f * d, (14f + rnd.nextFloat() * 14f) * d, ph, chicken, (18f + rnd.nextFloat() * 6f) * d, false)
            else -> if (rnd.nextInt(3) == 0)
                P(x, rnd.nextFloat() * h * 0.7f, (if (rnd.nextBoolean()) 1 else -1) * (30f + rnd.nextFloat() * 30f) * d, 0f, ph, bat, 20f * d, true)
            else P(x, y, (rnd.nextFloat() - 0.5f) * 10f * d, (16f + rnd.nextFloat() * 20f) * d, ph, pump, (16f + rnd.nextFloat() * 8f) * d, false)
        }
    }
    fun draw(c: Canvas, w: Int, h: Int, kind: String) {
        if (w != lw || h != lh || kind != lk) {
            lw = w; lh = h; lk = kind; ps.clear()
            val area = w.toFloat() * h / (d * d)
            val per = if (kind == "newyear") 9000f else if (kind == "mcbday") 14000f else 20000f
            repeat((area / per).toInt().coerceIn(2, 32)) { ps.add(spawn(kind, false)) }
        }
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (last == 0L || now - last > 200) 0.016f else (now - last) / 1000f
        last = now
        val fw = w.toFloat(); val fh = h.toFloat()
        for (i in ps.indices) {
            val p = ps[i]
            p.ph += dt * (if (p.bat) 5f else 1.5f)
            p.x += p.vx * dt + (if (p.bat) 0f else Math.sin(p.ph.toDouble()).toFloat() * 8f * d * dt)
            p.y += p.vy * dt + (if (p.bat) Math.sin(p.ph.toDouble()).toFloat() * 30f * d * dt else 0f)
            if (p.bat) { if (p.x < -30f * d) p.x = fw + 20f * d else if (p.x > fw + 30f * d) p.x = -20f * d }
            else if (p.y > fh + 24f * d) ps[i] = spawn(kind, true)
            val sh = p.size * p.bmp.height / p.bmp.width
            paint.alpha = if (kind == "newyear") 210 else 235
            c.drawBitmap(p.bmp, null, RectF(p.x, p.y, p.x + p.size, p.y + sh), paint)
        }
    }
}

/** FrameLayout, рисующий эффекты между своим фоном и содержимым. */
class FxFrame(ctx: Context) : android.widget.FrameLayout(ctx) {
    private val fx = FxPainter(this)
    init { setWillNotDraw(false); Fx.hosts.add(this) }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val k = Fx.kind
        if (k != null && Fx.on) { fx.draw(c, width, height, k); postInvalidateDelayed(33) }
    }
}

/** LinearLayout с теми же эффектами под детьми. */
class FxLinear(ctx: Context) : LinearLayout(ctx) {
    private val fx = FxPainter(this)
    init { setWillNotDraw(false); Fx.hosts.add(this) }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val k = Fx.kind
        if (k != null && Fx.on) { fx.draw(c, width, height, k); postInvalidateDelayed(33) }
    }
}

/** Мини-превью оформления: фон, верхняя полоса, область скина, строка версии и кнопка «Играть» в цветах выбранной гаммы. */
class StyleCard(ctx: Context, var c: IntArray, private val chosen: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()
        val r = 10f * d
        val clip = android.graphics.Path().apply { addRoundRect(RectF(0f, 0f, w, h), r, r, android.graphics.Path.Direction.CW) }
        cv.save(); cv.clipPath(clip)
        p.style = Paint.Style.FILL
        p.color = c[0]; cv.drawRect(0f, 0f, w, h, p)
        p.color = c[1]; cv.drawRect(0f, 0f, w, h * 0.16f, p)
        p.color = c[3]; cv.drawRect(w * 0.36f, h * 0.06f, w * 0.64f, h * 0.10f, p)
        p.color = c[5]; cv.drawRect(0f, h * 0.16f, w, h * 0.58f, p)
        p.color = c[3]; cv.drawCircle(w / 2, h * 0.30f, 7f * d, p)
        p.color = c[4]; cv.drawRect(w / 2 - 9f * d, h * 0.30f + 10f * d, w / 2 + 9f * d, h * 0.30f + 24f * d, p)
        p.color = c[2]; cv.drawRect(0f, h * 0.58f, w, h * 0.74f, p)
        p.color = c[4]; cv.drawRect(w * 0.10f, h * 0.64f, w * 0.40f, h * 0.68f, p)
        p.color = c.getOrElse(6) { 0xFF2EA84F.toInt() }; cv.drawRect(0f, h * 0.76f, w, h * 0.92f, p)
        p.color = c[1]; cv.drawRect(0f, h * 0.92f, w, h, p)
        for (i in 0 until 4) { p.color = if (i == 1) c[3] else c[4]; p.alpha = if (i == 1) 255 else 120; cv.drawRect(w * (0.12f + i * 0.22f), h * 0.945f, w * (0.12f + i * 0.22f) + 8f * d, h * 0.945f + 8f * d, p); p.alpha = 255 }
        cv.restore()
        p.style = Paint.Style.STROKE
        p.strokeWidth = (if (chosen) 3f else 1f) * d
        p.color = if (chosen) c[3] else 0x55888888
        cv.drawRoundRect(RectF(p.strokeWidth / 2, p.strokeWidth / 2, w - p.strokeWidth / 2, h - p.strokeWidth / 2), r, r, p)
    }
}

/** Пользовательские темы: из шести основных цветов автоматически получаются оттенки. */
object ThemeMath {
    /** Основные цвета и производные от них оттенки. */
    val BASE = listOf("bg", "bar", "text", "chip", "line", "accent")
    val SHADES = mapOf(
        "bg" to listOf("drawer", "skinBg"), "bar" to emptyList(), "text" to listOf("text2", "dim", "hint", "chipText"),
        "chip" to listOf("chip2"), "line" to listOf("line2"), "accent" to listOf("accentDk", "altBg", "altText"))

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }
    fun lum(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
    fun isLight(base: Map<String, Int>) = lum(base["bg"]!!) > 0.5

    /** Все 17 цветов лаунчера: основные как есть, остальное считается, если не переопределено вручную. */
    fun colors(base: Map<String, Int>, ov: Map<String, Int>): LinkedHashMap<String, Int> {
        val bg = base["bg"]!!; val bar = base["bar"]!!; val text = base["text"]!!
        val chip = base["chip"]!!; val line = base["line"]!!; val acc = base["accent"]!!
        val hsv = FloatArray(3); Color.colorToHSV(acc, hsv); hsv[2] = hsv[2] * 0.7f
        val d = mapOf(
            "drawer" to mix(bg, bar, 0.5f), "skinBg" to mix(bg, acc, 0.07f),
            "text2" to mix(text, bg, 0.20f), "dim" to mix(text, bg, 0.45f), "hint" to mix(text, bg, 0.62f), "chipText" to text,
            "chip2" to mix(chip, bg, 0.45f), "line2" to mix(line, bg, 0.5f),
            "accentDk" to Color.HSVToColor(hsv), "altBg" to acc, "altText" to (if (lum(acc) > 0.55) 0xFF101010.toInt() else 0xFFFFFFFF.toInt()))
        val m = LinkedHashMap<String, Int>()
        for (k in Th.KEYS) m[k] = ov[k] ?: base[k] ?: d[k]!!
        return m
    }
}

/** Цветовой круг: оттенок по углу, насыщенность по расстоянию от центра (яркость задаёт отдельный ползунок). */
class ColorWheel(ctx: Context) : View(ctx) {
    val hsv = floatArrayOf(0f, 0f, 1f)
    var onChange: (() -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sweep: android.graphics.SweepGradient? = null
    private var radial: android.graphics.RadialGradient? = null
    private fun geom(): Triple<Float, Float, Float> = Triple(width / 2f, height / 2f, minOf(width, height) / 2f - 6f * resources.displayMetrics.density)
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val (cx, cy, r) = geom()
        sweep = android.graphics.SweepGradient(cx, cy, IntArray(361) { Color.HSVToColor(floatArrayOf((it % 360).toFloat(), 1f, 1f)) }, null)
        radial = android.graphics.RadialGradient(cx, cy, r, 0xFFFFFFFF.toInt(), 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
    }
    override fun onDraw(c: Canvas) {
        val (cx, cy, r) = geom()
        p.style = Paint.Style.FILL
        p.shader = sweep; c.drawCircle(cx, cy, r, p)
        p.shader = radial; c.drawCircle(cx, cy, r, p)
        p.shader = null; p.color = Color.argb(((1f - hsv[2]) * 255).toInt(), 0, 0, 0); c.drawCircle(cx, cy, r, p)
        val a = Math.toRadians(hsv[0].toDouble())
        val mx = cx + (Math.cos(a) * hsv[1] * r).toFloat(); val my = cy + (Math.sin(a) * hsv[1] * r).toFloat()
        val d = resources.displayMetrics.density
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f * d; p.color = Color.BLACK; c.drawCircle(mx, my, 9f * d, p)
        p.strokeWidth = 2f * d; p.color = Color.WHITE; c.drawCircle(mx, my, 9f * d, p)
    }
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.action == android.view.MotionEvent.ACTION_DOWN || e.action == android.view.MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val (cx, cy, r) = geom()
            val dx = e.x - cx; val dy = e.y - cy
            hsv[1] = (Math.hypot(dx.toDouble(), dy.toDouble()) / r).toFloat().coerceIn(0f, 1f)
            hsv[0] = ((Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0).toFloat()
            invalidate(); onChange?.invoke()
            return true
        }
        return e.action == android.view.MotionEvent.ACTION_UP
    }
}
