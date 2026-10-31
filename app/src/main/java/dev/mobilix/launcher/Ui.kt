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
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF43E06A.toInt() }
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
    private fun c(d: Long, l: Long) = (if (light) l else d).toInt()
    val bg get() = c(0xFF0B0F0C, 0xFFF1F6F2)
    val bar get() = c(0xFF121A15, 0xFFE2EDE5)
    val drawer get() = c(0xFF0F1712, 0xFFFFFFFF)
    val skinBg get() = c(0xFF0E1F14, 0xFFDCEBDF)
    val accent get() = c(0xFF43E06A, 0xFF1B7F3A)
    val text get() = c(0xFFFFFFFF, 0xFF10200F)
    val text2 get() = c(0xFFB8E6C4, 0xFF2B4B33)
    val dim get() = c(0xFF7FA08A, 0xFF5A7A62)
    val line get() = c(0xFF22332A, 0xFFCADBCE)
    val line2 get() = c(0xFF1B2A20, 0xFFDCE8DF)
    val altBg get() = (if (light) 0xFF0B0F0C else 0xFFF1F6F2).toInt()
    val altText get() = (if (light) 0xFF43E06A else 0xFF10200F).toInt()
    val hint get() = c(0xFF58705F, 0xFF8AA391)
}

/** Квадратная кнопка фильтров: три ползунка (вверх, вниз, вверх) с квадратными ручками и число включённых фильтров. */
class FilterView(ctx: Context) : View(ctx) {
    var count = 0
        set(v) { field = v; invalidate() }
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14301D.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF2EA84F.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE; color = 0xFF43E06A.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF43E06A.toInt() }
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
            background = GradientDrawable().apply { setColor(Th.drawer); setStroke(dp(2), 0xFF2EA84F.toInt()); cornerRadius = dp(6).toFloat() }
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
                if (i > 0) col.addView(View(ctx).apply { setBackgroundColor(Th.line2) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                col.addView(TextView(ctx).apply {
                    text = label; textSize = 15f; setTextColor(Th.text); setPadding(dp(10), dp(12), dp(10), dp(12))
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0xFF14301D.toInt()))
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
                setBackgroundColor(if (primary) 0xFF2EA84F.toInt() else 0xFF14301D.toInt())
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
