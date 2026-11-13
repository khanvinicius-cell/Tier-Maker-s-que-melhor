package com.khancer.tiermaker

import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentValues
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
import android.widget.CheckBox
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
    private lateinit var titleImageView: ImageView
    private lateinit var tiersBox: LinearLayout
    private lateinit var poolFlow: FlowLayout
    private lateinit var poolHeader: TextView

    /** Chamado quando uma imagem escolhida (tier ou título) termina de ser importada. */
    private var pendingCb: ((String) -> Unit)? = null

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) importImages(uris)
        }
    private val pickBg =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importBackground(uri)
        }
    private val pickLabel =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importPicked(uri, true)
        }
    private val pickTitleImg =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importPicked(uri, false)
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

        if (state.hasBgImage && bgFile.exists()) {
            bgBitmap = BitmapFactory.decodeFile(bgFile.path)
        }
        rebuild()

        val prefs = getSharedPreferences("tm", MODE_PRIVATE)
        if (!prefs.getBoolean("hint", false)) {
            prefs.edit().putBoolean("hint", true).apply()
            Toast.makeText(this, "Dica: segure um tier, o título ou o fundo para editar", Toast.LENGTH_LONG).show()
        }
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

    private fun dialogBox(): LinearLayout {
        val b = vbox()
        b.setPadding(dp(16), dp(8), dp(16), dp(8))
        return b
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

    /** Linha "nome + quadradinho de cor". O quadradinho se atualiza sozinho ao escolher. */
    private fun colorRow(text: String, getColor: () -> Int, onPick: (Int) -> Unit): View {
        val r = hbox()
        r.setPadding(0, dp(4), 0, dp(4))
        r.addView(label(text, 15f), LinearLayout.LayoutParams(0, WRAP, 1f))
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

    private fun addSeek(
        box: LinearLayout, title: String, unit: String,
        minV: Int, maxV: Int, value: Int, live: Boolean, onChange: (Int) -> Unit
    ) {
        val lbl = label("$title: $value $unit", 14f)
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

    private fun buildTopBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(0xFF1A1A1A.toInt())
        bar.setPadding(dp(4), dp(4), dp(4), dp(4))
        bar.addView(button("Salvar imagem") { saveImage() }, LinearLayout.LayoutParams(MATCH, WRAP))
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

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(0xFF1A1A1A.toInt())
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
        boardFrame.setBackgroundColor(state.bgColor)
        bgView.bitmap = bgBitmap
        refreshTitle()

        tiersBox.removeAllViews()
        for (tier in state.tiers) tiersBox.addView(buildTierRow(tier))

        poolFlow.removeAllViews()
        for (id in state.pool) poolFlow.addView(makeIcon(id, dp(state.iconDp)))
        poolHeader.text = "Sem tier (${state.pool.size}) - segure uma imagem e arraste"
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
        for (id in tier.items) flow.addView(makeIcon(id, cell))
        row.addView(flow, LinearLayout.LayoutParams(0, WRAP, 1f))

        row.setOnDragListener(makeDropListener(tier.items, flow))
        row.setOnLongClickListener { showTierEditor(tier); true }

        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = dp(3)
        row.layoutParams = lp
        return row
    }

    /** Todo ícone é uma célula quadrada (1x1) com a imagem em center-crop. */
    private fun makeIcon(id: String, cell: Int): View {
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

    private fun deleteImage(id: String) {
        cache.remove(id)
        File(imgDir, "$id.png").delete()
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
        deleteImage(id)
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

    /** Mantém a proporção original, só reduzindo se o lado maior passar de maxSide. */
    private fun decodeFit(uri: Uri, maxSide: Int): Bitmap {
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

    /** Imagem do tier (quadrada) ou do título (proporção original); devolve o id via pendingCb. */
    private fun importPicked(uri: Uri, square: Boolean) {
        io.execute {
            try {
                val bmp = if (square) decodeSquare(uri, 256) else decodeFit(uri, 1200)
                val id = UUID.randomUUID().toString()
                FileOutputStream(File(imgDir, "$id.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
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

    // ------------------------------------------------------------------ editar tier (segurar o tier)

    private fun addTier() {
        val c = Color.parseColor(CLASSIC[state.tiers.size % CLASSIC.size])
        val size = if (state.tiers.isNotEmpty()) state.tiers.last().iconDp else state.iconDp
        val t = Tier("Novo", c, 1)
        t.iconDp = size
        state.tiers.add(t)
        saveState()
        rebuild()
        showTierEditor(t)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun deleteTier(t: Tier) {
        state.pool.addAll(t.items)
        val li = t.labelImage
        if (li != null) deleteImage(li)
        state.tiers.remove(t)
        saveState()
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
            saveState()
            rebuild()
        }
        box.addView(nameEdit)

        box.addView(section("Cor do tier"))
        box.addView(colorRow("Cor", { t.color }) {
            t.color = it
            saveState()
            rebuild()
        })

        box.addView(section("Imagens neste tier"))
        addSeek(box, "Tamanho dos ícones", "dp", 40, 140, t.iconDp, false) {
            t.iconDp = it
            saveState()
            rebuild()
        }

        val rowsLabel = label(" ${t.rows} ", 16f, true)
        val rowsBox = hbox()
        rowsBox.addView(label("Espaço (linhas de ícones): ", 14f))
        rowsBox.addView(button("−") {
            if (t.rows > 1) {
                t.rows--
                rowsLabel.text = " ${t.rows} "
                saveState()
                rebuild()
            }
        })
        rowsBox.addView(rowsLabel)
        rowsBox.addView(button("+") {
            if (t.rows < 8) {
                t.rows++
                rowsLabel.text = " ${t.rows} "
                saveState()
                rebuild()
            }
        })
        box.addView(rowsBox)

        box.addView(section("Imagem do tier (barra lateral)"))
        val imgInfo = label(if (t.labelImage != null) "Imagem definida" else "Sem imagem", 14f)
        box.addView(imgInfo)
        val imgRow = hbox()
        imgRow.addView(button("Adicionar / trocar") {
            pendingCb = { id ->
                val old = t.labelImage
                t.labelImage = id
                if (old != null) deleteImage(old)
                imgInfo.text = "Imagem definida"
                saveState()
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
                saveState()
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
                saveState()
                rebuild()
            }
        })
        moveRow.addView(button("▼ Descer") {
            val i = state.tiers.indexOf(t)
            if (i >= 0 && i < state.tiers.size - 1) {
                Collections.swap(state.tiers, i, i + 1)
                saveState()
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
            saveState()
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
                saveState()
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
            saveState()
            refreshTitle()
        }
        box.addView(bold)

        box.addView(section("Cor e tamanho"))
        box.addView(colorRow("Cor do texto", { state.titleColor }) {
            state.titleColor = it
            saveState()
            refreshTitle()
        })
        addSeek(box, "Tamanho do texto", "sp", 14, 60, state.titleSize, true) {
            state.titleSize = it
            saveState()
            refreshTitle()
        }

        box.addView(section("Posicionamento"))
        val alignRow = hbox()
        val alignNames = listOf("Esquerda", "Centro", "Direita")
        alignNames.forEachIndexed { i, n ->
            alignRow.addView(button(n) {
                state.titleAlign = i
                saveState()
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
                saveState()
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
                saveState()
                refreshTitle()
            }
        })
        box.addView(imgRow)
        addSeek(box, "Altura da imagem", "dp", 40, 300, state.titleImageDp, true) {
            state.titleImageDp = it
            saveState()
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
                    saveState()
                    rebuild()
                    render()
                })
            }
            hs.addView(themeRow)
            box.addView(hs)

            box.addView(section("Cores"))
            box.addView(colorRow("Plano de fundo", { state.bgColor }) {
                state.bgColor = it
                saveState()
                rebuild()
            })
            box.addView(colorRow("Fonte dos tiers", { state.tierTextColor }) {
                state.tierTextColor = it
                saveState()
                rebuild()
            })
            box.addView(colorRow("Área dos tiers", { state.areaColor }) {
                state.areaColor = it
                saveState()
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

    private fun pickColor(initial: Int, onPick: (Int) -> Unit) {
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

    // ------------------------------------------------------------------ salvar imagem

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

    private fun saveImage() {
        val bmp = renderBoard()
        io.execute {
            val ok = savePng(bmp)
            ui.post { toast(if (ok) "Imagem salva em Imagens/TierMaker" else "Falha ao salvar a imagem") }
        }
    }

    private fun savePng(bmp: Bitmap): Boolean {
        return try {
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "tierlist_${System.currentTimeMillis()}.png")
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TierMaker")
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
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
}
