package dev.mobilix.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Лёгкий программный 3D-просмотр скина игрока: вращается сам (30 кадров/с, пока виден), крутится пальцем. */
class SkinView(ctx: Context) : View(ctx) {
    private var skin: Bitmap? = null
    private var yaw = 25f
    private var pitch = 8f
    private var lastY = 0f
    private var lastX = 0f
    private var touching = false
    private var last = System.nanoTime()
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val msg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val m = Matrix()
    private val rf = RectF()

    /** Пауза: страница или меню закрывают скин, анимации не нужны (экономим процессор). */
    var paused = false
        set(v) {
            if (field == v) return
            field = v
            if (!v) { last = System.nanoTime(); postInvalidate() }
        }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    fun setSkin(b: Bitmap?) { skin = b; postInvalidate() }

    private class Part(
        val cx: Float, val cy: Float, val w: Float, val h: Float, val d: Float,
        val u: Int, val v: Int, val infl: Float, val over: Boolean
    )

    var slim = false
        set(v) { field = v; defs = buildDefs(); postInvalidate() }

    private fun partsFor(): List<Part> { val aw = if (slim) 3f else 4f; val ax = 4f + aw / 2f; return listOf(
        // база: голова, тело, руки, ноги
        Part(0f, 28f, 8f, 8f, 8f, 0, 0, 0f, false),
        Part(0f, 18f, 8f, 12f, 4f, 16, 16, 0f, false),
        Part(-ax, 18f, aw, 12f, 4f, 40, 16, 0f, false),
        Part(ax, 18f, aw, 12f, 4f, 32, 48, 0f, false),
        Part(-2f, 6f, 4f, 12f, 4f, 0, 16, 0f, false),
        Part(2f, 6f, 4f, 12f, 4f, 16, 48, 0f, false),
        // второй слой: шапка, куртка, рукава, штаны
        Part(0f, 28f, 8f, 8f, 8f, 32, 0, 0.5f, true),
        Part(0f, 18f, 8f, 12f, 4f, 16, 32, 0.25f, true),
        Part(-ax, 18f, aw, 12f, 4f, 40, 32, 0.25f, true),
        Part(ax, 18f, aw, 12f, 4f, 48, 48, 0.25f, true),
        Part(-2f, 6f, 4f, 12f, 4f, 0, 32, 0.25f, true),
        Part(2f, 6f, 4f, 12f, 4f, 0, 48, 0.25f, true)
    ) }

    /** Грань модели: углы в пространстве (заранее посчитаны один раз), прямоугольник текстуры и буфер проекции. */
    private class FDef(val c: FloatArray, val src: FloatArray, val rect: Rect, val over: Boolean) {
        val dst = FloatArray(8)
        var depth = 0f
    }

    private fun buildDefs(): Array<FDef> {
        val out = ArrayList<FDef>()
        for (pt in partsFor()) {
            val hx = pt.w / 2 + pt.infl; val hy = pt.h / 2 + pt.infl; val hz = pt.d / 2 + pt.infl
            val tw = pt.w.toInt(); val th = pt.h.toInt(); val td = pt.d.toInt()
            val u = pt.u; val v = pt.v
            // (4 угла: левый-верх, правый-верх, правый-низ, левый-низ), прямоугольник текстуры
            val cs = arrayOf(
                floatArrayOf(-hx, hy, hz, hx, hy, hz, hx, -hy, hz, -hx, -hy, hz),
                floatArrayOf(hx, hy, -hz, -hx, hy, -hz, -hx, -hy, -hz, hx, -hy, -hz),
                floatArrayOf(-hx, hy, -hz, -hx, hy, hz, -hx, -hy, hz, -hx, -hy, -hz),
                floatArrayOf(hx, hy, hz, hx, hy, -hz, hx, -hy, -hz, hx, -hy, hz),
                floatArrayOf(-hx, hy, -hz, hx, hy, -hz, hx, hy, hz, -hx, hy, hz),
                floatArrayOf(-hx, -hy, hz, hx, -hy, hz, hx, -hy, -hz, -hx, -hy, -hz)
            )
            val rs = arrayOf(
                Rect(u + td, v + td, u + td + tw, v + td + th),
                Rect(u + 2 * td + tw, v + td, u + 2 * td + 2 * tw, v + td + th),
                Rect(u, v + td, u + td, v + td + th),
                Rect(u + td + tw, v + td, u + 2 * td + tw, v + td + th),
                Rect(u + td, v, u + td + tw, v + td),
                Rect(u + td + tw, v, u + td + 2 * tw, v + td)
            )
            for (k in 0 until 6) {
                val c = FloatArray(12)
                for (i in 0 until 4) {
                    c[i * 3] = cs[k][i * 3] + pt.cx
                    c[i * 3 + 1] = cs[k][i * 3 + 1] + pt.cy - 16f
                    c[i * 3 + 2] = cs[k][i * 3 + 2]
                }
                val r = rs[k]
                val src = floatArrayOf(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.top.toFloat(),
                    r.right.toFloat(), r.bottom.toFloat(), r.left.toFloat(), r.bottom.toFloat())
                out.add(FDef(c, src, r, pt.over))
            }
        }
        return out.toTypedArray()
    }

