package com.playbokep

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PlayBokepProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PlayBokepProvider())
    }
}
