package soy.engindearing.omnitak.mobile.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.UdpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Credentials
import okhttp3.OkHttpClient
import soy.engindearing.omnitak.mobile.data.uas.RawH264UdpPlayer
import soy.engindearing.omnitak.mobile.data.video.FeedAction
import soy.engindearing.omnitak.mobile.data.video.FeedHealth
import soy.engindearing.omnitak.mobile.data.video.FeedHealthMachine
import soy.engindearing.omnitak.mobile.data.video.FeedStatus
import soy.engindearing.omnitak.mobile.data.video.VideoFeed
import soy.engindearing.omnitak.mobile.data.video.VideoFeedUrls
import soy.engindearing.omnitak.mobile.data.video.VideoProtocol

/** Live readout for the stats line. Null fields are unknown for this transport. */
data class FeedStats(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float? = null,
    val bitrateKbps: Int? = null,
    /** HLS only: how far behind the live edge playback is. */
    val liveOffsetMs: Long? = null,
)

/**
 * Plays one [VideoFeed] onto whichever [TextureView] is currently attached
 * (full-screen player, map PiP or UAS PiP), drives [FeedHealthMachine]
 * with real frame arrivals, and reconnects on its own.
 *
 * Frame detection: every frame the decoder pushes into the TextureView's
 * SurfaceTexture fires `onSurfaceTextureUpdated`. That is the most honest
 * "a new picture is on screen" signal there is, and it works the same for
 * Media3 and for the raw-H.264 MediaCodec path — so a frozen picture turns
 * STALE no matter which transport froze. (The player is fed a plain
 * [Surface] built from that SurfaceTexture rather than the TextureView
 * itself, because `ExoPlayer.setVideoTextureView` would replace our
 * listener.)
 *
 * All methods must be called on the main thread.
 */
