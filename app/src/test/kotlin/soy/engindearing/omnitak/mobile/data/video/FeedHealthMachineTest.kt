package soy.engindearing.omnitak.mobile.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedHealthMachineTest {

    private fun machine() = FeedHealthMachine(
        staleAfterMs = 3_000,
        reconnectAfterMs = 12_000,
        connectTimeoutMs = 20_000,
        backoff = Backoff(initialMs = 1_000, maxMs = 30_000),
    )

    @Test fun startsConnectingAndGoesLiveOnFirstFrame() {
        val m = machine()
        assertEquals(FeedStatus.IDLE, m.health.status)
        assertEquals(FeedAction.START, m.start(0))
        assertEquals(FeedStatus.CONNECTING, m.health.status)
        m.onFrame(500)
        assertEquals(FeedStatus.LIVE, m.health.status)
        assertEquals(500L, m.health.lastFrameAtMs)
    }

    @Test fun frozenPictureTurnsStaleThenReconnects() {
        val m = machine()
        m.start(0)
        m.onFrame(1_000)
        assertEquals(FeedAction.NONE, m.onTick(3_900))
        assertEquals(FeedStatus.LIVE, m.health.status)
        m.onTick(4_100) // 3.1 s without a frame
        assertEquals(FeedStatus.STALE, m.health.status)
        assertEquals(3_100L, m.health.frameAgeMs(4_100))
        // Still stale, not yet torn down.
        assertEquals(FeedAction.NONE, m.onTick(12_000))
        assertEquals(FeedStatus.STALE, m.health.status)
        // 12 s without a frame: tear down and schedule a retry.
        assertEquals(FeedAction.TEAR_DOWN, m.onTick(13_100))
        assertEquals(FeedStatus.RECONNECTING, m.health.status)
        assertEquals(1, m.health.attempt)
        // Last frame time survives so the UI can keep saying how old it is.
        assertEquals(1_000L, m.health.lastFrameAtMs)
    }

    @Test fun staleRecoversToLiveOnNewFrame() {
        val m = machine()
        m.start(0)
        m.onFrame(0)
        m.onTick(5_000)
        assertEquals(FeedStatus.STALE, m.health.status)
        m.onFrame(5_200)
        assertEquals(FeedStatus.LIVE, m.health.status)
    }

    @Test fun errorRetriesWithExponentialBackoff() {
        val m = machine()
        m.start(0)
        assertEquals(FeedAction.TEAR_DOWN, m.onError(100, "Can't reach the source", fatal = false))
        assertEquals(FeedStatus.RECONNECTING, m.health.status)
        assertEquals(1_100L, m.health.nextRetryAtMs)
        assertEquals("Can't reach the source", m.health.error)

        assertEquals(FeedAction.NONE, m.onTick(1_000))
        assertEquals(FeedAction.START, m.onTick(1_100))
        assertEquals(FeedStatus.CONNECTING, m.health.status)

        m.onError(1_200, "again", fatal = false)
        assertEquals(2, m.health.attempt)
        assertEquals(1_200L + 2_000, m.health.nextRetryAtMs)
        m.onTick(3_200)
        m.onError(3_300, "again", fatal = false)
        assertEquals(3_300L + 4_000, m.health.nextRetryAtMs)
    }

    @Test fun backoffIsCapped() {
        val b = Backoff(1_000, 30_000)
        assertEquals(1_000L, b.delayFor(1))
        assertEquals(2_000L, b.delayFor(2))
        assertEquals(16_000L, b.delayFor(5))
        assertEquals(30_000L, b.delayFor(6))
        assertEquals(30_000L, b.delayFor(500))
    }

    @Test fun frameResetsAttemptsAndError() {
        val m = machine()
        m.start(0)
        m.onError(10, "x", fatal = false)
        m.onTick(1_010)
        m.onFrame(1_500)
        assertEquals(FeedStatus.LIVE, m.health.status)
        assertEquals(0, m.health.attempt)
        assertNull(m.health.error)
    }

    @Test fun fatalErrorParksUntilRetryNow() {
        val m = machine()
        m.start(0)
        assertEquals(FeedAction.TEAR_DOWN, m.onError(50, "Access denied", fatal = true))
        assertEquals(FeedStatus.ERROR, m.health.status)
        // No automatic retry, however long we wait.
        assertEquals(FeedAction.NONE, m.onTick(600_000))
        assertEquals(FeedStatus.ERROR, m.health.status)
        // A late frame from a dying player must not flip it to LIVE.
        m.onFrame(600_001)
        assertEquals(FeedStatus.ERROR, m.health.status)
        assertEquals(FeedAction.START, m.retryNow(600_100))
        assertEquals(FeedStatus.CONNECTING, m.health.status)
        assertNull(m.health.error)
    }

    @Test fun connectTimeoutSchedulesRetry() {
        val m = machine()
        m.start(0)
        assertEquals(FeedAction.NONE, m.onTick(19_000))
        assertEquals(FeedAction.TEAR_DOWN, m.onTick(20_500))
        assertEquals(FeedStatus.RECONNECTING, m.health.status)
    }

    @Test fun reconnectingWhileLastFrameKnownIsNeverLive() {
        val m = machine()
        m.start(0)
        m.onFrame(100)
        m.onError(200, "Stream ended", fatal = false)
        assertEquals(FeedStatus.RECONNECTING, m.health.status)
        m.onTick(1_200) // retry fires
        assertEquals(FeedStatus.CONNECTING, m.health.status)
        // Old frame time is kept: the UI shows "NOT LIVE — last frame … ago".
        assertEquals(100L, m.health.lastFrameAtMs)
    }

    @Test fun lateFrameWhileReconnectingIsIgnored() {
        val m = machine()
        m.start(0)
        m.onFrame(100)
        m.onError(200, "dropped", fatal = false)
        m.onFrame(250) // queued by the player that was just torn down
        assertEquals(FeedStatus.RECONNECTING, m.health.status)
        assertEquals(100L, m.health.lastFrameAtMs)
    }

    @Test fun stopIgnoresLaterEvents() {
        val m = machine()
        m.start(0)
        m.onFrame(10)
        assertEquals(FeedAction.TEAR_DOWN, m.stop())
        assertEquals(FeedStatus.IDLE, m.health.status)
        m.onFrame(20)
        m.onError(30, "x", fatal = false)
        m.onTick(100_000)
        assertEquals(FeedStatus.IDLE, m.health.status)
        assertEquals(FeedAction.NONE, m.stop())
    }
}
