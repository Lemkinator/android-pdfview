package com.infomaniak.lib.pdfview

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
import com.infomaniak.lib.pdfview.PDFView.ScrollDir
import com.infomaniak.lib.pdfview.exception.PageRenderingException
import com.infomaniak.lib.pdfview.link.DefaultLinkHandler
import com.infomaniak.lib.pdfview.link.LinkHandler
import com.infomaniak.lib.pdfview.listener.OnAttachCompleteListener
import com.infomaniak.lib.pdfview.listener.OnBitmapsReadyListener
import com.infomaniak.lib.pdfview.listener.OnDetachCompleteListener
import com.infomaniak.lib.pdfview.listener.OnDrawListener
import com.infomaniak.lib.pdfview.listener.OnErrorListener
import com.infomaniak.lib.pdfview.listener.OnLoadCompleteListener
import com.infomaniak.lib.pdfview.listener.OnLongPressListener
import com.infomaniak.lib.pdfview.listener.OnPageChangeListener
import com.infomaniak.lib.pdfview.listener.OnPageErrorListener
import com.infomaniak.lib.pdfview.listener.OnPageScrollListener
import com.infomaniak.lib.pdfview.listener.OnRenderListener
import com.infomaniak.lib.pdfview.listener.OnTapListener
import com.infomaniak.lib.pdfview.model.PagePart
import com.infomaniak.lib.pdfview.scroll.ScrollHandle
import com.infomaniak.lib.pdfview.source.AssetSource
import com.infomaniak.lib.pdfview.source.ByteArraySource
import com.infomaniak.lib.pdfview.source.DocumentSource
import com.infomaniak.lib.pdfview.source.FileSource
import com.infomaniak.lib.pdfview.source.InputStreamSource
import com.infomaniak.lib.pdfview.source.UriSource
import com.infomaniak.lib.pdfview.util.Constants
import com.infomaniak.lib.pdfview.util.FitPolicy
import com.infomaniak.lib.pdfview.util.SnapEdge
import com.infomaniak.lib.pdfview.util.Util.getDP
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import com.shockwave.pdfium.util.Size
import com.shockwave.pdfium.util.SizeF
import java.io.File
import java.io.InputStream
import java.util.ArrayList

/**
 * It supports animations, zoom, cache, and swipe.
 *
 *
 * To fully understand this class you must know its principles :
 * - The PDF document is seen as if we always want to draw all the pages.
 * - The thing is that we only draw the visible parts.
 * - All parts are the same size, this is because we can't interrupt a native page rendering,
 * so we need these renderings to be as fast as possible, and be able to interrupt them
 * as soon as we can.
 * - The parts are loaded when the current offset or the current zoom level changes
 *
 *
 * Important :
 * - DocumentPage = A page of the PDF document.
 * - UserPage = A page as defined by the user.
 * By default, they're the same. But the user can change the pages order
 * using [.load]. In this
 * particular case, a userPage of 5 can refer to a documentPage of 17.
 */
class PDFView(context: Context, set: AttributeSet?) : RelativeLayout(context, set) {
    var minZoom = DEFAULT_MIN_SCALE
    var midZoom = DEFAULT_MID_SCALE
    var maxZoom = DEFAULT_MAX_SCALE

    /**
     * START - scrolling in first page direction
     * END - scrolling in last page direction
     * NONE - not scrolling
     */
    internal enum class ScrollDir {
        NONE, START, END
    }

    private var scrollDir = ScrollDir.NONE

    /**
     * Rendered parts go to the cache manager
     */
    /*if (isInEditMode) {
        return
    }*/var cacheManager: CacheManager = CacheManager()

    /**
     * Animation manager manage all offset and zoom animation
     */
    private val animationManager: AnimationManager = AnimationManager(this)

    /**
     * Drag manager manage all touch events
     */
    private val dragPinchManager: DragPinchManager = DragPinchManager(this, animationManager)

    var pdfFile: PdfFile? = null

