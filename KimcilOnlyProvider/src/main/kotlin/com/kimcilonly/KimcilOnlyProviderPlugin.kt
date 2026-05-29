package com.kimcilonly

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KimcilOnlyProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KimcilOnlyProvider())
    }
}
