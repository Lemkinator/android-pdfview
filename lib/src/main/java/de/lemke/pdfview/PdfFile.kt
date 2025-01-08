package de.lemke.pdfview

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.SparseBooleanArray
import de.lemke.pdfview.exception.PageRenderingException
import de.lemke.pdfview.util.PageSizeCalculator
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import com.shockwave.pdfium.util.Size
import com.shockwave.pdfium.util.SizeF
import java.lang.Exception
import java.util.ArrayList
import kotlin.math.max

class PdfFile(
    private val pdfiumCore: PdfiumCore,
    private var pdfDocument: PdfDocument?,
    /**
     * The pages the user want to display in order
     * (ex: 0, 2, 2, 8, 8, 1, 1, 1)
     */
    private var originalUserPages: IntArray?,
    private val displayOptions: DisplayOptions
) {
    var pagesCount = 0
    /**
     * Original page sizes
     */
    private val originalPageSizes: MutableList<Size> = ArrayList<Size>()
    /**
     * Scaled page sizes
     */
    private val pageSizes: MutableList<SizeF> = ArrayList<SizeF>()
    /**
     * Opened pages with indicator whether opening was successful
     */
    private val openedPages = SparseBooleanArray()
    /**
     * Page with maximum width
     */
    private var originalMaxWidthPageSize = Size(0, 0)
    /**
     * Page with maximum height
     */
    private var originalMaxHeightPageSize = Size(0, 0)
    /**
     * Scaled page with maximum height
     */
    private var maxHeightPageSize: SizeF = SizeF(0f, 0f)
    /**
     * Scaled page with maximum width
     */
    private var maxWidthPageSize: SizeF = SizeF(0f, 0f)
    /**
     * Calculated offsets for pages
     */
    private val pageOffsets: MutableList<Float> = ArrayList<Float>()
    /**
     * Calculated auto spacing for pages
     */
    private val pageSpacing: MutableList<Float> = ArrayList<Float>()
    /**
     * Calculated document length (width or height, depending on swipe mode)
     */
    private var documentLength = 0f

    init {
        pagesCount = if (originalUserPages != null) {
            originalUserPages!!.size
        } else {
            pdfiumCore.getPageCount(pdfDocument)
        }
        for (i in 0 until pagesCount) {
            val pageSize = pdfiumCore.getPageSize(pdfDocument, documentPage(i))
            if (pageSize.width > originalMaxWidthPageSize.width) {
                originalMaxWidthPageSize = pageSize
            }
            if (pageSize.height > originalMaxHeightPageSize.height) {
                originalMaxHeightPageSize = pageSize
            }
            originalPageSizes.add(pageSize)
        }
        recalculatePageSizes(displayOptions.viewSize)
    }

    /**
     * Call after view size change to recalculate page sizes, offsets and document length
     *
     * @param viewSize new size of changed view
     */
    fun recalculatePageSizes(viewSize: Size) {
        pageSizes.clear()
        val calculator = PageSizeCalculator(
            displayOptions.pageFitPolicy,
            originalMaxWidthPageSize,
            originalMaxHeightPageSize,
            viewSize,
            displayOptions.fitEachPage
        )
        calculator.getOptimalMaxWidthPageSize()?.let { maxWidthPageSize = it }
        calculator.getOptimalMaxHeightPageSize()?.let { maxHeightPageSize = it }

        for (size in originalPageSizes) {
            pageSizes.add(calculator.calculate(size))
        }
        if (displayOptions.pdfSpacing.autoSpacing) {
            prepareAutoSpacing(viewSize)
        }
        prepareDocLen()
        preparePagesOffset()
    }

    fun getPageSize(pageIndex: Int): SizeF {
        if (documentPage(pageIndex) < 0) {
            return SizeF(0f, 0f)
        }
        return pageSizes[pageIndex]
    }

    fun getScaledPageSize(pageIndex: Int, zoom: Float): SizeF {
        val size = getPageSize(pageIndex)
        return SizeF(size.width * zoom, size.height * zoom)
    }

    /**
     * Page size with the biggest dimension (width in vertical mode and height in horizontal mode)
     */
    val maxPageSize: SizeF
        get() = if (displayOptions.isVertical) maxWidthPageSize else maxHeightPageSize

    val maxPageWidth: Float
        get() = maxPageSize.width

    val maxPageHeight: Float
        get() = maxPageSize.height

    private fun prepareAutoSpacing(viewSize: Size) {
        pageSpacing.clear()
        for (i in 0 until pagesCount) {
            val pageSize = pageSizes[i]
            var spacing: Float = max(
                0f,
                (if (displayOptions.isVertical) viewSize.height - pageSize.height else viewSize.width - pageSize.width)
            )
            if (i < pagesCount - 1) {
                spacing += displayOptions.pdfSpacing.pageSeparatorSpacing.toFloat()
            }
            pageSpacing.add(spacing)
        }
    }

    private fun prepareDocLen() {
        var length = 0f
        for (i in 0 until pagesCount) {
            val pageSize = pageSizes[i]
            length += if (displayOptions.isVertical) pageSize.height else pageSize.width
            if (displayOptions.pdfSpacing.autoSpacing) {
                length += pageSpacing[i]
            } else if (i < pagesCount - 1) {
                length += displayOptions.pdfSpacing.pageSeparatorSpacing.toFloat()
            }
        }
        documentLength = length + displayOptions.pdfSpacing.startSpacing + displayOptions.pdfSpacing.endSpacing
    }

    private fun preparePagesOffset() {
        pageOffsets.clear()
        var offset = 0f
        for (i in 0 until pagesCount) {
            val pageSize = pageSizes[i]
            val size = if (displayOptions.isVertical) pageSize.height else pageSize.width
            if (displayOptions.pdfSpacing.autoSpacing) {
                offset += pageSpacing[i] / 2f
                if (i == 0) {
                    offset -= displayOptions.pdfSpacing.pageSeparatorSpacing / 2f
                } else if (i == pagesCount - 1) {
                    offset += displayOptions.pdfSpacing.pageSeparatorSpacing / 2f
                }
                pageOffsets.add(offset)
                offset += size + pageSpacing[i] / 2f
            } else {
                // Adding a space at the beginning to be able to zoom out with a space between the top of the screen
                // and the first page of the PDF
                if (i == 0) {
                    offset += displayOptions.pdfSpacing.startSpacing.toFloat()
                }
                pageOffsets.add(offset)
                offset += size + displayOptions.pdfSpacing.pageSeparatorSpacing
            }
        }
    }

    fun getDocLen(zoom: Float): Float = documentLength * zoom

    /**
     * Get the page's height if swiping vertical, or width if swiping horizontal.
     */
    fun getPageLength(pageIndex: Int, zoom: Float): Float {
        val size = getPageSize(pageIndex)
        return (if (displayOptions.isVertical) size.height else size.width) * zoom
    }

    fun getPageSpacing(pageIndex: Int, zoom: Float): Float {
        var spacing: Float = if (displayOptions.pdfSpacing.autoSpacing) {
            pageSpacing[pageIndex]
        } else {
            displayOptions.pdfSpacing.pageSeparatorSpacing.toFloat()
        }
        return spacing * zoom
    }

    /**
     * Get primary page offset, that is Y for vertical scroll and X for horizontal scroll
     */
    fun getPageOffset(pageIndex: Int, zoom: Float): Float {
        if (documentPage(pageIndex) < 0) {
            return 0f
        }
        return pageOffsets[pageIndex] * zoom
    }

    /**
     * Get secondary page offset, that is X for vertical scroll and Y for horizontal scroll
     */
    fun getSecondaryPageOffset(pageIndex: Int, zoom: Float): Float {
        val pageSize = getPageSize(pageIndex)
        return if (displayOptions.isVertical) {
            zoom * (maxPageWidth - pageSize.width) / 2 //x
        } else {
            zoom * (maxPageHeight - pageSize.height) / 2 //y
        }
    }

    fun getPageAtOffset(offset: Float, zoom: Float): Int {
        var currentPage = 0
        for (i in 0 until pagesCount) {
            val off = pageOffsets[i] * zoom - getPageSpacing(i, zoom) / 2f
            if (off >= offset) {
                break
            }
            currentPage++
        }
        return if (--currentPage >= 0) currentPage else 0
    }

    @Throws(PageRenderingException::class)
    fun openPage(pageIndex: Int): Boolean {
        val docPage = documentPage(pageIndex)
        if (docPage < 0) {
            return false
        }

        synchronized(lock) {
            if (openedPages.indexOfKey(docPage) < 0) {
                try {
                    pdfiumCore.openPage(pdfDocument, docPage)
                    openedPages.put(docPage, true)
                    return true
                } catch (e: Exception) {
                    openedPages.put(docPage, false)
                    throw PageRenderingException(pageIndex, e)
                }
            }
            return false
        }
    }

    fun pageHasError(pageIndex: Int): Boolean = !openedPages.get(documentPage(pageIndex), false)

    fun renderPageBitmap(bitmap: Bitmap?, pageIndex: Int, bounds: Rect, annotationRendering: Boolean) {
        pdfiumCore.renderPageBitmap(
            pdfDocument, bitmap, documentPage(pageIndex),
            bounds.left, bounds.top, bounds.width(), bounds.height(), annotationRendering
        )
    }

    fun getMetaData(): PdfDocument.Meta? {
        if (pdfDocument == null) {
            return null
        }
        return pdfiumCore.getDocumentMeta(pdfDocument)
    }

    fun getBookmarks(): MutableList<PdfDocument.Bookmark> {
        if (pdfDocument == null) {
            return ArrayList<PdfDocument.Bookmark>()
        }
        return pdfiumCore.getTableOfContents(pdfDocument)
    }

    fun getPageLinks(pageIndex: Int): MutableList<PdfDocument.Link> =
        pdfiumCore.getPageLinks(pdfDocument, documentPage(pageIndex))

    fun mapRectToDevice(pageIndex: Int, startX: Int, startY: Int, sizeX: Int, sizeY: Int, rect: RectF): RectF =
        pdfiumCore.mapRectToDevice(pdfDocument, documentPage(pageIndex), startX, startY, sizeX, sizeY, 0, rect)

    fun dispose() {
        if (pdfDocument != null) {
            pdfiumCore.closeDocument(pdfDocument)
        }
        pdfDocument = null
        originalUserPages = null
    }

    /**
     * Given the UserPage number, this method restrict it
     * to be sure it's an existing page. It takes care of
     * using the user defined pages if any.
     *
     * @param userPage A page number.
     * @return A restricted valid page number (example : -2 => 0)
     */
    fun determineValidPageNumberFrom(userPage: Int): Int {
        if (userPage <= 0) {
            return 0
        }
        if (originalUserPages != null) {
            if (userPage >= originalUserPages!!.size) {
                return originalUserPages!!.size - 1
            }
        } else {
            if (userPage >= pagesCount) {
                return pagesCount - 1
            }
        }
        return userPage
    }

    fun documentPage(userPage: Int): Int {
        var documentPage = userPage
        if (originalUserPages != null) {
            if (userPage < 0 || userPage >= originalUserPages!!.size) {
                return -1
            } else {
                documentPage = originalUserPages!![userPage]
            }
        }

        if (documentPage < 0 || userPage >= pagesCount) {
            return -1
        }

        return documentPage
    }

    companion object {
        private val lock = Any()
    }
}
