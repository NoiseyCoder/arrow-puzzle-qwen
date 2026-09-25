package com.arrowescape.game

/** Outcome of a rewarded-ad request. */
enum class AdResult { SUCCESS, FAILED, CANCELLED }

/**
 * Small suspend interface around a rewarded-video SDK ("Save me" and "watch video for a hint").
 *
 * Design rules honored here:
 *  - No forced ads, never during a level: the game only ever calls [show] from an explicit
 *    player-initiated button, and awaits the result without blocking game logic.
 *  - Graceful degradation: until a real SDK is wired in, [NoOpRewardedAdProvider] returns
 *    [AdResult.FAILED] after a short simulated delay, so callers can disable the affordance
 *    or show a friendly toast instead of crashing.
 */
interface RewardedAdProvider {
    /** True when a real ad could be served right now (stub reports false). */
    val isAvailable: Boolean get() = false

    /** Requests a rewarded video and suspends until the user finished/cancelled/it failed. */
    suspend fun show(): AdResult
}

/** Default implementation shipped with the app: no ad network, simply unavailable. */
class NoOpRewardedAdProvider : RewardedAdProvider {
    override val isAvailable: Boolean = false

    override suspend fun show(): AdResult {
        // Keep the call shape identical to a real provider (short async gap) so UI code paths
        // are exercised even while stubbed.
        kotlinx.coroutines.delay(250L)
        return AdResult.FAILED
    }
}
