package de.lemke.pdfview.listener

fun interface OnPasswordExceptionListener {
    /**
     * Called if an exception is thrown during opening the document because of a password protection.
     */
    fun onPasswordException()
}
