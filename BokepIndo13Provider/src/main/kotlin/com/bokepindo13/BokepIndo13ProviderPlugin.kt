package com.bokepindo13

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BokepIndo13ProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BokepIndo13Provider())
    }
}
