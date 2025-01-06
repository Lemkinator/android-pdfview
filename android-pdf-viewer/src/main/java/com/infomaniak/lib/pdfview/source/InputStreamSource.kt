package com.infomaniak.lib.pdfview.source

import android.content.Context
import com.infomaniak.lib.pdfview.util.Util
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.IOException
import java.io.InputStream

class InputStreamSource(private val inputStream: InputStream) : DocumentSource {
    @Throws(IOException::class)
    override fun createDocument(context: Context, core: PdfiumCore, password: String?): PdfDocument =
        core.newDocument(Util.toByteArray(inputStream), password)
}
