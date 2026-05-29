package com.kingbokep

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KingBokepProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KingBokepProvider())
    }
}
