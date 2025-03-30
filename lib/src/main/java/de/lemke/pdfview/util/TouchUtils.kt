package de.lemke.pdfview.util

import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

object TouchUtils {
    const val DIRECTION_LEFT: Int = -1
    const val DIRECTION_RIGHT: Int = 1
    const val DIRECTION_TOP: Int = -1
    const val DIRECTION_BOTTOM: Int = 1

    fun handleTouchPriority(
        event: MotionEvent,
        view: View,
        pointerCount: Int,
        shouldOverrideTouchPriority: Boolean,
        isZooming: Boolean,
    ) {
        val viewToDisableTouch = getViewToDisableTouch(view)
        val canScrollHorizontally = view.canScrollHorizontally(DIRECTION_RIGHT) && view.canScrollHorizontally(DIRECTION_LEFT)
        val canScrollVertically = view.canScrollVertically(DIRECTION_TOP) && view.canScrollVertically(DIRECTION_BOTTOM)
        if (shouldOverrideTouchPriority) {
            viewToDisableTouch?.requestDisallowInterceptTouchEvent(false)
            getViewPager(view)?.requestDisallowInterceptTouchEvent(true)
        } else if (event.pointerCount >= pointerCount || canScrollHorizontally || canScrollVertically) {
            if (event.action == ACTION_UP) {
                viewToDisableTouch?.requestDisallowInterceptTouchEvent(false)
            } else if (event.action == ACTION_DOWN || event.action == ACTION_MOVE || isZooming) {
                viewToDisableTouch?.requestDisallowInterceptTouchEvent(true)
            }
        }
    }

    private fun getViewToDisableTouch(view: View) = generateSequence(view.parent) { it.parent }.find { it is RecyclerView }
    private fun getViewPager(view: View) = generateSequence(view.parent) { it.parent }.find { it is ViewPager2 }
}
