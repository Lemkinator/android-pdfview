package com.infomaniak.lib.pdfview.listener

/**
 * Implements this interface to receive events from PDFView
 * when a page has been scrolled
 */
fun interface OnPageScrollListener {
    /**
     * Called on every move while scrolling
     *
     * @param page           current page index
     * @param positionOffset see [com.infomaniak.lib.pdfview.PDFView.getPositionOffset]
     */
    fun onPageScrolled(page: Int, positionOffset: Float)
}
