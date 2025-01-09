package de.lemke.pdfview.model

import android.graphics.Rect
import io.legere.pdfiumandroid.PdfDocument

data class LinkTapEvent(
    val originalX: Float,
    val originalY: Float,
    val documentX: Float,
    val documentY: Float,
    val mappedLinkRect: Rect,
    val link: PdfDocument.Link
)