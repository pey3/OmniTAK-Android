package soy.engindearing.omnitak.mobile.data.video

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Parses the TAK "video connection" API into [VideoFeed]s.
 *
 *  - **JSON** — `GET /Marti/api/video` (TAK Server 4.x+, OpenTAKServer):
 *    `{"videoConnections":[{"uuid","alias","active","feeds":[{"uuid","alias","url","active",…}]}]}`.
 *    OpenTAKServer fills `url` with an HLS link carrying a per-user `jwt`
 *    and regenerates the `uuid`s on every call, so feeds are keyed by
 *    alias, not uuid.
 *  - **XML** — `GET /Marti/vcm` (the legacy ATAK Video Connection Manager
 *    sync): `<videoConnections><feed><protocol/><alias/><uid/><address/>
 *    <port/><path/>…</feed></videoConnections>`, where `address` is a host
 *    (or, for some servers, a full URL).
 *
 * Both are tolerant: unknown fields are ignored, a malformed document
 * yields an empty list, never an exception.
 */
object MartiVideoParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Stable id for a server feed: server + alias (OTS uuids are per-request). */
    fun serverFeedId(serverId: String, alias: String): String = "srv:$serverId:$alias"

    fun parseJson(body: String, serverId: String): List<VideoFeed> {
        val root: JsonElement = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return emptyList()
        val conns: JsonArray = when (root) {
            is JsonObject -> (root["videoConnections"] ?: root["data"]) as? JsonArray
            is JsonArray -> root
            else -> null
        } ?: return emptyList()

        val out = LinkedHashMap<String, VideoFeed>()
        for (c in conns) {
            val conn = c as? JsonObject ?: continue
            if (conn.bool("active") == false) continue
            val connAlias = conn.str("alias")
            val feeds = conn["feeds"] as? JsonArray ?: continue
            for (f in feeds) {
                val feed = f as? JsonObject ?: continue
                if (feed.bool("active") == false) continue
                val rawUrl = feed.str("url") ?: feed.str("source") ?: continue
                val url = VideoFeedUrls.fixOtsHlsLink(rawUrl.trim())
                val protocol = VideoFeedUrls.detectProtocol(url) ?: continue
                val alias = feed.str("alias") ?: connAlias ?: VideoFeedUrls.redact(url)
                val id = serverFeedId(serverId, alias)
                out.putIfAbsent(
                    id,
                    VideoFeed(
                        id = id,
                        alias = alias,
                        url = url,
                        protocol = protocol,
                        rtspOverTcp = feed.str("rtspReliable")?.let { it != "0" } ?: true,
                        lowLatency = true,
                        origin = VideoFeedOrigin.SERVER,
                        serverId = serverId,
                    ),
                )
            }
        }
        return out.values.toList()
    }

    fun parseXml(body: String, serverId: String): List<VideoFeed> = runCatching {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            .newPullParser()
        parser.setInput(StringReader(body))
        val out = LinkedHashMap<String, VideoFeed>()
        var fields: MutableMap<String, String>? = null
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    if (name == "feed") {
                        fields = HashMap()
                    } else if (fields != null) {
                        val text = runCatching { parser.nextText() }.getOrNull()
                        if (!text.isNullOrBlank()) fields[name] = text.trim()
                        // nextText() leaves us on the END_TAG; fall through to next().
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "feed") {
                    fields?.let { feedFromXmlFields(it, serverId) }?.let { out.putIfAbsent(it.id, it) }
                    fields = null
                }
            }
            ev = parser.next()
        }
        out.values.toList()
    }.getOrDefault(emptyList())

    /** Assemble a URL from the `<feed>` fields. Internal for tests. */
    internal fun feedFromXmlFields(f: Map<String, String>, serverId: String): VideoFeed? {
        if (f["active"]?.equals("false", ignoreCase = true) == true) return null
        // ATAK's "raw" / "udp" protocols are MPEG-TS over UDP (often multicast).
        val protocolName = (f["protocol"] ?: "rtsp").lowercase().let { if (it == "raw") "udp" else it }
        val address = f["address"] ?: return null
        val url = if (address.contains("://")) {
            address
        } else {
            val port = f["port"]?.toIntOrNull()?.takeIf { it > 0 }
            val path = f["path"].orEmpty().let { if (it.isEmpty() || it.startsWith("/")) it else "/$it" }
            val host = if (address.contains(':') && !address.startsWith("[")) "[$address]" else address
            "$protocolName://$host${port?.let { ":$it" } ?: ""}$path"
        }
        val protocol = VideoFeedUrls.detectProtocol(url) ?: return null
        val alias = f["alias"] ?: f["path"]?.trimStart('/') ?: VideoFeedUrls.redact(url)
        val id = serverFeedId(serverId, alias)
        return VideoFeed(
            id = id,
            alias = alias,
            url = url,
            protocol = protocol,
            rtspOverTcp = f["rtspReliable"]?.let { it != "0" } ?: true,
            origin = VideoFeedOrigin.SERVER,
            serverId = serverId,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
}
