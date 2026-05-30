package com.kurakura21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Kurakura21ProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Kurakura21Provider())
    }
}
