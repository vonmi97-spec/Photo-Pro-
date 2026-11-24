package com.vonmi.photopro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Fusion de plusieurs photos : mode Nuit (empilement aligné) et HDR (fusion d'expositions). */
object Fusion {

    private fun decode(b: ByteArray, sample: Int, mutable: Boolean = false): Bitmap =
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = mutable
        })

    private fun encode(bmp: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return bos.toByteArray()
    }

    private fun lum(p: Int) = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8

    /** Essaie en pleine résolution, puis en demi-résolution si la mémoire manque. */
    fun <T> withMemoryFallback(block: (Int) -> T): T = try {
        block(1)
    } catch (e: OutOfMemoryError) {
        System.gc()
        block(2)
    }

    // -----------------------------------------------------------------
    // HDR : moyenne pondérée selon la « bonne exposition » de chaque pixel

    fun hdr(frames: List<ByteArray>, quality: Int): ByteArray = withMemoryFallback { sample ->
        val bmps = frames.map { decode(it, sample) }
        val w = bmps.minOf { it.width }; val h = bmps.minOf { it.height }
        val sw = max(1, w / 8); val sh = max(1, h / 8)
        // Cartes de poids en basse résolution, adoucies pour éviter les halos
        val weights = bmps.map { b ->
            val small = Bitmap.createScaledBitmap(b, sw, sh, true)
            val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh); small.recycle()
            val wt = FloatArray(sw * sh)
            for (i in px.indices) {
                val p = px[i]
                val r = ((p shr 16) and 0xFF) / 255f; val g = ((p shr 8) and 0xFF) / 255f; val bl = (p and 0xFF) / 255f
                val l = 0.3f * r + 0.59f * g + 0.11f * bl
                val sat = (max(r, max(g, bl)) - min(r, min(g, bl)))
                wt[i] = exp(-((l - 0.5f) * (l - 0.5f)) / (2f * 0.2f * 0.2f)) * (0.6f + sat) + 1e-4f
            }
            boxBlur(boxBlur(boxBlur(wt, sw, sh, 2), sw, sh, 2), sw, sh, 2)
        }
        // Normalisation
        for (i in 0 until sw * sh) {
            var s = 0f
            for (wm in weights) s += wm[i]
            for (wm in weights) wm[i] /= s
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rows = bmps.map { IntArray(w) }
        val res = IntArray(w)
        val fx = (sw - 1).toFloat() / max(1, w - 1); val fy = (sh - 1).toFloat() / max(1, h - 1)
        for (y in 0 until h) {
            for (k in bmps.indices) bmps[k].getPixels(rows[k], 0, w, 0, y, w, 1)
            val gy = y * fy; val y0 = gy.toInt(); val y1 = min(sh - 1, y0 + 1); val ty = gy - y0
            for (x in 0 until w) {
                val gx = x * fx; val x0 = gx.toInt(); val x1 = min(sw - 1, x0 + 1); val tx = gx - x0
                var r = 0f; var g = 0f; var b = 0f
                for (k in bmps.indices) {
                    val wm = weights[k]
                    val a = wm[y0 * sw + x0] * (1 - tx) + wm[y0 * sw + x1] * tx
                    val c = wm[y1 * sw + x0] * (1 - tx) + wm[y1 * sw + x1] * tx
                    val wk = a * (1 - ty) + c * ty
                    val p = rows[k][x]
                    r += wk * ((p shr 16) and 0xFF); g += wk * ((p shr 8) and 0xFF); b += wk * (p and 0xFF)
                }
                res[x] = (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
            }
            out.setPixels(res, 0, w, 0, y, w, 1)
        }
        bmps.forEach { it.recycle() }
        val bytes = encode(out, quality); out.recycle(); bytes
    }

    private fun clamp(v: Float) = v.roundToInt().coerceIn(0, 255)

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size); val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (k in -r..r) { val xx = x + k; if (xx in 0 until w) { s += src[y * w + xx]; n++ } }
            tmp[y * w + x] = s / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (k in -r..r) { val yy = y + k; if (yy in 0 until h) { s += tmp[yy * w + x]; n++ } }
            out[y * w + x] = s / n
        }
        return out
    }

    // -----------------------------------------------------------------
    // Nuit : alignement de chaque image sur la première puis moyenne (moins de bruit)

    fun night(frames: List<ByteArray>, quality: Int): ByteArray = withMemoryFallback { sample ->
        val base = decode(frames[0], sample, mutable = true)
        val w = base.width; val h = base.height
        val n = frames.size
        val accR = ShortArray(w * h); val accG = ShortArray(w * h); val accB = ShortArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            base.getPixels(row, 0, w, 0, y, w, 1)
            val o = y * w
            for (x in 0 until w) {
                val p = row[x]
                accR[o + x] = ((p shr 16) and 0xFF).toShort(); accG[o + x] = ((p shr 8) and 0xFF).toShort(); accB[o + x] = (p and 0xFF).toShort()
            }
        }
        val baseSmall = smallLum(base, 8)
        val baseRow = IntArray(w)
        for (k in 1 until n) {
            val f = runCatching { decode(frames[k], sample) }.getOrNull() ?: continue
            if (f.width != w || f.height != h) { f.recycle(); continue }
            val (cx, cy) = coarseShift(baseSmall, smallLum(f, 8), w / 8, h / 8, 24)
            val (dx, dy) = fineShift(base, f, cx * 8, cy * 8, 10)
            for (y in 0 until h) {
                val fy = y + dy
                base.getPixels(baseRow, 0, w, 0, y, w, 1)
                val inside = fy in 0 until h
                if (inside) f.getPixels(row, 0, w, 0, fy, w, 1)
                val o = y * w
                for (x in 0 until w) {
                    val fx = x + dx
                    val p = if (inside && fx in 0 until w) {
                        val q = row[fx]; val b0 = baseRow[x]
                        // Rejet des « fantômes » (objet qui a bougé) : on garde l'image de base
                        if (abs(lum(q) - lum(b0)) > 60) b0 else q
                    } else baseRow[x]
                    accR[o + x] = (accR[o + x] + ((p shr 16) and 0xFF)).toShort()
                    accG[o + x] = (accG[o + x] + ((p shr 8) and 0xFF)).toShort()
                    accB[o + x] = (accB[o + x] + (p and 0xFF)).toShort()
                }
            }
            f.recycle()
        }
        // Moyenne + éclaircissement doux des ombres
        val lut = IntArray(256) { (255.0 * (it / 255.0).pow(0.82)).roundToInt().coerceIn(0, 255) }
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) {
                val r = lut[accR[o + x] / n]; val g = lut[accG[o + x] / n]; val b = lut[accB[o + x] / n]
                row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            base.setPixels(row, 0, w, 0, y, w, 1)
        }
        val bytes = encode(base, quality); base.recycle(); bytes
    }

    private fun smallLum(b: Bitmap, factor: Int): IntArray {
        val sw = max(1, b.width / factor); val sh = max(1, b.height / factor)
        val s = Bitmap.createScaledBitmap(b, sw, sh, true)
        val px = IntArray(sw * sh); s.getPixels(px, 0, sw, 0, 0, sw, sh); s.recycle()
        return IntArray(px.size) { lum(px[it]) }
    }

    /** Décalage (dx, dy) tel que cur(x+dx, y+dy) ≈ ref(x, y), cherché sur toute l'image réduite. */
    private fun coarseShift(ref: IntArray, cur: IntArray, w: Int, h: Int, r: Int): Pair<Int, Int> {
        var best = Long.MAX_VALUE; var bx = 0; var by = 0
        val m = r + 2
        for (dy in -r..r) for (dx in -r..r) {
            var s = 0L
            var y = m
            while (y < h - m) {
                var x = m
                val o = y * w
                val oc = (y + dy) * w + dx
                while (x < w - m) { s += abs(ref[o + x] - cur[oc + x]); x += 2 }
                y += 2
            }
            if (s < best) { best = s; bx = dx; by = dy }
        }
        return Pair(bx, by)
    }

    /** Affinage en pleine résolution sur une zone centrale de 256×256. */
    private fun fineShift(ref: Bitmap, cur: Bitmap, cx: Int, cy: Int, r: Int): Pair<Int, Int> {
        val size = 256
        val x0 = ref.width / 2 - size / 2; val y0 = ref.height / 2 - size / 2
        val a = IntArray(size * size); ref.getPixels(a, 0, size, x0, y0, size, size)
        val la = IntArray(a.size) { lum(a[it]) }
        val pad = r
        val bw = size + 2 * pad
        val bx0 = (x0 + cx - pad).coerceIn(0, cur.width - bw); val by0 = (y0 + cy - pad).coerceIn(0, cur.height - bw)
        val b = IntArray(bw * bw); cur.getPixels(b, 0, bw, bx0, by0, bw, bw)
        val lb = IntArray(b.size) { lum(b[it]) }
        var best = Long.MAX_VALUE; var bestX = cx; var bestY = cy
        for (dy in -r..r) for (dx in -r..r) {
            val sx = x0 + cx + dx - bx0; val sy = y0 + cy + dy - by0
            if (sx < 0 || sy < 0 || sx + size > bw || sy + size > bw) continue
            var s = 0L
            var y = 0
            while (y < size) {
                val oa = y * size; val ob = (sy + y) * bw + sx
                var x = 0
                while (x < size) { s += abs(la[oa + x] - lb[ob + x]); x += 2 }
                y += 2
            }
            if (s < best) { best = s; bestX = cx + dx; bestY = cy + dy }
        }
        return Pair(bestX, bestY)
    }
}
