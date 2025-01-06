package com.infomaniak.lib.pdfview.link

import com.infomaniak.lib.pdfview.model.LinkTapEvent

interface LinkHandler {
    /**
     * Called when link was tapped by user
     *
     * @param event current event
     */
    fun handleLinkEvent(event: LinkTapEvent)
}
