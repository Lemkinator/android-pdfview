package de.lemke.pdfview

import android.os.AsyncTask
import de.lemke.pdfview.source.DocumentSource
import com.shockwave.pdfium.PdfiumCore
import com.shockwave.pdfium.util.Size
import java.lang.NullPointerException
import java.lang.ref.WeakReference

internal class DecodingAsyncTask(
    pdfView: PDFView?,
    private val docSource: DocumentSource,
    private val password: String?,
    private val userPages: IntArray?,
    private val pdfiumCore: PdfiumCore
) : AsyncTask<Void?, Void?, Throwable?>() {
    private var cancelled: Boolean = false

    private val pdfViewReference: WeakReference<PDFView?> = WeakReference<PDFView?>(pdfView)
    private var pdfFile: PdfFile? = null

    override fun doInBackground(vararg params: Void?): Throwable? {
        try {
            val pdfView = pdfViewReference.get()
            if (pdfView != null) {
                val pdfDocument = docSource.createDocument(pdfView.context, pdfiumCore, password)
                val pdfSpacing = PDFSpacing(
                    pdfView.pageSeparatorSpacing,
                    pdfView.startSpacing,
                    pdfView.endSpacing,
                    pdfView.autoSpacing
                )
                val displayOptions = DisplayOptions(
                    pdfView.swipeVertical,
                    pdfSpacing,
                    pdfView.fitEachPage,
                    getViewSize(pdfView),
                    pdfView.pageFitPolicy
                )
                pdfFile = PdfFile(
                    pdfiumCore,
                    pdfDocument,
                    userPages,
                    displayOptions
                )
                return null
            } else {
                return NullPointerException("pdfView == null")
            }
        } catch (t: Throwable) {
            return t
        }
    }

    private fun getViewSize(pdfView: PDFView): Size = Size(pdfView.width, pdfView.height)

    override fun onPostExecute(t: Throwable?) {
        val pdfView = pdfViewReference.get()
        if (pdfView != null) {
            if (t != null) {
                pdfView.loadError(t)
                return
            }
            if (!cancelled) {
                pdfFile?.let { pdfView.loadComplete(it) }
            }
        }
    }

    override fun onCancelled() {
        cancelled = true
    }
}
