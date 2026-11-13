package com.khancer.tiermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Layout em grade que quebra linha automaticamente; todas as células têm tamanho fixo (1x1). */
class FlowLayout(context: Context) : ViewGroup(context) {
    private val xs = ArrayList<Int>()
    private val ys = ArrayList<Int>()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = MeasureSpec.getSize(widthSpec)
        val limit = maxW - paddingRight
        xs.clear()
        ys.clear()
        var x = paddingLeft
        var y = paddingTop
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) {
                xs.add(0); ys.add(0)
                continue
            }
            val lp = c.layoutParams
            c.measure(
                MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
            )
            val cw = c.measuredWidth
            val ch = c.measuredHeight
            if (x + cw > limit && x > paddingLeft) {
                x = paddingLeft
                y += rowH
                rowH = 0
            }
            xs.add(x)
            ys.add(y)
            x += cw
            rowH = max(rowH, ch)
        }
        val contentH = y + rowH + paddingBottom
        setMeasuredDimension(
            resolveSize(maxW, widthSpec),
            resolveSize(max(contentH, suggestedMinimumHeight), heightSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.layout(xs[i], ys[i], xs[i] + c.measuredWidth, ys[i] + c.measuredHeight)
        }
    }
}

/** ScrollView que rola sozinho quando uma imagem é arrastada perto da borda. */
class AutoScrollView(context: Context) : ScrollView(context) {
    override fun dispatchDragEvent(ev: DragEvent): Boolean {
        if (ev.action == DragEvent.ACTION_DRAG_LOCATION) {
            val edge = 80 * resources.displayMetrics.density
            val step = (14 * resources.displayMetrics.density).toInt()
            if (ev.y < edge) scrollBy(0, -step) else if (ev.y > height - edge) scrollBy(0, step)
        }
        return super.dispatchDragEvent(ev)
    }
}

/** Desenha uma imagem preenchendo toda a área (center-crop) sem influenciar o tamanho do layout. */
class CoverView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val s = max(width / b.width.toFloat(), height / b.height.toFloat())
        val dx = (width - b.width * s) / 2f
        val dy = (height - b.height * s) / 2f
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(s, s)
        canvas.drawBitmap(b, 0f, 0f, paint)
        canvas.restore()
    }
}

/** Círculo de cores: o ângulo define o tom, a distância do centro define a saturação. */
class ColorWheelView(context: Context) : View(context) {
    var hue = 0f
    var sat = 0f
    var value = 1f
    var onChange: ((Int) -> Unit)? = null

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cx = 0f
    private var cy = 0f
    private var radius = 1f

    init {
        ringPaint.style = Paint.Style.STROKE
    }

    fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue, sat, value))

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val d = resources.displayMetrics.density
        cx = w / 2f
        cy = h / 2f
        radius = max(1f, min(w, h) / 2f - 12f * d)
        val colors = IntArray(7) { Color.HSVToColor(floatArrayOf((it * 60f) % 360f, 1f, 1f)) }
        wheelPaint.shader = SweepGradient(cx, cy, colors, null)
        satPaint.shader = RadialGradient(cx, cy, radius, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radius, wheelPaint)
        canvas.drawCircle(cx, cy, radius, satPaint)
        dimPaint.color = Color.argb(((1f - value) * 255f).toInt(), 0, 0, 0)
        canvas.drawCircle(cx, cy, radius, dimPaint)

        val a = Math.toRadians(hue.toDouble())
        val mx = cx + (cos(a) * sat * radius).toFloat()
        val my = cy + (sin(a) * sat * radius).toFloat()
        val d = resources.displayMetrics.density
        ringPaint.strokeWidth = 3f * d
        ringPaint.color = Color.BLACK
        canvas.drawCircle(mx, my, 10f * d, ringPaint)
        ringPaint.strokeWidth = 1.5f * d
        ringPaint.color = Color.WHITE
        canvas.drawCircle(mx, my, 10f * d, ringPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val dx = e.x - cx
            val dy = e.y - cy
            sat = min(1f, hypot(dx, dy) / radius)
            var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (deg < 0f) deg += 360f
            hue = deg
            invalidate()
            onChange?.invoke(currentColor())
        }
        return true
    }
}
