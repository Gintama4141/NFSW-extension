package com.avtub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AvtubProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AvtubProvider())
    }
}
