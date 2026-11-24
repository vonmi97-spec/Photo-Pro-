package com.vonmi.photopro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Traitements appliqués à la photo avant l'enregistrement : filigrane et position GPS. */
object PostProcess {

    private val keptTags = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
    )

    fun apply(
        ctx: Context, bytes: ByteArray, watermark: Int, customText: String,
        loc: Location?, place: String?, quality: Int,
    ): ByteArray {
        var out = bytes
        if (watermark > 0 || customText.isNotBlank()) {
            out = runCatching { drawWatermark(bytes, watermark, customText, place, quality) }.getOrDefault(bytes)
        }
        if (loc != null) out = runCatching { addGps(ctx, out, loc) }.getOrDefault(out)
        return out
    }

    private fun drawWatermark(bytes: ByteArray, mode: Int, custom: String, place: String?, quality: Int): ByteArray {
        val src = ExifInterface(ByteArrayInputStream(bytes))
        val orientation = src.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val saved = keptTags.associateWith { src.getAttribute(it) }

        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inMutable = true })
            ?: return bytes
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
            bmp.recycle()
            bmp = r.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== r) r.recycle() }
        }

        val lines = mutableListOf<String>()
        if (mode >= 1) lines += SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.FRANCE).format(Date())
        if (mode >= 2 && !place.isNullOrBlank()) lines += place
        if (custom.isNotBlank()) lines += custom.trim()
        if (lines.isEmpty()) return bytes

        val c = Canvas(bmp)
        val size = minOf(bmp.width, bmp.height) * 0.038f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            setShadowLayer(size * 0.18f, 0f, size * 0.06f, Color.argb(200, 0, 0, 0))
        }
        val margin = size * 0.9f
        var y = bmp.height - margin
        for (line in lines.asReversed()) {
            c.drawText(line, bmp.width - margin, y, paint)
            y -= size * 1.25f
        }

        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        bmp.recycle()
        var res = bos.toByteArray()

        // On remet les informations de prise de vue (ISO, vitesse…) perdues au réencodage
        if (saved.values.any { it != null }) {
            res = withTempFile(null, res) { exif -> saved.forEach { (k, v) -> if (v != null) exif.setAttribute(k, v) } }
        }
        return res
    }

    private fun addGps(ctx: Context, bytes: ByteArray, loc: Location): ByteArray =
        withTempFile(ctx, bytes) { exif -> exif.setGpsInfo(loc) }

    private var tmpDir: File? = null

    fun init(ctx: Context) { tmpDir = ctx.cacheDir }

    private fun withTempFile(ctx: Context?, bytes: ByteArray, edit: (ExifInterface) -> Unit): ByteArray {
        val dir = ctx?.cacheDir ?: tmpDir ?: return bytes
        val f = File(dir, "exif_${System.nanoTime()}.jpg")
        return try {
            f.writeBytes(bytes)
            val exif = ExifInterface(f.absolutePath)
            edit(exif)
            exif.saveAttributes()
            f.readBytes()
        } catch (_: Exception) {
            bytes
        } finally {
            f.delete()
        }
    }
}
