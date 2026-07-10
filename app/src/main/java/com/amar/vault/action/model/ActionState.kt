package com.amar.vault.action.model

enum class ActionState {
    INTERNAL_CANDIDATE,
    CONFIRMED_ACTION,
    STALE,
    FAILED,
    ARCHIVED
}
