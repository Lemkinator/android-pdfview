package de.lemke.pdfview.link

import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import de.lemke.pdfview.PDFView
import de.lemke.pdfview.model.LinkTapEvent

class DefaultLinkHandler(private val pdfView: PDFView) : LinkHandler {

    override fun handleLinkEvent(event: LinkTapEvent) {
        val uri = event.link.uri
        val page = event.link.destPageIdx
        if (uri != null && !uri.isEmpty()) {
            handleUri(uri)
        } else if (page != null) {
            handlePage(page)
        }
    }

    private fun handleUri(uri: String?) {
        val parsedUri = uri?.toUri()
        if (parsedUri == null) {
            Log.w(TAG, "Invalid URI: $uri")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, parsedUri)
        val context = pdfView.context
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            Log.w(TAG, "No activity found for URI: $uri")
        }
    }

    private fun handlePage(page: Int) {
        pdfView.jumpTo(page)
    }

    companion object {
        private val TAG: String = DefaultLinkHandler::class.java.getSimpleName()
    }
}
