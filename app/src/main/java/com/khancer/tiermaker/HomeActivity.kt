package com.khancer.tiermaker

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.DateFormat
import java.util.Date

/** Página inicial: listas criadas, nova lista e configurações do app. */
class HomeActivity : BaseActivity() {

    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.migrateLegacy(this)

        val page = vbox()
        page.setPadding(dp(16), dp(28), dp(16), dp(8))

        page.addView(label("Tier Maker", 32f, true, true))
        val sub = label("Crie, edite e personalize suas listas", 14f, false, true)
        sub.alpha = 0.7f
        page.addView(sub)

        val row = hbox()
        val lpA = LinearLayout.LayoutParams(0, WRAP, 1f)
        lpA.rightMargin = dp(4)
        val lpB = LinearLayout.LayoutParams(0, WRAP, 1f)
        lpB.leftMargin = dp(4)
        row.addView(button("+ Nova lista") { showNewDialog() }, lpA)
        row.addView(button("Configurações") { startActivity(Intent(this, SettingsActivity::class.java)) }, lpB)
        val rowLp = LinearLayout.LayoutParams(MATCH, WRAP)
        rowLp.topMargin = dp(16)
        page.addView(row, rowLp)

        val sec = section("Minhas listas")
        page.addView(sec)

        val sv = ScrollView(this)
        listBox = vbox()
        sv.addView(listBox, LinearLayout.LayoutParams(MATCH, WRAP))
        page.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        setPage(page)
    }

    override fun onResume() {
        super.onResume()
        refreshLists()
    }

    private fun refreshLists() {
        listBox.removeAllViews()
        val entries = ArrayList<Triple<String, TierState, Long>>()
        for (id in Store.ids(this)) {
            val s = Store.load(this, id) ?: continue
            entries.add(Triple(id, s, Store.listFile(this, id).lastModified()))
        }
        entries.sortByDescending { it.third }

        if (entries.isEmpty()) {
            val empty = label("Nenhuma lista ainda. Toque em \"Nova lista\" para começar.", 15f, false, true)
            empty.alpha = 0.7f
            empty.setPadding(0, dp(16), 0, 0)
            listBox.addView(empty)
            return
        }
        for (e in entries) listBox.addView(buildCard(e.first, e.second, e.third))
    }

    private fun buildCard(id: String, s: TierState, modified: Long): View {
        val card = hbox()
        card.setPadding(dp(12), dp(12), dp(12), dp(12))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(12).toFloat()
        bg.setColor(AppSettings.panelColor)
        card.background = bg

        val thumb = ImageView(this)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        var firstId: String? = s.pool.firstOrNull()
        if (firstId == null) {
            for (t in s.tiers) {
                if (t.items.isNotEmpty()) {
                    firstId = t.items[0]
                    break
                }
            }
        }
        if (firstId == null) {
            for (t in s.tiers) {
                if (t.labelImage != null) {
                    firstId = t.labelImage
                    break
                }
            }
        }
        val f = if (firstId != null) Store.png(this, firstId) else null
        if (f != null && f.exists()) {
            thumb.setImageBitmap(BitmapFactory.decodeFile(f.path))
        } else {
            thumb.setBackgroundColor(if (s.tiers.isNotEmpty()) s.tiers[0].color else Color.GRAY)
        }
        card.addView(thumb, LinearLayout.LayoutParams(dp(56), dp(56)))

        val info = vbox()
        info.addView(label(s.displayName(), 17f, true, true))
        var images = s.pool.size
        for (t in s.tiers) images += t.items.size
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(modified))
        val det = label("${s.tiers.size} tiers • $images imagens • $date", 12f, false, true)
        det.alpha = 0.7f
        info.addView(det)
        val infoLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        infoLp.leftMargin = dp(12)
        card.addView(info, infoLp)

        card.addView(button("⋮") { showMenu(id, s) })
        card.setOnClickListener { openList(id) }

        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = dp(8)
        card.layoutParams = lp
        return card
    }

    private fun openList(id: String) {
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("listId", id)
        startActivity(i)
    }

    private fun showNewDialog() {
        val box = dialogBox()
        val edit = EditText(this)
        edit.setText("Minha Tier List")
        edit.setSelectAllOnFocus(true)
        edit.hint = "Nome da lista"
        edit.setSingleLine()
        box.addView(edit)
        AlertDialog.Builder(this)
            .setTitle("Nova lista")
            .setView(box)
            .setPositiveButton("Criar") { _, _ ->
                val name = edit.text.toString().trim()
                val id = Store.create(this, if (name.isEmpty()) "Minha Tier List" else name)
                openList(id)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showMenu(id: String, s: TierState) {
        AlertDialog.Builder(this)
            .setTitle(s.displayName())
            .setItems(arrayOf("Abrir", "Renomear", "Duplicar", "Excluir")) { _, which ->
                when (which) {
                    0 -> openList(id)
                    1 -> showRename(id, s)
                    2 -> {
                        Store.duplicate(this, id)
                        refreshLists()
                    }
                    else -> confirmDelete(id, s)
                }
            }
            .show()
    }

    private fun showRename(id: String, s: TierState) {
        val box = dialogBox()
        val edit = EditText(this)
        edit.setText(s.title)
        edit.setSingleLine()
        box.addView(edit)
        AlertDialog.Builder(this)
            .setTitle("Renomear lista")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                s.title = edit.text.toString().trim()
                Store.save(this, id, s)
                refreshLists()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDelete(id: String, s: TierState) {
        AlertDialog.Builder(this)
            .setTitle("Excluir lista?")
            .setMessage("\"${s.displayName()}\" e todas as imagens dela serão apagadas.")
            .setPositiveButton("Excluir") { _, _ ->
                Store.delete(this, id)
                refreshLists()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
