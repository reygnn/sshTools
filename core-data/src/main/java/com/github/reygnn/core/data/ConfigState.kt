package com.github.reygnn.core.data

/**
 * Three-state representation for "is the app configured?".
 *
 * Previously this was carried by a `Boolean?`, with `null` as the loading
 * sentinel — that works, but the `null` contract is subtle and had to be
 * commented at every branch. This sealed interface makes the
 * loading state explicit.
 */
sealed interface ConfigState {
    data object Loading : ConfigState
    data object Unconfigured : ConfigState
    data object Configured : ConfigState
}