    private var defs: Array<FDef> = buildDefs()
    private val order = ArrayList<FDef>(80)
    private val cmp = Comparator<FDef> { a, b -> b.depth.compareTo(a.depth) }

    /** Проецирует грани для текущего поворота без лишних выделений памяти; видимые, от дальних к ближним. */
    private fun project(w: Int, h: Int): List<FDef> {
        val a = Math.toRadians(yaw.toDouble()); val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
        val p = Math.toRadians(-pitch.toDouble()); val cp = Math.cos(p).toFloat(); val sp = Math.sin(p).toFloat()
        val dist = 70f
        val f = h * 0.88f * dist / 36f
        order.clear()
        for (fd in defs) {
            val c = fd.c; val dst = fd.dst
            var dsum = 0f
            for (i in 0 until 4) {
                val x = c[i * 3]; val y = c[i * 3 + 1]; val z = c[i * 3 + 2]
                val x1 = x * ca + z * sa; val z1 = -x * sa + z * ca
                val y2 = y * cp - z1 * sp; val z2 = y * sp + z1 * cp
                val depth = dist - z2
                dst[i * 2] = w / 2f + f * x1 / depth
                dst[i * 2 + 1] = h / 2f - f * y2 / depth
                dsum += depth
            }
            var area = 0f
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                area += dst[i * 2] * dst[j * 2 + 1] - dst[j * 2] * dst[i * 2 + 1]
            }
            if (area <= 0f) continue
            fd.depth = dsum / 4f - (if (fd.over) 0.6f else 0f)
            order.add(fd)
        }
        java.util.Collections.sort(order, cmp)
        return order
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); last = System.nanoTime(); invalidate() }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) { last = System.nanoTime(); invalidate() }
    }

    override fun onDraw(c: Canvas) {
        val b = skin
        if (b == null) {
            msg.textSize = 14f * resources.displayMetrics.density
            c.drawText("Скин появится после первой загрузки игры", width / 2f, height / 2f, msg)
            return
        }
        val now = System.nanoTime()
        if (!touching && !paused) { yaw += (now - last) / 1e9f * 25f; if (yaw > 360f) yaw -= 360f }
        last = now
        for (fd in project(width, height)) {
            if (!m.setPolyToPoly(fd.src, 0, fd.dst, 0, 4)) continue
            c.save()
            c.concat(m)
            rf.set(fd.rect)
            c.drawBitmap(b, fd.rect, rf, paint)
            c.restore()
        }
        // 30 кадров/с вместо 60: вращение остаётся плавным, а интерфейс не перегружается
        if (!paused && !touching && isShown && windowVisibility == VISIBLE) postInvalidateDelayed(33)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touching = true; lastX = e.x; lastY = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> { yaw += (e.x - lastX) * 0.6f; lastX = e.x
                pitch = (pitch + (e.y - lastY) * 0.5f).coerceIn(-70f, 70f); lastY = e.y; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false; last = System.nanoTime(); invalidate() }
        }
        return true
    }
}
