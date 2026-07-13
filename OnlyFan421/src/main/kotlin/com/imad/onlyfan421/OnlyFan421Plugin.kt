package com.imad.onlyfan421

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OnlyFan421Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(OnlyFan421())
    }
}
