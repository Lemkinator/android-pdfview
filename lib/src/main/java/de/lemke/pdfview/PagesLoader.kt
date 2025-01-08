package de.lemke.pdfview

import android.graphics.RectF
import android.util.Log
import de.lemke.pdfview.CacheManager.Companion.CACHE_SIZE
import de.lemke.pdfview.PagesLoader.GridSize
import de.lemke.pdfview.PagesLoader.RenderRange
import de.lemke.pdfview.RenderingHandler.RenderingSize
import de.lemke.pdfview.util.Util.getDP
import java.util.LinkedList
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal class PagesLoader(private val pdfView: PDFView) {
    companion object {
        /**
         * The size of the rendered parts (default 256).
         * Tinier : a little bit slower to have the whole page rendered but more reactive.
         * Bigger : user will have to wait longer to have the first visual results.
         */
        const val PART_SIZE = 256.0f
        /**
         * Part of document above and below screen that should be preloaded, in dp.
         */
        const val PRELOAD_OFFSET = 20
    }

    private var cacheOrder = 0
    private var xOffset = 0f
    private var yOffset = 0f
    private var pageRelativePartWidth = 0f
    private var pageRelativePartHeight = 0f
    private var partRenderWidth = 0f
    private var partRenderHeight = 0f
    private val thumbnailRect = RectF(0f, 0f, 1f, 1f)
    private val preloadOffset: Int = getDP(pdfView.context, PRELOAD_OFFSET)

    private class Holder {
        var row: Int = 0
        var col: Int = 0

        override fun toString(): String = "Holder{row=$row, col=$col}"
    }

    private class RenderRange {
        var page: Int = 0
        var gridSize: GridSize = GridSize()
        var leftTop: Holder = Holder()
        var rightBottom: Holder = Holder()

        override fun toString(): String = "RenderRange{" +
                "page=" + page +
                ", gridSize=" + gridSize +
                ", leftTop=" + leftTop +
                ", rightBottom=" + rightBottom +
                '}'
    }

    private class GridSize {
        var rows: Int = 0
        var cols: Int = 0

        override fun toString(): String = "GridSize{rows=$rows, cols=$cols}"
    }

    fun loadPagesForPrinting(pagesCount: Int) = loadAllForPrinting(pagesCount)

    private fun getPageColsRows(grid: GridSize, pageIndex: Int) {
        val pdfFile = pdfView.pdfFile
        if (pdfFile == null) {
            Log.e("PagesLoader", "getPageColsRows: pdfFile is null")
            return
        }
        val size = pdfFile.getPageSize(pageIndex)
        val ratioX = 1f / size.width
        val ratioY = 1f / size.height
        val partHeight = (PART_SIZE * ratioY) / pdfView.zoom
        val partWidth = (PART_SIZE * ratioX) / pdfView.zoom
        grid.rows = ceil((1f / partHeight)).toInt()
        grid.cols = ceil((1f / partWidth)).toInt()
    }

    private fun calculatePartSize(grid: GridSize) {
        pageRelativePartWidth = 1f / grid.cols.toFloat()
        pageRelativePartHeight = 1f / grid.rows.toFloat()
        partRenderWidth = PART_SIZE / pageRelativePartWidth
        partRenderHeight = PART_SIZE / pageRelativePartHeight
    }

    /**
     * calculate the render range of each page
     */
    private fun getRenderRangeList(
        firstXOffset: Float,
        firstYOffset: Float,
        lastXOffset: Float,
        lastYOffset: Float
    ): MutableList<RenderRange> {
        val fixedFirstXOffset: Float = -min(firstXOffset, 0f)
        val fixedFirstYOffset: Float = -min(firstYOffset, 0f)

        val fixedLastXOffset: Float = -min(lastXOffset, 0f)
        val fixedLastYOffset: Float = -min(lastYOffset, 0f)

        val offsetFirst = if (pdfView.swipeVertical) fixedFirstYOffset else fixedFirstXOffset
        val offsetLast = if (pdfView.swipeVertical) fixedLastYOffset else fixedLastXOffset

        val pdfFile = pdfView.pdfFile
        if (pdfFile == null) {
            Log.e("PagesLoader", "getRenderRangeList: pdfFile is null")
            return LinkedList()
        }

        val firstPage = pdfFile.getPageAtOffset(offsetFirst, pdfView.zoom)
        val lastPage = pdfFile.getPageAtOffset(offsetLast, pdfView.zoom)
        val pageCount = lastPage - firstPage + 1

        val renderRanges: MutableList<RenderRange> = LinkedList<RenderRange>()

        for (page in firstPage..lastPage) {
            val range = RenderRange()
            range.page = page

            var pageFirstXOffset: Float
            var pageFirstYOffset: Float
            var pageLastXOffset: Float
            var pageLastYOffset: Float
            if (page == firstPage) {
                pageFirstXOffset = fixedFirstXOffset
                pageFirstYOffset = fixedFirstYOffset
                if (pageCount == 1) {
                    pageLastXOffset = fixedLastXOffset
                    pageLastYOffset = fixedLastYOffset
                } else {
                    val pageOffset = pdfFile.getPageOffset(page, pdfView.zoom)
                    val pageSize = pdfFile.getScaledPageSize(page, pdfView.zoom)
                    if (pdfView.swipeVertical) {
                        pageLastXOffset = fixedLastXOffset
                        pageLastYOffset = pageOffset + pageSize.height
                    } else {
                        pageLastYOffset = fixedLastYOffset
                        pageLastXOffset = pageOffset + pageSize.width
                    }
                }
            } else if (page == lastPage) {
                val pageOffset = pdfFile.getPageOffset(page, pdfView.zoom)

                if (pdfView.swipeVertical) {
                    pageFirstXOffset = fixedFirstXOffset
                    pageFirstYOffset = pageOffset
                } else {
                    pageFirstYOffset = fixedFirstYOffset
                    pageFirstXOffset = pageOffset
                }

                pageLastXOffset = fixedLastXOffset
                pageLastYOffset = fixedLastYOffset
            } else {
                val pageOffset = pdfFile.getPageOffset(page, pdfView.zoom)
                val pageSize = pdfFile.getScaledPageSize(page, pdfView.zoom)
                if (pdfView.swipeVertical) {
                    pageFirstXOffset = fixedFirstXOffset
                    pageFirstYOffset = pageOffset

                    pageLastXOffset = fixedLastXOffset
                    pageLastYOffset = pageOffset + pageSize.height
                } else {
                    pageFirstXOffset = pageOffset
                    pageFirstYOffset = fixedFirstYOffset

                    pageLastXOffset = pageOffset + pageSize.width
                    pageLastYOffset = fixedLastYOffset
                }
            }

            getPageColsRows(range.gridSize, range.page) // get the page's grid size that rows and cols
            val scaledPageSize = pdfFile.getScaledPageSize(range.page, pdfView.zoom)
            val rowHeight = scaledPageSize.height / range.gridSize.rows
            val colWidth = scaledPageSize.width / range.gridSize.cols

            // Get the page offset int the whole file
            // ---------------------------------------
            // |            |           |            |
            // |<--offset-->|   (page)  |<--offset-->|
            // |            |           |            |
            // |            |           |            |
            // ---------------------------------------
            val secondaryOffset = pdfFile.getSecondaryPageOffset(page, pdfView.zoom)

            // calculate the row,col of the point in the leftTop and rightBottom
            if (pdfView.swipeVertical) {
                range.leftTop.row = floor(
                    (abs((pageFirstYOffset - pdfFile.getPageOffset(range.page, pdfView.zoom))) / rowHeight)
                ).toInt()
                range.leftTop.col =
                    floor((max((pageFirstXOffset - secondaryOffset), 0f) / colWidth)).toInt()

                range.rightBottom.row =
                    ceil((abs((pageLastYOffset - pdfFile.getPageOffset(range.page, pdfView.zoom))) / rowHeight)).toInt()
                range.rightBottom.col = floor((max((pageLastXOffset - secondaryOffset), 0f) / colWidth)).toInt()
            } else {
                range.leftTop.col =
                    floor((abs((pageFirstXOffset - pdfFile.getPageOffset(range.page, pdfView.zoom))) / colWidth)).toInt()
                range.leftTop.row = floor((max((pageFirstYOffset - secondaryOffset), 0f) / rowHeight)).toInt()

                range.rightBottom.col =
                    floor((abs((pageLastXOffset - pdfFile.getPageOffset(range.page, pdfView.zoom))) / colWidth)).toInt()
                range.rightBottom.row = floor((max((pageLastYOffset - secondaryOffset), 0f) / rowHeight)).toInt()
            }

            renderRanges.add(range)
        }

        return renderRanges
    }

    private fun loadAllForPrinting(pagesCount: Int) {
        for (i in 0 until pagesCount) {
            loadThumbnail(i, true)
        }
    }

    private fun loadVisible() {
        var parts = 0
        val scaledPreloadOffset = preloadOffset.toFloat()
        val firstXOffset = -xOffset + scaledPreloadOffset
        val lastXOffset = -xOffset - pdfView.width - scaledPreloadOffset
        val firstYOffset = -yOffset + scaledPreloadOffset
        val lastYOffset = -yOffset - pdfView.height - scaledPreloadOffset

        val rangeList = getRenderRangeList(firstXOffset, firstYOffset, lastXOffset, lastYOffset)

        for (range in rangeList) {
            loadThumbnail(range.page, false)
        }

        for (range in rangeList) {
            calculatePartSize(range.gridSize)
            parts += loadPage(
                range.page,
                range.leftTop.row,
                range.rightBottom.row,
                range.leftTop.col,
                range.rightBottom.col,
                CACHE_SIZE - parts
            )
            if (parts >= CACHE_SIZE) {
                break
            }
        }
    }

    private fun loadPage(page: Int, firstRow: Int, lastRow: Int, firstCol: Int, lastCol: Int, nbOfPartsLoadable: Int): Int {
        var loaded = 0
        for (row in firstRow..lastRow) {
            for (col in firstCol..lastCol) {
                if (loadCell(page, row, col, pageRelativePartWidth, pageRelativePartHeight)) {
                    loaded++
                }
                if (loaded >= nbOfPartsLoadable) {
                    return loaded
                }
            }
        }
        return loaded
    }

    private fun loadCell(page: Int, row: Int, col: Int, pageRelativePartWidth: Float, pageRelativePartHeight: Float): Boolean {
        val relX = pageRelativePartWidth * col
        val relY = pageRelativePartHeight * row
        var relWidth = pageRelativePartWidth
        var relHeight = pageRelativePartHeight

        var renderWidth = partRenderWidth
        var renderHeight = partRenderHeight
        if (relX + relWidth > 1) {
            relWidth = 1 - relX
        }
        if (relY + relHeight > 1) {
            relHeight = 1 - relY
        }
        renderWidth *= relWidth
        renderHeight *= relHeight
        val pageRelativeBounds = RectF(relX, relY, relX + relWidth, relY + relHeight)

        if (renderWidth > 0 && renderHeight > 0) {
            if (!pdfView.cacheManager.upPartIfContained(page, pageRelativeBounds, cacheOrder)) {
                pdfView.renderingHandler?.addRenderingTask(
                    page,
                    RenderingSize(renderWidth, renderHeight, pageRelativeBounds),
                    false,
                    cacheOrder,
                    pdfView.bestQuality,
                    pdfView.annotationRenderingEnabled,
                    false
                )
            }

            cacheOrder++
            return true
        }
        return false
    }

    private fun loadThumbnail(page: Int, isForPrinting: Boolean) {
        val pdfFile = pdfView.pdfFile
        if (pdfFile == null) {
            Log.e("PagesLoader", "loadThumbnail: pdfFile is null")
            return
        }
        val minQualityForPrinting = 0.75f
        val pageSize = pdfFile.getPageSize(page)
        val thumbnailRatio = if (isForPrinting) minQualityForPrinting else pdfView.thumbnailRatio
        val thumbnailWidth = pageSize.width * thumbnailRatio
        val thumbnailHeight = pageSize.height * thumbnailRatio
        if (!pdfView.cacheManager.containsThumbnail(page, thumbnailRect)) {
            pdfView.renderingHandler?.addRenderingTask(
                page,
                RenderingSize(thumbnailWidth, thumbnailHeight, thumbnailRect),
                true,
                0,
                pdfView.bestQuality,
                pdfView.annotationRenderingEnabled,
                isForPrinting
            )
        } else if (page == pdfView.getPageCount() - 1 && isForPrinting) {
            pdfView.onBitmapsReadyListener?.bitmapsReady(pdfView.getPagesAsBitmaps())
        }
    }

    fun loadPages() {
        cacheOrder = 1
        xOffset = -min(pdfView.currentXOffset, 0f)
        yOffset = -min(pdfView.currentYOffset, 0f)

        loadVisible()
    }
}