@OptIn(UnstableApi::class)
class VideoFeedController(
    context: Context,
    val feed: VideoFeed,
    /** HTTPS client for HLS: TAK-server CA pin when the host is a known server. */
    private val httpClient: () -> OkHttpClient,
    private val machine: FeedHealthMachine = FeedHealthMachine(),
    /** Monotonic: a wall clock stepped back by GPS/NTP would hide staleness. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _health = MutableStateFlow(FeedHealth())
    val health: StateFlow<FeedHealth> = _health.asStateFlow()

    private val _stats = MutableStateFlow(FeedStats())
    val stats: StateFlow<FeedStats> = _stats.asStateFlow()

    private val _muted = MutableStateFlow(true)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private var exo: ExoPlayer? = null
    private var raw: RawH264UdpPlayer? = null
    private var surface: Surface? = null
    private var attachedView: TextureView? = null
    private var wanted = false
    private var released = false
    private var tickerRunning = false
    /** Bumped for every engine started; callbacks from older engines are dropped. */
    private var generation = 0
    /** Last health actually emitted; frame-only changes are throttled. */
    private var publishedFrameAtMs: Long? = null
    private val okHttp: OkHttpClient by lazy { httpClient() }

    // Stats bookkeeping.
    private var frameCount = 0
    private var frameWindowStartMs = SystemClock.elapsedRealtime()
    private var bytesInWindow = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (released || !tickerRunning) return
            apply(machine.onTick(clock()))
            sampleStats()
            main.postDelayed(this, TICK_MS)
        }
    }

    private val detachGrace = Runnable { if (attachedView == null) pauseForNoSurface() }

    // ------------------------------------------------------------------
    // Surface plumbing
    // ------------------------------------------------------------------

    /** Listener to install on a TextureView; see [attach]. */
    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            setSurface(Surface(st))
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            clearSurface()
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
            // A frame queued by an engine we already tore down is not news.
            if (exo == null && raw == null) return
            frameCount++
            apply(machine.onFrame(clock()))
        }
    }

    /**
     * Show the feed on [view]. The most recently attached view wins; the
     * previous one is left blank. Starts playback if [start] was called.
     */
    fun attach(view: TextureView) {
        if (released) return
        main.removeCallbacks(detachGrace)
        if (attachedView === view) return
        // Hand-over: let go of the previous view's surface right away. If
        // the new view's texture isn't ready yet, the player renders
        // nowhere for a moment (and may honestly show STALE) instead of
        // drawing into a surface nobody is watching.
        attachedView?.let {
            it.surfaceTextureListener = null
            it.keepScreenOn = false
            clearSurface()
        }
        attachedView = view
        view.surfaceTextureListener = textureListener
        view.keepScreenOn = true
        val st = view.surfaceTexture
        if (view.isAvailable && st != null) setSurface(Surface(st))
    }

    /** [view] left the screen. Playback pauses if nothing replaces it quickly. */
    fun detach(view: TextureView) {
        if (attachedView !== view) return
        view.surfaceTextureListener = null
        view.keepScreenOn = false
        attachedView = null
        clearSurface()
        main.postDelayed(detachGrace, DETACH_GRACE_MS)
    }

    private fun setSurface(s: Surface) {
        val old = surface
        surface = s
        when {
            // MediaCodec is bound to its output surface: rebuild it.
            raw != null -> { stopEngines(); startEngine() }
            exo != null -> exo?.setVideoSurface(s)
            wanted -> when (machine.health.status) {
                FeedStatus.IDLE -> apply(machine.start(clock()))
                FeedStatus.CONNECTING, FeedStatus.LIVE, FeedStatus.STALE -> startEngine()
                // The ticker restarts RECONNECTING; ERROR waits for Retry.
                FeedStatus.RECONNECTING, FeedStatus.ERROR -> Unit
            }
        }
        old?.release()
    }

    private fun clearSurface() {
        exo?.clearVideoSurface()
        if (raw != null) stopEngines()
        surface?.release()
        surface = null
    }

    private fun pauseForNoSurface() {
        apply(machine.stop())
        publish()
    }

    // ------------------------------------------------------------------
    // Public control
    // ------------------------------------------------------------------

    /**
     * Begin (or resume) playing. Idempotent: calling it on a feed that is
     * already connecting or live does nothing, so a view hand-over or a
     * lifecycle ON_START replay never restarts a healthy stream.
     */
    fun start() {
        if (released) return
        val wasWanted = wanted
        wanted = true
        ensureTicker()
        if (surface != null && (!wasWanted || machine.health.status == FeedStatus.IDLE)) {
            apply(machine.start(clock()))
        }
        publish()
    }

    fun stop() {
        wanted = false
        main.removeCallbacks(ticker)
        tickerRunning = false
        apply(machine.stop())
        publish()
    }

    /** Reconnect now, whatever the state (the Retry button). */
    fun retryNow() {
        if (released) return
        wanted = true
        ensureTicker()
        apply(machine.retryNow(clock()))
    }

    private fun ensureTicker() {
        if (tickerRunning) return
        tickerRunning = true
        main.post(ticker)
    }

    fun setMuted(muted: Boolean) {
        _muted.value = muted
        exo?.volume = if (muted) 0f else 1f
    }

    /** Current picture, or null if nothing has been drawn yet. */
    fun snapshot(): Bitmap? = attachedView?.takeIf { it.isAvailable }?.bitmap

    fun release() {
        if (released) return
        wanted = false
        main.removeCallbacks(ticker)
        tickerRunning = false
        main.removeCallbacks(detachGrace)
        stopEngines()
        attachedView?.let {
            it.surfaceTextureListener = null
            it.keepScreenOn = false
        }
        attachedView = null
        surface?.release()
        surface = null
        released = true
        machine.stop()
        publish()
    }

    // ------------------------------------------------------------------
    // State machine side effects
    // ------------------------------------------------------------------

    private fun apply(action: FeedAction) {
        when (action) {
            FeedAction.NONE -> Unit
            FeedAction.TEAR_DOWN -> stopEngines()
            FeedAction.START -> {
                stopEngines()
                if (surface != null) startEngine()
            }
        }
        publish()
    }

    /**
     * Emit the machine's health. A change that is only a newer frame time
     * (LIVE → LIVE) is throttled to a few per second so a 30 fps feed does
     * not recompose every badge 30 times a second.
     */
    private fun publish() {
        val h = machine.health
        val cur = _health.value
        if (cur == h) return
        val frameOnly = cur.copy(lastFrameAtMs = h.lastFrameAtMs) == h
        if (frameOnly) {
            val last = publishedFrameAtMs
            val t = h.lastFrameAtMs
            if (last != null && t != null && t - last < PUBLISH_FRAME_MS) return
        }
        publishedFrameAtMs = h.lastFrameAtMs
        _health.value = h
    }

    /**
     * Report a failure of engine [gen] (defaults to the current one). Posted
     * to the main thread; dropped if that engine has since been replaced —
     * a late error from a dead MediaCodec thread must not tear down the
     * fresh player.
     */
    private fun fail(message: String, fatal: Boolean, gen: Int = generation) {
        Log.w(TAG, "feed '${feed.alias}' (${feed.protocol}): $message")
        main.post {
            if (gen != generation || released) return@post
            apply(machine.onError(clock(), message, fatal))
        }
    }

    private fun startEngine() {
        val s = surface ?: return
        if (!feed.protocol.playable) {
            fail("${feed.protocol.label} can't be played directly", fatal = true)
            return
        }
        VideoFeedUrls.validate(feed.url, feed.protocol)?.let {
            fail(it, fatal = true)
            return
        }
        resetStatsWindow()
        val gen = ++generation
        if (feed.protocol == VideoProtocol.UDP_RAW_H264) {
            val port = VideoFeedUrls.udpPort(feed.url) ?: return fail("Bad UDP port", fatal = true)
            raw = RawH264UdpPlayer(
                port = port,
                surface = s,
                onError = { t -> fail("UDP $port: ${t.javaClass.simpleName}", fatal = false, gen = gen) },
            ).also { it.start() }
            return
        }
        val source = runCatching { buildMediaSource() }.getOrElse {
            fail("Can't open feed: ${it.javaClass.simpleName}", fatal = true)
            return
        }
        exo = ExoPlayer.Builder(appContext)
            .setLoadControl(loadControl())
            .build()
            .also { p ->
                p.volume = if (_muted.value) 0f else 1f
                p.setVideoSurface(s)
                p.addListener(playerListener)
                p.addAnalyticsListener(analyticsListener)
                p.setMediaSource(source)
                p.prepare()
                p.playWhenReady = true
            }
    }

    private fun stopEngines() {
        if (exo != null || raw != null) generation++
        exo?.let {
            it.removeListener(playerListener)
            it.removeAnalyticsListener(analyticsListener)
            it.release()
        }
        exo = null
        raw?.stop()
        raw = null
    }

    private fun buildMediaSource(): MediaSource {
        val itemBuilder = MediaItem.Builder()
        return when (feed.protocol) {
            VideoProtocol.RTSP -> {
                val uri = VideoFeedUrls.withCredentials(feed.url, feed.username, feed.password)
                RtspMediaSource.Factory()
                    .setForceUseRtpTcp(feed.rtspOverTcp)
                    .setTimeoutMs(8_000)
                    .createMediaSource(itemBuilder.setUri(uri).build())
            }
            VideoProtocol.HLS -> {
                val client = okHttp.let { base ->
                    val user = feed.username
                    if (user.isNullOrBlank()) base
                    else base.newBuilder().addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("Authorization", Credentials.basic(user, feed.password.orEmpty()))
                                .build(),
                        )
                    }.build()
                }
                val dsf: DataSource.Factory = OkHttpDataSource.Factory(client).setUserAgent(USER_AGENT)
                val live = MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(if (feed.lowLatency) 2_000 else 6_000)
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.08f)
                    .build()
                HlsMediaSource.Factory(dsf)
                    .createMediaSource(itemBuilder.setUri(feed.url).setLiveConfiguration(live).build())
            }
            VideoProtocol.UDP_MPEGTS -> {
                val port = VideoFeedUrls.udpPort(feed.url) ?: error("bad port")
                // A multicast group (udp://239.x.x.x:port) is joined by
                // UdpDataSource; no host means any local interface.
                val host = VideoFeedUrls.udpHost(feed.url) ?: "0.0.0.0"
                val udp = DataSource.Factory { UdpDataSource(4096) }
                val extractors = DefaultExtractorsFactory()
                    .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
                    .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
                ProgressiveMediaSource.Factory(udp, extractors)
                    .createMediaSource(itemBuilder.setUri("udp://$host:$port").build())
            }
            VideoProtocol.RTMP, VideoProtocol.UDP_RAW_H264 -> error("not an ExoPlayer source")
        }
    }

    private fun loadControl(): DefaultLoadControl {
        val b = DefaultLoadControl.Builder()
        if (feed.lowLatency) {
            // (min, max, bufferForPlayback, bufferForPlaybackAfterRebuffer)
            b.setBufferDurationsMs(200, 2_000, 100, 200)
        } else {
            b.setBufferDurationsMs(1_500, 8_000, 750, 1_500)
        }
        return b.setPrioritizeTimeOverSizeThresholds(true).build()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val (msg, fatal) = describe(error)
            fail(msg, fatal)
        }

        override fun onPlaybackStateChanged(state: Int) {
            // A live source that ENDs means the publisher stopped.
            if (state == Player.STATE_ENDED) fail("Stream ended", fatal = false)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                val w = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
                _stats.value = _stats.value.copy(width = w, height = videoSize.height)
            }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            if (loadEventInfo.bytesLoaded > 0) bytesInWindow += loadEventInfo.bytesLoaded
        }
    }

    private fun resetStatsWindow() {
        frameCount = 0
        bytesInWindow = 0
        frameWindowStartMs = SystemClock.elapsedRealtime()
        _stats.value = FeedStats()
    }

    private fun sampleStats() {
        val now = SystemClock.elapsedRealtime()
        val span = now - frameWindowStartMs
        if (span < STATS_WINDOW_MS) return
        val fps = frameCount * 1000f / span
        val kbps = if (bytesInWindow > 0) (bytesInWindow * 8 / span).toInt() else null
        val offset = exo?.currentLiveOffset?.takeIf { it != C.TIME_UNSET && it >= 0 }
        _stats.value = _stats.value.copy(
            fps = fps,
            bitrateKbps = kbps ?: _stats.value.bitrateKbps,
            liveOffsetMs = offset,
        )
        frameCount = 0
        bytesInWindow = 0
        frameWindowStartMs = now
    }

    companion object {
        private const val TAG = "VideoFeedController"
        private const val TICK_MS = 500L
        private const val STATS_WINDOW_MS = 2_000L
        private const val DETACH_GRACE_MS = 1_500L
        private const val USER_AGENT = "OmniTAK-Android video"
        private const val PUBLISH_FRAME_MS = 250L
        private val AUTH_FAILURE = Regex("""\b401\b|unauthori[sz]ed""", RegexOption.IGNORE_CASE)

        /**
         * Display-safe message + whether retrying can help. Never includes
         * the URL (it may carry credentials or a token).
         */
        internal fun describe(error: PlaybackException): Pair<String, Boolean> {
            val cause = error.cause
            val http = generateSequence(cause as Throwable?) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
            if (http != null) {
                return when (http.responseCode) {
                    401, 403 -> "Access denied (HTTP ${http.responseCode}) — check credentials" to true
                    404 -> "Not published yet (HTTP 404)" to false
                    else -> "Server error (HTTP ${http.responseCode})" to false
                }
            }
            val text = generateSequence(error as Throwable?) { it.cause }
                .mapNotNull { it.message }.joinToString(" ")
            // OkHttp refuses cleartext with a plain IOException, which Media3
            // reports as a generic network failure: retrying cannot help.
            if (text.contains("CLEARTEXT", ignoreCase = true)) {
                return "Plain HTTP is blocked — use https:// or RTSP" to true
            }
            // Whole-token 401 only: "port 8401" or "4015 ms" are not auth failures.
            if (AUTH_FAILURE.containsMatchIn(text)) {
                return "Access denied — check username and password" to true
            }
            return when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                    "Plain HTTP is blocked — use https:// or RTSP" to true
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                    "This phone can't decode the stream's video format" to true
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                    "Unsupported stream format" to true
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    "Can't reach the source" to false
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "Connection timed out" to false
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                    "Fell behind live — rejoining" to false
                else -> "Playback failed (${error.errorCodeName})" to false
            }
        }
    }
}
