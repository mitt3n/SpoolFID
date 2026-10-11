package com.spoolfid.labels

/** How labels are laid out on paper. */
enum class LabelLayout(val label: String) {
    /** Several labels on a normal sheet (A4 or Letter), to cut out. */
    SHEET("Sheet"),

    /** One label per page, the page being the label, for a label printer. */
    ROLL("Label printer"),
}

data class LabelSettings(
    val layout: LabelLayout = LabelLayout.SHEET,
    val widthMm: Int = 50,
    val heightMm: Int = 30,
    /** The QR code holds a link to the spool's page instead of Spoolman's own code. */
    val qrAsLink: Boolean = false,
    val showVendor: Boolean = true,
    val showMaterial: Boolean = true,
) {
    companion object {
        val MIN_MM = 15
        val MAX_MM = 150

        /** Common label sizes (width x height, mm). */
        val PRESETS = listOf(30 to 20, 50 to 30, 62 to 29, 70 to 40, 100 to 50)
    }
}

/** Where labels go on the pages of a print job. Sizes are in millimetres. */
class LabelPlan(
    val columns: Int,
    val rows: Int,
    val pages: Int,
    private val originX: Float,
    private val originY: Float,
    private val labelW: Float,
    private val labelH: Float,
) {
    val perPage: Int get() = columns * rows

    /** Top-left corner of the [indexOnPage]th label of a page, reading left to right, then down. */
    fun origin(indexOnPage: Int): Pair<Float, Float> =
        (originX + (indexOnPage % columns) * labelW) to (originY + (indexOnPage / columns) * labelH)

    /** How many labels go on page [page] (0-based) when [total] are printed. */
    fun onPage(page: Int, total: Int): Int = (total - page * perPage).coerceIn(0, perPage)

    companion object {
        /**
         * Plans [count] labels on pages of [pageWidthMm] x [pageHeightMm]. A sheet is packed as many across and down as fit
         * inside [marginMm]; with the roll layout the page is the label, one per page. Null when a label doesn't fit.
         */
        fun compute(count: Int, s: LabelSettings, pageWidthMm: Float, pageHeightMm: Float, marginMm: Float = 8f): LabelPlan? {
            if (count <= 0) return null
            val w = s.widthMm.toFloat()
            val h = s.heightMm.toFloat()
            if (s.layout == LabelLayout.ROLL) return LabelPlan(1, 1, count, 0f, 0f, pageWidthMm, pageHeightMm)
            val usableW = pageWidthMm - 2 * marginMm
            val usableH = pageHeightMm - 2 * marginMm
            val cols = (usableW / w).toInt()
            val rows = (usableH / h).toInt()
            if (cols < 1 || rows < 1) return null
            // Centre the block of labels so the margins are even.
            val ox = (pageWidthMm - cols * w) / 2
            val oy = (pageHeightMm - rows * h) / 2
            val per = cols * rows
            return LabelPlan(cols, rows, (count + per - 1) / per, ox, oy, w, h)
        }
    }
}
