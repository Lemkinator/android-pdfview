package de.lemke.pdfview

data class PDFSpacing(
    /** Fixed spacing between pages in pixels  */
    val pageSeparatorSpacing: Int = 0,
    /** Fixed spacing between pages in pixels  */
    val startSpacing: Int = 0,
    /** Fixed spacing between pages in pixels  */
    val endSpacing: Int = 0,
    /** Calculate spacing automatically so each page fits on it's own in the center of the view  */
    val autoSpacing: Boolean = false
)
