package de.lemke.pdfview.source

import android.content.Context
import android.os.ParcelFileDescriptor
import de.lemke.pdfview.util.FileUtils
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.IOException

class AssetSource(private val assetName: String) : DocumentSource {
    @Throws(IOException::class)
    override fun createDocument(context: Context, core: PdfiumCore, password: String?): PdfDocument = core.newDocument(
        ParcelFileDescriptor.open(
            FileUtils.fileFromAsset(context, assetName),
            ParcelFileDescriptor.MODE_READ_ONLY
        ), password
    )
}
