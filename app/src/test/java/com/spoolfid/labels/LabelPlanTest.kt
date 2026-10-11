package com.spoolfid.labels

import com.spoolfid.spoolman.QrCodes
import com.spoolfid.spoolman.ScannedCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelPlanTest {
    private val sheet = LabelSettings(LabelLayout.SHEET, 50, 30)

    @Test
    fun aSheetIsPackedWithAsManyLabelsAsFit() {
        val plan = LabelPlan.compute(40, sheet, 210f, 297f, marginMm = 8f)!!
        assertEquals(3, plan.columns) // (210 - 16) / 50 = 3
        assertEquals(9, plan.rows) // (297 - 16) / 30 = 9
        assertEquals(27, plan.perPage)
        assertEquals(2, plan.pages)
    }

    @Test
    fun labelsReadLeftToRightThenDownAndTheBlockIsCentred() {
        val plan = LabelPlan.compute(5, sheet, 210f, 297f)!!
        val (x0, y0) = plan.origin(0)
        val (x1, y1) = plan.origin(1)
        val (x3, y3) = plan.origin(3)
        assertEquals((210f - 150f) / 2, x0, 1e-4f)
        assertEquals(y0, y1, 0f)
        assertEquals(x0 + 50f, x1, 1e-4f)
        assertEquals(x0, x3, 1e-4f)
        assertEquals(y0 + 30f, y3, 1e-4f)
    }

    @Test
    fun theLastPageHoldsTheRemainder() {
        val plan = LabelPlan.compute(30, sheet, 210f, 297f)!!
        assertEquals(27, plan.onPage(0, 30))
        assertEquals(3, plan.onPage(1, 30))
        assertEquals(0, plan.onPage(2, 30))
    }

    @Test
    fun aLabelPrinterGetsOneLabelPerPage() {
        val plan = LabelPlan.compute(7, sheet.copy(layout = LabelLayout.ROLL), 50f, 30f)!!
        assertEquals(7, plan.pages)
        assertEquals(1, plan.perPage)
        assertEquals(0f to 0f, plan.origin(0))
    }

    @Test
    fun aLabelBiggerThanThePageHasNoPlan() {
        assertNull(LabelPlan.compute(1, LabelSettings(widthMm = 200, heightMm = 30), 210f, 297f))
        assertNull(LabelPlan.compute(0, sheet, 210f, 297f))
    }
}

class QrCodesTest {
    @Test
    fun spoolmansOwnCodesAreRead() {
        assertEquals(ScannedCode.OfSpool(12), QrCodes.parse("WEB+SPOOLMAN:S-12"))
        assertEquals(ScannedCode.OfSpool(12), QrCodes.parse("web+spoolman:s-12"))
        assertEquals(ScannedCode.OfFilament(3), QrCodes.parse(" WEB+SPOOLMAN:F-3 "))
    }

    @Test
    fun linksToASpoolOrFilamentPageAreRead() {
        assertEquals(ScannedCode.OfSpool(7), QrCodes.parse("http://192.168.1.2:7912/spool/show/7"))
        assertEquals(ScannedCode.OfSpool(7), QrCodes.parse("https://spools.example/spool/show/7/"))
        assertEquals(ScannedCode.OfFilament(9), QrCodes.parse("http://host/filament/show/9?x=1"))
    }

    @Test
    fun anythingElseIsIgnored() {
        assertNull(QrCodes.parse("https://example.com/"))
        assertNull(QrCodes.parse("WEB+SPOOLMAN:S-"))
        assertNull(QrCodes.parse("WEB+SPOOLMAN:X-4"))
        assertNull(QrCodes.parse(""))
        assertNull(QrCodes.parse("WEB+SPOOLMAN:S-99999999999"))
    }

    @Test
    fun printedCodesReadBackAsTheSameSpool() {
        assertEquals(ScannedCode.OfSpool(41), QrCodes.parse(QrCodes.spoolCode(41)))
        assertEquals(ScannedCode.OfSpool(41), QrCodes.parse(QrCodes.spoolLink("http://host:7912/", 41)))
        assertTrue(QrCodes.spoolLink("http://host:7912/", 41).endsWith("/spool/show/41"))
    }
}

class QrRoundTripTest {
    /** Draws a code the way the labels do (same encoder settings), then reads it back as a camera would see it. */
    private fun decode(text: String): String {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            text,
            com.google.zxing.BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                com.google.zxing.EncodeHintType.MARGIN to 0,
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            ),
        )
        val scale = 6
        val quiet = 4 * scale // a printed label leaves white around the code
        val size = matrix.width * scale + 2 * quiet
        val pixels = IntArray(size * size) { 0xFFFFFFFF.toInt() }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (!matrix.get(x, y)) continue
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[(quiet + y * scale + dy) * size + quiet + x * scale + dx] = 0xFF000000.toInt()
            }
        }
        val source = com.google.zxing.RGBLuminanceSource(size, size, pixels)
        return com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))).text
    }

    @Test
    fun aPrintedSpoolCodeReadsBackAsThatSpool() {
        val text = decode(QrCodes.spoolCode(137))
        assertEquals("WEB+SPOOLMAN:S-137", text)
        assertEquals(ScannedCode.OfSpool(137), QrCodes.parse(text))
    }

    @Test
    fun aPrintedLinkReadsBackAsThatSpool() {
        val text = decode(QrCodes.spoolLink("http://192.168.1.2:7912", 8))
        assertEquals(ScannedCode.OfSpool(8), QrCodes.parse(text))
    }
}
