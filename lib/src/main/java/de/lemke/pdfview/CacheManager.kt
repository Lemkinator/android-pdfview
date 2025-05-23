package de.lemke.pdfview

import android.graphics.RectF
import de.lemke.pdfview.model.PagePart
import java.util.PriorityQueue

class CacheManager {
    private val passiveCache: PriorityQueue<PagePart>

    private val activeCache: PriorityQueue<PagePart>

    private val thumbnails: MutableList<PagePart>

    private val passiveActiveLock = Any()

    init {
        val orderComparator = PagePartComparator()
        activeCache = PriorityQueue<PagePart>(CACHE_SIZE, orderComparator)
        passiveCache = PriorityQueue<PagePart>(CACHE_SIZE, orderComparator)
        thumbnails = ArrayList<PagePart>()
    }

    fun cachePart(part: PagePart?) {
        synchronized(passiveActiveLock) {
            // If cache too big, remove and recycle
            makeAFreeSpace()

            // Then add part
            activeCache.offer(part)
        }
    }

    fun makeANewSet() {
        synchronized(passiveActiveLock) {
            passiveCache.addAll(activeCache)
            activeCache.clear()
        }
    }

    private fun makeAFreeSpace() {
        synchronized(passiveActiveLock) {
            while ((activeCache.size + passiveCache.size) >= CACHE_SIZE && !passiveCache.isEmpty()) {
                recycleBitmapsFromPart(passiveCache)
            }
            while ((activeCache.size + passiveCache.size) >= CACHE_SIZE && !activeCache.isEmpty()) {
                recycleBitmapsFromPart(activeCache)
            }
        }
    }

    fun cacheThumbnail(part: PagePart?, isForPrinting: Boolean) {
        synchronized(thumbnails) {
            // If cache too big, remove and recycle. But if we're printing, we don't want any limit.
            while (!isForPrinting && thumbnails.size >= THUMBNAILS_CACHE_SIZE) {
                thumbnails.removeAt(0).renderedBitmap?.recycle()
            }

            // Then add thumbnail
            addWithoutDuplicates(thumbnails, part)
        }
    }

    fun upPartIfContained(page: Int, pageRelativeBounds: RectF, toOrder: Int): Boolean {
        val fakePart = PagePart(page, null, pageRelativeBounds, false, 0)

        var found: PagePart?
        synchronized(passiveActiveLock) {
            if ((find(passiveCache, fakePart).also { found = it }) != null) {
                passiveCache.remove(found)
                found!!.cacheOrder = toOrder
                activeCache.offer(found)
                return true
            }
            return find(activeCache, fakePart) != null
        }
    }

    /**
     * Return true if already contains the described PagePart
     */
    fun containsThumbnail(page: Int, pageRelativeBounds: RectF): Boolean {
        val fakePart = PagePart(page, null, pageRelativeBounds, true, 0)
        synchronized(thumbnails) {
            for (part in thumbnails) {
                if (part == fakePart) {
                    return true
                }
            }
            return false
        }
    }

    private fun recycleBitmapsFromPart(cache: PriorityQueue<PagePart>) {
        val part = cache.poll()
        part?.renderedBitmap?.recycle()
    }

    /**
     * Add part if it doesn't exist, recycle bitmap otherwise
     */
    private fun addWithoutDuplicates(collection: MutableCollection<PagePart>, newPart: PagePart?) {
        for (part in collection) {
            if (part == newPart) {
                newPart.renderedBitmap?.recycle()
                return
            }
        }
        collection.add(newPart!!)
    }

    fun getPageParts(): MutableList<PagePart?> {
        synchronized(passiveActiveLock) {
            val parts: MutableList<PagePart?> = ArrayList<PagePart?>(passiveCache)
            parts.addAll(activeCache)
            return parts
        }
    }

    fun getThumbnails(): MutableList<PagePart> {
        synchronized(thumbnails) {
            return thumbnails
        }
    }

    fun recycle() {
        synchronized(passiveActiveLock) {
            for (part in passiveCache) {
                part.renderedBitmap?.recycle()
            }
            passiveCache.clear()
            for (part in activeCache) {
                part.renderedBitmap?.recycle()
            }
            activeCache.clear()
        }
        synchronized(thumbnails) {
            for (part in thumbnails) {
                part.renderedBitmap?.recycle()
            }
            thumbnails.clear()
        }
    }

    internal class PagePartComparator : Comparator<PagePart> {
        override fun compare(part1: PagePart, part2: PagePart): Int {
            if (part1.cacheOrder == part2.cacheOrder) {
                return 0
            }
            return if (part1.cacheOrder > part2.cacheOrder) 1 else -1
        }
    }

    companion object {
        private fun find(vector: PriorityQueue<PagePart>, fakePart: PagePart?): PagePart? {
            for (part in vector) {
                if (part == fakePart) {
                    return part
                }
            }
            return null
        }


        /**
         * The size of the cache (number of bitmaps kept).
         */
        const val CACHE_SIZE = 120
        const val THUMBNAILS_CACHE_SIZE = 8
    }
}
