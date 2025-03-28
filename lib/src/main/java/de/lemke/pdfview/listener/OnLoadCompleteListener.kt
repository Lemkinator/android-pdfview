package de.lemke.pdfview.listener

/**
 * Implement this interface to receive events from PDFView
 * when loading is complete.
 */
fun interface OnLoadCompleteListener {
    /**
     * Called when the PDF is loaded
     *
     * @param nbPages the number of pages in this PDF file
     */
    fun loadComplete(nbPages: Int)
}
