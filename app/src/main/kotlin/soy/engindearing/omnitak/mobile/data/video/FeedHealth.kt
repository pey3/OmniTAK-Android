package soy.engindearing.omnitak.mobile.data.video

/**
 * What the operator is told about a playing feed. Project rule: stale data
 * must look stale — a frozen last frame must never read as [LIVE].
 */
enum class FeedStatus(val label: String) {
    IDLE("IDLE"),
    CONNECTING("CONNECTING"),
    LIVE("LIVE"),
    RECONNECTING("RECONNECTING"),
    STALE("STALE"),
    ERROR("ERROR"),
}

/**
 * Snapshot of a feed's health. Times come from a MONOTONIC clock
 * (`SystemClock.elapsedRealtime()` in the app): a wall clock stepped back
 * by GPS/NTP sync would otherwise make a frozen feed look fresh.
 */
data class FeedHealth(
    val status: FeedStatus = FeedStatus.IDLE,
    /** When the last decoded frame reached the screen; null if none yet this session. */
    val lastFrameAtMs: Long? = null,
    /** Consecutive failed attempts since the last frame. */
    val attempt: Int = 0,
    /** When the next automatic retry fires (RECONNECTING only). */
    val nextRetryAtMs: Long? = null,
    /** Last failure, display-safe (no URL, no credentials). */
    val error: String? = null,
    /** When the current connection attempt started (CONNECTING only). */
    val connectStartedAtMs: Long? = null,
) {
    /** Milliseconds since the last frame at [nowMs], or null if never. */
    fun frameAgeMs(nowMs: Long): Long? = lastFrameAtMs?.let { (nowMs - it).coerceAtLeast(0) }
}

/**
 * Side effect the player must perform after a transition. The machine is
 * pure; the controller owns the actual Media3 / MediaCodec objects.
 */
enum class FeedAction {
    NONE,
    /** Release the current player (keep the surface). */
    TEAR_DOWN,
    /** Build a fresh player and start connecting. */
    START,
}

/**
 * Exponential reconnect backoff: [initialMs], doubling, capped at [maxMs].
 * attempt 1 → initial, 2 → 2×, 3 → 4×, …
 */
data class Backoff(val initialMs: Long = 1_000, val maxMs: Long = 30_000) {
    fun delayFor(attempt: Int): Long {
        if (attempt <= 1) return initialMs
        val shift = (attempt - 1).coerceAtMost(20)
        return (initialMs shl shift).coerceAtMost(maxMs)
    }
}

/**
 * Feed health state machine.
 *
 * ```
 * IDLE ──start──▶ CONNECTING ──frame──▶ LIVE ◀──frame── STALE
 *                   │  ▲                  │ no frame > staleAfterMs ▲
 *        error/timeout  │ retry due        └────────────────────────┘
 *                   ▼  │                     no frame > reconnectAfterMs
 *              RECONNECTING ◀────────────────────────(from STALE)
 *                   │ fatal error (auth, bad URL, unsupported codec)
 *                   ▼
 *                 ERROR ──retryNow──▶ CONNECTING
 * ```
 *
 * Retries never give up on transient failures: in the field a link that
 * drops for ten minutes must come back on its own. Only failures that a
 * retry cannot fix (wrong password, unsupported format) park in ERROR
 * until the operator taps Retry.
 */
class FeedHealthMachine(
    /** A LIVE feed with no new frame for this long turns STALE. */
    val staleAfterMs: Long = 3_000,
    /** A STALE feed with no new frame for this long is torn down and retried. */
    val reconnectAfterMs: Long = 12_000,
    /** A CONNECTING attempt that yields no frame within this is retried. */
    val connectTimeoutMs: Long = 20_000,
    val backoff: Backoff = Backoff(),
) {
    var health: FeedHealth = FeedHealth()
        private set

    fun start(nowMs: Long): FeedAction {
        health = FeedHealth(
            status = FeedStatus.CONNECTING,
            lastFrameAtMs = health.lastFrameAtMs,
            attempt = 0,
            connectStartedAtMs = nowMs,
        )
        return FeedAction.START
    }

    fun stop(): FeedAction {
        val wasRunning = health.status != FeedStatus.IDLE
        health = health.copy(status = FeedStatus.IDLE, nextRetryAtMs = null, connectStartedAtMs = null)
        return if (wasRunning) FeedAction.TEAR_DOWN else FeedAction.NONE
    }

    /**
     * A decoded frame reached the screen. Ignored unless a player is
     * supposed to be running: a frame still in flight from a player that
     * was just torn down must not turn RECONNECTING / ERROR into LIVE.
     */
    fun onFrame(nowMs: Long): FeedAction {
        val s = health.status
        if (s != FeedStatus.CONNECTING && s != FeedStatus.LIVE && s != FeedStatus.STALE) return FeedAction.NONE
        health = FeedHealth(status = FeedStatus.LIVE, lastFrameAtMs = nowMs)
        return FeedAction.NONE
    }

    /**
     * The player failed. [fatal] failures park in ERROR; others schedule a
     * retry with backoff. [message] must already be display-safe.
     */
    fun onError(nowMs: Long, message: String, fatal: Boolean): FeedAction {
        if (health.status == FeedStatus.IDLE) return FeedAction.NONE
        if (fatal) {
            health = health.copy(
                status = FeedStatus.ERROR, error = message,
                nextRetryAtMs = null, connectStartedAtMs = null,
            )
            return FeedAction.TEAR_DOWN
        }
        return scheduleRetry(nowMs, message)
    }

    /** Periodic clock tick (the controller calls this about twice a second). */
    fun onTick(nowMs: Long): FeedAction {
        val h = health
        return when (h.status) {
            FeedStatus.LIVE -> {
                val age = h.frameAgeMs(nowMs) ?: 0
                if (age > staleAfterMs) health = h.copy(status = FeedStatus.STALE)
                FeedAction.NONE
            }
            FeedStatus.STALE -> {
                val age = h.frameAgeMs(nowMs) ?: Long.MAX_VALUE
                if (age > reconnectAfterMs) scheduleRetry(nowMs, "No video for ${age / 1000} s")
                else FeedAction.NONE
            }
            FeedStatus.CONNECTING -> {
                val started = h.connectStartedAtMs ?: nowMs
                if (nowMs - started > connectTimeoutMs) {
                    scheduleRetry(nowMs, "No video after ${connectTimeoutMs / 1000} s")
                } else FeedAction.NONE
            }
            FeedStatus.RECONNECTING -> {
                val due = h.nextRetryAtMs ?: nowMs
                if (nowMs >= due) {
                    health = h.copy(status = FeedStatus.CONNECTING, nextRetryAtMs = null, connectStartedAtMs = nowMs)
                    FeedAction.START
                } else FeedAction.NONE
            }
            FeedStatus.IDLE, FeedStatus.ERROR -> FeedAction.NONE
        }
    }

    /** Operator tapped Retry: reconnect now, whatever the state. */
    fun retryNow(nowMs: Long): FeedAction {
        health = health.copy(
            status = FeedStatus.CONNECTING, nextRetryAtMs = null,
            connectStartedAtMs = nowMs, error = null,
        )
        return FeedAction.START
    }

    private fun scheduleRetry(nowMs: Long, message: String): FeedAction {
        val attempt = health.attempt + 1
        health = health.copy(
            status = FeedStatus.RECONNECTING,
            attempt = attempt,
            nextRetryAtMs = nowMs + backoff.delayFor(attempt),
            error = message,
            connectStartedAtMs = null,
        )
        return FeedAction.TEAR_DOWN
    }
}
