package soy.engindearing.omnitak.mobile.data.video

import soy.engindearing.omnitak.mobile.data.CotXml

/**
 * CoT video detail, both directions.
 *
 * ATAK / iTAK attach a feed to a marker as
 * ```
 * <__video uid="…" url="rtsp://host:8554/cam">
 *   <ConnectionEntry uid="…" alias="Cam 1" protocol="rtsp" address="host"
 *       port="8554" path="/cam" networkTimeout="12000" bufferTime="-1"
 *       roverPort="-1" rtspReliable="1" ignoreEmbeddedKLV="false"/>
 * </__video>
 * ```
 * where `ConnectionEntry.address` is normally a bare host, but some
 * producers put the full URL there, or send only `url`. OmniTAK before this
 * change emitted `<video><ConnectionEntry address="rtsp://…"/></video>`
 * (no underscores), which is also accepted on input.
 */
object CotVideo {

    /** Resolve a playable URL from the parsed attributes, or null. */
    fun resolveUrl(videoAttrs: Map<String, String>?, entryAttrs: Map<String, String>?): String? {
        entryAttrs?.let { e ->
            val address = e["address"]?.trim().orEmpty()
            if (address.contains("://")) return address
            if (address.isNotEmpty()) {
                // ATAK "raw" = MPEG-TS over UDP (usually multicast).
                val protocol = (e["protocol"]?.trim()?.lowercase()).takeUnless { it.isNullOrEmpty() }
                    ?.let { if (it == "raw") "udp" else it } ?: "rtsp"
                val port = e["port"]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
                val path = e["path"]?.trim().orEmpty().let { if (it.isEmpty() || it.startsWith("/")) it else "/$it" }
                val host = if (address.contains(':') && !address.startsWith("[")) "[$address]" else address
                return "$protocol://$host${port?.let { ":$it" } ?: ""}$path"
            }
        }
        return videoAttrs?.get("url")?.trim()?.takeIf { it.contains("://") }
    }

    /** Alias for the feed: ConnectionEntry alias, else null (callers use the callsign). */
    fun resolveAlias(entryAttrs: Map<String, String>?): String? =
        entryAttrs?.get("alias")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * ATAK-standard `__video` element for [url]. Credentials and access
     * tokens are removed first ([VideoFeedUrls.shareable]) — a password or
     * jwt must never ride in CoT, which every team member (and the server's
     * history) receives.
     */
    fun detailXml(uid: String, alias: String, url: String): String {
        val safe = VideoFeedUrls.shareable(url)
        val protocol = VideoFeedUrls.scheme(safe) ?: "rtsp"
        val host = VideoFeedUrls.host(safe).orEmpty()
        val port = VideoFeedUrls.port(safe) ?: defaultPort(protocol)
        val path = safe.substringAfter("://").let { rest ->
            val slash = rest.indexOf('/')
            if (slash < 0) "" else rest.substring(slash)
        }
        val e = CotXml::escape
        return buildString {
            append("<__video uid=\"").append(e(uid)).append("\" url=\"").append(e(safe)).append("\">")
            append("<ConnectionEntry uid=\"").append(e(uid)).append("\" alias=\"").append(e(alias))
            append("\" protocol=\"").append(e(protocol)).append("\" address=\"").append(e(host))
            append("\" port=\"").append(port).append("\" path=\"").append(e(path))
            append("\" networkTimeout=\"12000\" bufferTime=\"-1\" roverPort=\"-1\"")
            append(" rtspReliable=\"1\" ignoreEmbeddedKLV=\"false\"/>")
            append("</__video>")
        }
    }

    /**
     * A standalone video marker (`b-i-v`, the CoT "video" point type ATAK
     * uses for its Video Alias markers) at [lat]/[lon] carrying [url].
     */
    fun buildFeedMarker(
        uid: String,
        alias: String,
        url: String,
        lat: Double,
        lon: Double,
        hae: Double,
        timeIso: String,
        staleIso: String,
        senderUid: String?,
    ): String {
        val detail = buildString {
            append("<detail>")
            append("<contact callsign=\"").append(CotXml.escape(alias)).append("\"/>")
            append(detailXml(uid, alias, url))
            append("<remarks>").append(CotXml.escape("Video feed: $alias")).append("</remarks>")
            if (!senderUid.isNullOrBlank()) {
                append("<link uid=\"").append(CotXml.escape(senderUid))
                append("\" relation=\"p-p\" type=\"a-f-G-U-C\"/>")
            }
            append("<archive/>")
            append("</detail>")
        }
        return CotXml.buildEvent(
            uid = uid,
            type = VIDEO_MARKER_TYPE,
            how = "h-g-i-g-o",
            lat = lat, lon = lon, hae = hae,
            timeIso = timeIso,
            staleIso = staleIso,
            detailXml = detail,
        )
    }

    const val VIDEO_MARKER_TYPE = "b-i-v"

    private fun defaultPort(protocol: String): Int = when (protocol) {
        "rtsp" -> 554
        "rtsps" -> 322
        "https" -> 443
        "http" -> 80
        "rtmp" -> 1935
        else -> -1
    }
}
