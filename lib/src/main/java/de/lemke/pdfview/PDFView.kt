package de.lemke.pdfview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.AsyncTask
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.widget.RelativeLayout
import androidx.annotation.FloatRange
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfPasswordException
import com.shockwave.pdfium.PdfiumCore
import com.shockwave.pdfium.util.Size
import com.shockwave.pdfium.util.SizeF
import de.lemke.pdfview.exception.PageRenderingException
import de.lemke.pdfview.link.DefaultLinkHandler
import de.lemke.pdfview.link.LinkHandler
import de.lemke.pdfview.listener.OnAttachCompleteListener
import de.lemke.pdfview.listener.OnBitmapsReadyListener
import de.lemke.pdfview.listener.OnDetachCompleteListener
import de.lemke.pdfview.listener.OnDrawListener
import de.lemke.pdfview.listener.OnErrorListener
import de.lemke.pdfview.listener.OnLoadCompleteListener
import de.lemke.pdfview.listener.OnLongPressListener
import de.lemke.pdfview.listener.OnPageChangeListener
import de.lemke.pdfview.listener.OnPageErrorListener
import de.lemke.pdfview.listener.OnPageScrollListener
import de.lemke.pdfview.listener.OnPasswordExceptionListener
import de.lemke.pdfview.listener.OnRenderListener
import de.lemke.pdfview.listener.OnTapListener
import de.lemke.pdfview.model.PagePart
import de.lemke.pdfview.scroll.ScrollHandle
import de.lemke.pdfview.source.AssetSource
import de.lemke.pdfview.source.ByteArraySource
import de.lemke.pdfview.source.DocumentSource
import de.lemke.pdfview.source.FileSource
import de.lemke.pdfview.source.InputStreamSource
import de.lemke.pdfview.source.UriSource
import de.lemke.pdfview.util.FitPolicy
import de.lemke.pdfview.util.SnapEdge
import de.lemke.pdfview.util.Util.getDP
import java.io.File
import java.io.InputStream

/**
 * This class supports animations, zoom, caching, and swiping.
 *
 * Key principles:
 * - The PDF document is treated as if all pages are always drawn.
 * - Only the visible parts of the pages are actually drawn.
 * - All parts are the same size,because we can't interrupt a native page rendering,
 *   so we need these renderings to be as fast as possible, and be able to interrupt them
 * - Parts are loaded when the current offset or zoom level changes.
 *
 * Important terms:
 * - DocumentPage: A page of the PDF document.
 * - UserPage: A page as defined by the user. By default, it is the same as DocumentPage.
 *   However, the user can change the page order using the `.load` method. For example,
 *   a UserPage of 5 can refer to a DocumentPage of 17.
 */
@Suppress("unused")
class PDFView(context: Context, set: AttributeSet?) : RelativeLayout(context, set) {
    var onLoadCompleteListener: OnLoadCompleteListener? = null
    var onAttachCompleteListener: OnAttachCompleteListener? = null
    var onDetachCompleteListener: OnDetachCompleteListener? = null
    var onBitmapsReadyListener: OnBitmapsReadyListener? = null
    var onErrorListener: OnErrorListener? = null
    var onPasswordExceptionListener: OnPasswordExceptionListener? = null
    var onPageErrorListener: OnPageErrorListener? = null
    var onRenderListener: OnRenderListener? = null
    var onPageChangeListener: OnPageChangeListener? = null
    var onPageScrollListener: OnPageScrollListener? = null
    var onDrawListener: OnDrawListener? = null
    var onDrawAllListener: OnDrawListener? = null
    var onTapListener: OnTapListener? = null
    var onLongPressListener: OnLongPressListener? = null
    var linkHandler: LinkHandler? = null


