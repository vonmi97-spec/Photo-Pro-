package com.vonmi.photopro

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Panorama : images de l'aperçu prises pendant que l'on tourne vers la droite.
 * Chaque image est recalée sur la précédente puis l'ensemble est assemblé avec des raccords en fondu.
 */
class PanoramaBuilder {
    private class Frame(val bmp: Bitmap, val ox: Int, val oy: Int)

    private val kept = mutableListOf<Frame>()
    private var prevSmall: IntArray? = null
    private var sw = 0; private var sh = 0
    private var curX = 0; private var curY = 0
    private var w = 0; private var h = 0

    val count get() = kept.size
    /** Largeur atteinte, en nombre d'écrans. */
    val progress get() = if (w == 0) 0f else (curX + w).toFloat() / w
    var tooFast = false; private set

    /** Ajoute une image de l'aperçu ; renvoie false si le panorama est plein. */
    fun add(bmp: Bitmap): Boolean {
        if (w == 0) { w = bmp.width; h = bmp.height; sw = w / 4; sh = h / 4 }
        if (bmp.width != w || bmp.height != h) return true
        val small = smallLum(bmp)
        val prev = prevSmall
        if (prev == null) {
            kept += Frame(bmp, 0, 0)
            prevSmall = small
            return true
        }
        val (dx, dy, err) = match(prev, small)
        tooFast = err > 38
        if (tooFast) return true // image floue ou mouvement trop rapide : on l'ignore
        curX += dx * 4; curY += dy * 4
        prevSmall = small
        val last = kept.last()
        if (curX - last.ox >= w * 0.30f) kept += Frame(bmp, curX, curY)
        return kept.size < MAX && progress < 8f
    }

    private fun lum(p: Int) = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8

    private fun smallLum(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, sw, sh, true)
        val px = IntArray(sw * sh); s.getPixels(px, 0, sw, 0, 0, sw, sh); s.recycle()
        return IntArray(px.size) { lum(px[it]) }
    }

    /** Cherche (dx, dy) tel que cur(x, y) ≈ prev(x + dx, y + dy). Renvoie aussi l'erreur moyenne. */
    private fun match(prev: IntArray, cur: IntArray): Triple<Int, Int, Int> {
        val maxDx = (sw * 0.45f).toInt(); val maxDy = max(2, sh / 30)
        var best = Long.MAX_VALUE; var bx = 0; var by = 0; var bestN = 1
        for (dy in -maxDy..maxDy) for (dx in -4..maxDx) {
            var s = 0L; var n = 0
            var y = maxDy
            while (y < sh - maxDy) {
                val oc = y * sw; val op = (y + dy) * sw + dx
                var x = max(0, -dx)
                val xEnd = min(sw, sw - dx)
                while (x < xEnd) { s += abs(cur[oc + x] - prev[op + x]); n++; x += 2 }
                y += 3
            }
            if (n < 200) continue
            val norm = s * 1000 / n
            if (norm < best) { best = norm; bx = dx; by = dy; bestN = n }
        }
        return Triple(bx, by, (best / 1000).toInt())
    }

    /** Assemble le panorama final. */
    fun build(): Bitmap? {
        if (kept.size < 2) return null
        val top = kept.maxOf { it.oy }; val bottom = kept.minOf { it.oy } + h
        val outH = bottom - top
        val outW = kept.last().ox + w
        if (outH < h / 2) return null
        val out = IntArray(outW * outH)
        val row = IntArray(w)
        for ((k, f) in kept.withIndex()) {
            val prevEnd = if (k > 0) kept[k - 1].ox + w else Int.MIN_VALUE
            for (r in 0 until outH) {
                val fy = top + r - f.oy
                if (fy !in 0 until h) continue
                f.bmp.getPixels(row, 0, w, 0, fy, w, 1)
                val o = r * outW
                for (x in 0 until w) {
                    val gx = f.ox + x
                    if (gx >= outW) break
                    val p = row[x]
                    if (k > 0 && gx < prevEnd) {
                        val a = (gx - f.ox).toFloat() / (prevEnd - f.ox).coerceAtLeast(1)
                        out[o + gx] = mix(out[o + gx], p, a)
                    } else out[o + gx] = p
                }
            }
        }
        return Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        val r = (((a shr 16) and 0xFF) * u + ((b shr 16) and 0xFF) * t).toInt()
        val g = (((a shr 8) and 0xFF) * u + ((b shr 8) and 0xFF) * t).toInt()
        val bl = ((a and 0xFF) * u + (b and 0xFF) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    fun recycle() { kept.forEach { it.bmp.recycle() }; kept.clear() }

    companion object { const val MAX = 16 }
}
