package com.example.mob_dev_portfolio.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-scoped "has the user passed the biometric gate?" flag.
 *
 * The flag is process-lived, not persisted — a cold start, process
 * death, or [com.example.mob_dev_portfolio.MainActivity.onStop] all
 * reset it to `false`, forcing a fresh authentication on resume. The
 * biometric-lock preference (stored in DataStore) decides whether the
 * gate is actually *shown*; this controller only answers "has it been
 * cleared for the current session?"
 *
 * Kept in [com.example.mob_dev_portfolio.AppContainer] rather than in
 * MainActivity so the Settings screen can flip it when the user turns
 * the preference on from inside an already-unlocked app — otherwise
 * toggling the pref on would immediately kick them to the lock screen
 * even though they just proved presence by touching the toggle.
 */
class AppLockController {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /**
     * One-shot bypass for the very next `markLocked()`. Flipped to true
     * by UI code right before it deliberately starts a sibling activity
     * that will cause our activity to lose foreground — the camera
     * intent and the system gallery picker being the concrete cases.
     * Volatile because the callers may be on any dispatcher and the
     * `onStop` that consumes it runs on the main thread.
     */
    @Volatile
    private var suppressNextRelock: Boolean = false

    /** Called when BiometricPrompt reports a successful authentication. */
    fun markUnlocked() {
        _unlocked.value = true
    }

    /**
     * Re-locks the session. Driven from the Activity's `onStop` so
     * swapping apps, locking the phone, or sending the process to the
     * background all require re-authentication on the way back in.
     *
     * Honours a pending [suppressNextRelock] request: when set, the
     * very next `onStop` is treated as an expected round-trip to a
     * sibling activity (camera, gallery picker, etc.) and skipped. The
     * flag is consumed in place so a subsequent genuine backgrounding
     * still re-locks.
     */
    fun markLocked() {
        if (suppressNextRelock) {
            suppressNextRelock = false
            return
        }
        _unlocked.value = false
    }

    /**
     * Announce that the next foreground loss is an in-app picker
     * round-trip — the user hasn't left on purpose, so the biometric
     * gate should *not* re-prompt on return. Call immediately before
     * `launcher.launch(…)` for any contract that opens a separate
     * activity (camera, gallery, share sheet, …). Permission dialogs
     * don't need this: they render as floating system UI and don't
     * push our activity past `onStop`.
     *
     * Idempotent — calling twice before a single onStop is harmless;
     * the flag is consumed once either way.
     */
    fun suppressNextRelock() {
        suppressNextRelock = true
    }
}
