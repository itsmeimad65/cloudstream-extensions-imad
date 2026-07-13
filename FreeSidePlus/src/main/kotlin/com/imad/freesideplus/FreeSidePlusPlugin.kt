package com.imad.freesideplus

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FreeSidePlusPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FreeSidePlus())
        registerExtractorAPI(FreeSidePlusExtractor())
    }
}
