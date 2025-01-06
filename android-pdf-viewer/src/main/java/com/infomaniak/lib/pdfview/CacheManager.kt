/*
 * Infomaniak android-pdf-viewer
 * Copyright (C) 2024 Infomaniak Network SA
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.infomaniak.lib.pdfview;

import static com.infomaniak.lib.pdfview.util.Constants.Cache.CACHE_SIZE;
import static com.infomaniak.lib.pdfview.util.Constants.Cache.THUMBNAILS_CACHE_SIZE;

import android.graphics.RectF;

import androidx.annotation.Nullable;

import com.infomaniak.lib.pdfview.model.PagePart;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

class CacheManager {

    private final PriorityQueue<PagePart> passiveCache;

    private final PriorityQueue<PagePart> activeCache;

    private final List<PagePart> thumbnails;

    private final Object passiveActiveLock = new Object();

    private final PagePartComparator orderComparator = new PagePartComparator();

    public CacheManager() {
        activeCache = new PriorityQueue<>(CACHE_SIZE, orderComparator);
        passiveCache = new PriorityQueue<>(CACHE_SIZE, orderComparator);
        thumbnails = new ArrayList<>();
    }

    @Nullable
    private static PagePart find(PriorityQueue<PagePart> vector, PagePart fakePart) {
        for (PagePart part : vector) {
            if (part.equals(fakePart)) {
                return part;
            }
        }
        return null;
    }

    public void cachePart(PagePart part) {
        synchronized (passiveActiveLock) {
            // If cache too big, remove and recycle
            makeAFreeSpace();

            // Then add part
            activeCache.offer(part);
        }
    }

    public void makeANewSet() {
        synchronized (passiveActiveLock) {
            passiveCache.addAll(activeCache);
            activeCache.clear();
        }
    }

    private void makeAFreeSpace() {
        synchronized (passiveActiveLock) {
            while ((activeCache.size() + passiveCache.size()) >= CACHE_SIZE && !passiveCache.isEmpty()) {
                recycleBitmapsFromPart(passiveCache);
            }

            while ((activeCache.size() + passiveCache.size()) >= CACHE_SIZE && !activeCache.isEmpty()) {
                recycleBitmapsFromPart(activeCache);
            }
        }
    }

    public void cacheThumbnail(PagePart part, boolean isForPrinting) {
        synchronized (thumbnails) {
            // If cache too big, remove and recycle. But if we're printing, we don't want any limit.
            while (!isForPrinting && thumbnails.size() >= THUMBNAILS_CACHE_SIZE) {
                thumbnails.remove(0).getRenderedBitmap().recycle();
            }

            // Then add thumbnail
            addWithoutDuplicates(thumbnails, part);
        }
    }

    public boolean upPartIfContained(int page, RectF pageRelativeBounds, int toOrder) {
        PagePart fakePart = new PagePart(page, null, pageRelativeBounds, false, 0);

        PagePart found;
        synchronized (passiveActiveLock) {
            if ((found = find(passiveCache, fakePart)) != null) {
                passiveCache.remove(found);
                found.setCacheOrder(toOrder);
                activeCache.offer(found);
                return true;
            }

            return find(activeCache, fakePart) != null;
        }
    }

    /**
     * Return true if already contains the described PagePart
     */
    public boolean containsThumbnail(int page, RectF pageRelativeBounds) {
        PagePart fakePart = new PagePart(page, null, pageRelativeBounds, true, 0);
        synchronized (thumbnails) {
            for (PagePart part : thumbnails) {
                if (part.equals(fakePart)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void recycleBitmapsFromPart(PriorityQueue<PagePart> cache) {
        PagePart part = cache.poll();
        if (part != null) {
            part.getRenderedBitmap().recycle();
        }
    }

    /**
     * Add part if it doesn't exist, recycle bitmap otherwise
     */
    private void addWithoutDuplicates(Collection<PagePart> collection, PagePart newPart) {
        for (PagePart part : collection) {
            if (part.equals(newPart)) {
                newPart.getRenderedBitmap().recycle();
                return;
            }
        }
        collection.add(newPart);
    }

    public List<PagePart> getPageParts() {
        synchronized (passiveActiveLock) {
            List<PagePart> parts = new ArrayList<>(passiveCache);
            parts.addAll(activeCache);
            return parts;
        }
    }

    public List<PagePart> getThumbnails() {
        synchronized (thumbnails) {
            return thumbnails;
        }
    }

    public void recycle() {
        synchronized (passiveActiveLock) {
            for (PagePart part : passiveCache) {
                part.getRenderedBitmap().recycle();
            }
            passiveCache.clear();
            for (PagePart part : activeCache) {
                part.getRenderedBitmap().recycle();
            }
            activeCache.clear();
        }
        synchronized (thumbnails) {
            for (PagePart part : thumbnails) {
                part.getRenderedBitmap().recycle();
            }
            thumbnails.clear();
        }
    }

    class PagePartComparator implements Comparator<PagePart> {
        @Override
        public int compare(PagePart part1, PagePart part2) {
            if (part1.getCacheOrder() == part2.getCacheOrder()) {
                return 0;
            }
            return part1.getCacheOrder() > part2.getCacheOrder() ? 1 : -1;
        }
    }
}
