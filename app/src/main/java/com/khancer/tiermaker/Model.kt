package com.khancer.tiermaker

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

class Tier(
    var name: String,
    var color: Int,
    var rows: Int,
    val items: MutableList<String> = mutableListOf(),
    var labelImage: String? = null,
    var iconDp: Int = 64
)

/** Recorte quadrado (em pixels do GIF original) usado para desenhar um GIF animado como ícone 1x1. */
class AnimMeta(var cx: Int, var cy: Int, var size: Int)

class Theme(
    val name: String,
    val bg: String,
    val title: String,
    val area: String,
    val tierText: String,
    val palette: List<String>
)

val CLASSIC = listOf("#FF7F7F", "#FFBF7F", "#FFDF7F", "#FFFF7F", "#BFFF7F", "#7FFF7F", "#7FFFFF", "#7FBFFF")

val THEMES = listOf(
    Theme("Escuro", "#121212", "#FFFFFF", "#1F1F1F", "#000000", CLASSIC),
    Theme("Claro", "#F2F2F2", "#111111", "#FFFFFF", "#000000", CLASSIC),
    Theme("Neon", "#0B0B1A", "#00F0FF", "#15152B", "#0B0B1A",
        listOf("#FF2E97", "#FF8A00", "#FFE600", "#00F0FF", "#7A5CFF")),
    Theme("Oceano", "#0A1F33", "#E0F4FF", "#123556", "#06202F",
        listOf("#00B4D8", "#48CAE4", "#90E0EF", "#ADE8F4", "#CAF0F8")),
    Theme("Pôr do sol", "#12091F", "#FFD6A5", "#1E1230", "#FFFFFF",
        listOf("#FF6B35", "#F7931E", "#E0457B", "#8E44AD", "#3B4BC4"))
)

val PALETTE = listOf(
    "#000000", "#212121", "#616161", "#9E9E9E", "#E0E0E0", "#FFFFFF",
    "#F44336", "#E91E63", "#9C27B0", "#673AB7", "#3F51B5", "#2196F3",
    "#03A9F4", "#00BCD4", "#009688", "#4CAF50", "#8BC34A", "#CDDC39",
    "#FFEB3B", "#FFC107", "#FF9800", "#FF5722", "#795548", "#FF7F7F"
)

val FONT_NAMES = listOf("Padrão", "Serifada", "Monoespaçada", "Cursiva", "Casual", "Condensada", "Pesada")

class TierState {
    var title = "Minha Tier List"
    var titleColor = Color.WHITE
    var titleSize = 26
    var titleFont = 0
    var titleBold = true
    var titleAlign = 1 // 0 esquerda, 1 centro, 2 direita
    var titleImage: String? = null
    var titleImageDp = 80
    var bgColor = Color.parseColor("#121212")
    var areaColor = Color.parseColor("#1F1F1F")
    var tierTextColor = Color.BLACK
    var hasBgImage = false
    var iconDp = 64 // tamanho dos ícones na área "sem tier"
    val tiers = mutableListOf<Tier>()
    val pool = mutableListOf<String>()
    val anims = mutableMapOf<String, AnimMeta>()

    fun displayName(): String = if (title.isNotBlank()) title else "Lista sem título"

    fun allImageIds(): List<String> {
        val set = LinkedHashSet<String>()
        set.addAll(pool)
        for (t in tiers) {
            set.addAll(t.items)
            val li = t.labelImage
            if (li != null) set.add(li)
        }
        val ti = titleImage
        if (ti != null) set.add(ti)
        return set.toList()
    }

    fun remapIds(m: Map<String, String>) {
        for (i in pool.indices) pool[i] = m[pool[i]] ?: pool[i]
        for (t in tiers) {
            for (i in t.items.indices) t.items[i] = m[t.items[i]] ?: t.items[i]
            val li = t.labelImage
            if (li != null) t.labelImage = m[li] ?: li
        }
        val ti = titleImage
        if (ti != null) titleImage = m[ti] ?: ti
        val copy = HashMap(anims)
        anims.clear()
        for ((k, v) in copy) anims[m[k] ?: k] = v
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("title", title)
        o.put("titleColor", titleColor)
        o.put("titleSize", titleSize)
        o.put("titleFont", titleFont)
        o.put("titleBold", titleBold)
        o.put("titleAlign", titleAlign)
        if (titleImage != null) o.put("titleImage", titleImage)
        o.put("titleImageDp", titleImageDp)
        o.put("bgColor", bgColor)
        o.put("areaColor", areaColor)
        o.put("tierTextColor", tierTextColor)
        o.put("hasBgImage", hasBgImage)
        o.put("iconDp", iconDp)
        val ta = JSONArray()
        for (t in tiers) {
            val to = JSONObject()
            to.put("name", t.name)
            to.put("color", t.color)
            to.put("rows", t.rows)
            to.put("iconDp", t.iconDp)
            to.put("items", JSONArray(t.items))
            if (t.labelImage != null) to.put("labelImage", t.labelImage)
            ta.put(to)
        }
        o.put("tiers", ta)
        o.put("pool", JSONArray(pool))
        val ao = JSONObject()
        for ((k, v) in anims) ao.put(k, JSONArray(listOf(v.cx, v.cy, v.size)))
        o.put("anims", ao)
        return o.toString()
    }

    companion object {
        fun default(): TierState {
            val s = TierState()
            val names = listOf("S", "A", "B", "C", "D")
            names.forEachIndexed { i, n ->
                s.tiers.add(Tier(n, Color.parseColor(CLASSIC[i]), 1))
            }
            return s
        }

        fun fromJson(text: String): TierState {
            val o = JSONObject(text)
            val s = TierState()
            s.title = o.optString("title", s.title)
            s.titleColor = o.optInt("titleColor", s.titleColor)
            s.titleSize = o.optInt("titleSize", s.titleSize)
            s.titleFont = o.optInt("titleFont", 0)
            s.titleBold = o.optBoolean("titleBold", true)
            s.titleAlign = o.optInt("titleAlign", 1)
            val ti = o.optString("titleImage", "")
            if (ti.isNotEmpty()) s.titleImage = ti
            s.titleImageDp = o.optInt("titleImageDp", 80)
            s.bgColor = o.optInt("bgColor", s.bgColor)
            s.areaColor = o.optInt("areaColor", s.areaColor)
            s.tierTextColor = o.optInt("tierTextColor", s.tierTextColor)
            s.hasBgImage = o.optBoolean("hasBgImage", false)
            s.iconDp = o.optInt("iconDp", 64)
            val ta = o.optJSONArray("tiers")
            if (ta != null) {
                for (i in 0 until ta.length()) {
                    val to = ta.getJSONObject(i)
                    val t = Tier(
                        to.optString("name", "?"),
                        to.optInt("color", Color.GRAY),
                        to.optInt("rows", 1).coerceIn(1, 8)
                    )
                    t.iconDp = to.optInt("iconDp", s.iconDp)
                    val ia = to.optJSONArray("items")
                    if (ia != null) for (j in 0 until ia.length()) t.items.add(ia.getString(j))
                    val li = to.optString("labelImage", "")
                    if (li.isNotEmpty()) t.labelImage = li
                    s.tiers.add(t)
                }
            }
            val pa = o.optJSONArray("pool")
            if (pa != null) for (i in 0 until pa.length()) s.pool.add(pa.getString(i))
            val ao = o.optJSONObject("anims")
            if (ao != null) {
                val keys = ao.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val a = ao.getJSONArray(k)
                    s.anims[k] = AnimMeta(a.getInt(0), a.getInt(1), a.getInt(2))
                }
            }
            return s
        }
    }
}
