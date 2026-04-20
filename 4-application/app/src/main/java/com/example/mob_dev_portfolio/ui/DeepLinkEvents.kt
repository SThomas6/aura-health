package com.example.mob_dev_portfolio.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pending-action holder for notification deep-links.
 *
 * The subtle constraint here is that on a cold start [MainActivity] calls
 * [emit] inside `onCreate` *before* `setContent` has run — so Compose hasn't
 * had a chance to subscribe yet. A `SharedFlow(replay = 0)` drops events in
 * that window (its buffer is only for slow subscribers, not late ones),
 * which was the original implementation and manifested as "tapping the
 * notification after swiping the app away lands on Home instead of the
 * Analysis screen".
 *
 * Using a nullable [StateFlow] instead means a subscriber that joins later
 * still observes the pending value. To keep the "handle once" contract we
 * require callers to [consume] the target after acting on it, which flips
 * the holder back to `null`. Rotation and other config-change driven
 * re-subscriptions then see `null` and do nothing — no phantom re-navigation.
 */
class DeepLinkEvents {
    private val _pending = MutableStateFlow<DeepLinkTarget?>(null)
    val pending: StateFlow<DeepLinkTarget?> = _pending.asStateFlow()

    fun emit(target: DeepLinkTarget) {
        _pending.value = target
    }

    /**
     * Clears the pending target if and only if it still matches [target]. The
     * CAS avoids racing a rapid second tap (new target arrives between
     * navigate() and consume()) — that second target would otherwise get
     * wiped before the collector saw it.
     */
    fun consume(target: DeepLinkTarget) {
        _pending.compareAndSet(target, null)
    }
}

/**
 * Closed set of deep-link destinations the app knows how to handle. Keep
 * this as a sealed interface so adding a new entry (e.g. "open the latest
 * log") forces the navigation layer to handle it.
 */
sealed interface DeepLinkTarget {
    data object AnalysisResult : DeepLinkTarget
}
