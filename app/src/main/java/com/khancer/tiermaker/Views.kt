package com.khancer.tiermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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

/** Editor de recorte: um quadrado 1x1 que pode ser movido e ampliado/reduzido pelos cantos. */
class CropView(context: Context) : View(context) {
    private var bmp: Bitmap? = null
    private val imgRect = RectF()
    private val crop = RectF()
    private var scale = 1f
    private var mode = 0 // 0 nada, 1 mover, 2 redimensionar
    private var corner = 0
    private var lastX = 0f
    private var lastY = 0f
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        dim.color = 0xAA000000.toInt()
        line.style = Paint.Style.STROKE
        line.color = Color.WHITE
        dot.color = Color.WHITE
    }

    fun setBitmap(b: Bitmap) {
        bmp = b
        crop.setEmpty()
        layoutImage()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        crop.setEmpty()
        layoutImage()
    }

    private fun layoutImage() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        val pad = 12f * d
        scale = min((width - 2 * pad) / b.width, (height - 2 * pad) / b.height)
        val iw = b.width * scale
        val ih = b.height * scale
        imgRect.set((width - iw) / 2f, (height - ih) / 2f, (width + iw) / 2f, (height + ih) / 2f)
        if (crop.isEmpty) {
            val side = min(iw, ih)
            val cx = width / 2f
            val cy = height / 2f
            crop.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        }
    }

    /** Área escolhida, em pixels da imagem original. */
    fun getCrop(): Rect {
        val b = bmp ?: return Rect(0, 0, 1, 1)
        val l = Math.round((crop.left - imgRect.left) / scale).coerceIn(0, b.width - 1)
        val t = Math.round((crop.top - imgRect.top) / scale).coerceIn(0, b.height - 1)
        var side = Math.round(crop.width() / scale)
        side = min(side, min(b.width - l, b.height - t))
        side = max(side, 1)
        return Rect(l, t, l + side, t + side)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        canvas.drawBitmap(b, null, imgRect, paint)
        canvas.save()
        canvas.clipRect(imgRect)
        canvas.clipOutRect(crop)
        canvas.drawRect(imgRect, dim)
        canvas.restore()

        line.strokeWidth = 2f * d
        canvas.drawRect(crop, line)
        line.strokeWidth = 1f * d
        line.alpha = 110
        val tw = crop.width() / 3f
        canvas.drawLine(crop.left + tw, crop.top, crop.left + tw, crop.bottom, line)
        canvas.drawLine(crop.left + 2 * tw, crop.top, crop.left + 2 * tw, crop.bottom, line)
        canvas.drawLine(crop.left, crop.top + tw, crop.right, crop.top + tw, line)
        canvas.drawLine(crop.left, crop.top + 2 * tw, crop.right, crop.top + 2 * tw, line)
        line.alpha = 255

        val cs = corners()
        for (i in 0 until 4) canvas.drawCircle(cs[i * 2], cs[i * 2 + 1], 9f * d, dot)
    }

    private fun corners(): FloatArray = floatArrayOf(
        crop.left, crop.top,
        crop.right, crop.top,
        crop.left, crop.bottom,
        crop.right, crop.bottom
    )

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val hr = 32f * d
                val cs = corners()
                mode = 0
                for (i in 0 until 4) {
                    if (hypot(e.x - cs[i * 2], e.y - cs[i * 2 + 1]) < hr) {
                        mode = 2
                        corner = i
                        break
                    }
                }
                if (mode == 0 && crop.contains(e.x, e.y)) mode = 1
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 1) {
                    val nl = (crop.left + (e.x - lastX)).coerceIn(imgRect.left, imgRect.right - crop.width())
                    val nt = (crop.top + (e.y - lastY)).coerceIn(imgRect.top, imgRect.bottom - crop.height())
                    crop.offsetTo(nl, nt)
                    lastX = e.x
                    lastY = e.y
                } else if (mode == 2) {
                    resize(e.x, e.y)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }

    private fun resize(x: Float, y: Float) {
        // canto arrastado: 0 = topo-esq, 1 = topo-dir, 2 = base-esq, 3 = base-dir
        val sx = if (corner == 1 || corner == 3) 1f else -1f
        val sy = if (corner == 2 || corner == 3) 1f else -1f
        val ax = if (sx > 0f) crop.left else crop.right
        val ay = if (sy > 0f) crop.top else crop.bottom
        var side = max((x - ax) * sx, (y - ay) * sy)
        val availX = if (sx > 0f) imgRect.right - ax else ax - imgRect.left
        val availY = if (sy > 0f) imgRect.bottom - ay else ay - imgRect.top
        val avail = min(availX, availY)
        side = min(side, avail)
        side = max(side, min(48f * d, avail))
        val l = if (sx > 0f) ax else ax - side
        val t = if (sy > 0f) ay else ay - side
        crop.set(l, t, l + side, t + side)
    }
}
