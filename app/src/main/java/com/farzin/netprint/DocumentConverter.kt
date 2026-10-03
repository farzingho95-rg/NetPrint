package com.farzin.netprint

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Turns images / text into PDF so any PDF-capable printer can take them. */
object DocumentConverter {
    private const val W = 595; private const val H = 842; private const val M = 36   // A4 @72dpi

    fun read(ctx: Context, uri: Uri) = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    fun toPdf(ctx: Context, uri: Uri, mime: String): ByteArray = when {
        mime == "application/pdf" -> read(ctx, uri)
        mime.startsWith("image/") -> imageToPdf(read(ctx, uri))
        else -> textToPdf(String(read(ctx, uri)))
    }

    private fun imageToPdf(bytes: ByteArray): ByteArray {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
        var s = 1; while (opt.outWidth / s > 2480 || opt.outHeight / s > 3508) s *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        val doc = PdfDocument(); val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val sc = minOf((W - 2f * M) / bmp.width, (H - 2f * M) / bmp.height)
        val dw = bmp.width * sc; val dh = bmp.height * sc
        val l = (W - dw) / 2; val t = (H - dh) / 2
        page.canvas.drawBitmap(bmp, null, RectF(l, t, l + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        doc.finishPage(page); return doc.bytes()
    }

    private fun textToPdf(text: String): ByteArray {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.MONOSPACE }
        val lineH = 14f; val maxW = W - 2f * M
        val lines = text.lines().flatMap { raw ->
            if (raw.isEmpty()) listOf("") else buildList {
                var r = raw; while (r.isNotEmpty()) { val n = paint.breakText(r, true, maxW, null).coerceAtLeast(1); add(r.take(n)); r = r.drop(n) }
            }
        }
        val perPage = ((H - 2 * M) / lineH).toInt()
        val doc = PdfDocument()
        lines.chunked(perPage).ifEmpty { listOf(listOf("")) }.forEachIndexed { i, chunk ->
            val pg = doc.startPage(PdfDocument.PageInfo.Builder(W, H, i + 1).create())
            chunk.forEachIndexed { j, ln -> pg.canvas.drawText(ln, M.toFloat(), M + lineH * (j + 1), paint) }
            doc.finishPage(pg)
        }
        return doc.bytes()
    }

    private fun PdfDocument.bytes() = ByteArrayOutputStream().also { writeTo(it); close() }.toByteArray()
}
