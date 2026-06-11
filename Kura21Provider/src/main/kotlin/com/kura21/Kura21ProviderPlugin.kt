package com.kura21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Kura21ProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Kura21Provider())
    }
}
