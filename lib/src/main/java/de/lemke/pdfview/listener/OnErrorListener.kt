package de.lemke.pdfview.listener

fun interface OnErrorListener {
    /**
     * Called if error occurred while opening PDF
     *
     * @param t Throwable with error
     */
    fun onError(t: Throwable?)
}
