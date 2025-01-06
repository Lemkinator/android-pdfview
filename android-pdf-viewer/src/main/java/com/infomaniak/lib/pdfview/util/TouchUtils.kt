package com.infomaniak.lib.pdfview.util;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.recyclerview.widget.RecyclerView;

public class TouchUtils {

    public static final int DIRECTION_SCROLLING_LEFT = -1;
    public static final int DIRECTION_SCROLLING_RIGHT = 1;
    static final int DIRECTION_SCROLLING_TOP = -1;
    static final int DIRECTION_SCROLLING_BOTTOM = 1;

    private TouchUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static void handleTouchPriority(MotionEvent event,
                                           View view,
                                           int pointerCount,
                                           boolean shouldOverrideTouchPriority,
                                           boolean isZooming) {
        ViewParent viewToDisableTouch = getViewToDisableTouch(view);

        if (viewToDisableTouch == null) {
            return;
        }

        boolean canScrollHorizontally =
                view.canScrollHorizontally(DIRECTION_SCROLLING_RIGHT) && view.canScrollHorizontally(DIRECTION_SCROLLING_LEFT);
        boolean canScrollVertically =
                view.canScrollVertically(DIRECTION_SCROLLING_TOP) && view.canScrollVertically(DIRECTION_SCROLLING_BOTTOM);
        if (shouldOverrideTouchPriority) {
            viewToDisableTouch.requestDisallowInterceptTouchEvent(false);
        } else if (event.getPointerCount() >= pointerCount || canScrollHorizontally || canScrollVertically) {
            int action = event.getAction();

            if (action == MotionEvent.ACTION_UP) {
                viewToDisableTouch.requestDisallowInterceptTouchEvent(false);
            } else if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || isZooming) {
                viewToDisableTouch.requestDisallowInterceptTouchEvent(true);
            }
        }
    }

    private static ViewParent getViewToDisableTouch(View startingView) {
        ViewParent parentView = startingView.getParent();
        while (parentView != null && !(parentView instanceof RecyclerView)) {
            parentView = parentView.getParent();
        }
        return parentView;
    }
}
