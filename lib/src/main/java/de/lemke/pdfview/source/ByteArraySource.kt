package de.lemke.pdfview.source

import android.content.Context
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.IOException

class ByteArraySource(private val data: ByteArray?) : DocumentSource {
    @Throws(IOException::class)
    override fun createDocument(context: Context, core: PdfiumCore, password: String?): PdfDocument =
        core.newDocument(data, password)
}
