package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import soy.engindearing.omnitak.mobile.data.CertVault
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.CoTParser
import soy.engindearing.omnitak.mobile.data.CotXml
import soy.engindearing.omnitak.mobile.data.TAKServer
import soy.engindearing.omnitak.mobile.data.TakRestApiClient
import soy.engindearing.omnitak.mobile.data.net.TakTls
import soy.engindearing.omnitak.mobile.data.video.CotVideo
import soy.engindearing.omnitak.mobile.data.video.VideoFeed
import soy.engindearing.omnitak.mobile.data.video.VideoFeedOrigin
import soy.engindearing.omnitak.mobile.data.video.VideoFeedStore
import soy.engindearing.omnitak.mobile.data.video.VideoFeedUrls
import soy.engindearing.omnitak.mobile.ui.video.VideoFeedController
import java.util.concurrent.TimeUnit

/** Server-listed feeds for one TAK server. */
data class ServerFeeds(
    val serverId: String,
    val serverName: String,
    val feeds: List<VideoFeed> = emptyList(),
    val loading: Boolean = false,
    /** Display-safe failure from the last refresh. */
    val error: String? = null,
    val fetchedAtMs: Long? = null,
)

/**
 * Everything "video feeds": the manual list ([VideoFeedStore]), feeds the
 * TAK server lists over the Marti video API, feeds carried by received
 * markers' `__video`, and the one shared [VideoFeedController] that the
 * full-screen player and the map picture-in-picture both display.
 *
 * Works without MAVLink: nothing here touches the UAS stack.
 */
