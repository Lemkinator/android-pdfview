package com.infomaniak.lib.pdfview.util

object Constants {

    const val DEBUG_MODE = false

    /**
     * Between 0 and 1, the thumbnails quality (default 0.3). Increasing this value may cause performances decrease.
     */
    const val THUMBNAIL_RATIO = 0.3f

    /**
     * Minimum quality for printing.
     */
    const val THUMBNAIL_RATIO_PRINTING = 0.75f

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

    object Cache {
        /**
         * The size of the cache (number of bitmaps kept).
         */
        const val CACHE_SIZE = 120
        const val THUMBNAILS_CACHE_SIZE = 8
    }

    object Pinch {
        const val MAXIMUM_ZOOM = 100.0f
        const val MINIMUM_ZOOM = 0.3f
    }
}
