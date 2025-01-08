package de.lemke.pdfview

import android.graphics.PointF
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ScaleGestureDetector.OnScaleGestureListener
import android.view.View
import android.view.View.OnTouchListener
import de.lemke.pdfview.model.LinkTapEvent
import de.lemke.pdfview.util.TouchUtils
import de.lemke.pdfview.util.TouchUtils.Companion.handleTouchPriority
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * This Manager takes care of moving the PDFView,
 * set its zoom track user actions.
 */
internal class DragPinchManager(
    private val pdfView: PDFView,
    private val animationManager: AnimationManager
) : GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener,
    OnScaleGestureListener,
    OnTouchListener {
    private val gestureDetector: GestureDetector = GestureDetector(pdfView.context, this)
    private val scaleGestureDetector: ScaleGestureDetector = ScaleGestureDetector(pdfView.context, this)

    private var scrolling = false
    private var scaling = false
    var enabled = false
    var hasTouchPriority = false
    private var startingScrollingXPosition = STARTING_TOUCH_POSITION_NOT_INITIALIZED
    private var startingTouchXPosition = STARTING_TOUCH_POSITION_NOT_INITIALIZED
    private var startingTouchYPosition = STARTING_TOUCH_POSITION_NOT_INITIALIZED

    init {
        pdfView.setOnTouchListener(this)
    }

    @Suppress("UsePropertyAccessSyntax")
    fun disableLongPress() {
        gestureDetector.setIsLongpressEnabled(false)
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        val onTapHandled = pdfView.onTapListener?.onTap(e) == true
        val linkTapped = checkLinkTapped(e.x, e.y)
        if (!onTapHandled && !linkTapped) {
            val ps = pdfView.scrollHandle
            if (ps != null && !pdfView.documentFitsView()) {
                if (!ps.shown()) {
                    ps.show()
                } else {
                    ps.hide()
                }
            }
        }
        pdfView.performClick()
        return true
    }

    private fun checkLinkTapped(x: Float, y: Float): Boolean {
        val pdfFile = pdfView.pdfFile
        if (pdfFile == null) {
            return false
        }
        val mappedX = -pdfView.currentXOffset + x
        val mappedY = -pdfView.currentYOffset + y
        val page = pdfFile.getPageAtOffset(if (pdfView.swipeVertical) mappedY else mappedX, pdfView.zoom)
        val pageSize = pdfFile.getScaledPageSize(page, pdfView.zoom)
        var pageX: Int
        var pageY: Int
        if (pdfView.swipeVertical) {
            pageX = pdfFile.getSecondaryPageOffset(page, pdfView.zoom).toInt()
            pageY = pdfFile.getPageOffset(page, pdfView.zoom).toInt()
        } else {
            pageY = pdfFile.getSecondaryPageOffset(page, pdfView.zoom).toInt()
            pageX = pdfFile.getPageOffset(page, pdfView.zoom).toInt()
        }
        for (link in pdfFile.getPageLinks(page)) {
            val mapped = pdfFile.mapRectToDevice(
                page, pageX, pageY, pageSize.width.toInt(), pageSize.height.toInt(),
                link.bounds
            )
            mapped.sort()
            if (mapped.contains(mappedX, mappedY)) {
                pdfView.linkHandler?.handleLinkEvent(LinkTapEvent(x, y, mappedX, mappedY, mapped, link))
                return true
            }
        }
        return false
    }

    private fun startPageFling(
        downEvent: MotionEvent, ev: MotionEvent, velocityX: Float,
        velocityY: Float
    ) {
        if (!checkDoPageFling(velocityX, velocityY)) {
            return
        }
        var direction: Int = if (pdfView.swipeVertical) {
            if (velocityY > 0) -1 else 1
        } else {
            if (velocityX > 0) -1 else 1
        }
        // Get the focused page during the down event to ensure only a single page is changed
        val delta = if (pdfView.swipeVertical) ev.y - downEvent.y else ev.x - downEvent.x
        val offsetX = pdfView.currentXOffset - delta * pdfView.zoom
        val offsetY = pdfView.currentYOffset - delta * pdfView.zoom
        val startingPage = pdfView.findFocusPage(offsetX, offsetY)
        val targetPage: Int = max(0, min((pdfView.getPageCount() - 1), (startingPage + direction)))

        val edge = pdfView.findSnapEdge(targetPage)
        val offset = pdfView.snapOffsetForPage(targetPage, edge)
        animationManager.startPageFlingAnimation(-offset)
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        if (!pdfView.doubleTapEnabled) {
            return false
        }

        if (pdfView.zoom < pdfView.minZoom) {
            pdfView.resetZoomWithAnimation()
        } else if (pdfView.zoom < pdfView.midZoom) {
            pdfView.zoomWithAnimation(e.x, e.y, pdfView.midZoom)
        } else if (pdfView.zoom < pdfView.maxZoom) {
            pdfView.zoomWithAnimation(e.x, e.y, pdfView.maxZoom)
        } else {
            pdfView.resetZoomWithAnimation()
        }
        return true
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false

    override fun onDown(e: MotionEvent): Boolean {
        animationManager.stopFling()
        return true
    }

    override fun onShowPress(e: MotionEvent) {
        // Nothing to do here since we don't want to show anything when the user press the view
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean = false

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        scrolling = true
        if (pdfView.isZooming || pdfView.swipeEnabled) {
            pdfView.moveRelativeTo(-distanceX, -distanceY)
        }
        if (!scaling || pdfView.renderDuringScaleEnabled) {
            pdfView.loadPageByOffset()
        }
        return true
    }

    private fun onScrollEnd() {
        pdfView.loadPages()
        hideHandle()
        if (!animationManager.isFlinging()) {
            pdfView.performPageSnap()
        }
    }

    override fun onLongPress(e: MotionEvent) {
        pdfView.onLongPressListener?.onLongPress(e)
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        if (!pdfView.swipeEnabled) {
            return false
        }
        if (e1 == null) {
            return false
        }
        if (pdfView.pageFlingEnabled) {
            if (pdfView.pageFillsScreen()) {
                onBoundedFling(velocityX, velocityY)
            } else {
                startPageFling(e1, e2, velocityX, velocityY)
            }
            return true
        }

        val xOffset = pdfView.currentXOffset.toInt()
        val yOffset = pdfView.currentYOffset.toInt()

        var minX: Float
        var minY: Float
        pdfView.pdfFile?.apply {
            if (pdfView.swipeVertical) {
                minX = -(pdfView.toCurrentScale(maxPageWidth) - pdfView.width)
                minY = -(getDocLen(pdfView.zoom) - pdfView.height)
            } else {
                minX = -(getDocLen(pdfView.zoom) - pdfView.width)
                minY = -(pdfView.toCurrentScale(maxPageHeight) - pdfView.height)
            }
            animationManager.startFlingAnimation(
                xOffset, yOffset, (velocityX).toInt(), (velocityY).toInt(), minX.toInt(), 0, minY.toInt(),
                0
            )
        }
        return true
    }

    private fun onBoundedFling(velocityX: Float, velocityY: Float) {
        val xOffset = pdfView.currentXOffset.toInt()
        val yOffset = pdfView.currentYOffset.toInt()

        val pdfFile = pdfView.pdfFile
        if (pdfFile == null) {
            Log.e("DragPinchManager", "onBoundedFling: pdfView.pdfFile is null")
            return
        }

        val pageStart = -pdfFile.getPageOffset(pdfView.currentPage, pdfView.zoom)
        val pageEnd = pageStart - pdfFile.getPageLength(pdfView.currentPage, pdfView.zoom)
        var minX: Float
        var minY: Float
        var maxX: Float
        var maxY: Float
        if (pdfView.swipeVertical) {
            minX = -(pdfView.toCurrentScale(pdfFile.maxPageWidth) - pdfView.width)
            minY = pageEnd + pdfView.height
            maxX = 0f
            maxY = pageStart
        } else {
            minX = pageEnd + pdfView.width
            minY = -(pdfView.toCurrentScale(pdfFile.maxPageHeight) - pdfView.height)
            maxX = pageStart
            maxY = 0f
        }

        animationManager.startFlingAnimation(
            xOffset, yOffset, (velocityX).toInt(), (velocityY).toInt(), minX.toInt(), maxX.toInt(),
            minY.toInt(), maxY.toInt()
        )
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        var dr = detector.getScaleFactor()
        val wantedZoom = pdfView.zoom * dr
        val minZoom: Float = min(MINIMUM_ZOOM, pdfView.minZoom)
        val maxZoom: Float = max(MAXIMUM_ZOOM, pdfView.maxZoom)
        if (wantedZoom < minZoom) {
            dr = minZoom / pdfView.zoom
        } else if (wantedZoom > maxZoom) {
            dr = maxZoom / pdfView.zoom
        }
        pdfView.zoomCenteredRelativeTo(dr, PointF(detector.focusX, detector.focusY))
        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        scaling = true
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
        pdfView.loadPages()
        hideHandle()
        scaling = false
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (!enabled) {
            return false
        }

        var retVal = scaleGestureDetector.onTouchEvent(event)
        retVal = gestureDetector.onTouchEvent(event) || retVal

        if (event.action == MotionEvent.ACTION_MOVE && event.pointerCount >= TOUCH_POINTER_COUNT) {
            startingScrollingXPosition = STARTING_TOUCH_POSITION_NOT_INITIALIZED
            startingTouchXPosition = STARTING_TOUCH_POSITION_NOT_INITIALIZED
            startingTouchYPosition = STARTING_TOUCH_POSITION_NOT_INITIALIZED
        }

        if (event.action == MotionEvent.ACTION_UP && scrolling) {
            startingScrollingXPosition = STARTING_TOUCH_POSITION_NOT_INITIALIZED
            startingTouchXPosition = STARTING_TOUCH_POSITION_NOT_INITIALIZED
            startingTouchYPosition = STARTING_TOUCH_POSITION_NOT_INITIALIZED
            scrolling = false
            onScrollEnd()
        }

        if (hasTouchPriority) {
            handleTouchPriority(
                event,
                v,
                TOUCH_POINTER_COUNT,
                shouldOverrideTouchPriority(v, event),
                pdfView.isZooming
            )
        }

        return retVal
    }

    private fun shouldOverrideTouchPriority(v: View, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            startingScrollingXPosition = event.x
            startingTouchXPosition =
                if (!v.canScrollHorizontally(TouchUtils.Companion.DIRECTION_SCROLLING_LEFT) || !v.canScrollHorizontally(TouchUtils.Companion.DIRECTION_SCROLLING_RIGHT)) {
                    event.x
                } else {
                    STARTING_TOUCH_POSITION_NOT_INITIALIZED
                }
            startingTouchYPosition = event.y
        }

        val scrollDirection = getScrollingDirection(event.x)

        val canScrollLeft = v.canScrollHorizontally(TouchUtils.Companion.DIRECTION_SCROLLING_LEFT)
        val canScrollRight = v.canScrollHorizontally(TouchUtils.Companion.DIRECTION_SCROLLING_RIGHT)
        val canScrollHorizontally = canScrollLeft && canScrollRight
        val isScrollingBlocked =
            (!canScrollRight && scrollDirection == TouchUtils.Companion.DIRECTION_SCROLLING_LEFT)
                    || (!canScrollLeft && scrollDirection == TouchUtils.Companion.DIRECTION_SCROLLING_RIGHT)

        if (event.action == MotionEvent.ACTION_MOVE && canScrollHorizontally) {
            startingTouchXPosition = STARTING_TOUCH_POSITION_NOT_INITIALIZED
        }

        if (!isScrollingBlocked || startingTouchXPosition == STARTING_TOUCH_POSITION_NOT_INITIALIZED) {
            return false
        } else {
            val deltaX: Float = abs((event.x - startingTouchXPosition))
            val deltaY: Float = abs((event.y - startingTouchYPosition))
            return deltaX >= MIN_TRIGGER_DELTA_X_TOUCH_PRIORITY && deltaY < MIN_TRIGGER_DELTA_Y_TOUCH_PRIORITY
        }
    }

    private fun getScrollingDirection(x: Float): Int = if (x > startingScrollingXPosition) {
        TouchUtils.Companion.DIRECTION_SCROLLING_RIGHT
    } else {
        TouchUtils.Companion.DIRECTION_SCROLLING_LEFT
    }

    private fun hideHandle() {
        val scrollHandle = pdfView.scrollHandle
        if (scrollHandle != null && scrollHandle.shown()) {
            scrollHandle.hideDelayed()
        }
    }

    private fun checkDoPageFling(velocityX: Float, velocityY: Float): Boolean {
        val absX: Float = abs(velocityX)
        val absY: Float = abs(velocityY)
        return if (pdfView.swipeVertical) absY > absX else absX > absY
    }

    companion object {
        private const val MIN_TRIGGER_DELTA_X_TOUCH_PRIORITY = 150f
        private const val MIN_TRIGGER_DELTA_Y_TOUCH_PRIORITY = 100f
        private const val STARTING_TOUCH_POSITION_NOT_INITIALIZED = -1f
        private const val TOUCH_POINTER_COUNT = 2
        const val MAXIMUM_ZOOM = 100.0f
        const val MINIMUM_ZOOM = 0.3f
    }
}
