package com.infomaniak.lib.pdfview.listener

import android.graphics.Bitmap

/**
 * Implement this interface to receive events from PDFView
 * when bitmaps has been generated. Used to print password protected PDF.
 */
fun interface OnBitmapsReadyListener {
    /**
     * Called when bitmaps has been generated
     *
     * @param bitmaps pages of the PDF as bitmaps
     */
    fun bitmapsReady(bitmaps: List<Bitmap?>)
}
