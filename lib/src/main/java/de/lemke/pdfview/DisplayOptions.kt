package de.lemke.pdfview

import de.lemke.pdfview.util.FitPolicy
import io.legere.pdfiumandroid.util.Size

data class DisplayOptions(
    /** True if scrolling is vertical, else it's horizontal  */
    val isVertical: Boolean,
    val pdfSpacing: PDFSpacing,
    /**
     * True if every page should fit separately according to the FitPolicy,
     * else the largest page fits and other pages scale relatively
     */
    val fitEachPage: Boolean,
    val viewSize: Size,
    val pageFitPolicy: FitPolicy
)
