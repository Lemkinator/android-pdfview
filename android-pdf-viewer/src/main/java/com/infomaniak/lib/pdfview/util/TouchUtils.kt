package com.infomaniak.lib.pdfview.util

import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import androidx.recyclerview.widget.RecyclerView
import java.lang.IllegalStateException

class TouchUtils private constructor() {
    init {
        throw IllegalStateException("Utility class")
    }

    companion object {
        const val DIRECTION_SCROLLING_LEFT: Int = -1
        const val DIRECTION_SCROLLING_RIGHT: Int = 1
        const val DIRECTION_SCROLLING_TOP: Int = -1
        const val DIRECTION_SCROLLING_BOTTOM: Int = 1

        @JvmStatic
        fun handleTouchPriority(
            event: MotionEvent,
            view: View,
            pointerCount: Int,
            shouldOverrideTouchPriority: Boolean,
            isZooming: Boolean
        ) {
            val viewToDisableTouch = getViewToDisableTouch(view)

            if (viewToDisableTouch == null) {
                return
            }

            val canScrollHorizontally =
                view.canScrollHorizontally(DIRECTION_SCROLLING_RIGHT) && view.canScrollHorizontally(DIRECTION_SCROLLING_LEFT)
            val canScrollVertically =
                view.canScrollVertically(DIRECTION_SCROLLING_TOP) && view.canScrollVertically(DIRECTION_SCROLLING_BOTTOM)
            if (shouldOverrideTouchPriority) {
                viewToDisableTouch.requestDisallowInterceptTouchEvent(false)
            } else if (event.pointerCount >= pointerCount || canScrollHorizontally || canScrollVertically) {
                val action = event.action

                if (action == MotionEvent.ACTION_UP) {
                    viewToDisableTouch.requestDisallowInterceptTouchEvent(false)
                } else if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || isZooming) {
                    viewToDisableTouch.requestDisallowInterceptTouchEvent(true)
                }
            }
        }

        private fun getViewToDisableTouch(startingView: View): ViewParent? {
            var parentView = startingView.parent
            while (parentView != null && parentView !is RecyclerView) {
                parentView = parentView.parent
            }
            return parentView
        }
    }
}
