package com.infomaniak.lib.pdfview.listener

fun interface OnPageErrorListener {
    /**
     * Called if error occurred while loading PDF page
     *
     * @param t Throwable with error
     */
    fun onPageError(page: Int, t: Throwable?)
}
