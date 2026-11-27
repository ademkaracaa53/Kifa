package com.kifa.transkript

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

private val SPK_COLORS = intArrayOf(
    0xFF3346C8.toInt(), 0xFFB5481C.toInt(), 0xFF1D7F52.toInt(), 0xFF8A3FB0.toInt(),
    0xFFA0711A.toInt(), 0xFF0F7A8A.toInt(), 0xFFB8336A.toInt(), 0xFF566070.toInt(),
)
fun speakerColor(id: Int) = SPK_COLORS[((id - 1) % SPK_COLORS.size + SPK_COLORS.size) % SPK_COLORS.size]

/** A4 PDF: başlık, tarih, konuşmacı adları renkli, metin satır kaydırmalı ve sayfa numaralı. */
fun writePdf(out: OutputStream) {
    val W = 595; val H = 842; val M = 52f
    val width = (W - 2 * M).toInt()
    val doc = PdfDocument()
    val titleP = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
    val metaP = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.GRAY }
    val nameP = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; typeface = Typeface.DEFAULT_BOLD }
    val bodyP = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = 0xFF141A24.toInt() }
    val trP = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = 0xFF1677D9.toInt(); typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) }
    val footP = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.GRAY; textAlign = android.graphics.Paint.Align.CENTER }

    fun layout(text: String, p: TextPaint) =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.25f).build()

    var pageNo = 0
    var page: PdfDocument.Page? = null
    var y = 0f
    fun newPage() {
        page?.let { doc.finishPage(it) }
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        page!!.canvas.drawText("$pageNo", W / 2f, H - 28f, footP)
        y = 56f
    }
    // Bir metin bloğunu çiz; sayfaya sığmayan satırları sonraki sayfaya taşı
    fun block(text: String, p: TextPaint, gapAfter: Float) {
        val l = layout(text, p)
        var line = 0
        while (line < l.lineCount) {
            if (y + (l.getLineBottom(line) - l.getLineTop(line)) > H - 60f) newPage()
            val c = page!!.canvas
            val top = l.getLineTop(line)
            c.save(); c.translate(M, y - top)
            c.clipRect(0f, top.toFloat(), width.toFloat(), l.getLineBottom(line).toFloat())
            l.draw(c); c.restore()
            y += (l.getLineBottom(line) - top)
            line++
        }
        y += gapAfter
    }

    newPage()
    block(Transcript.title.ifBlank { "eFAM Transkript" }, titleP, 4f)
    block(Transcript.dateText(), metaP, 18f)
    for (para in Transcript.paras) {
        if (para.spk > 0) {
            nameP.color = speakerColor(para.spk)
            if (y > H - 100f) newPage()   // ad ile metni ayırma
            block(Transcript.name(para.spk), nameP, 1f)
        }
        val tr = para.translation()
        if (tr.isEmpty()) block(para.text.trim(), bodyP, 10f)
        else { block(para.text.trim(), bodyP, 3f); block("→ $tr", trP, 10f) }
    }
    page?.let { doc.finishPage(it) }
    doc.writeTo(out)
    doc.close()
}
