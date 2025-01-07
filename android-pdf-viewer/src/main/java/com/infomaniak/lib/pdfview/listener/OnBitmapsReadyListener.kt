
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

package com.infomaniak.lib.pdfview.listener;

import android.graphics.Bitmap;

import java.util.List;

/**
 * Implement this interface to receive events from PDFView
 * when bitmaps has been generated. Used to print password protected PDF.
 */
public interface OnReadyForPrintingListener {

    /**
     * Called when bitmaps has been generated
     *
     * @param bitmaps pages of the PDF as bitmaps
     */
    void bitmapsReady(List<Bitmap> bitmaps);
}
