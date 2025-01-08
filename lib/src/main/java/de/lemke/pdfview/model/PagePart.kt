package de.lemke.pdfview.model

import android.graphics.Bitmap
import android.graphics.RectF

data class PagePart(
    val page: Int,
    val renderedBitmap: Bitmap?,
    val pageRelativeBounds: RectF,
    val isThumbnail: Boolean,
    var cacheOrder: Int
) {
    override fun equals(obj: Any?): Boolean {
        if (obj !is PagePart) {
            return false
        }

        return obj.page == page &&
                obj.pageRelativeBounds.left == pageRelativeBounds.left
                && obj.pageRelativeBounds.right == pageRelativeBounds.right
                && obj.pageRelativeBounds.top == pageRelativeBounds.top
                && obj.pageRelativeBounds.bottom == pageRelativeBounds.bottom
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + page
        result = 31 * result + (renderedBitmap?.hashCode() ?: 0)
        result = 31 * result + pageRelativeBounds.hashCode()
        result = 31 * result + isThumbnail.hashCode()
        result = 31 * result + cacheOrder
        return result
    }
}
