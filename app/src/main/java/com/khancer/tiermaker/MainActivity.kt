package com.khancer.tiermaker

import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val ACCENT = 0xFFFF8A3D.toInt()

class MainActivity : ComponentActivity() {

    private lateinit var state: TierState
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var bgBitmap: Bitmap? = null

    private lateinit var scroll: AutoScrollView
    private lateinit var boardFrame: FrameLayout
    private lateinit var bgView: CoverView
    private lateinit var titleView: TextView
    private lateinit var tiersBox: LinearLayout
    private lateinit var poolFlow: FlowLayout
    private lateinit var poolHeader: TextView

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) importImages(uris)
        }
    private val pickBg =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importBackground(uri)
        }

    private var pendingLabelCb: ((String) -> Unit)? = null
    private val pickLabel =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importLabelImage(uri)
        }

    private val stateFile: File get() = File(filesDir, "state.json")
    private val bgFile: File get() = File(filesDir, "bg.jpg")
    private val imgDir: File
        get() {
            val d = File(filesDir, "imgs")
            d.mkdirs()
            return d
        }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = loadState()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)
        root.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, WRAP))

        scroll = AutoScrollView(this)
        boardFrame = FrameLayout(this)
        bgView = CoverView(this)
        boardFrame.addView(bgView, FrameLayout.LayoutParams(MATCH, MATCH))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(8), dp(12), dp(8), dp(12))

        titleView = TextView(this)
        titleView.gravity = Gravity.CENTER
        titleView.textSize = 26f
        titleView.setTypeface(Typeface.DEFAULT_BOLD)
        titleView.setPadding(dp(8), dp(4), dp(8), dp(12))

        tiersBox = LinearLayout(this)
        tiersBox.orientation = LinearLayout.VERTICAL

        content.addView(titleView, LinearLayout.LayoutParams(MATCH, WRAP))
        content.addView(tiersBox, LinearLayout.LayoutParams(MATCH, WRAP))
        boardFrame.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        scroll.addView(boardFrame, ViewGroup.LayoutParams(MATCH, WRAP))

        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(buildPool(), LinearLayout.LayoutParams(MATCH, dp(170)))
        setContentView(root)

        if (state.hasBgImage && bgFile.exists()) {
            bgBitmap = BitmapFactory.decodeFile(bgFile.path)
        }
        rebuild()
    }

    // ------------------------------------------------------------------ helpers de UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun label(text: String, size: Float = 14f, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD)
        return t
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.minWidth = 0
        b.minimumWidth = 0
        b.setOnClickListener { onClick() }
        return b
    }

    private fun hbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    private fun vbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun swatch(color: Int, onClick: () -> Unit): View {
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

    private fun section(text: String): TextView {
        val t = label(text, 15f, true)
        t.setTextColor(ACCENT)
        t.setPadding(0, dp(16), 0, dp(4))
        return t
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(0xFF1A1A1A.toInt())
        bar.setPadding(dp(4), dp(4), dp(4), dp(4))
        bar.addView(button("+ Imagens") { pickImages.launch("image/*") }, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(button("Ajustes") { showSettings() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(button("Exportar") { showExportMenu() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        return bar
    }

    private fun buildPool(): View {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(0xFF1A1A1A.toInt())
        panel.setPadding(dp(8), dp(6), dp(8), dp(6))

        poolHeader = TextView(this)
        poolHeader.setTextColor(Color.LTGRAY)
        poolHeader.textSize = 13f

        val sv = ScrollView(this)
        poolFlow = FlowLayout(this)
        sv.addView(poolFlow, ViewGroup.LayoutParams(MATCH, WRAP))

        panel.addView(poolHeader, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(sv, LinearLayout.LayoutParams(MATCH, MATCH))
        panel.setOnDragListener(makeDropListener(state.pool, null))
        return panel
    }

    // ------------------------------------------------------------------ montagem da lista

    private fun rebuild() {
        boardFrame.setBackgroundColor(state.bgColor)
        bgView.bitmap = bgBitmap
        titleView.text = state.title
        titleView.setTextColor(state.titleColor)
        titleView.visibility = if (state.title.isBlank()) View.GONE else View.VISIBLE

        tiersBox.removeAllViews()
        for (tier in state.tiers) tiersBox.addView(buildTierRow(tier))

        poolFlow.removeAllViews()
        for (id in state.pool) poolFlow.addView(makeIcon(id))
        poolHeader.text = "Sem tier (${state.pool.size}) - toque e segure para arrastar"
    }

    private fun buildTierRow(tier: Tier): View {
        val cell = dp(state.iconDp)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val labelBox = FrameLayout(this)
        labelBox.setBackgroundColor(tier.color)
        val labelId = tier.labelImage
        val labelBmp = if (labelId != null) getBitmap(labelId) else null
        if (labelBmp != null) {
            val cover = CoverView(this)
            cover.bitmap = labelBmp
            labelBox.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        if (tier.name.isNotBlank()) {
            val name = TextView(this)
            name.text = tier.name
            name.gravity = Gravity.CENTER
            name.textSize = 16f
            name.setTypeface(Typeface.DEFAULT_BOLD)
            name.setTextColor(state.tierTextColor)
            name.setPadding(dp(4), dp(4), dp(4), dp(4))
            if (labelBmp != null) name.setShadowLayer(6f, 0f, 0f, Color.BLACK)
            labelBox.addView(name, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        row.addView(labelBox, LinearLayout.LayoutParams(max(cell, dp(80)), MATCH))

        val flow = FlowLayout(this)
        flow.setBackgroundColor(state.areaColor)
        flow.minimumHeight = tier.rows * cell
        for (id in tier.items) flow.addView(makeIcon(id))
        row.addView(flow, LinearLayout.LayoutParams(0, WRAP, 1f))

        row.setOnDragListener(makeDropListener(tier.items, flow))

        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = dp(3)
        row.layoutParams = lp
        return row
    }

    /** Todo ícone é uma célula quadrada (1x1) com a imagem em center-crop. */
    private fun makeIcon(id: String): View {
        val cell = dp(state.iconDp)
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.layoutParams = ViewGroup.LayoutParams(cell, cell)
        val bmp = getBitmap(id)
        if (bmp != null) iv.setImageBitmap(bmp)
        iv.setOnClickListener { showItemMenu(id) }
        iv.setOnLongClickListener { v ->
            v.startDragAndDrop(ClipData.newPlainText("id", id), View.DragShadowBuilder(v), null, 0)
            true
        }
        return iv
    }

    private fun getBitmap(id: String): Bitmap? {
        val cached = cache[id]
        if (cached != null) return cached
        val f = File(imgDir, "$id.png")
        if (!f.exists()) return null
        val b = BitmapFactory.decodeFile(f.path) ?: return null
        cache[id] = b
        return b
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
        saveState()
        rebuild()
    }

    private fun removeItem(id: String) {
        findList(id)?.remove(id)
        cache.remove(id)
        File(imgDir, "$id.png").delete()
        saveState()
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

    // ------------------------------------------------------------------ importação de imagens

    private fun importImages(uris: List<Uri>) {
        toast("Importando ${uris.size} imagem(ns)...")
        io.execute {
            val added = ArrayList<String>()
            for (u in uris) {
                try {
                    val bmp = decodeSquare(u, 256)
                    val id = UUID.randomUUID().toString()
                    FileOutputStream(File(imgDir, "$id.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    cache[id] = bmp
                    added.add(id)
                } catch (e: Exception) {
                    // ignora imagens que não puderem ser lidas
                }
            }
            ui.post {
                state.pool.addAll(added)
                saveState()
                rebuild()
                if (added.size < uris.size) toast("Algumas imagens não puderam ser lidas")
            }
        }
    }

    /** Recorta o centro da imagem em formato quadrado (1x1) e reduz para size x size. */
    private fun decodeSquare(uri: Uri, size: Int): Bitmap {
        val src = ImageDecoder.createSource(contentResolver, uri)
        val full = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val f = size.toFloat() / min(w, h)
            if (f < 1f) {
                decoder.setTargetSize(
                    max(size, (w * f).roundToInt()),
                    max(size, (h * f).roundToInt())
                )
            }
        }
        val s = min(full.width, full.height)
        val x = (full.width - s) / 2
        val y = (full.height - s) / 2
        val square = Bitmap.createBitmap(full, x, y, s, s)
        return if (s != size) Bitmap.createScaledBitmap(square, size, size, true) else square
    }

    private fun importLabelImage(uri: Uri) {
        io.execute {
            try {
                val bmp = decodeSquare(uri, 256)
                val id = UUID.randomUUID().toString()
                FileOutputStream(File(imgDir, "$id.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                cache[id] = bmp
                ui.post { pendingLabelCb?.invoke(id) }
            } catch (e: Exception) {
                ui.post { toast("Não foi possível usar essa imagem") }
            }
        }
    }

    private fun importBackground(uri: Uri) {
        io.execute {
            try {
                val src = ImageDecoder.createSource(contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val m = max(info.size.width, info.size.height)
                    if (m > 1600) {
                        val f = 1600f / m
                        decoder.setTargetSize(
                            max(1, (info.size.width * f).roundToInt()),
                            max(1, (info.size.height * f).roundToInt())
                        )
                    }
                }
                FileOutputStream(bgFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                ui.post {
                    bgBitmap = bmp
                    state.hasBgImage = true
                    saveState()
                    rebuild()
                }
            } catch (e: Exception) {
                ui.post { toast("Não foi possível usar essa imagem") }
            }
        }
    }

    private fun removeBackground() {
        bgFile.delete()
        bgBitmap = null
        state.hasBgImage = false
        saveState()
        rebuild()
    }

    // ------------------------------------------------------------------ persistência

    private fun loadState(): TierState {
        return try {
            if (stateFile.exists()) TierState.fromJson(stateFile.readText()) else TierState.default()
        } catch (e: Exception) {
            TierState.default()
        }
    }

    private fun saveState() {
        try {
            stateFile.writeText(state.toJson())
        } catch (e: Exception) {
            // ignora
        }
    }

    // ------------------------------------------------------------------ ajustes

    private fun applyTheme(s: TierState, t: Theme) {
        s.bgColor = Color.parseColor(t.bg)
        s.titleColor = Color.parseColor(t.title)
        s.areaColor = Color.parseColor(t.area)
        s.tierTextColor = Color.parseColor(t.tierText)
        s.tiers.forEachIndexed { i, tier ->
            tier.color = Color.parseColor(t.palette[i % t.palette.size])
        }
    }

    private fun commitSettings(d: TierState) {
        state.title = d.title
        state.titleColor = d.titleColor
        state.bgColor = d.bgColor
        state.areaColor = d.areaColor
        state.tierTextColor = d.tierTextColor
        state.iconDp = d.iconDp
        val oldLabels = state.tiers.mapNotNull { it.labelImage }
        state.tiers.clear()
        state.tiers.addAll(d.tiers)
        state.pool.clear()
        state.pool.addAll(d.pool)
        val newLabels = state.tiers.mapNotNull { it.labelImage }
        for (id in oldLabels) {
            if (id !in newLabels) {
                cache.remove(id)
                File(imgDir, "$id.png").delete()
            }
        }
        saveState()
        rebuild()
    }

    private fun colorRow(text: String, color: Int, onPick: (Int) -> Unit): View {
        val r = hbox()
        r.setPadding(0, dp(4), 0, dp(4))
        r.addView(label(text, 15f), LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(swatch(color) { pickColor(color, onPick) }, LinearLayout.LayoutParams(dp(48), dp(36)))
        return r
    }

    private fun showSettings() {
        val draft = state.deepCopy()
        val container = vbox()
        container.setPadding(dp(16), dp(8), dp(16), dp(8))
        val sv = ScrollView(this)
        sv.addView(container)

        fun render() {
            val keepY = sv.scrollY
            container.removeAllViews()

            container.addView(section("Título da lista"))
            val titleEdit = EditText(this)
            titleEdit.setText(draft.title)
            titleEdit.hint = "Título"
            titleEdit.doAfterTextChanged { draft.title = it?.toString() ?: "" }
            container.addView(titleEdit)

            container.addView(section("Tema pronto"))
            val hs = HorizontalScrollView(this)
            val themeRow = hbox()
            for (t in THEMES) themeRow.addView(button(t.name) { applyTheme(draft, t); render() })
            hs.addView(themeRow)
            container.addView(hs)

            container.addView(section("Cores"))
            container.addView(colorRow("Plano de fundo", draft.bgColor) { draft.bgColor = it; render() })
            container.addView(colorRow("Fonte do título", draft.titleColor) { draft.titleColor = it; render() })
            container.addView(colorRow("Fonte dos tiers", draft.tierTextColor) { draft.tierTextColor = it; render() })
            container.addView(colorRow("Área dos tiers", draft.areaColor) { draft.areaColor = it; render() })

            container.addView(section("Imagem de fundo"))
            val bgRow = hbox()
            bgRow.addView(button("Escolher imagem") { pickBg.launch("image/*") })
            bgRow.addView(button("Remover") { removeBackground() })
            container.addView(bgRow)

            container.addView(section("Tamanho dos ícones"))
            val sizeLabel = label("${draft.iconDp} dp", 14f)
            val seek = SeekBar(this)
            seek.max = 80
            seek.progress = draft.iconDp - 40
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    draft.iconDp = 40 + p
                    sizeLabel.text = "${draft.iconDp} dp"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            container.addView(sizeLabel)
            container.addView(seek)

            container.addView(section("Tiers (${draft.tiers.size})"))
            draft.tiers.forEachIndexed { i, t ->
                val card = vbox()
                card.setPadding(0, dp(8), 0, dp(8))

                val r1 = hbox()
                r1.addView(swatch(t.color) { pickColor(t.color) { c -> t.color = c; render() } },
                    LinearLayout.LayoutParams(dp(40), dp(40)))
                val nameEdit = EditText(this)
                nameEdit.setText(t.name)
                nameEdit.setSingleLine()
                nameEdit.hint = "Nome"
                nameEdit.doAfterTextChanged { t.name = it?.toString() ?: "" }
                r1.addView(nameEdit, LinearLayout.LayoutParams(0, WRAP, 1f))
                r1.addView(button("▲") { if (i > 0) { Collections.swap(draft.tiers, i, i - 1); render() } })
                r1.addView(button("▼") { if (i < draft.tiers.size - 1) { Collections.swap(draft.tiers, i, i + 1); render() } })
                r1.addView(button("✕") {
                    draft.pool.addAll(t.items)
                    draft.tiers.removeAt(i)
                    render()
                })
                card.addView(r1)

                val r2 = hbox()
                r2.addView(label("Espaço (linhas de ícones): ", 14f))
                r2.addView(button("−") { if (t.rows > 1) { t.rows--; render() } })
                r2.addView(label(" ${t.rows} ", 16f, true))
                r2.addView(button("+") { if (t.rows < 8) { t.rows++; render() } })
                card.addView(r2)

                val r3 = hbox()
                r3.addView(button(if (t.labelImage == null) "+ Imagem do tier" else "Trocar imagem") {
                    pendingLabelCb = { id -> t.labelImage = id; render() }
                    pickLabel.launch("image/*")
                })
                if (t.labelImage != null) {
                    r3.addView(button("Remover imagem") { t.labelImage = null; render() })
                }
                card.addView(r3)

                container.addView(card)
            }

            container.addView(button("+ Adicionar tier") {
                val c = Color.parseColor(CLASSIC[draft.tiers.size % CLASSIC.size])
                draft.tiers.add(Tier("Novo", c, 1))
                render()
            })
            container.addView(button("Devolver todas as imagens para \"sem tier\"") {
                for (t in draft.tiers) {
                    draft.pool.addAll(t.items)
                    t.items.clear()
                }
                toast("Feito. Toque em Aplicar para confirmar.")
            })

            sv.post { sv.scrollTo(0, keepY) }
        }

        render()
        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(sv)
            .setPositiveButton("Aplicar") { _, _ -> commitSettings(draft) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickColor(initial: Int, onPick: (Int) -> Unit) {
        val box = vbox()
        box.setPadding(dp(16), dp(8), dp(16), dp(8))
        val hex = EditText(this)
        hex.hint = "#RRGGBB"
        hex.setSingleLine()
        hex.setText(String.format("#%06X", initial and 0xFFFFFF))

        var dlg: AlertDialog? = null
        val colors = PALETTE.map { Color.parseColor(it) }
        for (r in 0 until 4) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER
            for (c in 0 until 6) {
                val col = colors[r * 6 + c]
                val lp = LinearLayout.LayoutParams(dp(44), dp(44))
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                row.addView(swatch(col) {
                    dlg?.dismiss()
                    onPick(col)
                }, lp)
            }
            box.addView(row)
        }
        box.addView(hex)

        dlg = AlertDialog.Builder(this)
            .setTitle("Escolher cor")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                try {
                    onPick(Color.parseColor(hex.text.toString().trim()))
                } catch (e: Exception) {
                    toast("Cor inválida")
                }
            }
            .setNegativeButton("Cancelar", null)
            .create()
        dlg?.show()
    }

    // ------------------------------------------------------------------ exportar

    private fun showExportMenu() {
        AlertDialog.Builder(this)
            .setTitle("Exportar como imagem")
            .setItems(arrayOf("Salvar na galeria", "Compartilhar")) { _, which -> export(which == 1) }
            .show()
    }

    private fun renderBoard(): Bitmap {
        val w = boardFrame.width
        val h = boardFrame.height
        var scale = 2f
        if (h * scale > 9000f || w * scale > 4096f) scale = 1f
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

    private fun export(share: Boolean) {
        val bmp = renderBoard()
        io.execute {
            val uri = savePng(bmp)
            ui.post {
                if (uri == null) {
                    toast("Falha ao salvar a imagem")
                } else if (share) {
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "image/png"
                    i.putExtra(Intent.EXTRA_STREAM, uri)
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(i, "Compartilhar tier list"))
                } else {
                    toast("Salva em Imagens/TierMaker")
                }
            }
        }
    }

    private fun savePng(bmp: Bitmap): Uri? {
        return try {
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "tierlist_${System.currentTimeMillis()}.png")
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TierMaker")
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            uri
        } catch (e: Exception) {
            null
        }
    }
}
