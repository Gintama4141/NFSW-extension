package com.kura21

import com.lagradost.cloudstream3.plugins.Plugin

class Kura21ProviderPlugin : Plugin() {
    override fun getLoad(): Class<out MainAPI> = Kura21Provider::class.java
}
