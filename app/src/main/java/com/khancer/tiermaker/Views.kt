package com.khancer.tiermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.max

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
