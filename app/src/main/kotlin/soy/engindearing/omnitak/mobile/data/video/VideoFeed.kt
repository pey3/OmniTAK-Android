package soy.engindearing.omnitak.mobile.data.video

import kotlinx.serialization.Serializable

/**
 * Transport a [VideoFeed] is played with. [RTMP] is accepted on input so
 * an operator can paste the URL their drone app publishes to, but it is
 * not played directly (Media3's RTMP extension pulls in a native LGPL
 * librtmp); [VideoFeedUrls.rtmpPlaybackHint] suggests the RTSP URL that
 * MediaMTX / OpenTAKServer re-serve the same stream on.
 */
@Serializable
enum class VideoProtocol(val label: String, val playable: Boolean) {
    RTSP("RTSP", true),
    HLS("HLS", true),
    RTMP("RTMP", false),
    UDP_MPEGTS("UDP MPEG-TS", true),
    UDP_RAW_H264("UDP raw H.264", true),
}

/** Where a feed came from. Only [MANUAL] feeds are persisted by [VideoFeedStore]. */
@Serializable
enum class VideoFeedOrigin {
    /** Added on this device in the Video Feeds screen. */
    MANUAL,
    /** Listed by the connected TAK server's video API (`/Marti/api/video`). */
    SERVER,
    /** Carried by a received CoT marker's `__video` detail. */
    COT,
    /** The connected drone's camera (UAS tool). */
    UAS,
}

/**
 * One video feed the operator can watch.
 *
 * Secrets: [password] is held in memory only. [VideoFeedStore] strips it
 * before the feed list hits DataStore and keeps it in the Keystore-backed
 * [FeedCredentialStore] instead. [url] must never embed credentials for a
 * MANUAL feed: [VideoFeedUrls.splitCredentials] moves `user:pass@` out of
 * a pasted URL into [username] / [password] when the feed is saved.
 *
 * SERVER feeds may carry an access token in [url]'s query (OpenTAKServer
 * appends `?jwt=…` to its HLS links); [VideoFeedUrls.redact] masks it for
 * display and the URL is never written to disk or into CoT.
 */
@Serializable
data class VideoFeed(
    val id: String,
    val alias: String,
    val url: String,
    val protocol: VideoProtocol,
    val username: String? = null,
    val password: String? = null,
    /** RTSP only: interleave RTP in the RTSP TCP connection (works through NAT/VPN). */
    val rtspOverTcp: Boolean = true,
    /** Small buffers: lower delay, more stutter on a weak link. */
    val lowLatency: Boolean = true,
    val origin: VideoFeedOrigin = VideoFeedOrigin.MANUAL,
    /** For SERVER feeds, the [soy.engindearing.omnitak.mobile.data.TAKServer.id] that listed it. */
    val serverId: String? = null,
    /** For COT feeds, the uid of the marker that carried it. */
    val sourceUid: String? = null,
) {
    val hasCredentials: Boolean get() = !username.isNullOrBlank()
}
