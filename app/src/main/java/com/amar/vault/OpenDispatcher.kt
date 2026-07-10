package com.amar.vault

import android.content.Context

object OpenDispatcher {
    fun open(context: Context, item: StashItemWithVaultItem) {
        ContentOpenManager.open(context, item)
    }

    fun open(context: Context, item: VaultItem) {
        ContentOpenManager.open(context, item)
    }

    fun share(context: Context, item: StashItemWithVaultItem) {
        ContentOpenManager.share(context, item)
    }

    fun share(context: Context, item: VaultItem) {
        ContentOpenManager.share(context, item)
    }

    fun openOriginal(context: Context, item: StashItemWithVaultItem) {
        ContentOpenManager.openOriginal(context, item)
    }
}