    private var defaultPage = 0
    var swipeEnabled = true
    var doubleTapEnabled = true
    var swipeVertical = true
    var pageFlingEnabled = true
    var pageSnap = true
    var pageFitPolicy: FitPolicy = FitPolicy.BOTH
    var fitEachPage = false
    private var nightMode = false
        set(value) {
            field = value
            if (value) {
                paint.setColorFilter(
                    ColorMatrixColorFilter(
                        ColorMatrix(
                            floatArrayOf(
                                -1f, 0f, 0f, 0f, 255f,
                                0f, -1f, 0f, 0f, 255f,
                                0f, 0f, -1f, 0f, 255f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                )
            } else {
                paint.setColorFilter(null)
            }
        }

    /**
     * By default, generated bitmaps are compressed with [Bitmap.Config.RGB_565] format to reduce memory consumption.
     * If [.useBestQuality] is true, rendering will be done with [Bitmap.Config.ARGB_8888].
     */
    var bestQuality = false
    var antialiasingEnabled = true
    private val antialiasFilter = PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    var annotationRenderingEnabled = false

    /**
     * Spacing between pages in px
     */
    var pageSeparatorSpacing = 0
        private set

    /**
     * Start spacing in px
     */
    var startSpacing = 0
        private set

    /**
     * End spacing in px
     */
    var endSpacing = 0
        private set

    /**
     * Add dynamic spacing to fit each page separately on the screen.
     */
    var autoSpacing = false

    var minZoom = DEFAULT_MIN_SCALE
    var midZoom = DEFAULT_MID_SCALE
    var maxZoom = DEFAULT_MAX_SCALE
    var zoom = minZoom
    val isZooming: Boolean
        get() = zoom != minZoom

    var renderDuringScaleEnabled = false

    var scrollHandle: ScrollHandle? = null

    private var isScrollHandleInit = false

    /**
     * Thumbnail ratio (subpart of the PDF)
     * Between 0 and 1 where 1 is the best quality possible but it'll take more memory to render the PDF
     * Throw an exception if the value is 0
     */
    var thumbnailRatio = THUMBNAIL_RATIO
        set(value) {
            require(value != 0f) { "thumbnailRatio must be greater than 0" }
            field = value
        }

    /**
     * Horizontal border in pixels. This value represent how far you can scroll after an horizontal border of the PDF.
     */
    private var horizontalBorder = 0
        set(value) {
            field = getDP(context, value)
        }

    /**
     * Vertical border in pixels. This value represent how far you can scroll after an vertical border of the PDF.
     */
    private var verticalBorder = 0
        set(value) {
            field = getDP(context, value)
        }

    var currentPage = 0
        private set

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    var currentXOffset = 0f
        private set

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    var currentYOffset = 0f
        private set

    /**
     * True if the PDFView has been recycled
     */
    var recycled = true
        private set

    /**
     * Current state of the view
     */
    private var state = State.DEFAULT

    /**
     * START - scrolling in first page direction
     * END - scrolling in last page direction
     * NONE - not scrolling
     */
    internal enum class ScrollDir {
        NONE, START, END
    }

    private var scrollDir = ScrollDir.NONE


    var pdfFile: PdfFile? = null

    /**
     * Rendered parts go to the cache manager
     */
    var cacheManager: CacheManager = CacheManager()

    /**
     * Animation manager manage all offset and zoom animation
     */
    private val animationManager: AnimationManager = AnimationManager(this)

    /**
     * Drag manager manage all touch events
     */
    private val dragPinchManager: DragPinchManager = DragPinchManager(this, animationManager)

    /**
     * Async task used during the loading phase to decode a PDF document
     */
    private var decodingAsyncTask: DecodingAsyncTask? = null

    /**
     * The thread [.renderingHandler] will run on
     */
    private var renderingHandlerThread: HandlerThread? = null
    /**
     * Handler always waiting in the background and rendering tasks
     */
    var renderingHandler: RenderingHandler? = null

    private val pagesLoader: PagesLoader = PagesLoader(this)

    /**
     * Paint object for drawing
     */
    private val paint: Paint = Paint()

    /**
     * Paint object for drawing debug stuff
     */
    private val debugPaint: Paint = Paint()

    /**
     * Pdfium core for loading and rendering PDFs
     */
    private val pdfiumCore: PdfiumCore

    /**
     * Pages numbers used when calling onDrawAllListener
     */
    private val onDrawPagesNums: MutableList<Int> = ArrayList<Int>(10)

    /**
     * Holds info whether view has been added to layout and has width and height
     */
    private var hasSize = false

    /**
     * Holds last used Configurator that should be loaded when view has size
     */
    private var waitingDocumentConfigurator: Configurator? = null

    /**
     * Construct the initial view
     */
    init {
        /*if (isInEditMode) {
        return
        }*/
        debugPaint.style = Paint.Style.STROKE
        pdfiumCore = PdfiumCore(context)
        setWillNotDraw(false)
    }

    fun getPagesAsBitmaps(): List<Bitmap?> = cacheManager.getThumbnails().map { it.renderedBitmap }

    private fun load(docSource: DocumentSource, password: String?, userPages: IntArray? = null) {
        check(recycled) { "Don't call load on a PDF View without recycling it first." }
        recycled = false
        decodingAsyncTask = DecodingAsyncTask(this, pdfiumCore, docSource, password, userPages).apply {
            executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        }
    }

    /**
     * Go to the given page.
     *
     * @param page Page index.
     */
    @JvmOverloads
    fun jumpTo(page: Int, withAnimation: Boolean = false) {
        pdfFile?.let {
            val validPage = it.determineValidPageNumberFrom(page)
            val offset = -it.getPageOffset(validPage, zoom) + pageSeparatorSpacing
            if (swipeVertical) {
                if (withAnimation) {
                    animationManager.startYAnimation(currentYOffset, offset)
                } else {
                    moveTo(currentXOffset, offset, false)
                }
            } else {
                if (withAnimation) {
                    animationManager.startXAnimation(currentXOffset, offset)
                } else {
                    moveTo(offset, currentYOffset, false)
                }
            }
            showPage(validPage)
        }
    }

    fun showPage(pageNumber: Int) {
        if (recycled) return

        currentPage = pdfFile!!.determineValidPageNumberFrom(pageNumber)
        loadPages()

        scrollHandle?.takeIf { !documentFitsView() }?.setPageNum(currentPage + 1)
        onPageChangeListener?.onPageChanged(currentPage, pdfFile!!.pagesCount)
    }

    /**
     * Get current position as ratio of document length to visible area.
     * 0 means that document start is visible, 1 that document end is visible
     *
     * @return offset between 0 and 1
     */
    fun getPositionOffset(): Float {
        val offset = if (swipeVertical) -currentYOffset else -currentXOffset
        val docLen = pdfFile!!.getDocLen(zoom) - if (swipeVertical) height else width
        return (offset / docLen).coerceIn(0f, 1f)
    }

    /**
     * @param progress   must be between 0 and 1
     * @param moveHandle whether to move scroll handle
     * @see PDFView.getPositionOffset
     */
    fun setPositionOffset(progress: Float, moveHandle: Boolean = true) {
        val offset = (-pdfFile!!.getDocLen(zoom) + if (swipeVertical) height else width) * progress
        moveTo(if (swipeVertical) currentXOffset else offset, if (swipeVertical) offset else currentYOffset, moveHandle)
        loadPageByOffset()
    }

    override fun isShown(): Boolean = state == State.SHOWN

    fun stopFling() = animationManager.stopFling()

    fun getPageCount(): Int = pdfFile?.pagesCount ?: 0

    fun onPageError(ex: PageRenderingException) {
        onPageErrorListener?.onPageError(ex.page, ex.cause)
        Log.e(TAG, "Cannot open page ${ex.page}", ex.cause)
    }

    fun recycle() {
        waitingDocumentConfigurator = null
        animationManager.stopAll()
        dragPinchManager.enabled = false
        // Stop tasks
        renderingHandler?.removeMessages(RenderingHandler.MSG_RENDER_TASK)
        decodingAsyncTask?.cancel(true)
        // Clear caches
        cacheManager.recycle()
        if (isScrollHandleInit) {
            scrollHandle?.destroyLayout()
        }
        pdfFile?.dispose()
        pdfFile = null
        renderingHandler = null
        scrollHandle = null
        isScrollHandleInit = false
        currentYOffset = 0f
        currentXOffset = currentYOffset
        zoom = DEFAULT_MIN_SCALE
        recycled = true
        state = State.DEFAULT
    }

    /**
     * Handle fling animation
     */
    override fun computeScroll() {
        super.computeScroll()
        if (!isInEditMode) {
            animationManager.computeFling()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onAttachCompleteListener?.onAttachComplete()
        renderingHandlerThread = renderingHandlerThread ?: HandlerThread("PDF renderer")
    }

    override fun onDetachedFromWindow() {
        onDetachCompleteListener?.onDetachComplete()
        recycle()
        renderingHandlerThread?.quitSafely()
        renderingHandlerThread = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        hasSize = true
        waitingDocumentConfigurator?.load()
        if (isInEditMode || state != State.SHOWN) return

        val centerPointInStripXOffset = -currentXOffset + oldw * 0.5f
        val centerPointInStripYOffset = -currentYOffset + oldh * 0.5f

        val (relativeCenterPointInStripXOffset, relativeCenterPointInStripYOffset) = if (swipeVertical) {
            centerPointInStripXOffset / pdfFile!!.maxPageWidth to centerPointInStripYOffset / pdfFile!!.getDocLen(zoom)
        } else {
            centerPointInStripXOffset / pdfFile!!.getDocLen(zoom) to centerPointInStripYOffset / pdfFile!!.maxPageHeight
        }

        animationManager.stopAll()
        pdfFile!!.recalculatePageSizes(Size(w, h))

        if (swipeVertical) {
            currentXOffset = -relativeCenterPointInStripXOffset * pdfFile!!.maxPageWidth + w * 0.5f
            currentYOffset = -relativeCenterPointInStripYOffset * pdfFile!!.getDocLen(zoom) + h * 0.5f
        } else {
            currentXOffset = -relativeCenterPointInStripXOffset * pdfFile!!.getDocLen(zoom) + w * 0.5f
            currentYOffset = -relativeCenterPointInStripYOffset * pdfFile!!.maxPageHeight + h * 0.5f
        }
        moveTo(currentXOffset, currentYOffset)
        loadPageByOffset()
    }

    override fun canScrollHorizontally(direction: Int): Boolean = when {
        pdfFile == null -> true
        swipeVertical -> {
            (direction < 0 && currentXOffset < 0) || (direction > 0 && currentXOffset + toCurrentScale(pdfFile!!.maxPageWidth) > width)
        }
        else -> {
            (direction < 0 && currentXOffset < 0) || (direction > 0 && currentXOffset + pdfFile!!.getDocLen(zoom) > width)
        }
    }

    override fun canScrollVertically(direction: Int): Boolean = when {
        pdfFile == null -> true
        swipeVertical -> {
            (direction < 0 && currentYOffset < 0) || (direction > 0 && currentYOffset + pdfFile!!.getDocLen(zoom) > height)
        }
        else -> {
            (direction < 0 && currentYOffset < 0) || (direction > 0 && currentYOffset + toCurrentScale(pdfFile!!.maxPageHeight) > height)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (isInEditMode) return
        // As I said in this class javadoc, we can think of this canvas as a huge
        // strip on which we draw all the images. We actually only draw the rendered
        // parts, of course, but we render them in the place they belong in this huge
        // strip.

        // That's where Canvas.translate(x, y) becomes very helpful.
        // This is the situation :
        //  _______________________________________________
        // |   			 |					 			   |
        // | the actual  |					The big strip  |
        // |	canvas	 | 								   |
        // |_____________|								   |
        // |_______________________________________________|
        //
        // If the rendered part is on the bottom right corner of the strip
        // we can draw it but we won't see it because the canvas is not big enough.

        // But if we call translate(-X, -Y) on the canvas just before drawing the object :
        //  _______________________________________________
        // |   			  					  _____________|
        // |   The big strip     			 |			   |
        // |		    					 |	the actual |
        // |								 |	canvas	   |
        // |_________________________________|_____________|
        //
        // The object will be on the canvas.
        // This technique is massively used in this method, and allows
        // abstraction of the screen position when rendering the parts.

        // Draws background
        if (antialiasingEnabled) {
            canvas.setDrawFilter(antialiasFilter)
        }
        if (background == null) {
            canvas.drawColor(if (nightMode) Color.BLACK else Color.WHITE)
        } else {
            background.draw(canvas)
        }
        if (recycled || state != State.SHOWN) return
        // Moves the canvas before drawing any element
        canvas.translate(currentXOffset, currentYOffset)
        // Draws thumbnails and parts
        cacheManager.getThumbnails().forEach { drawPart(canvas, it) }
        cacheManager.getPageParts().forEach { part ->
            drawPart(canvas, part!!)
            if (onDrawAllListener != null && !onDrawPagesNums.contains(part.page)) {
                onDrawPagesNums.add(part.page)
            }
        }
        // Draws with listeners
        onDrawPagesNums.forEach { drawWithListener(canvas, it, onDrawAllListener) }
        onDrawPagesNums.clear()
        drawWithListener(canvas, currentPage, onDrawListener)
        // Restores the canvas position
        canvas.translate(-currentXOffset, -currentYOffset)
    }

    private fun drawWithListener(canvas: Canvas, page: Int, listener: OnDrawListener?) {
        listener?.let {
            val (translateX, translateY) = if (swipeVertical) {
                0f to pdfFile!!.getPageOffset(page, zoom)
            } else {
                pdfFile!!.getPageOffset(page, zoom) to 0f
            }

            canvas.translate(translateX, translateY)
            val size = pdfFile!!.getPageSize(page)
            it.onLayerDrawn(
                canvas,
                toCurrentScale(size.width),
                toCurrentScale(size.height),
                page
            )
            canvas.translate(-translateX, -translateY)
        }
    }

    /**
     * Draw a given PagePart on the canvas
     */
    private fun drawPart(canvas: Canvas, part: PagePart) {
        // Can seem strange, but avoid lot of calls
        val pageRelativeBounds = part.pageRelativeBounds
        val renderedBitmap = part.renderedBitmap
        if (renderedBitmap!!.isRecycled) return

        // Move to the target page
        var localTranslationX: Float
        var localTranslationY: Float
        val size = pdfFile!!.getPageSize(part.page)

        if (swipeVertical) {
            localTranslationY = pdfFile!!.getPageOffset(part.page, zoom)
            val maxWidth = pdfFile!!.maxPageWidth
            localTranslationX = toCurrentScale(maxWidth - size.width) / 2
        } else {
            localTranslationX = pdfFile!!.getPageOffset(part.page, zoom)
            val maxHeight = pdfFile!!.maxPageHeight
            localTranslationY = toCurrentScale(maxHeight - size.height) / 2
        }
        canvas.translate(localTranslationX, localTranslationY)

        val srcRect = Rect(0, 0, renderedBitmap.getWidth(), renderedBitmap.getHeight())
        val offsetX = toCurrentScale(pageRelativeBounds.left * size.width)
        val offsetY = toCurrentScale(pageRelativeBounds.top * size.height)
        val width = toCurrentScale(pageRelativeBounds.width() * size.width)
        val height = toCurrentScale(pageRelativeBounds.height() * size.height)
        // If we use float values for this rectangle, there will be
        // a possible gap between page parts, especially when
        // the zoom level is high.
        val dstRect = RectF(
            offsetX.toInt().toFloat(), offsetY.toInt().toFloat(),
            (offsetX + width).toInt().toFloat(),
            (offsetY + height).toInt().toFloat()
        )

        // Check if bitmap is in the screen
        val translationX = currentXOffset + localTranslationX
        val translationY = currentYOffset + localTranslationY
        if (translationX + dstRect.left >= getWidth() || translationX + dstRect.right <= 0 || translationY + dstRect.top >= getHeight() || translationY + dstRect.bottom <= 0) {
            canvas.translate(-localTranslationX, -localTranslationY)
            return
        }

        canvas.drawBitmap(renderedBitmap, srcRect, dstRect, paint)

        //debugPaint.setColor(if (part.page % 2 == 0) Color.RED else Color.BLUE) //for debugging
        //canvas.drawRect(dstRect, debugPaint)

        // Restore the canvas position
        canvas.translate(-localTranslationX, -localTranslationY)
    }

    /**
     * Load all the parts around the center of the screen,
     * taking into account X and Y offsets, zoom level, and
     * the current page displayed
     */
    fun loadPages() {
        pdfFile?.let {
            renderingHandler?.apply {
                removeMessages(RenderingHandler.MSG_RENDER_TASK)
                cacheManager.makeANewSet()
                pagesLoader.loadPages()
                invalidate()
            }
        }
    }

    /**
     * Force the generation of bitmaps for all pages.
     * Implement [OnBitmapsReadyListener] to retrieve the bitmaps.
     */
    fun loadPagesForPrinting() {
        pdfFile?.let {
            renderingHandler?.apply {
                removeMessages(RenderingHandler.MSG_RENDER_TASK)
                cacheManager.makeANewSet()
                pagesLoader.loadPagesForPrinting(getPageCount())
            }
        }
    }

    /**
     * Called when the PDF is loaded
     */
    fun loadComplete(pdfFile: PdfFile) {
        state = State.LOADED
        this.pdfFile = pdfFile
        if (renderingHandlerThread == null) return
        if (!renderingHandlerThread!!.isAlive) {
            renderingHandlerThread!!.start()
        }
        renderingHandler = RenderingHandler(renderingHandlerThread!!.getLooper(), this)
        renderingHandler!!.start()
        if (scrollHandle != null) {
            scrollHandle?.setupLayout(this)
            isScrollHandleInit = true
        }
        dragPinchManager.enabled = true
        onLoadCompleteListener?.loadComplete(pdfFile.pagesCount)
        jumpTo(defaultPage, false)
    }

    fun loadError(t: Throwable?) {
        state = State.ERROR
        // store reference, because callbacks will be cleared in recycle() method
        recycle()
        invalidate()
        if (t is PdfPasswordException) onPasswordExceptionListener?.onPasswordException()
        else onErrorListener?.onError(t)
        Log.e(TAG, "load pdf error", t)
    }

    /**
     * Called when a rendering task is over and
     * a PagePart has been freshly created.
     *
     * @param part The created PagePart.
     */
    fun onBitmapRendered(part: PagePart, isForPrinting: Boolean) {
        // when it is first rendered part
        if (state == State.LOADED) {
            state = State.SHOWN
            onRenderListener?.onInitiallyRendered(pdfFile!!.pagesCount)
        }

        if (part.isThumbnail) {
            cacheManager.cacheThumbnail(part, isForPrinting)
            if (isForPrinting && pdfFile!!.pagesCount - 1 == part.page) {
                onBitmapsReadyListener?.bitmapsReady(getPagesAsBitmaps())
            }
        } else {
            cacheManager.cachePart(part)
        }
        invalidate()
    }

    /**
     * Move to the given X and Y offsets, but check them ahead of time
     * to be sure not to go outside the big strip.
     *
     * @param offsetX    The big strip X offset to use as the left border of the screen.
     * @param offsetY    The big strip Y offset to use as the right border of the screen.
     * @param moveHandle whether to move scroll handle or not
     */
    @JvmOverloads
    fun moveTo(offsetX: Float, offsetY: Float, moveHandle: Boolean = true) {
        var offsetX = offsetX
        var offsetY = offsetY
        if (swipeVertical) {
            // Check X offset
            val scaledPageWidth = toCurrentScale(pdfFile!!.maxPageWidth)
            if (scaledPageWidth < width) {
                offsetX = width / 2f - scaledPageWidth / 2f
            } else {
                if (offsetX > horizontalBorder) {
                    offsetX = horizontalBorder.toFloat()
                } else if (offsetX + scaledPageWidth + horizontalBorder < width) {
                    offsetX = width - scaledPageWidth - horizontalBorder
                }
            }

            // Check Y offset
            val contentHeight = pdfFile!!.getDocLen(zoom)
            if (contentHeight < height) { // whole document height visible on screen
                offsetY = (height - contentHeight) / 2
            } else {
                val maxOffsetY = toCurrentScale((verticalBorder * 2f) - startSpacing)
                if (offsetY > maxOffsetY) { // top visible
                    offsetY = maxOffsetY
                } else if (offsetY + contentHeight + toCurrentScale((verticalBorder * 2f)) < height + toCurrentScale(
                        endSpacing.toFloat()
                    )
                ) { // bottom visible
                    offsetY = -contentHeight + height + toCurrentScale(endSpacing - (verticalBorder * 2f))
                }
            }

            scrollDir = if (offsetY < currentYOffset) {
                ScrollDir.END
            } else if (offsetY > currentYOffset) {
                ScrollDir.START
            } else {
                ScrollDir.NONE
            }
        } else {
            // Check Y offset
            val scaledPageHeight = toCurrentScale(pdfFile!!.maxPageHeight)
            if (scaledPageHeight < height) {
                offsetY = height / 2f - scaledPageHeight / 2f
            } else {
                if (offsetY > horizontalBorder) {
                    offsetY = horizontalBorder.toFloat()
                } else if (offsetY + scaledPageHeight + horizontalBorder < height) {
                    offsetY = height - scaledPageHeight - horizontalBorder
                }
            }

            // Check X offset
            val contentWidth = pdfFile!!.getDocLen(zoom)
            if (contentWidth < width) { // whole document width visible on screen
                offsetX = (width - contentWidth) / 2f
            } else {
                val maxOffsetX = toCurrentScale((horizontalBorder * 2f) - startSpacing)
                if (offsetX > maxOffsetX) { // left visible
                    offsetX = maxOffsetX
                } else if (offsetX + contentWidth + toCurrentScale((horizontalBorder * 2f)) < width + toCurrentScale(
                        endSpacing.toFloat()
                    )
                ) { // right visible
                    offsetX = -contentWidth + width + toCurrentScale(endSpacing - (horizontalBorder * 2f))
                }
            }

            scrollDir = if (offsetX < currentXOffset) {
                ScrollDir.END
            } else if (offsetX > currentXOffset) {
                ScrollDir.START
            } else {
                ScrollDir.NONE
            }
        }

        currentXOffset = offsetX
        currentYOffset = offsetY
        val positionOffset = getPositionOffset()

        if (moveHandle && !documentFitsView()) {
            scrollHandle?.setScroll(positionOffset)
        }

        onPageScrollListener?.onPageScrolled(currentPage, positionOffset)

        invalidate()
    }

    fun loadPageByOffset() {
        pdfFile?.let {
            if (it.pagesCount == 0) return
            val offset = if (swipeVertical) currentYOffset else currentXOffset
            val screenCenter = if (swipeVertical) height / 2f else width / 2f
            val page = it.getPageAtOffset(-(offset - screenCenter), zoom)
            if (page in 0 until it.pagesCount && page != currentPage) {
                showPage(page)
            } else {
                loadPages()
            }
        }
    }

    /**
     * Animate to the nearest snapping position for the current SnapPolicy
     */
    fun performPageSnap() {
        if (!pageSnap || pdfFile?.pagesCount == 0) return

        val centerPage = findFocusPage(currentXOffset, currentYOffset)
        val edge = findSnapEdge(centerPage)
        if (edge == SnapEdge.NONE) return

        val offset = snapOffsetForPage(centerPage, edge)
        if (swipeVertical) animationManager.startYAnimation(currentYOffset, -offset)
        else animationManager.startXAnimation(currentXOffset, -offset)
    }

    /**
     * Find the edge to snap to when showing the specified page
     */
    fun findSnapEdge(page: Int): SnapEdge {
        if (!pageSnap || page < 0) return SnapEdge.NONE

        val currentOffset = if (swipeVertical) currentYOffset else currentXOffset
        val offset = -pdfFile!!.getPageOffset(page, zoom)
        val length = if (swipeVertical) height else width
        val pageLength = pdfFile!!.getPageLength(page, zoom)

        return when {
            length >= pageLength -> SnapEdge.CENTER
            currentOffset >= offset -> SnapEdge.START
            offset - pageLength > currentOffset - length -> SnapEdge.END
            else -> SnapEdge.NONE
        }
    }

    /**
     * Get the offset to move to in order to snap to the page
     */
    fun snapOffsetForPage(pageIndex: Int, edge: SnapEdge?): Float {
        var offset = pdfFile!!.getPageOffset(pageIndex, zoom)

        val length = (if (swipeVertical) height else width).toFloat()
        val pageLength = pdfFile!!.getPageLength(pageIndex, zoom)

        if (edge == SnapEdge.CENTER) {
            offset = offset - length / 2f + pageLength / 2f
        } else if (edge == SnapEdge.END) {
            offset = offset - length + pageLength
        }
        return offset
    }

    fun findFocusPage(xOffset: Float, yOffset: Float): Int {
        val currOffset = if (swipeVertical) yOffset else xOffset
        val length = (if (swipeVertical) height else width).toFloat()
        // make sure first and last page can be found
        if (currOffset > -1) {
            return 0
        } else if (currOffset < -pdfFile!!.getDocLen(zoom) + length + 1) {
            return pdfFile!!.pagesCount - 1
        }
        // else find page in center
        val center = currOffset - length / 2f
        return pdfFile!!.getPageAtOffset(-center, zoom)
    }

    /**
     * Set touch priority to the PDFView. Use this method if you use the PDFView
     * in a ViewPager, RecyclerView, etc. to avoid any problems when dragging while zoomed in.
     *
     * @param hasPriority true if you want the PDFView to disable touch capabilities of the first parent RecyclerView
     */
    fun setTouchPriority(hasPriority: Boolean) {
        dragPinchManager.hasTouchPriority = hasPriority
    }

    /**
     * @return true if single page fills the entire screen in the scrolling direction
     */
    fun pageFillsScreen(): Boolean {
        val start = -pdfFile!!.getPageOffset(currentPage, zoom)
        val end = start - pdfFile!!.getPageLength(currentPage, zoom)
        val offset = if (swipeVertical) currentYOffset else currentXOffset
        val dimension = if (swipeVertical) height else width
        return start > offset && end < offset - dimension
    }

    /**
     * Move relatively to the current position.
     *
     * @param dx The X difference you want to apply.
     * @param dy The Y difference you want to apply.
     * @see .moveTo
     */
    fun moveRelativeTo(dx: Float, dy: Float) = moveTo(currentXOffset + dx, currentYOffset + dy)

    /**
     * Change the zoom level, relatively to a pivot point.
     * It will call moveTo() to make sure the given point stays
     * in the middle of the screen.
     *
     * @param zoomLevel  The zoom level.
     * @param pivot The point on the screen that should stay.
     */
    fun zoomCenteredTo(zoomLevel: Float, pivot: PointF) {
        val newZoom = zoomLevel / this.zoom
        zoom = zoomLevel
        val baseX = currentXOffset * newZoom + pivot.x * (1 - newZoom)
        val baseY = currentYOffset * newZoom + pivot.y * (1 - newZoom)
        moveTo(baseX, baseY)
    }

    /**
     * @see .zoomCenteredTo
     */
    fun zoomCenteredRelativeTo(zoomLevel: Float, pivot: PointF) = zoomCenteredTo(zoom * zoomLevel, pivot)

    /**
     * Checks if whole document can be displayed on screen, doesn't include zoom
     *
     * @return true if whole document can displayed at once, false otherwise
     */
    fun documentFitsView(): Boolean = pdfFile!!.getDocLen(1f) < if (swipeVertical) height else width

    fun fitToWidth(page: Int) {
        pdfFile?.let {
            if (state == State.SHOWN) {
                zoom = width / it.getPageSize(page).width
                jumpTo(page)
            } else {
                Log.e(TAG, "Cannot fit, document not rendered yet")
            }
        }
    }

    fun getPageSize(pageIndex: Int): SizeF = pdfFile?.getPageSize(pageIndex) ?: SizeF(0f, 0f)

    fun toRealScale(size: Float): Float = size / zoom

    fun toCurrentScale(size: Float): Float = size * zoom

    fun resetZoom() {
        zoom = minZoom
    }

    fun resetZoomWithAnimation() = zoomWithAnimation(minZoom)

    fun zoomWithAnimation(centerX: Float, centerY: Float, scale: Float) {
        animationManager.startZoomAnimation(centerX, centerY, zoom, scale)
    }

    fun zoomWithAnimation(scale: Float) {
        animationManager.startZoomAnimation(width.toFloat() / 2, height.toFloat() / 2, zoom, scale)
    }

    /**
     * Get page number at given offset
     *
     * @param positionOffset scroll offset between 0 and 1
     * @return page number at given offset, starting from 0
     */
    fun getPageAtPositionOffset(positionOffset: Float): Int =
        pdfFile!!.getPageAtOffset(pdfFile!!.getDocLen(zoom) * positionOffset, zoom)

    /**
     * Returns null if document is not loaded
     */
    val documentMeta: PdfDocument.Meta?
        get() = if (pdfFile == null) {
            null
        } else pdfFile!!.getMetaData()

    /**
     * Will be empty until document is loaded
     */
    val tableOfContents: MutableList<PdfDocument.Bookmark>
        get() = if (pdfFile == null) {
            mutableListOf<PdfDocument.Bookmark>()
        } else pdfFile!!.getBookmarks()

    /**
     * Will be empty until document is loaded
     */
    fun getLinks(page: Int): MutableList<PdfDocument.Link> {
        if (pdfFile == null) {
            return mutableListOf<PdfDocument.Link>()
        }
        return pdfFile!!.getPageLinks(page)
    }

    /**
     * Use an asset file as the pdf source
     */
    fun fromAsset(assetName: String): Configurator = Configurator(AssetSource(assetName))

    /**
     * Use a file as the pdf source
     */
    fun fromFile(file: File?): Configurator = Configurator(FileSource(file))

    /**
     * Use URI as the pdf source, for use with content providers
     */
    fun fromUri(uri: Uri): Configurator = Configurator(UriSource(uri))

    /**
     * Use bytearray as the pdf source, documents is not saved
     */
    fun fromBytes(bytes: ByteArray?): Configurator = Configurator(ByteArraySource(bytes))

    /**
     * Use stream as the pdf source. Stream will be written to bytearray, because native code does not support Java Streams
     */
    fun fromStream(stream: InputStream): Configurator = Configurator(InputStreamSource(stream))

    /**
     * Use custom source as pdf source
     */
    fun fromSource(docSource: DocumentSource): Configurator = Configurator(docSource)

    private enum class State {
        DEFAULT, LOADED, SHOWN, ERROR
    }

    @Suppress("unused")
    inner class Configurator(private val documentSource: DocumentSource) {
        var onDrawListener: OnDrawListener? = null
        var onDrawAllListener: OnDrawListener? = null
        var onBitmapsReadyListener: OnBitmapsReadyListener? = null
        var onLoadCompleteListener: OnLoadCompleteListener? = null
        var onAttachCompleteListener: OnAttachCompleteListener? = null
        var onDetachCompleteListener: OnDetachCompleteListener? = null
        var onErrorListener: OnErrorListener? = null
        var onPasswordExceptionListener: OnPasswordExceptionListener? = null
        var onPageChangeListener: OnPageChangeListener? = null
        var onPageScrollListener: OnPageScrollListener? = null
        var onRenderListener: OnRenderListener? = null
        var onTapListener: OnTapListener? = null
        var onLongPressListener: OnLongPressListener? = null
        var onPageErrorListener: OnPageErrorListener? = null
        var linkHandler: LinkHandler? = DefaultLinkHandler(this@PDFView)

        var pageNumbers: IntArray? = null
        var defaultPage = 0
        var enableSwipe = true
        var enableDoubletap = true
        var swipeHorizontal = false
        var pageFling = false
        var pageSnap = false
        var pageFitPolicy: FitPolicy = FitPolicy.BOTH
        var fitEachPage = false
        var nightMode = false
        var useBestQuality = false
        var antialiasing = true
        var annotationRendering = false
        var pageSeparatorSpacing = 0
        var startSpacing = 0
        var endSpacing = 0
        var autoSpacing = false
        var minZoom = DEFAULT_MIN_SCALE
        var midZoom = DEFAULT_MID_SCALE
        var maxZoom = DEFAULT_MAX_SCALE
        var touchPriority = false
        var password: String? = null
        var scrollHandle: ScrollHandle? = null
        var thumbnailRatio = THUMBNAIL_RATIO
        var horizontalBorder = 0
        var verticalBorder = 0

        fun onDraw(onDrawListener: OnDrawListener?) = apply { this.onDrawListener = onDrawListener }
        fun onDrawAll(onDrawAllListener: OnDrawListener?) = apply { this.onDrawAllListener = onDrawAllListener }
        fun onBitmapsReady(listener: OnBitmapsReadyListener?) = apply { this.onBitmapsReadyListener = listener }
        fun onLoad(listener: OnLoadCompleteListener?) = apply { this.onLoadCompleteListener = listener }
        fun onAttach(listener: OnAttachCompleteListener?) = apply { this.onAttachCompleteListener = listener }
        fun onDetach(listener: OnDetachCompleteListener?) = apply { this.onDetachCompleteListener = listener }
        fun onPageScroll(listener: OnPageScrollListener?) = apply { this.onPageScrollListener = listener }
        fun onError(listener: OnErrorListener?) = apply { this.onErrorListener = listener }
        fun onPasswordException(listener: OnPasswordExceptionListener?) = apply { this.onPasswordExceptionListener = listener }
        fun onPageError(listener: OnPageErrorListener?) = apply { this.onPageErrorListener = listener }
        fun onPageChange(listener: OnPageChangeListener?) = apply { this.onPageChangeListener = listener }
        fun onRender(listener: OnRenderListener?) = apply { this.onRenderListener = listener }
        fun onTap(listener: OnTapListener?) = apply { this.onTapListener = listener }
        fun onLongPress(listener: OnLongPressListener?) = apply { this.onLongPressListener = listener }
        fun linkHandler(linkHandler: LinkHandler?) = apply { this.linkHandler = linkHandler }

        fun pages(vararg pageNumbers: Int) = apply { this.pageNumbers = pageNumbers }
        fun pages(range: IntRange) = apply { this.pageNumbers = range.toList().toIntArray() }
        fun defaultPage(defaultPage: Int) = apply { this.defaultPage = defaultPage }
        fun enableSwipe(enableSwipe: Boolean) = apply { this.enableSwipe = enableSwipe }
        fun enableDoubletap(enableDoubletap: Boolean) = apply { this.enableDoubletap = enableDoubletap }
        fun swipeHorizontal(swipeHorizontal: Boolean) = apply { this.swipeHorizontal = swipeHorizontal }
        fun pageFling(pageFling: Boolean) = apply { this.pageFling = pageFling }
        fun pageSnap(pageSnap: Boolean) = apply { this.pageSnap = pageSnap }
        fun pageFitPolicy(pageFitPolicy: FitPolicy) = apply { this.pageFitPolicy = pageFitPolicy }
        fun fitEachPage(fitEachPage: Boolean) = apply { this.fitEachPage = fitEachPage }
        fun nightMode(nightMode: Boolean) = apply { this.nightMode = nightMode }
        fun useBestQuality(useBestQuality: Boolean) = apply { this.useBestQuality = useBestQuality }
        fun enableAntialiasing(antialiasing: Boolean) = apply { this.antialiasing = antialiasing }
        fun enableAnnotationRendering(annotationRendering: Boolean) = apply { this.annotationRendering = annotationRendering }
        fun pageSeparatorSpacing(pageSeparatorSpacing: Int) = apply { this.pageSeparatorSpacing = pageSeparatorSpacing }
        fun startEndSpacing(start: Int, end: Int) = apply { this.startSpacing = start; this.endSpacing = end }
        fun autoSpacing(autoSpacing: Boolean) = apply { this.autoSpacing = autoSpacing }
        fun zoom(min: Float, mid: Float, max: Float) = apply { this.minZoom = min; this.midZoom = mid; this.maxZoom = max }
        fun touchPriority(hasPriority: Boolean) = apply { this.touchPriority = hasPriority }
        fun password(password: String?) = apply { this.password = password }
        fun scrollHandle(scrollHandle: ScrollHandle?) = apply { this.scrollHandle = scrollHandle }
        fun thumbnailRatio(@FloatRange(from = 0.1, to = 1.0) ratio: Float) = apply { this.thumbnailRatio = ratio }
        fun horizontalBorder(horizontalBorder: Int) = apply { this.horizontalBorder = horizontalBorder }
        fun verticalBorder(verticalBorder: Int) = apply { this.verticalBorder = verticalBorder }
        fun disableLongPress() = apply { this@PDFView.dragPinchManager.disableLongPress() }

        fun load() {
            if (!hasSize) {
                waitingDocumentConfigurator = this
                return
            }
            this@PDFView.recycle()
            this@PDFView.onLoadCompleteListener = onLoadCompleteListener
            this@PDFView.onAttachCompleteListener = onAttachCompleteListener
            this@PDFView.onDetachCompleteListener = onDetachCompleteListener
            this@PDFView.onBitmapsReadyListener = onBitmapsReadyListener
            this@PDFView.onErrorListener = onErrorListener
            this@PDFView.onPasswordExceptionListener = onPasswordExceptionListener
            this@PDFView.onDrawListener = onDrawListener
            this@PDFView.onDrawAllListener = onDrawAllListener
            this@PDFView.onPageChangeListener = onPageChangeListener
            this@PDFView.onPageScrollListener = onPageScrollListener
            this@PDFView.onRenderListener = onRenderListener
            this@PDFView.onTapListener = onTapListener
            this@PDFView.onLongPressListener = onLongPressListener
            this@PDFView.onPageErrorListener = onPageErrorListener
            this@PDFView.linkHandler = linkHandler
            this@PDFView.setTouchPriority(touchPriority)
            this@PDFView.defaultPage = defaultPage
            this@PDFView.swipeEnabled = enableSwipe
            this@PDFView.doubleTapEnabled = enableDoubletap
            this@PDFView.swipeVertical = !swipeHorizontal
            this@PDFView.pageFlingEnabled = pageFling
            this@PDFView.pageSnap = pageSnap
            this@PDFView.pageFitPolicy = pageFitPolicy
            this@PDFView.fitEachPage = fitEachPage
            this@PDFView.nightMode = nightMode
            this@PDFView.bestQuality = useBestQuality
            this@PDFView.antialiasingEnabled = antialiasing
            this@PDFView.annotationRenderingEnabled = annotationRendering
            this@PDFView.pageSeparatorSpacing = getDP(context, pageSeparatorSpacing)
            this@PDFView.startSpacing = getDP(context, startSpacing)
            this@PDFView.endSpacing = getDP(context, endSpacing)
            this@PDFView.autoSpacing = autoSpacing
            this@PDFView.minZoom = minZoom
            this@PDFView.midZoom = midZoom
            this@PDFView.maxZoom = maxZoom
            this@PDFView.scrollHandle = scrollHandle
            this@PDFView.thumbnailRatio = thumbnailRatio
            this@PDFView.horizontalBorder = horizontalBorder
            this@PDFView.verticalBorder = verticalBorder
            this@PDFView.load(documentSource, password, pageNumbers)
        }
    }

    companion object {
        private val TAG: String = PDFView::class.java.getSimpleName()

        const val DEFAULT_MAX_SCALE: Float = 2f //3f (disable)
        const val DEFAULT_MID_SCALE: Float = 2f
        const val DEFAULT_MIN_SCALE: Float = 1f

        /**
         * Between 0 and 1, the thumbnails quality (default 0.3). Increasing this value may cause performances decrease.
         */
        const val THUMBNAIL_RATIO = 0.3f
    }
}
