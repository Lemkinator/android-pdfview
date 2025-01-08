package de.lemke.pdfview.source

import android.content.Context
import android.net.Uri
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.IOException

@JvmRecord
data class UriSource(val uri: Uri) : DocumentSource {
    @Throws(IOException::class)
    override fun createDocument(context: Context, core: PdfiumCore, password: String?): PdfDocument =
        core.newDocument(context.contentResolver.openFileDescriptor(uri, "r"), password)
}