class VideoFeedManager(
    context: Context,
    private val scope: CoroutineScope,
    private val store: VideoFeedStore,
    private val serverManager: ServerManager,
    private val contactStore: ContactStore,
    private val certVault: CertVault,
    private val selfUid: () -> String?,
) {
    private val appContext = context.applicationContext

    val manualFeeds: StateFlow<List<VideoFeed>> =
        store.feeds.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _serverFeeds = MutableStateFlow<Map<String, ServerFeeds>>(emptyMap())
    val serverFeeds: StateFlow<Map<String, ServerFeeds>> = _serverFeeds.asStateFlow()

    /** Feeds advertised on markers currently on the map. */
    val markerFeeds: StateFlow<List<VideoFeed>> = contactStore.contacts
        .map { contacts -> contacts.values.mapNotNull { feedFromContact(it) }.sortedBy { it.alias.lowercase() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Id of the feed shown in the map picture-in-picture, or null. */
    private val _pipFeedId = MutableStateFlow<String?>(null)
    val pipFeedId: StateFlow<String?> = _pipFeedId.asStateFlow()

    /** The one controller currently playing (full screen and/or map PiP). */
    private val _playing = MutableStateFlow<VideoFeedController?>(null)
    val playing: StateFlow<VideoFeedController?> = _playing.asStateFlow()

    private val current: VideoFeedController? get() = _playing.value

    // ------------------------------------------------------------------
    // Manual feeds
    // ------------------------------------------------------------------

    fun save(feed: VideoFeed) {
        scope.launch { store.upsert(feed.copy(origin = VideoFeedOrigin.MANUAL)) }
    }

    fun delete(id: String) {
        scope.launch { store.delete(id) }
        if (current?.feed?.id == id) closePlayback()
    }

    /** True when the Keystore is usable, i.e. feed passwords are persisted. */
    val passwordsPersisted: Boolean get() = store.passwordsPersisted

    // ------------------------------------------------------------------
    // Server feeds (Marti video API)
    // ------------------------------------------------------------------

    /** Servers we can query: same rule as mission sync (TLS + client cert). */
    fun queryableServers(): List<TAKServer> =
        serverManager.servers.value.filter { it.enabled && it.useTLS && it.certificateName != null }

    fun refreshServerFeeds() {
        val servers = queryableServers()
        _serverFeeds.update { old -> old.filterKeys { id -> servers.any { it.id == id } } }
        servers.forEach { server ->
            _serverFeeds.update {
                it + (server.id to (it[server.id] ?: ServerFeeds(server.id, server.name)).copy(
                    serverName = server.name, loading = true, error = null,
                ))
            }
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { TakRestApiClient(server, certVault).getVideoFeeds() }
                }
                _serverFeeds.update { map ->
                    val prev = map[server.id] ?: ServerFeeds(server.id, server.name)
                    val next = result.fold(
                        onSuccess = { prev.copy(feeds = it, loading = false, error = null, fetchedAtMs = System.currentTimeMillis()) },
                        onFailure = {
                            Log.w(TAG, "video list from ${server.name} failed: ${it.javaClass.simpleName}")
                            prev.copy(loading = false, error = it.message?.let(VideoFeedUrls::redact) ?: "Request failed")
                        },
                    )
                    map + (server.id to next)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Markers
    // ------------------------------------------------------------------

    /** The feed a received marker advertises, or null. */
    fun feedFromContact(event: CoTEvent): VideoFeed? {
        val url = event.videoUrl ?: return null
        val protocol = VideoFeedUrls.detectProtocol(url) ?: return null
        return VideoFeed(
            id = "cot:${event.uid}",
            alias = event.videoAlias ?: event.callsign ?: event.uid,
            url = url,
            protocol = protocol,
            origin = VideoFeedOrigin.COT,
            sourceUid = event.uid,
        )
    }

    /**
     * Send [feed] to the team as a video marker at [lat]/[lon]. Credentials
     * and tokens are stripped ([CotVideo.detailXml]); receivers bring their
     * own. Returns false when no TAK server took it.
     */
    suspend fun shareAsMarker(feed: VideoFeed, lat: Double, lon: Double, hae: Double): Boolean {
        // Stable per (sender, feed): re-sharing updates the same marker.
        val uid = "video-" + "${selfUid().orEmpty()}|${feed.id}".hashCode().toUInt().toString(16)
        val now = System.currentTimeMillis()
        val xml = CotVideo.buildFeedMarker(
            uid = uid,
            alias = feed.alias,
            url = feed.url,
            lat = lat, lon = lon, hae = hae,
            timeIso = CotXml.isoMillis(now),
            staleIso = CotXml.isoMillis(now + MARKER_STALE_MS),
            senderUid = selfUid(),
        )
        CoTParser.parse(xml)?.let { contactStore.ingest(it) }
        return serverManager.sendCoT(xml)
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    /**
     * The controller for [feed], creating (and starting) it if needed. One
     * feed plays at a time; opening another releases the previous one.
     */
    fun controllerFor(feed: VideoFeed): VideoFeedController {
        current?.let { c ->
            // Same feed (a server feed re-fetched with a fresh token counts
            // as changed: the old token may have expired).
            if (c.feed.id == feed.id && c.feed == feed) return c
            c.release()
        }
        if (_pipFeedId.value != feed.id) _pipFeedId.value = null
        return VideoFeedController(appContext, feed, httpClient = { httpClientFor(feed) })
            .also { it.start(); _playing.value = it }
    }

    /** The controller currently playing, if any. */
    fun currentController(): VideoFeedController? = current

    /** Show [feed] floating over the map. */
    fun showPip(feed: VideoFeed) {
        controllerFor(feed)
        _pipFeedId.value = feed.id
    }

    fun hidePip() {
        _pipFeedId.value = null
    }

    /** Stop playback and drop the PiP. */
    fun closePlayback() {
        _pipFeedId.value = null
        current?.release()
        _playing.value = null
    }

    /**
     * HTTPS client for HLS. When the feed's host is one of our TAK servers
     * (OpenTAKServer's `/hls/` lives on the same nginx), trust is that
     * server's enrollment CA pin via [TakTls] — the same policy as the CoT
     * and Marti planes. Other hosts use the system trust store.
     */
    fun httpClientFor(feed: VideoFeed): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
        val host = VideoFeedUrls.host(feed.url)
        val server = serverManager.servers.value.firstOrNull { it.id == feed.serverId }
            ?: serverManager.servers.value.firstOrNull { it.useTLS && it.host.equals(host, ignoreCase = true) }
        if (server != null) {
            val tls = TakTls.forOkHttp(server, certVault)
            builder.sslSocketFactory(tls.socketFactory, tls.trustManager)
            tls.hostnameVerifier?.let { builder.hostnameVerifier(it) }
        }
        return builder.build()
    }

    private companion object {
        const val TAG = "VideoFeedManager"
        /** Shared video markers stay on peers' maps for a day. */
        const val MARKER_STALE_MS = 24L * 60 * 60 * 1000
    }
}