    /**
     * The index of the current sequence
     */
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
     * The zoom level, always >= DEFAULT_MIN_SCALE
     */
    var zoom = minZoom

    val isZooming: Boolean
        get() = zoom != minZoom

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

    var onBitmapsReadyListener: OnBitmapsReadyListener? = null

    /**
     * Call back object to call when the PDF is loaded
     */
    var onLoadCompleteListener: OnLoadCompleteListener? = null
    var onAttachCompleteListener: OnAttachCompleteListener? = null
    var onDetachCompleteListener: OnDetachCompleteListener? = null

    /**
     * Call back object to call when document loading error occurs
     */
    var onErrorListener: OnErrorListener? = null

    /**
     * Call back object to call when the page load error occurs
     */
    var onPageErrorListener: OnPageErrorListener? = null

    /**
     * Call back object to call when the document is initially rendered
     */
    var onRenderListener: OnRenderListener? = null

    /**
     * Call back object to call when the page has changed
     */
    var onPageChangeListener: OnPageChangeListener? = null

    /**
     * Call back object to call when the page is scrolled
     */
    var onPageScrollListener: OnPageScrollListener? = null

    /**
     * Call back object to call when the above layer is to drawn
     */
    var onDrawListener: OnDrawListener? = null

    var onDrawAllListener: OnDrawListener? = null

    /**
     * Call back object to call when the user does a tap gesture
     */
    var onTapListener: OnTapListener? = null

    /**
     * Call back object to call when the user does a long tap gesture
     */
    var onLongPressListener: OnLongPressListener? = null

    /**
     * Call back object to call when clicking link
     */
    var linkHandler: LinkHandler? = null

    /**
     * Paint object for drawing
     */
    private val paint: Paint = Paint()

    /**
     * Paint object for drawing debug stuff
     */
    private val debugPaint: Paint = Paint()

    /**
     * Policy for fitting pages to screen
     */
    var pageFitPolicy: FitPolicy = FitPolicy.WIDTH

    var fitEachPage = false

    private var defaultPage = 0

    /**
     * True if should scroll through pages vertically instead of horizontally
     */
    var swipeVertical = true

    var swipeEnabled = true

    var doubleTapEnabled = true

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

    var pageSnap = true

    /**
     * Pdfium core for loading and rendering PDFs
     */
    private val pdfiumCore: PdfiumCore

    var scrollHandle: ScrollHandle? = null

    private var isScrollHandleInit = false

    /**
     * By default, generated bitmaps are compressed with [Bitmap.Config.RGB_565] format to reduce memory consumption.
     * If [.useBestQuality] is true, rendering will be done with [Bitmap.Config.ARGB_8888].
     */
    var bestQuality = false

    /**
     * Thumbnail ratio (subpart of the PDF)
     * Between 0 and 1 where 1 is the best quality possible but it'll take more memory to render the PDF
     * Throw an exception if the value is 0
     */
    var thumbnailRatio = Constants.THUMBNAIL_RATIO
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

    /**
     * True if annotations should be rendered
     * False otherwise
     */
    var annotationRenderingEnabled = false

    /**
     * True if the view should render during scaling<br></br>
     * Can not be forced on older API versions (< Build.VERSION_CODES.KITKAT) as the GestureDetector does
     * not detect scrolling while scaling.<br></br>
     * False otherwise
     */
    var renderDuringScaleEnabled = false

    /**
     * Antialiasing and bitmap filtering
     */
    var antialiasingEnabled = true
    private val antialiasFilter = PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * Spacing between pages, in px
     */
    var pageSeparatorSpacing = 0
        private set

    /**
     * Start spacing, in px
     */
    var startSpacing = 0
        private set

    /**
     * End spacing, in px
     */
    var endSpacing = 0
        private set

    /**
     * Add dynamic spacing to fit each page separately on the screen.
     */
    var autoSpacing = false

