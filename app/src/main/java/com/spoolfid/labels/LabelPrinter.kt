package com.spoolfid.labels

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.text.TextPaint
import android.text.TextUtils
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.spoolfid.spoolman.QrCodes
import com.spoolfid.spoolman.Spool
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/** Prints spool labels with a QR code through Android's print service: a normal printer, a label printer, or a PDF. */
object LabelPrinter {
    private const val MM = 72f / 25.4f // PDF points per millimetre

    fun print(context: Context, spools: List<Spool>, settings: LabelSettings, baseUrl: String) {
        if (spools.isEmpty()) return
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attributes = PrintAttributes.Builder().apply {
            if (settings.layout == LabelLayout.ROLL) {
                // The page is the label. MediaSize is stored upright, so a wide label is turned to landscape.
                val w = (settings.widthMm / 25.4f * 1000).roundToInt()
                val h = (settings.heightMm / 25.4f * 1000).roundToInt()
                val size = PrintAttributes.MediaSize("spoolfid_label", "Label", min(w, h), maxOf(w, h))
                setMediaSize(if (w > h) size.asLandscape() else size)
                setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            } else {
                setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            }
        }.build()
        manager.print("SpoolFID labels", Adapter(context, spools, settings, baseUrl), attributes)
    }

    private class Adapter(
        private val context: Context,
        private val spools: List<Spool>,
        private val settings: LabelSettings,
        private val baseUrl: String,
    ) : PrintDocumentAdapter() {
        private var plan: LabelPlan? = null
        private var attributes: PrintAttributes? = null

        override fun onLayout(
            old: PrintAttributes?,
            new: PrintAttributes,
            cancel: CancellationSignal,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancel.isCanceled) {
                callback.onLayoutCancelled()
                return
            }
            attributes = new
            val media = new.mediaSize ?: PrintAttributes.MediaSize.ISO_A4
            val widthMm = media.widthMils * 25.4f / 1000f
            val heightMm = media.heightMils * 25.4f / 1000f
            val p = LabelPlan.compute(spools.size, settings, widthMm, heightMm)
            if (p == null) {
                callback.onLayoutFailed("These labels are too big for the paper. Change the label size in Labels → Layout.")
                return
            }
            plan = p
            callback.onLayoutFinished(
                PrintDocumentInfo.Builder("spoolfid-labels.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(p.pages)
                    .build(),
                true,
            )
        }

        override fun onWrite(
            pages: Array<PageRange>,
            destination: ParcelFileDescriptor,
            cancel: CancellationSignal,
            callback: WriteResultCallback,
        ) {
            val p = plan
            val a = attributes
            if (p == null || a == null) {
                callback.onWriteFailed("Nothing to print")
                return
            }
            val pdf = PrintedPdfDocument(context, a)
            try {
                for (page in 0 until p.pages) {
                    if (cancel.isCanceled) {
                        callback.onWriteCancelled()
                        return
                    }
                    if (pages.none { page >= it.start && page <= it.end }) continue
                    val pdfPage = pdf.startPage(page)
                    for (i in 0 until p.onPage(page, spools.size)) {
                        val (x, y) = p.origin(i)
                        drawLabel(pdfPage.canvas, spools[page * p.perPage + i], x * MM, y * MM)
                    }
                    pdf.finishPage(pdfPage)
                }
                FileOutputStream(destination.fileDescriptor).use { pdf.writeTo(it) }
                callback.onWriteFinished(pages)
            } catch (e: Exception) {
                callback.onWriteFailed(e.message ?: "Couldn't make the labels")
            } finally {
                pdf.close()
            }
        }

        /** One label: the QR code on the left, the spool's ID, name and details beside it. [x] and [y] are in points. */
        private fun drawLabel(canvas: Canvas, spool: Spool, x: Float, y: Float) {
            val w = settings.widthMm * MM
            val h = settings.heightMm * MM
            val pad = 2f * MM

            if (settings.layout == LabelLayout.SHEET) {
                // A hairline to cut along.
                canvas.drawRect(x, y, x + w, y + h, Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 0.3f
                    color = Color.LTGRAY
                })
            }

            val qrSize = min(h - 2 * pad, w * 0.5f)
            val text = if (settings.qrAsLink) QrCodes.spoolLink(baseUrl, spool.id) else QrCodes.spoolCode(spool.id)
            drawQr(canvas, text, x + pad, y + (h - qrSize) / 2, qrSize)

            val left = x + pad + qrSize + pad
            val room = x + w - pad - left
            if (room < 8f * MM) return
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

            // The colour of the filament, as a small dot in the corner, so spools can be told apart at a glance.
            val dot = min(h * 0.16f, 5f * MM)
            spool.colorHex?.let { hex ->
                runCatching { Color.parseColor("#" + hex.removePrefix("#").take(6)) }.getOrNull()?.let { c ->
                    canvas.drawCircle(x + w - pad - dot / 2, y + pad + dot / 2, dot / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c })
                    canvas.drawCircle(x + w - pad - dot / 2, y + pad + dot / 2, dot / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 0.4f
                        color = Color.GRAY
                    })
                }
            }

            val idPaint = TextPaint(ink).apply {
                typeface = Typeface.DEFAULT_BOLD
                textSize = h * 0.26f
            }
            val namePaint = TextPaint(ink).apply { textSize = h * 0.14f }
            val detailPaint = TextPaint(ink).apply {
                textSize = h * 0.11f
                color = Color.DKGRAY
            }

            var baseline = y + pad + idPaint.textSize * 0.85f
            canvas.drawText(TextUtils.ellipsize("#${spool.id}", idPaint, room - dot - 1f * MM, TextUtils.TruncateAt.END).toString(), left, baseline, idPaint)
            baseline += namePaint.textSize * 1.35f
            canvas.drawText(TextUtils.ellipsize(spool.title, namePaint, room, TextUtils.TruncateAt.END).toString(), left, baseline, namePaint)
            val details = listOfNotNull(
                spool.vendor?.takeIf { settings.showVendor && it.isNotBlank() },
                spool.material?.takeIf { settings.showMaterial && it.isNotBlank() },
            ).joinToString(" · ")
            if (details.isNotEmpty() && baseline + detailPaint.textSize * 1.4f < y + h - pad / 2) {
                baseline += detailPaint.textSize * 1.4f
                canvas.drawText(TextUtils.ellipsize(details, detailPaint, room, TextUtils.TruncateAt.END).toString(), left, baseline, detailPaint)
            }
        }

        /** The QR code as one filled shape, so there are no hairline gaps between its squares. */
        private fun drawQr(canvas: Canvas, text: String, x: Float, y: Float, size: Float) {
            val matrix = QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
            val module = size / matrix.width
            val path = Path()
            for (row in 0 until matrix.height) {
                for (col in 0 until matrix.width) {
                    if (matrix.get(col, row)) path.addRect(x + col * module, y + row * module, x + (col + 1) * module, y + (row + 1) * module, Path.Direction.CW)
                }
            }
            canvas.drawPath(path, Paint().apply {
                style = Paint.Style.FILL
                color = Color.BLACK
                isAntiAlias = false
            })
        }
    }
}
