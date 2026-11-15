package com.khancer.tiermaker

import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class AnimSrc(val movie: Movie, val dur: Int)
private class Live(val view: ImageView, val id: String, val bmp: Bitmap, val canvas: Canvas)
private class Loaded(val bmp: Bitmap, val gif: ByteArray?, val mw: Int)

/** Editor de uma lista. */
@Suppress("DEPRECATION")
class MainActivity : BaseActivity() {

    private lateinit var listId: String
    private lateinit var state: TierState
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private var bgBitmap: Bitmap? = null

    private lateinit var scroll: AutoScrollView
    private lateinit var boardFrame: FrameLayout
    private lateinit var bgView: CoverView
    private lateinit var titleView: TextView
    private lateinit var titleImageView: ImageView
    private lateinit var tiersBox: LinearLayout
    private lateinit var poolFlow: FlowLayout
    private lateinit var poolHeader: TextView

    // animação (GIFs sincronizados)
    private val sources = HashMap<String, AnimSrc>()
    private val badSources = HashSet<String>()
    private val live = ArrayList<Live>()
    private var cycleMs = 0
    private var t0 = 0L

    /** Chamado quando uma imagem escolhida (tier ou título) termina de ser importada. */
    private var pendingCb: ((String) -> Unit)? = null

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) processQueue(uris, 0, ArrayList(), false)
        }
    private val pickBg =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importBackground(uri)
        }
    private val pickLabel =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importLabel(uri)
        }
    private val pickTitleImg =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importTitleImage(uri)
        }

    private val ticker = object : Runnable {
        override fun run() {
            renderLive()
            ui.postDelayed(this, (1000 / max(1, AppSettings.animFps)).toLong())
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("listId")
        if (id == null) {
            finish()
            return
        }
        listId = id
        state = Store.load(this, id) ?: TierState.default()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(AppSettings.bgColor)
        root.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, WRAP))

        scroll = AutoScrollView(this)
        boardFrame = FrameLayout(this)
        bgView = CoverView(this)
        boardFrame.addView(bgView, FrameLayout.LayoutParams(MATCH, MATCH))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(8), dp(12), dp(8), dp(12))

        titleView = TextView(this)
        titleView.setPadding(dp(8), dp(4), dp(8), dp(12))
        titleView.setOnLongClickListener { showTitleEditor(); true }

        titleImageView = ImageView(this)
        titleImageView.adjustViewBounds = true
        titleImageView.scaleType = ImageView.ScaleType.FIT_CENTER
        titleImageView.visibility = View.GONE
        titleImageView.setOnLongClickListener { showTitleEditor(); true }

        tiersBox = LinearLayout(this)
        tiersBox.orientation = LinearLayout.VERTICAL

        content.addView(titleView, LinearLayout.LayoutParams(MATCH, WRAP))
        content.addView(titleImageView, LinearLayout.LayoutParams(WRAP, dp(80)))
        content.addView(tiersBox, LinearLayout.LayoutParams(MATCH, WRAP))
        boardFrame.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        boardFrame.setOnLongClickListener { showBackgroundEditor(); true }

        val wrapper = vbox()
        wrapper.addView(boardFrame, LinearLayout.LayoutParams(MATCH, WRAP))
        val addTierLp = LinearLayout.LayoutParams(MATCH, WRAP)
        addTierLp.setMargins(dp(8), dp(8), dp(8), dp(8))
        wrapper.addView(button("+ Adicionar tier") { addTier() }, addTierLp)
        scroll.addView(wrapper, ViewGroup.LayoutParams(MATCH, WRAP))

        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(buildPool(), LinearLayout.LayoutParams(MATCH, dp(150)))
        root.addView(buildBottomBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)

        val bg = Store.bgFile(this, listId)
        if (state.hasBgImage && bg.exists()) {
            bgBitmap = BitmapFactory.decodeFile(bg.path)
        }
        rebuild()

        val prefs = getSharedPreferences("tm", MODE_PRIVATE)
        if (!prefs.getBoolean("hint", false)) {
            prefs.edit().putBoolean("hint", true).apply()
            Toast.makeText(this, "Dica: segure um tier, o título ou o fundo para editar", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    private fun save() = Store.save(this, listId, state)

    // ------------------------------------------------------------------ barras

    private fun buildTopBar(): View {
        val bar = hbox()
        bar.setBackgroundColor(AppSettings.panelColor)
        bar.setPadding(dp(4), dp(4), dp(4), dp(4))
        bar.addView(button("← Listas") { finish() }, LinearLayout.LayoutParams(WRAP, WRAP))
        val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp.leftMargin = dp(6)
        bar.addView(button("Salvar imagem") { saveImage() }, lp)
        return bar
    }

    private fun buildPool(): View {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(AppSettings.panelColor)
        panel.setPadding(dp(8), dp(6), dp(8), dp(6))

        poolHeader = TextView(this)
        poolHeader.setTextColor(AppSettings.textColor)
        poolHeader.alpha = 0.75f
        poolHeader.textSize = 13f

        val sv = ScrollView(this)
        poolFlow = FlowLayout(this)
        sv.addView(poolFlow, ViewGroup.LayoutParams(MATCH, WRAP))

        panel.addView(poolHeader, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(sv, LinearLayout.LayoutParams(MATCH, MATCH))
        panel.setOnDragListener(makeDropListener(state.pool, null))
        return panel
    }

    private fun buildBottomBar(): View {
        val bar = hbox()
        bar.setBackgroundColor(AppSettings.panelColor)
        bar.setPadding(dp(4), dp(4), dp(4), dp(4))
        bar.addView(button("Adicionar imagens") { pickImages.launch("image/*") }, LinearLayout.LayoutParams(0, WRAP, 1f))

        val trash = TextView(this)
        trash.text = "🗑 Lixeira"
        trash.textSize = 14f
        trash.setTextColor(Color.WHITE)
        trash.gravity = Gravity.CENTER
        trash.setPadding(dp(14), dp(12), dp(14), dp(12))
        val g = GradientDrawable()
        g.cornerRadius = dp(6).toFloat()
        g.setColor(0xFF7A1F1F.toInt())
        trash.background = g
        trash.setOnDragListener { v, ev ->
            when (ev.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    v.alpha = 0.6f
                    true
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    v.alpha = 1f
                    true
                }
                DragEvent.ACTION_DROP -> {
                    v.alpha = 1f
                    val id = ev.clipData.getItemAt(0).text.toString()
                    ui.post { removeItem(id) }
                    true
                }
                else -> true
            }
        }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.leftMargin = dp(8)
        bar.addView(trash, lp)
        return bar
    }

    // ------------------------------------------------------------------ montagem da lista

    private fun rebuild() {
        live.clear()
        boardFrame.setBackgroundColor(state.bgColor)
        bgView.bitmap = bgBitmap
        refreshTitle()

        tiersBox.removeAllViews()
        for (tier in state.tiers) tiersBox.addView(buildTierRow(tier))

        poolFlow.removeAllViews()
        for (id in state.pool) poolFlow.addView(makeIcon(id, dp(state.iconDp), 256))
        poolHeader.text = "Sem tier (${state.pool.size}) - segure uma imagem e arraste"

        var maxDur = 0
        for (l in live) {
            val s = getSource(l.id)
            if (s != null) maxDur = max(maxDur, s.dur)
        }
        cycleMs = if (maxDur == 0) 0 else min(max(maxDur, 500), 15000)
        t0 = SystemClock.uptimeMillis()
        if (cycleMs > 0) renderLiveAt(0)
    }

    private fun titleTypeface(): Typeface {
        val family = when (state.titleFont) {
            1 -> "serif"
            2 -> "monospace"
            3 -> "cursive"
            4 -> "casual"
            5 -> "sans-serif-condensed"
            6 -> "sans-serif-black"
            else -> "sans-serif"
        }
        return Typeface.create(family, if (state.titleBold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun refreshTitle() {
        val imgId = state.titleImage
        val bmp = if (imgId != null) getBitmap(imgId) else null
        if (bmp != null) {
            titleView.visibility = View.GONE
            titleImageView.setImageBitmap(bmp)
            val lp = LinearLayout.LayoutParams(WRAP, dp(state.titleImageDp))
            lp.gravity = when (state.titleAlign) {
                0 -> Gravity.START
                2 -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
            lp.bottomMargin = dp(12)
            titleImageView.layoutParams = lp
            titleImageView.visibility = View.VISIBLE
        } else {
            titleImageView.visibility = View.GONE
            titleView.text = state.title
            titleView.setTextColor(state.titleColor)
            titleView.textSize = state.titleSize.toFloat()
            titleView.setTypeface(titleTypeface())
            titleView.gravity = when (state.titleAlign) {
                0 -> Gravity.START
                2 -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
            titleView.visibility = if (state.title.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun buildTierRow(tier: Tier): View {
        val cell = dp(tier.iconDp)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        // Barra lateral: a imagem do tier é sempre um quadrado 1x1 (do tamanho dos ícones),
        // posicionado no topo; o tier cresce com as linhas, a imagem não estica.
        val labelBox = FrameLayout(this)
        labelBox.setBackgroundColor(tier.color)
        val labelId = tier.labelImage
        var hasImg = false
        if (labelId != null && Store.png(this, labelId).exists()) {
            hasImg = true
            labelBox.addView(makeIcon(labelId, cell, 384, false), FrameLayout.LayoutParams(cell, cell))
        }
        if (tier.name.isNotBlank()) {
            val name = TextView(this)
            name.text = tier.name
            name.gravity = Gravity.CENTER
            name.textSize = 16f
            name.setTypeface(Typeface.DEFAULT_BOLD)
            name.setTextColor(state.tierTextColor)
            name.setPadding(dp(4), dp(4), dp(4), dp(4))
            if (hasImg) {
                name.setShadowLayer(6f, 0f, 0f, Color.BLACK)
                labelBox.addView(name, FrameLayout.LayoutParams(cell, cell))
            } else {
                labelBox.addView(name, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        }
        val labelW = if (hasImg) cell else max(cell, dp(80))
        row.addView(labelBox, LinearLayout.LayoutParams(labelW, MATCH))

        val flow = FlowLayout(this)
        flow.setBackgroundColor(state.areaColor)
        flow.minimumHeight = tier.rows * cell
        for (id in tier.items) flow.addView(makeIcon(id, cell, 256))
        row.addView(flow, LinearLayout.LayoutParams(0, WRAP, 1f))

        row.setOnDragListener(makeDropListener(tier.items, flow))
        row.setOnLongClickListener { showTierEditor(tier); true }

        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = dp(3)
        row.layoutParams = lp
        return row
    }

    /**
     * Todo ícone é uma célula quadrada (1x1). Toque rápido = menu; segurar um instante e
     * mover = arrastar (sem abrir o menu).
     */
    private fun makeIcon(id: String, cell: Int, outSize: Int, interactive: Boolean = true): View {
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.layoutParams = ViewGroup.LayoutParams(cell, cell)
        val staticBmp = getBitmap(id)
        val animated = AppSettings.animEnabled && state.anims.containsKey(id) && getSource(id) != null
        if (animated) {
            val target = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
            val c = Canvas(target)
            if (staticBmp != null) c.drawBitmap(staticBmp, null, Rect(0, 0, outSize, outSize), null)
            iv.setImageBitmap(target)
            live.add(Live(iv, id, target, c))
        } else if (staticBmp != null) {
            iv.setImageBitmap(staticBmp)
        }

        if (!interactive) return iv

        iv.setOnClickListener { showItemMenu(id) }

        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        iv.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var starter: Runnable? = null

            private fun cancel(v: View) {
                val r = starter
                if (r != null) v.removeCallbacks(r)
                starter = null
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        cancel(v)
                        val r = Runnable {
                            starter = null
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            v.startDragAndDrop(ClipData.newPlainText("id", id), View.DragShadowBuilder(v), null, 0)
                        }
                        starter = r
                        v.postDelayed(r, 220)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (hypot(e.rawX - downX, e.rawY - downY) > slop) cancel(v)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancel(v)
                }
                return false
            }
        })
        return iv
    }

    private fun getBitmap(id: String): Bitmap? {
        val cached = cache[id]
        if (cached != null) return cached
        val f = Store.png(this, id)
        if (!f.exists()) return null
        val b = BitmapFactory.decodeFile(f.path) ?: return null
        cache[id] = b
        return b
    }

    private fun deleteImage(id: String) {
        cache.remove(id)
        sources.remove(id)
        state.anims.remove(id)
        Store.deleteImage(this, id)
    }

    // ------------------------------------------------------------------ animação (GIFs sincronizados)

    private fun getSource(id: String): AnimSrc? {
        val cached = sources[id]
        if (cached != null) return cached
        if (badSources.contains(id)) return null
        val f = Store.gif(this, id)
        if (!f.exists()) {
            badSources.add(id)
            return null
        }
        return try {
            val bytes = f.readBytes()
            val m = Movie.decodeByteArray(bytes, 0, bytes.size)
            if (m != null && m.duration() > 0) {
                val s = AnimSrc(m, m.duration())
                sources[id] = s
                s
            } else {
                badSources.add(id)
                null
            }
        } catch (e: Exception) {
            badSources.add(id)
            null
        }
    }

    /**
     * Todos os GIFs seguem o mesmo relógio. O ciclo da lista tem a duração do GIF mais longo; cada GIF
     * repete k vezes (k = ciclo / duração do GIF, arredondado) com velocidade ajustada para fechar o
     * ciclo exatamente. Assim nenhum congela esperando outro e todos reiniciam juntos.
     */
    private fun drawFrame(l: Live, t: Int) {
        val src = getSource(l.id) ?: return
        val meta = state.anims[l.id] ?: return
        val k = max(1, (cycleMs.toFloat() / src.dur).roundToInt())
        val phase = ((t.toLong() * k) % cycleMs).toFloat() / cycleMs
        val frameTime = (phase * src.dur).toInt().coerceIn(0, src.dur - 1)
        src.movie.setTime(frameTime)
        l.bmp.eraseColor(Color.TRANSPARENT)
        val c = l.canvas
        c.save()
        val sc = l.bmp.width.toFloat() / max(1, meta.size)
        c.scale(sc, sc)
        c.translate(-meta.cx.toFloat(), -meta.cy.toFloat())
        src.movie.draw(c, 0f, 0f)
        c.restore()
    }

    private fun renderLive() {
        if (live.isEmpty() || cycleMs <= 0) return
        val t = ((SystemClock.uptimeMillis() - t0) % cycleMs).toInt()
        val r = Rect()
        for (l in live) {
            if (!l.view.isShown || !l.view.getLocalVisibleRect(r)) continue
            drawFrame(l, t)
            l.view.invalidate()
        }
    }

    private fun renderLiveAt(t: Int) {
        for (l in live) {
            drawFrame(l, t)
            l.view.invalidate()
        }
    }

    // ------------------------------------------------------------------ arrastar e soltar

    private fun makeDropListener(target: MutableList<String>, flow: FlowLayout?): View.OnDragListener {
        return View.OnDragListener { v, ev ->
            when (ev.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    v.alpha = 0.75f
                    true
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    v.alpha = 1f
                    true
                }
                DragEvent.ACTION_DROP -> {
                    v.alpha = 1f
                    val id = ev.clipData.getItemAt(0).text.toString()
                    var idx = target.size
                    if (flow != null) idx = insertIndex(flow, ev.x - flow.left, ev.y - flow.top)
                    ui.post { moveItem(id, target, idx) }
                    true
                }
                else -> true
            }
        }
    }

    private fun insertIndex(flow: FlowLayout, x: Float, y: Float): Int {
        var n = 0
        for (i in 0 until flow.childCount) {
            val c = flow.getChildAt(i)
            if (y > c.bottom || (y >= c.top && x > c.left + c.width / 2f)) n++ else break
        }
        return n
    }

    private fun findList(id: String): MutableList<String>? {
        if (state.pool.contains(id)) return state.pool
        for (t in state.tiers) if (t.items.contains(id)) return t.items
        return null
    }

    private fun moveItem(id: String, target: MutableList<String>, idx: Int) {
        var i = idx
        val src = findList(id)
        if (src != null) {
            val old = src.indexOf(id)
            src.removeAt(old)
            if (src === target && old < i) i--
        }
        target.add(i.coerceIn(0, target.size), id)
        save()
        rebuild()
    }

    private fun removeItem(id: String) {
        findList(id)?.remove(id)
        deleteImage(id)
        save()
        rebuild()
    }

    private fun showItemMenu(id: String) {
        val names = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        for (t in state.tiers) {
            names.add("Mover para: ${t.name}")
            actions.add { moveItem(id, t.items, t.items.size) }
        }
        names.add("Mover para: sem tier")
        actions.add { moveItem(id, state.pool, state.pool.size) }
        names.add("Remover imagem")
        actions.add { removeItem(id) }
        AlertDialog.Builder(this)
            .setItems(names.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    // ------------------------------------------------------------------ importação (com recorte 1x1)

    private fun isGif(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { s ->
                val h = ByteArray(3)
                s.read(h) == 3 && h[0] == 'G'.code.toByte() && h[1] == 'I'.code.toByte() && h[2] == 'F'.code.toByte()
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun loadForCrop(uri: Uri): Loaded {
        val bmp = decodeFit(uri, 2048)
        if (AppSettings.animEnabled && isGif(uri)) {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                val m = Movie.decodeByteArray(bytes, 0, bytes.size)
                if (m != null && m.duration() > 0) return Loaded(bmp, bytes, m.width())
            }
        }
        return Loaded(bmp, null, 0)
    }

    /** Salva o recorte quadrado (ou o centro, se rect for nulo). Para GIFs guarda também o original. */
    private fun saveCropped(l: Loaded, rect: Rect?, outSize: Int): String {
        val side = min(l.bmp.width, l.bmp.height)
        val r = rect ?: Rect(
            (l.bmp.width - side) / 2, (l.bmp.height - side) / 2,
            (l.bmp.width - side) / 2 + side, (l.bmp.height - side) / 2 + side
        )
        val sq = Bitmap.createBitmap(l.bmp, r.left, r.top, r.width(), r.height())
        val out = if (sq.width != outSize) Bitmap.createScaledBitmap(sq, outSize, outSize, true) else sq
        val id = UUID.randomUUID().toString()
        FileOutputStream(Store.png(this, id)).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cache[id] = out
        val gif = l.gif
        if (gif != null && l.mw > 0) {
            FileOutputStream(Store.gif(this, id)).use { it.write(gif) }
            val sx = l.mw.toFloat() / l.bmp.width
            val cx = (r.left * sx).roundToInt()
            val cy = (r.top * sx).roundToInt()
            val size = max(1, (r.width() * sx).roundToInt())
            state.anims[id] = AnimMeta(cx, cy, size)
        }
        return id
    }

    private fun processQueue(uris: List<Uri>, idx: Int, added: ArrayList<String>, centerAll: Boolean) {
        if (idx >= uris.size) {
            state.pool.addAll(added)
            save()
            rebuild()
            return
        }
        io.execute {
            val loaded = try {
                loadForCrop(uris[idx])
            } catch (e: Exception) {
                null
            }
            ui.post {
                if (loaded == null) {
                    toast("Uma imagem não pôde ser lida")
                    processQueue(uris, idx + 1, added, centerAll)
                } else if (centerAll) {
                    added.add(saveCropped(loaded, null, 256))
                    processQueue(uris, idx + 1, added, true)
                } else {
                    val remaining = uris.size - idx
                    showCropper(loaded.bmp, "Recortar imagem ${idx + 1} de ${uris.size}", remaining > 1) { action, rect ->
                        when (action) {
                            0 -> {
                                added.add(saveCropped(loaded, rect, 256))
                                processQueue(uris, idx + 1, added, false)
                            }
                            1 -> {
                                added.add(saveCropped(loaded, null, 256))
                                processQueue(uris, idx + 1, added, false)
                            }
                            2 -> {
                                added.add(saveCropped(loaded, null, 256))
                                processQueue(uris, idx + 1, added, true)
                            }
                            else -> {
                                state.pool.addAll(added)
                                save()
                                rebuild()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun importLabel(uri: Uri) {
        io.execute {
            val loaded = try {
                loadForCrop(uri)
            } catch (e: Exception) {
                null
            }
            ui.post {
                if (loaded == null) {
                    toast("Não foi possível usar essa imagem")
                } else {
                    showCropper(loaded.bmp, "Recortar imagem do tier", false) { action, rect ->
                        if (action != 3) {
                            val id = saveCropped(loaded, if (action == 0) rect else null, 384)
                            pendingCb?.invoke(id)
                        }
                    }
                }
            }
        }
    }

    private fun importTitleImage(uri: Uri) {
        io.execute {
            try {
                val bmp = decodeFit(uri, 1200)
                val id = UUID.randomUUID().toString()
                FileOutputStream(Store.png(this, id)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                cache[id] = bmp
                ui.post { pendingCb?.invoke(id) }
            } catch (e: Exception) {
                ui.post { toast("Não foi possível usar essa imagem") }
            }
        }
    }

    private fun importBackground(uri: Uri) {
        io.execute {
            try {
                val bmp = decodeFit(uri, 1600)
                FileOutputStream(Store.bgFile(this, listId)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                ui.post {
                    bgBitmap = bmp
                    state.hasBgImage = true
                    save()
                    rebuild()
                }
            } catch (e: Exception) {
                ui.post { toast("Não foi possível usar essa imagem") }
            }
        }
    }

    private fun removeBackground() {
        Store.bgFile(this, listId).delete()
        bgBitmap = null
        state.hasBgImage = false
        save()
        rebuild()
    }

    // ------------------------------------------------------------------ editar tier (segurar o tier)

    private fun addTier() {
        val c = Color.parseColor(CLASSIC[state.tiers.size % CLASSIC.size])
        val size = if (state.tiers.isNotEmpty()) state.tiers.last().iconDp else state.iconDp
        val t = Tier("Novo", c, 1)
        t.iconDp = size
        state.tiers.add(t)
        save()
        rebuild()
        showTierEditor(t)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun deleteTier(t: Tier) {
        state.pool.addAll(t.items)
        val li = t.labelImage
        if (li != null) deleteImage(li)
        state.tiers.remove(t)
        save()
        rebuild()
    }

    private fun showTierEditor(t: Tier) {
        val box = dialogBox()
        val sv = ScrollView(this)
        sv.addView(box)

        box.addView(section("Nome do tier"))
        val nameEdit = EditText(this)
        nameEdit.setText(t.name)
        nameEdit.hint = "Nome"
        nameEdit.setSingleLine()
        nameEdit.doAfterTextChanged {
            t.name = it?.toString() ?: ""
            save()
            rebuild()
        }
        box.addView(nameEdit)

        box.addView(section("Cor do tier"))
        box.addView(colorRow("Cor", { t.color }) {
            t.color = it
            save()
            rebuild()
        })

        box.addView(section("Imagens neste tier"))
        addSeek(box, "Tamanho dos ícones", "dp", 40, 140, t.iconDp, false) {
            t.iconDp = it
            save()
            rebuild()
        }

        val rowsLabel = label(" ${t.rows} ", 16f, true)
        val rowsBox = hbox()
        rowsBox.addView(label("Espaço (linhas de ícones): ", 14f))
        rowsBox.addView(button("−") {
            if (t.rows > 1) {
                t.rows--
                rowsLabel.text = " ${t.rows} "
                save()
                rebuild()
            }
        })
        rowsBox.addView(rowsLabel)
        rowsBox.addView(button("+") {
            if (t.rows < 8) {
                t.rows++
                rowsLabel.text = " ${t.rows} "
                save()
                rebuild()
            }
        })
        box.addView(rowsBox)

        box.addView(section("Imagem do tier (quadrada 1x1, do tamanho dos ícones)"))
        val imgInfo = label(if (t.labelImage != null) "Imagem definida" else "Sem imagem", 14f)
        box.addView(imgInfo)
        val imgRow = hbox()
        imgRow.addView(button("Adicionar / trocar") {
            pendingCb = { id ->
                val old = t.labelImage
                t.labelImage = id
                if (old != null) deleteImage(old)
                imgInfo.text = "Imagem definida"
                save()
                rebuild()
            }
            pickLabel.launch("image/*")
        })
        imgRow.addView(button("Remover") {
            val old = t.labelImage
            if (old != null) {
                t.labelImage = null
                deleteImage(old)
                imgInfo.text = "Sem imagem"
                save()
                rebuild()
            }
        })
        box.addView(imgRow)

        box.addView(section("Ordem"))
        val moveRow = hbox()
        moveRow.addView(button("▲ Subir") {
            val i = state.tiers.indexOf(t)
            if (i > 0) {
                Collections.swap(state.tiers, i, i - 1)
                save()
                rebuild()
            }
        })
        moveRow.addView(button("▼ Descer") {
            val i = state.tiers.indexOf(t)
            if (i >= 0 && i < state.tiers.size - 1) {
                Collections.swap(state.tiers, i, i + 1)
                save()
                rebuild()
            }
        })
        box.addView(moveRow)

        AlertDialog.Builder(this)
            .setTitle("Editar tier")
            .setView(sv)
            .setPositiveButton("Concluído", null)
            .setNeutralButton("Excluir tier") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Excluir este tier?")
                    .setMessage("As imagens dele voltam para \"sem tier\".")
                    .setPositiveButton("Excluir") { _, _ -> deleteTier(t) }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .show()
    }

    // ------------------------------------------------------------------ editar título (segurar o título)

    private fun showTitleEditor() {
        val box = dialogBox()
        val sv = ScrollView(this)
        sv.addView(box)

        box.addView(section("Texto"))
        val edit = EditText(this)
        edit.setText(state.title)
        edit.hint = "Título"
        edit.doAfterTextChanged {
            state.title = it?.toString() ?: ""
            save()
            refreshTitle()
        }
        box.addView(edit)

        box.addView(section("Fonte"))
        val fontBtns = ArrayList<Button>()
        val hs = HorizontalScrollView(this)
        val fontRow = hbox()
        FONT_NAMES.forEachIndexed { i, n ->
            val b = button(n) {
                state.titleFont = i
                save()
                refreshTitle()
                fontBtns.forEachIndexed { j, bb -> bb.alpha = if (j == i) 1f else 0.5f }
            }
            b.alpha = if (state.titleFont == i) 1f else 0.5f
            fontBtns.add(b)
            fontRow.addView(b)
        }
        hs.addView(fontRow)
        box.addView(hs)

        val bold = CheckBox(this)
        bold.text = "Negrito"
        bold.isChecked = state.titleBold
        bold.setOnCheckedChangeListener { _, c ->
            state.titleBold = c
            save()
            refreshTitle()
        }
        box.addView(bold)

        box.addView(section("Cor e tamanho"))
        box.addView(colorRow("Cor do texto", { state.titleColor }) {
            state.titleColor = it
            save()
            refreshTitle()
        })
        addSeek(box, "Tamanho do texto", "sp", 14, 60, state.titleSize, true) {
            state.titleSize = it
            save()
            refreshTitle()
        }

        box.addView(section("Posicionamento"))
        val alignRow = hbox()
        val alignNames = listOf("Esquerda", "Centro", "Direita")
        alignNames.forEachIndexed { i, n ->
            alignRow.addView(button(n) {
                state.titleAlign = i
                save()
                refreshTitle()
            })
        }
        box.addView(alignRow)

        box.addView(section("Imagem personalizada (no lugar do texto)"))
        val imgInfo = label(if (state.titleImage != null) "Usando imagem" else "Usando texto", 14f)
        box.addView(imgInfo)
        val imgRow = hbox()
        imgRow.addView(button("Escolher / trocar") {
            pendingCb = { id ->
                val old = state.titleImage
                state.titleImage = id
                if (old != null) deleteImage(old)
                imgInfo.text = "Usando imagem"
                save()
                refreshTitle()
            }
            pickTitleImg.launch("image/*")
        })
        imgRow.addView(button("Voltar ao texto") {
            val old = state.titleImage
            if (old != null) {
                state.titleImage = null
                deleteImage(old)
                imgInfo.text = "Usando texto"
                save()
                refreshTitle()
            }
        })
        box.addView(imgRow)
        addSeek(box, "Altura da imagem", "dp", 40, 300, state.titleImageDp, true) {
            state.titleImageDp = it
            save()
            refreshTitle()
        }

        AlertDialog.Builder(this)
            .setTitle("Editar título")
            .setView(sv)
            .setPositiveButton("Concluído", null)
            .show()
    }

    // ------------------------------------------------------------------ editar fundo e tema (segurar o fundo)

    private fun applyTheme(s: TierState, t: Theme) {
        s.bgColor = Color.parseColor(t.bg)
        s.titleColor = Color.parseColor(t.title)
        s.areaColor = Color.parseColor(t.area)
        s.tierTextColor = Color.parseColor(t.tierText)
        s.tiers.forEachIndexed { i, tier ->
            tier.color = Color.parseColor(t.palette[i % t.palette.size])
        }
    }

    private fun showBackgroundEditor() {
        val box = dialogBox()
        val sv = ScrollView(this)
        sv.addView(box)

        fun render() {
            val keepY = sv.scrollY
            box.removeAllViews()

            box.addView(section("Tema pronto"))
            val hs = HorizontalScrollView(this)
            val themeRow = hbox()
            for (t in THEMES) {
                themeRow.addView(button(t.name) {
                    applyTheme(state, t)
                    save()
                    rebuild()
                    render()
                })
            }
            hs.addView(themeRow)
            box.addView(hs)

            box.addView(section("Cores"))
            box.addView(colorRow("Plano de fundo", { state.bgColor }) {
                state.bgColor = it
                save()
                rebuild()
            })
            box.addView(colorRow("Fonte dos tiers", { state.tierTextColor }) {
                state.tierTextColor = it
                save()
                rebuild()
            })
            box.addView(colorRow("Área dos tiers", { state.areaColor }) {
                state.areaColor = it
                save()
                rebuild()
            })

            box.addView(section("Imagem de fundo"))
            val bgRow = hbox()
            bgRow.addView(button("Escolher imagem") { pickBg.launch("image/*") })
            bgRow.addView(button("Remover") { removeBackground() })
            box.addView(bgRow)

            box.addView(section("Título"))
            box.addView(button("Editar título") { showTitleEditor() })

            sv.post { sv.scrollTo(0, keepY) }
        }

        render()
        AlertDialog.Builder(this)
            .setTitle("Fundo e tema")
            .setView(sv)
            .setPositiveButton("Concluído", null)
            .show()
    }

    // ------------------------------------------------------------------ salvar imagem

    private fun renderBoard(forGif: Boolean): Bitmap {
        val w = boardFrame.width
        val h = boardFrame.height
        var scale = if (forGif) min(1f, 720f / w) else 2f
        if (!forGif && (h * scale > 9000f || w * scale > 4096f)) scale = 1f
        val bmp = Bitmap.createBitmap(
            max(1, (w * scale).toInt()),
            max(1, (h * scale).toInt()),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)
        boardFrame.draw(canvas)
        return bmp
    }

    private fun saveImage() {
        if (live.isNotEmpty() && cycleMs > 0) {
            AlertDialog.Builder(this)
                .setTitle("Salvar como")
                .setItems(arrayOf("GIF animado", "Imagem estática (PNG)")) { _, which ->
                    if (which == 0) exportGif() else savePngAsync()
                }
                .show()
        } else {
            savePngAsync()
        }
    }

    private fun savePngAsync() {
        val bmp = renderBoard(false)
        io.execute {
            val ok = savePng(bmp)
            ui.post { toast(if (ok) "Imagem salva em Imagens/TierMaker" else "Falha ao salvar a imagem") }
        }
    }

    private fun newMediaUri(name: String, mime: String): Uri? {
        val values = ContentValues()
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name)
        values.put(MediaStore.Images.Media.MIME_TYPE, mime)
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TierMaker")
        return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun savePng(bmp: Bitmap): Boolean {
        return try {
            val uri = newMediaUri("tierlist_${System.currentTimeMillis()}.png", "image/png")
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun renderBoardAt(t: Int): Bitmap? {
        val latch = CountDownLatch(1)
        var out: Bitmap? = null
        ui.post {
            try {
                renderLiveAt(t)
                out = renderBoard(true)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return out
    }

    /** Salva a lista como GIF animado: todos os GIFs no mesmo ciclo e com o mesmo número de quadros. */
    private fun exportGif() {
        val fps = max(1, AppSettings.animFps)
        val total = cycleMs
        val n = ((total * fps) / 1000f).roundToInt().coerceIn(2, 150)
        val delayCs = max(2, (total.toFloat() / n / 10f).roundToInt())

        val progress = label("Preparando...", 16f)
        progress.setPadding(dp(24), dp(20), dp(24), dp(20))
        val dlg = AlertDialog.Builder(this)
            .setTitle("Salvando GIF animado")
            .setView(progress)
            .setCancelable(false)
            .create()
        dlg.show()

        Thread {
            var ok = false
            var uri: Uri? = null
            try {
                uri = newMediaUri("tierlist_${System.currentTimeMillis()}.gif", "image/gif")
                val os = BufferedOutputStream(contentResolver.openOutputStream(uri!!)!!)
                var writer: GifWriter? = null
                for (i in 0 until n) {
                    val t = (i.toLong() * total / n).toInt()
                    val bmp = renderBoardAt(t) ?: throw IllegalStateException("render")
                    val w = writer ?: GifWriter(os, bmp.width, bmp.height)
                    writer = w
                    w.addFrame(bmp, delayCs)
                    bmp.recycle()
                    ui.post { progress.text = "Quadro ${i + 1} de $n" }
                }
                writer?.finish()
                os.close()
                ok = true
            } catch (e: Exception) {
                try {
                    val u = uri
                    if (u != null) contentResolver.delete(u, null, null)
                } catch (e2: Exception) {
                    // ignora
                }
            }
            ui.post {
                dlg.dismiss()
                toast(if (ok) "GIF salvo em Imagens/TierMaker" else "Falha ao salvar o GIF")
            }
        }.start()
    }
}