    /**
     * Fling a single page at a time
     */
    var pageFlingEnabled = true

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
        debugPaint.style = Paint.Style.STROKE
        pdfiumCore = PdfiumCore(context)
        setWillNotDraw(false)
    }

    fun getPagesAsBitmaps(): List<Bitmap?> = cacheManager.getThumbnails().map { it.renderedBitmap }

    private fun load(docSource: DocumentSource, password: String?, userPages: IntArray? = null) {
        check(recycled) { "Don't call load on a PDF View without recycling it first." }
        recycled = false
        decodingAsyncTask = DecodingAsyncTask(this, docSource, password, userPages, pdfiumCore).apply {
            executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        }
    }

    override fun isShown(): Boolean = state == State.SHOWN

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
        if (renderingHandler != null) {
            renderingHandler!!.stop()
            renderingHandler!!.removeMessages(RenderingHandler.MSG_RENDER_TASK)
        }
        if (decodingAsyncTask != null) {
            decodingAsyncTask!!.cancel(true)
        }

        // Clear caches
        cacheManager.recycle()

        if (scrollHandle != null && isScrollHandleInit) {
            scrollHandle!!.destroyLayout()
        }

        if (pdfFile != null) {
            pdfFile!!.dispose()
            pdfFile = null
        }

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

        if (Constants.DEBUG_MODE) {
            debugPaint.setColor(if (part.page % 2 == 0) Color.RED else Color.BLUE)
            canvas.drawRect(dstRect, debugPaint)
        }

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
            scrollHandle!!.setupLayout(this)
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
        onErrorListener?.onError(t)
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

        if (moveHandle && scrollHandle != null && !documentFitsView()) {
            scrollHandle!!.setScroll(positionOffset)
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

    inner class Configurator(private val documentSource: DocumentSource) {

        private var pageNumbers: IntArray? = null

        private var enableSwipe = true

        private var enableDoubletap = true

        private var onDrawListener: OnDrawListener? = null

        private var onDrawAllListener: OnDrawListener? = null

        private var onBitmapsReadyListener: OnBitmapsReadyListener? = null
        private var onLoadCompleteListener: OnLoadCompleteListener? = null
        private var onAttachCompleteListener: OnAttachCompleteListener? = null
        private var onDetachCompleteListener: OnDetachCompleteListener? = null

        private var onErrorListener: OnErrorListener? = null

        private var onPageChangeListener: OnPageChangeListener? = null

        private var onPageScrollListener: OnPageScrollListener? = null

        private var onRenderListener: OnRenderListener? = null

        private var onTapListener: OnTapListener? = null

        private var onLongPressListener: OnLongPressListener? = null

        private var onPageErrorListener: OnPageErrorListener? = null

        private var linkHandler: LinkHandler? = DefaultLinkHandler(this@PDFView)

        private var defaultPage = 0

        private var swipeHorizontal = false

        private var annotationRendering = false

        private var password: String? = null

        private var scrollHandle: ScrollHandle? = null

        private var antialiasing = true

        private var pageSeparatorSpacing = 0
        private var startSpacing = 0
        private var endSpacing = 0
        private var minZoom = DEFAULT_MIN_SCALE
        private var midZoom = DEFAULT_MID_SCALE
        private var maxZoom = DEFAULT_MAX_SCALE

        private var autoSpacing = false

        private var pageFitPolicy: FitPolicy = FitPolicy.WIDTH

        private var fitEachPage = false

        private var pageFling = false

        private var pageSnap = false

        private var nightMode = false
        private var touchPriority = false
        private var useBestQuality = false
        private var thumbnailRatio = Constants.THUMBNAIL_RATIO
        private var horizontalBorder = 0
        private var verticalBorder = 0

        fun pages(vararg pageNumbers: Int): Configurator {
            this.pageNumbers = pageNumbers
            return this
        }

        fun enableSwipe(enableSwipe: Boolean): Configurator {
            this.enableSwipe = enableSwipe
            return this
        }

        fun enableDoubletap(enableDoubletap: Boolean): Configurator {
            this.enableDoubletap = enableDoubletap
            return this
        }

        fun enableAnnotationRendering(annotationRendering: Boolean): Configurator {
            this.annotationRendering = annotationRendering
            return this
        }

        fun onDraw(onDrawListener: OnDrawListener?): Configurator {
            this.onDrawListener = onDrawListener
            return this
        }

        fun onDrawAll(onDrawAllListener: OnDrawListener?): Configurator {
            this.onDrawAllListener = onDrawAllListener
            return this
        }

        fun onReadyForPrinting(onReadyForPrintingListener: OnBitmapsReadyListener?): Configurator {
            this.onBitmapsReadyListener = onReadyForPrintingListener
            return this
        }

        fun onLoad(onLoadCompleteListener: OnLoadCompleteListener?): Configurator {
            this.onLoadCompleteListener = onLoadCompleteListener
            return this
        }

        fun onAttach(onAttachCompleteListener: OnAttachCompleteListener?): Configurator {
            this.onAttachCompleteListener = onAttachCompleteListener
            return this
        }

        fun onDetach(onDetachCompleteListener: OnDetachCompleteListener?): Configurator {
            this.onDetachCompleteListener = onDetachCompleteListener
            return this
        }

        fun onPageScroll(onPageScrollListener: OnPageScrollListener?): Configurator {
            this.onPageScrollListener = onPageScrollListener
            return this
        }

        fun onError(onErrorListener: OnErrorListener?): Configurator {
            this.onErrorListener = onErrorListener
            return this
        }

        fun onPageError(onPageErrorListener: OnPageErrorListener?): Configurator {
            this.onPageErrorListener = onPageErrorListener
            return this
        }

        fun onPageChange(onPageChangeListener: OnPageChangeListener?): Configurator {
            this.onPageChangeListener = onPageChangeListener
            return this
        }

        fun onRender(onRenderListener: OnRenderListener?): Configurator {
            this.onRenderListener = onRenderListener
            return this
        }

        fun onTap(onTapListener: OnTapListener?): Configurator {
            this.onTapListener = onTapListener
            return this
        }

        fun onLongPress(onLongPressListener: OnLongPressListener?): Configurator {
            this.onLongPressListener = onLongPressListener
            return this
        }

        fun linkHandler(linkHandler: LinkHandler?): Configurator {
            this.linkHandler = linkHandler
            return this
        }

        fun defaultPage(defaultPage: Int): Configurator {
            this.defaultPage = defaultPage
            return this
        }

        fun swipeHorizontal(swipeHorizontal: Boolean): Configurator {
            this.swipeHorizontal = swipeHorizontal
            return this
        }

        fun password(password: String?): Configurator {
            this.password = password
            return this
        }

        fun scrollHandle(scrollHandle: ScrollHandle?): Configurator {
            this.scrollHandle = scrollHandle
            return this
        }

        fun enableAntialiasing(antialiasing: Boolean): Configurator {
            this.antialiasing = antialiasing
            return this
        }

        fun pageSeparatorSpacing(pageSeparatorSpacing: Int): Configurator {
            this.pageSeparatorSpacing = pageSeparatorSpacing
            return this
        }

        fun startEndSpacing(startSpacing: Int, endSpacing: Int): Configurator {
            this.startSpacing = startSpacing
            this.endSpacing = endSpacing
            return this
        }

        fun zoom(minZoom: Float, midZoom: Float, maxZoom: Float): Configurator {
            this.minZoom = minZoom
            this.midZoom = midZoom
            this.maxZoom = maxZoom
            return this
        }

        fun autoSpacing(autoSpacing: Boolean): Configurator {
            this.autoSpacing = autoSpacing
            return this
        }

        fun pageFitPolicy(pageFitPolicy: FitPolicy): Configurator {
            this.pageFitPolicy = pageFitPolicy
            return this
        }

        fun fitEachPage(fitEachPage: Boolean): Configurator {
            this.fitEachPage = fitEachPage
            return this
        }

        fun pageSnap(pageSnap: Boolean): Configurator {
            this.pageSnap = pageSnap
            return this
        }

        fun pageFling(pageFling: Boolean): Configurator {
            this.pageFling = pageFling
            return this
        }

        fun nightMode(nightMode: Boolean): Configurator {
            this.nightMode = nightMode
            return this
        }

        fun disableLongPress(): Configurator {
            this@PDFView.dragPinchManager.disableLongPress()
            return this
        }

        fun touchPriority(hasPriority: Boolean): Configurator {
            this.touchPriority = hasPriority
            return this
        }

        /**
         * By default, generated bitmaps are compressed with [Bitmap.Config.RGB_565] format to reduce memory consumption.
         * If [.useBestQuality] is true, rendering will be done with [Bitmap.Config.ARGB_8888].
         *
         * @param useBestQuality true to use [Bitmap.Config.ARGB_8888], false for [Bitmap.Config.RGB_565]
         */
        fun useBestQuality(useBestQuality: Boolean): Configurator {
            this.useBestQuality = useBestQuality
            return this
        }

        fun thumbnailRatio(@FloatRange(from = 0.1, to = 1.0) thumbnailRatio: Float): Configurator {
            this.thumbnailRatio = thumbnailRatio
            return this
        }

        fun horizontalBorder(horizontalBorder: Int): Configurator {
            this.horizontalBorder = horizontalBorder
            return this
        }

        fun verticalBorder(verticalBorder: Int): Configurator {
            this.verticalBorder = verticalBorder
            return this
        }

        fun load() {
            if (!hasSize) {
                waitingDocumentConfigurator = this
                return
            }
            this@PDFView.recycle()
            this@PDFView.onBitmapsReadyListener = onBitmapsReadyListener
            this@PDFView.onLoadCompleteListener = onLoadCompleteListener
            this@PDFView.onAttachCompleteListener = onAttachCompleteListener
            this@PDFView.onDetachCompleteListener = onDetachCompleteListener
            this@PDFView.onErrorListener = onErrorListener
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
            this@PDFView.swipeEnabled = enableSwipe
            this@PDFView.nightMode = nightMode
            this@PDFView.doubleTapEnabled = enableDoubletap
            this@PDFView.defaultPage = defaultPage
            this@PDFView.swipeVertical = !swipeHorizontal
            this@PDFView.annotationRenderingEnabled = annotationRendering
            this@PDFView.scrollHandle = scrollHandle
            this@PDFView.antialiasingEnabled = antialiasing
            this@PDFView.autoSpacing = autoSpacing
            this@PDFView.pageFitPolicy = pageFitPolicy
            this@PDFView.fitEachPage = fitEachPage
            this@PDFView.pageSnap = pageSnap
            this@PDFView.pageFlingEnabled = pageFling
            this@PDFView.minZoom = minZoom
            this@PDFView.midZoom = midZoom
            this@PDFView.maxZoom = maxZoom
            this@PDFView.bestQuality = useBestQuality
            this@PDFView.thumbnailRatio = thumbnailRatio
            this@PDFView.horizontalBorder = horizontalBorder
            this@PDFView.verticalBorder = verticalBorder
            this@PDFView.pageSeparatorSpacing = getDP(context, pageSeparatorSpacing)
            this@PDFView.startSpacing = getDP(context, startSpacing)
            this@PDFView.endSpacing = getDP(context, endSpacing)

            if (pageNumbers != null) {
                this@PDFView.load(documentSource, password, pageNumbers)
            } else {
                this@PDFView.load(documentSource, password)
            }
        }
    }

    companion object {
        private val TAG: String = PDFView::class.java.getSimpleName()

        const val DEFAULT_MAX_SCALE: Float = 2f //3f;
        const val DEFAULT_MID_SCALE: Float = 2f
        const val DEFAULT_MIN_SCALE: Float = 1f
    }
}
