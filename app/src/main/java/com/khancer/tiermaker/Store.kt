package com.khancer.tiermaker

import android.content.Context
import java.io.File
import java.util.UUID

/** Armazenamento de várias listas: cada lista é um JSON em files/lists; as imagens ficam em files/imgs. */
object Store {
    fun listsDir(ctx: Context): File {
        val d = File(ctx.filesDir, "lists")
        d.mkdirs()
        return d
    }

    fun imgDir(ctx: Context): File {
        val d = File(ctx.filesDir, "imgs")
        d.mkdirs()
        return d
    }

    fun listFile(ctx: Context, id: String) = File(listsDir(ctx), "$id.json")
    fun bgFile(ctx: Context, id: String) = File(ctx.filesDir, "bg_$id.jpg")
    fun png(ctx: Context, imgId: String) = File(imgDir(ctx), "$imgId.png")
    fun gif(ctx: Context, imgId: String) = File(imgDir(ctx), "$imgId.gif")

    fun ids(ctx: Context): List<String> {
        val files = listsDir(ctx).listFiles() ?: return emptyList()
        return files.filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }
    }

    fun load(ctx: Context, id: String): TierState? {
        return try {
            TierState.fromJson(listFile(ctx, id).readText())
        } catch (e: Exception) {
            null
        }
    }

    fun save(ctx: Context, id: String, s: TierState) {
        try {
            listFile(ctx, id).writeText(s.toJson())
        } catch (e: Exception) {
            // ignora
        }
    }

    fun deleteImage(ctx: Context, imgId: String) {
        png(ctx, imgId).delete()
        gif(ctx, imgId).delete()
    }

    fun create(ctx: Context, title: String): String {
        val id = UUID.randomUUID().toString()
        val s = TierState.default()
        s.title = title
        save(ctx, id, s)
        return id
    }

    fun delete(ctx: Context, id: String) {
        val s = load(ctx, id)
        if (s != null) for (img in s.allImageIds()) deleteImage(ctx, img)
        bgFile(ctx, id).delete()
        listFile(ctx, id).delete()
    }

    /** Duplica a lista copiando também as imagens (assim apagar uma lista não afeta a outra). */
    fun duplicate(ctx: Context, id: String): String? {
        val s = load(ctx, id) ?: return null
        val map = HashMap<String, String>()
        for (old in s.allImageIds()) {
            val n = UUID.randomUUID().toString()
            map[old] = n
            val p = png(ctx, old)
            if (p.exists()) p.copyTo(png(ctx, n), true)
            val g = gif(ctx, old)
            if (g.exists()) g.copyTo(gif(ctx, n), true)
        }
        s.remapIds(map)
        s.title = s.title + " (cópia)"
        val nid = UUID.randomUUID().toString()
        save(ctx, nid, s)
        val bg = bgFile(ctx, id)
        if (bg.exists()) bg.copyTo(bgFile(ctx, nid), true)
        return nid
    }

    /** Converte a lista única da versão antiga para o novo formato com várias listas. */
    fun migrateLegacy(ctx: Context) {
        val old = File(ctx.filesDir, "state.json")
        if (!old.exists()) return
        val id = UUID.randomUUID().toString()
        try {
            old.copyTo(listFile(ctx, id), true)
            old.delete()
            val oldBg = File(ctx.filesDir, "bg.jpg")
            if (oldBg.exists()) oldBg.renameTo(bgFile(ctx, id))
        } catch (e: Exception) {
            // ignora
        }
    }
}
