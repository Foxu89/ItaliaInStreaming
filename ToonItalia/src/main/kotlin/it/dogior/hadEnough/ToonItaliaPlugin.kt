package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import it.dogior.hadEnough.extractors.MaxStreamExtractor
import it.dogior.hadEnough.extractors.StreamTapeExtractor

@CloudstreamPlugin
class ToonItaliaPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner
        registerMainAPI(ToonItalia())
        registerExtractorAPI(StreamTapeExtractor())
        registerExtractorAPI(MaxStreamExtractor())
    }
}