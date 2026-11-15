package com.khancer.tiermaker

import android.app.AlertDialog
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Funções de interface compartilhadas entre as telas (início, editor e configurações). */
abstract class BaseActivity : ComponentActivity() {

    protected val io: ExecutorService = Executors.newSingleThreadExecutor()
    protected val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
    }

    // ------------------------------------------------------------------ helpers de UI

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    protected fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    protected fun label(text: String, size: Float = 14f, bold: Boolean = false, page: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD)
        if (page) t.setTextColor(AppSettings.textColor)
        return t
    }

    protected fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.minWidth = 0
        b.minimumWidth = 0
        val accent = AppSettings.accent
        b.backgroundTintList = ColorStateList.valueOf(accent)
        b.setTextColor(if (ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE)
        b.setOnClickListener { onClick() }
        return b
    }

    protected fun hbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    protected fun vbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    protected fun dialogBox(): LinearLayout {
        val b = vbox()
        b.setPadding(dp(16), dp(8), dp(16), dp(8))
        return b
    }

    protected fun swatch(color: Int, onClick: () -> Unit): View {
        val v = View(this)
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = dp(8).toFloat()
        g.setColor(color)
        g.setStroke(dp(2), Color.GRAY)
        v.background = g
        v.setOnClickListener { onClick() }
        return v
    }

    protected fun section(text: String): TextView {
        val t = label(text, 15f, true)
        t.setTextColor(AppSettings.accent)
        t.setPadding(0, dp(16), 0, dp(4))
        return t
    }

    /** Fundo do app (cor + papel de parede opcional) por trás do conteúdo da tela. */
    protected fun setPage(content: View) {
        val frame = FrameLayout(this)
        frame.setBackgroundColor(AppSettings.bgColor)
        val wp = AppSettings.wallpaper()
        if (wp != null) {
            val cover = CoverView(this)
            cover.bitmap = wp
            frame.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))
            val dimView = View(this)
            dimView.setBackgroundColor(Color.argb(AppSettings.wallpaperDim * 255 / 100, 0, 0, 0))
            frame.addView(dimView, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        frame.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(frame)
    }

    /** Linha "nome + quadradinho de cor". O quadradinho se atualiza sozinho ao escolher. */
    protected fun colorRow(text: String, getColor: () -> Int, page: Boolean = false, onPick: (Int) -> Unit): View {
        val r = hbox()
        r.setPadding(0, dp(4), 0, dp(4))
        r.addView(label(text, 15f, false, page), LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = swatch(getColor()) {}
        sw.setOnClickListener {
            pickColor(getColor()) { c ->
                onPick(c)
                (sw.background as GradientDrawable).setColor(c)
            }
        }
        r.addView(sw, LinearLayout.LayoutParams(dp(48), dp(36)))
        return r
    }

    protected fun addSeek(
        box: LinearLayout, title: String, unit: String,
        minV: Int, maxV: Int, value: Int, live: Boolean, page: Boolean = false, onChange: (Int) -> Unit
    ) {
        val lbl = label("$title: $value $unit", 14f, false, page)
        val sb = SeekBar(this)
        sb.max = maxV - minV
        sb.progress = (value - minV).coerceIn(0, maxV - minV)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                lbl.text = "$title: ${minV + p} $unit"
                if (live && fromUser) onChange(minV + p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                if (!live) onChange(minV + (s?.progress ?: 0))
            }
        })
        box.addView(lbl)
        box.addView(sb)
    }

    // ------------------------------------------------------------------ imagens

    /** Mantém a proporção original, só reduzindo se o lado maior passar de maxSide. */
    protected fun decodeFit(uri: Uri, maxSide: Int): Bitmap {
        val src = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val m = max(info.size.width, info.size.height)
            if (m > maxSide) {
                val f = maxSide.toFloat() / m
                decoder.setTargetSize(
                    max(1, (info.size.width * f).roundToInt()),
                    max(1, (info.size.height * f).roundToInt())
                )
            }
        }
    }

    /**
     * Tela de recorte com proporção fixa 1x1.
     * Ações devolvidas: 0 = cortar (com o retângulo), 1 = centro automático,
     * 2 = centro automático em todas as restantes, 3 = cancelar.
     */
    protected fun showCropper(bmp: Bitmap, heading: String, multi: Boolean, cb: (Int, Rect?) -> Unit) {
        val dlg = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        dlg.setCancelable(false)

        val root = vbox()
        root.setBackgroundColor(Color.BLACK)
        root.setPadding(dp(8), dp(8), dp(8), dp(8))

        val head = label(heading, 16f, true)
        head.setTextColor(Color.WHITE)
        val hint = label("Arraste o quadrado para mover ou os cantos para ampliar. Ele sempre fica 1x1.", 12f)
        hint.setTextColor(Color.LTGRAY)
        val crop = CropView(this)
        crop.setBitmap(bmp)

        root.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(crop, LinearLayout.LayoutParams(MATCH, 0, 1f))

        root.addView(button("Cortar") {
            val r = crop.getCrop()
            dlg.dismiss()
            cb(0, r)
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        val row = hbox()
        row.addView(button("Cancelar") { dlg.dismiss(); cb(3, null) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(button("Centro auto") { dlg.dismiss(); cb(1, null) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (multi) {
            row.addView(button("Auto em todas") { dlg.dismiss(); cb(2, null) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

        dlg.setContentView(root)
        dlg.show()
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    // ------------------------------------------------------------------ seletor de cor

    protected fun pickColor(initial: Int, onPick: (Int) -> Unit) {
        val box = dialogBox()
        val sv = ScrollView(this)
        sv.addView(box)

        var current = initial or 0xFF000000.toInt()
        var updating = false

        val wheel = ColorWheelView(this)
        val bright = SeekBar(this)
        bright.max = 100
        val hex = EditText(this)
        hex.hint = "#RRGGBB"
        hex.setSingleLine()
        val preview = swatch(current) {}

        // source: 0 = círculo, 1 = brilho, 2 = código hex, 3 = paleta
        fun refresh(c: Int, source: Int) {
            updating = true
            current = c
            (preview.background as GradientDrawable).setColor(c)
            if (source != 2) hex.setText(String.format("#%06X", c and 0xFFFFFF))
            if (source >= 2) {
                wheel.setColor(c)
                bright.progress = (wheel.value * 100f).roundToInt()
            }
            updating = false
        }

        wheel.setColor(current)
        bright.progress = (wheel.value * 100f).roundToInt()
        hex.setText(String.format("#%06X", current and 0xFFFFFF))

        wheel.onChange = { c -> refresh(c, 0) }
        bright.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    wheel.value = p / 100f
                    wheel.invalidate()
                    refresh(wheel.currentColor(), 1)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        hex.doAfterTextChanged {
            if (!updating) {
                val t = it?.toString()?.trim() ?: ""
                if (t.length == 7 && t[0] == '#') {
                    try {
                        refresh(Color.parseColor(t), 2)
                    } catch (e: Exception) {
                        // código ainda incompleto ou inválido
                    }
                }
            }
        }

        val wheelLp = LinearLayout.LayoutParams(dp(240), dp(240))
        wheelLp.gravity = Gravity.CENTER_HORIZONTAL
        box.addView(wheel, wheelLp)

        box.addView(label("Brilho (exposição): arraste para escurecer", 13f))
        box.addView(bright)

        val hexRow = hbox()
        hexRow.addView(preview, LinearLayout.LayoutParams(dp(48), dp(40)))
        val hexLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        hexLp.leftMargin = dp(12)
        hexRow.addView(hex, hexLp)
        box.addView(hexRow)

        box.addView(section("Cores rápidas"))
        val colors = PALETTE.map { Color.parseColor(it) }
        for (r in 0 until 4) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER
            for (c in 0 until 6) {
                val col = colors[r * 6 + c]
                val lp = LinearLayout.LayoutParams(dp(40), dp(40))
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                row.addView(swatch(col) { refresh(col, 3) }, lp)
            }
            box.addView(row)
        }

        AlertDialog.Builder(this)
            .setTitle("Escolher cor")
            .setView(sv)
            .setPositiveButton("OK") { _, _ -> onPick(current) }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
