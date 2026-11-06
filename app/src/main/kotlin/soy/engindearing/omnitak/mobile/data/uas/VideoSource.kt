package soy.engindearing.omnitak.mobile.data.uas

import soy.engindearing.omnitak.mobile.data.video.VideoFeed
import soy.engindearing.omnitak.mobile.data.video.VideoFeedOrigin
import soy.engindearing.omnitak.mobile.data.video.VideoFeedUrls
import soy.engindearing.omnitak.mobile.data.video.VideoProtocol

/**
 * Where the live drone camera stream is coming from.
 *
 *  - [None]: no video. PIP not rendered.
 *  - [Rtsp]: RTSP URL the operator configured. Decoded by Media3
 *    ExoPlayer + RtspMediaSource, RTP-over-TCP forced (see
 *    UasVideoPip for the rationale).
 *  - [RawH264Udp]: raw H264 Annex B NAL units delivered as UDP
 *    datagrams to [port] on any local interface. Compatible with
 *    RubyFPV "Video Forward To USB Device: Raw (H264)" and any
 *    similar long-range FPV ground station that doesn't expose RTSP.
 *    Decoded by MediaCodec directly.
 *  - [MpegTsUdp]: H264 elementary stream wrapped in MPEG-TS, delivered
 *    as UDP datagrams to [port]. This is what ATAK UAS Tool consumes
 *    and what RubyFPV operators produce via the canonical gstreamer
 *    pipeline (h264parse → mpegtsmux → udpsink). Decoded by Media3
 *    ExoPlayer + UdpDataSource + TsExtractor.
 */
sealed class VideoSource {
    object None : VideoSource()
    data class Rtsp(val url: String) : VideoSource()
    data class RawH264Udp(val port: Int) : VideoSource()
    data class MpegTsUdp(val port: Int) : VideoSource()

    val isActive: Boolean get() = this !is None

    companion object {
        fun rtsp(url: String): VideoSource =
            if (url.isBlank()) None else Rtsp(url.trim())

        fun rawH264Udp(port: Int): VideoSource =
            if (port in 1..65535) RawH264Udp(port) else None

        fun mpegTsUdp(port: Int): VideoSource =
            if (port in 1..65535) MpegTsUdp(port) else None
    }
}

/**
 * The drone camera as a [VideoFeed], so the UAS PiP plays through the same
 * shared player (and health badge) as every other feed. Credentials typed
 * into the RTSP URL are split out like for manual feeds. Null for [VideoSource.None].
 */
fun VideoSource.toVideoFeed(alias: String = "UAS video"): VideoFeed? = when (this) {
    is VideoSource.None -> null
    is VideoSource.Rtsp -> {
        val (clean, user, pass) = VideoFeedUrls.splitCredentials(url)
        VideoFeed(
            id = "uas:rtsp", alias = alias, url = clean, protocol = VideoProtocol.RTSP,
            username = user, password = pass, origin = VideoFeedOrigin.UAS,
        )
    }
    is VideoSource.RawH264Udp -> VideoFeed(
        id = "uas:raw:$port", alias = alias, url = "udp://@:$port",
        protocol = VideoProtocol.UDP_RAW_H264, origin = VideoFeedOrigin.UAS,
    )
    is VideoSource.MpegTsUdp -> VideoFeed(
        id = "uas:ts:$port", alias = alias, url = "udp://@:$port",
        protocol = VideoProtocol.UDP_MPEGTS, origin = VideoFeedOrigin.UAS,
    )
}
