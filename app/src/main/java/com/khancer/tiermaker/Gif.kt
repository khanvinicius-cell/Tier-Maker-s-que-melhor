package com.khancer.tiermaker

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max

/**
 * Gravador de GIF animado (GIF89a). Cada quadro recebe a própria paleta de 256 cores
 * (median cut) e é comprimido com LZW.
 */
class GifWriter(private val out: OutputStream, private val w: Int, private val h: Int) {
    private var started = false

    fun addFrame(source: Bitmap, delayCs: Int) {
        val bmp = if (source.width != w || source.height != h) Bitmap.createScaledBitmap(source, w, h, true) else source
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val indices = ByteArray(px.size)
        val palette = quantize(px, indices)

        if (!started) {
            writeHeader()
            started = true
        }
        // controle gráfico: atraso do quadro
        out.write(0x21)
        out.write(0xF9)
        out.write(4)
        out.write(0x04)
        writeShort(delayCs)
        out.write(0)
        out.write(0)
        // descritor da imagem com tabela de cores local de 256 entradas
        out.write(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(w)
        writeShort(h)
        out.write(0x87)
        out.write(palette)
        out.write(8)
        val data = lzw(indices)
        var pos = 0
        while (pos < data.size) {
            val n = minOf(255, data.size - pos)
            out.write(n)
            out.write(data, pos, n)
            pos += n
        }
        out.write(0)
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    private fun writeHeader() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(w)
        writeShort(h)
        out.write(0x70)
        out.write(0)
        out.write(0)
        // repetição infinita
        out.write(0x21)
        out.write(0xFF)
        out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3)
        out.write(1)
        writeShort(0)
        out.write(0)
    }

    private fun writeShort(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun chan(bin: Int, ch: Int): Int = when (ch) {
        0 -> bin shr 10
        1 -> (bin shr 5) and 31
        else -> bin and 31
    }

    private fun quantize(px: IntArray, indices: ByteArray): ByteArray {
        val counts = IntArray(32768)
        for (p in px) {
            val r = (p shr 19) and 31
            val g = (p shr 11) and 31
            val b = (p shr 3) and 31
            counts[(r shl 10) or (g shl 5) or b]++
        }
        val bins = ArrayList<Int>()
        for (i in 0 until 32768) if (counts[i] > 0) bins.add(i)
        val boxes = ArrayList<List<Int>>()
        boxes.add(bins)

        while (boxes.size < 256) {
            var bestIdx = -1
            var bestRange = 0
            var bestCh = 0
            for (bi in boxes.indices) {
                val box = boxes[bi]
                if (box.size < 2) continue
                var rmin = 31; var rmax = 0
                var gmin = 31; var gmax = 0
                var bmin = 31; var bmax = 0
                for (bin in box) {
                    val r = bin shr 10
                    val g = (bin shr 5) and 31
                    val b = bin and 31
                    if (r < rmin) rmin = r
                    if (r > rmax) rmax = r
                    if (g < gmin) gmin = g
                    if (g > gmax) gmax = g
                    if (b < bmin) bmin = b
                    if (b > bmax) bmax = b
                }
                val rr = rmax - rmin
                val gr = gmax - gmin
                val br = bmax - bmin
                val m = max(rr, max(gr, br))
                if (m > bestRange) {
                    bestRange = m
                    bestIdx = bi
                    bestCh = if (m == rr) 0 else if (m == gr) 1 else 2
                }
            }
            if (bestIdx < 0) break
            val box = boxes[bestIdx]
            val ch = bestCh
            val sorted = box.sortedBy { chan(it, ch) }
            var total = 0L
            for (b in sorted) total += counts[b]
            var acc = 0L
            var k = 1
            for (i in sorted.indices) {
                acc += counts[sorted[i]]
                if (acc * 2 >= total) {
                    k = i + 1
                    break
                }
            }
            k = k.coerceIn(1, sorted.size - 1)
            boxes[bestIdx] = sorted.subList(0, k)
            boxes.add(sorted.subList(k, sorted.size))
        }

        val palette = ByteArray(768)
        val map = IntArray(32768)
        for (bi in boxes.indices) {
            var sr = 0L
            var sg = 0L
            var sb = 0L
            var cnt = 0L
            for (bin in boxes[bi]) {
                val c = counts[bin].toLong()
                val r5 = bin shr 10
                val g5 = (bin shr 5) and 31
                val b5 = bin and 31
                sr += c * ((r5 shl 3) or (r5 shr 2))
                sg += c * ((g5 shl 3) or (g5 shr 2))
                sb += c * ((b5 shl 3) or (b5 shr 2))
                cnt += c
                map[bin] = bi
            }
            if (cnt > 0) {
                palette[bi * 3] = (sr / cnt).toByte()
                palette[bi * 3 + 1] = (sg / cnt).toByte()
                palette[bi * 3 + 2] = (sb / cnt).toByte()
            }
        }
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 19) and 31
            val g = (p shr 11) and 31
            val b = (p shr 3) and 31
            indices[i] = map[(r shl 10) or (g shl 5) or b].toByte()
        }
        return palette
    }

    private fun lzw(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size / 2 + 1024)
        var cur = 0
        var bits = 0
        fun put(code: Int, size: Int) {
            cur = cur or (code shl bits)
            bits += size
            while (bits >= 8) {
                bos.write(cur and 0xFF)
                cur = cur ushr 8
                bits -= 8
            }
        }

        val clear = 256
        val eoi = 257
        val hsize = 5003
        val keys = IntArray(hsize)
        val vals = IntArray(hsize)
        java.util.Arrays.fill(keys, -1)
        var next = 258
        var size = 9
        var maxCode = 511

        put(clear, size)
        var prefix = data[0].toInt() and 0xFF
        for (i in 1 until data.size) {
            val c = data[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            var h = ((key shl 3) xor key) % hsize
            var found = -1
            while (keys[h] != -1) {
                if (keys[h] == key) {
                    found = vals[h]
                    break
                }
                h++
                if (h == hsize) h = 0
            }
            if (found != -1) {
                prefix = found
                continue
            }
            put(prefix, size)
            if (next > maxCode && size < 12) {
                size++
                maxCode = (1 shl size) - 1
            }
            if (next < 4096) {
                keys[h] = key
                vals[h] = next
                next++
            } else {
                put(clear, size)
                java.util.Arrays.fill(keys, -1)
                next = 258
                size = 9
                maxCode = 511
            }
            prefix = c
        }
        put(prefix, size)
        if (next > maxCode && size < 12) {
            size++
            maxCode = (1 shl size) - 1
        }
        put(eoi, size)
        if (bits > 0) bos.write(cur and 0xFF)
        return bos.toByteArray()
    }
}
