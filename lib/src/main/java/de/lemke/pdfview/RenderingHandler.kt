package de.lemke.pdfview

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import de.lemke.pdfview.exception.PageRenderingException
import de.lemke.pdfview.model.PagePart
import kotlin.math.roundToInt
import androidx.core.graphics.createBitmap

/**
 * A [Handler] that will process incoming [RenderingTask] messages
 * and alert [PDFView.onBitmapRendered] when the portion of the
 * PDF is ready to render.
 */
class RenderingHandler(
    looper: Looper?,
    private val pdfView: PDFView,
) : Handler(looper!!) {
    private val renderBounds = RectF()
    private val roundedRenderBounds = Rect()
    private val renderMatrix = Matrix()
    private var running = false

    fun addRenderingTask(
        page: Int,
        renderingSize: RenderingSize,
        thumbnail: Boolean,
        cacheOrder: Int,
        bestQuality: Boolean,
        annotationRendering: Boolean,
        isForPrinting: Boolean,
    ) {
        val task = RenderingTask(
            renderingSize,
            page,
            thumbnail,
            cacheOrder,
            bestQuality,
            annotationRendering,
            isForPrinting,
        )
        val msg = obtainMessage(MSG_RENDER_TASK, task)
        sendMessage(msg)
    }

    fun stop() {
        running = false
    }

    fun start() {
        running = true
    }

    override fun handleMessage(message: Message): Unit = with(pdfView) {
        val task = message.obj as RenderingTask
        runCatching {
            proceed(task)?.let { pagePart ->
                if (running) {
                    post { onBitmapRendered(pagePart, task.isForPrinting) }
                } else {
                    pagePart.renderedBitmap?.recycle()
                }
            }
        }.onFailure { exception ->
            if (exception is PageRenderingException) post { onPageError(exception) }
        }
    }

    @Throws(PageRenderingException::class)
    private fun proceed(renderingTask: RenderingTask): PagePart? {
        val pdfFile = pdfView.pdfFile
        if (pdfFile == null) {
            Log.e(TAG, "proceed: pdfView.pdfFile is null.")
            return null
        }
        pdfFile.openPage(renderingTask.page)

        val w = renderingTask.renderingSize.width.roundToInt()
        val h = renderingTask.renderingSize.height.roundToInt()

        if (w == 0 || h == 0 || pdfFile.pageHasError(renderingTask.page)) {
            return null
        }

        var render: Bitmap? = null
        runCatching { createBitmap(w, h, if (renderingTask.bestQuality) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565) }
            .onSuccess { renderedBitmap -> render = renderedBitmap }
            .onFailure {
                Log.e(TAG, "Cannot create bitmap", it)
                render = null
            }

        calculateBounds(w, h, renderingTask.renderingSize.bounds)

        pdfFile.renderPageBitmap(
            render, renderingTask.page, roundedRenderBounds, renderingTask.annotationRendering
        )

        return PagePart(
            renderingTask.page,
            render,
            renderingTask.renderingSize.bounds,
            renderingTask.thumbnail,
            renderingTask.cacheOrder
        )
    }

    private fun calculateBounds(width: Int, height: Int, pageSliceBounds: RectF) {
        renderMatrix.reset()
        renderMatrix.postTranslate(-pageSliceBounds.left * width, -pageSliceBounds.top * height)
        renderMatrix.postScale(1 / pageSliceBounds.width(), 1 / pageSliceBounds.height())

        renderBounds[0f, 0f, width.toFloat()] = height.toFloat()
        renderMatrix.mapRect(renderBounds)
        renderBounds.round(roundedRenderBounds)
    }

    data class RenderingSize(
        var width: Float,
        var height: Float,
        var bounds: RectF,
    )

    private data class RenderingTask(
        var renderingSize: RenderingSize,
        var page: Int,
        var thumbnail: Boolean,
        var cacheOrder: Int,
        var bestQuality: Boolean,
        var annotationRendering: Boolean,
        var isForPrinting: Boolean,
    )

    companion object {
        /**
         * [Message.what] kind of message this handler processes.
         */
        const val MSG_RENDER_TASK: Int = 1

        private val TAG: String = RenderingHandler::class.java.name
    }
}
