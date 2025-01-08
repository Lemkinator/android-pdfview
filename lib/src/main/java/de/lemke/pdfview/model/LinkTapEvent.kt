package de.lemke.pdfview.model

import android.graphics.RectF
import com.shockwave.pdfium.PdfDocument

data class LinkTapEvent(
    val originalX: Float,
    val originalY: Float,
    val documentX: Float,
    val documentY: Float,
    val mappedLinkRect: RectF,
    val link: PdfDocument.Link
)