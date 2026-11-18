package com.amar.vault.indexing

object AdaptiveImageOcrControl {
    @Volatile
    var tier1Enabled: Boolean = true

    @Volatile
    var paintSettledLines: Boolean = true
}
