package soy.engindearing.omnitak.mobile.data.video

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pure string helpers for video feed URLs: protocol detection, validation,
 * and — above all — keeping credentials and access tokens out of the UI,
 * the logs, the disk and CoT.
 *
 * Deliberately string-based rather than `java.net.URI` / `android.net.Uri`:
 * operators paste RTSP URLs whose passwords contain unescaped `@`, `#` or
 * `:` (which `URI` rejects), and these helpers must run in JVM unit tests.
 */
object VideoFeedUrls {

    /**
     * Query parameters treated as secrets. OpenTAKServer's HLS links carry
     * `jwt=…`, MediaMTX accepts `token=…`, and DJI Fly-style RTMP URLs
     * carry `user=…&pass=…`.
     */
    val SENSITIVE_PARAMS: Set<String> = setOf(
        "jwt", "token", "access_token", "auth", "key", "api_key", "apikey",
        "secret", "session", "sessionid", "sig", "signature", "x-amz-signature",
        "pass", "password", "pwd", "user", "username",
    )

    private const val MASK = "***"

    private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1")

    /** Best-effort protocol from the URL scheme. Null when unknown. */
    fun detectProtocol(url: String): VideoProtocol? {
        val scheme = scheme(url) ?: return null
        return when (scheme) {
            "rtsp", "rtsps", "rtspt" -> VideoProtocol.RTSP
            "rtmp", "rtmps" -> VideoProtocol.RTMP
            "udp" -> VideoProtocol.UDP_MPEGTS
            "http", "https" -> VideoProtocol.HLS
            else -> null
        }
    }

    /**
     * Human-readable problem with [url] for [protocol], or null when it is
     * playable as configured.
     */
    fun validate(url: String, protocol: VideoProtocol): String? {
        val u = url.trim()
        if (u.isEmpty()) return "URL is empty"
        if (u.any { it.isWhitespace() }) return "URL contains spaces"
        val scheme = scheme(u) ?: return "URL needs a scheme, e.g. rtsp://"
        return when (protocol) {
            VideoProtocol.RTSP ->
                if (scheme !in setOf("rtsp", "rtsps", "rtspt")) "RTSP URLs start with rtsp://"
                else if (host(u).isNullOrBlank()) "URL has no host" else null
            VideoProtocol.HLS ->
                if (scheme !in setOf("http", "https")) "HLS URLs start with https://"
                else if (host(u).isNullOrBlank()) "URL has no host"
                // The app's network security config only allows plain HTTP
                // to loopback; say so here instead of failing at playback.
                else if (scheme == "http" && host(u)?.lowercase() !in LOOPBACK) {
                    "Plain http:// is blocked by the app — use https:// (OpenTAKServer: https://<server>/hls/<path>/index.m3u8) or RTSP"
                } else null
            VideoProtocol.RTMP ->
                "RTMP is not played directly. Use the RTSP address the server re-serves it on" +
                    (rtmpPlaybackHint(u)?.let { ": ${redact(it)}" } ?: ".")
            VideoProtocol.UDP_MPEGTS, VideoProtocol.UDP_RAW_H264 ->
                if (scheme != "udp") "UDP sources look like udp://@:5600"
                else if (udpPort(u) == null) "UDP URL needs a port between 1 and 65535" else null
        }
    }

    /** Lower-cased scheme, or null. */
    fun scheme(url: String): String? {
        val i = url.indexOf("://")
        if (i <= 0) return null
        val s = url.substring(0, i)
        if (!s.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        return s.lowercase()
    }

    /**
     * Split `scheme://user:pass@host…` into the clean URL and decoded
     * credentials. URLs without user-info come back unchanged with nulls.
     */
    fun splitCredentials(url: String): Triple<String, String?, String?> {
        val parts = Parts.of(url) ?: return Triple(url, null, null)
        // `udp://@:5600` — an empty user-info is the "any interface" marker.
        val info = parts.userInfo?.takeIf { it.isNotEmpty() } ?: return Triple(url, null, null)
        val colon = info.indexOf(':')
        val user = decode(if (colon >= 0) info.substring(0, colon) else info).ifBlank { null }
        val pass = if (colon >= 0) decode(info.substring(colon + 1)).ifEmpty { null } else null
        return Triple(parts.copy(userInfo = null).toString(), user, pass)
    }

    /** [url] with any user-info removed. */
    fun stripCredentials(url: String): String = splitCredentials(url).first

    /**
     * Display-safe form: user-info becomes `***@`, sensitive query values
     * become `***`. Use this everywhere a feed URL reaches the screen or a
     * log line.
     */
    fun redact(url: String): String {
        val parts = Parts.of(url) ?: return maskQuery(url)
        val masked = parts.copy(
            userInfo = parts.userInfo?.let { if (it.isEmpty()) it else MASK },
            query = parts.query?.let { maskQueryString(it) },
            fragment = parts.fragment?.let { MASK },
        )
        return masked.toString()
    }

    /**
     * Form safe to hand to other people (CoT `__video`, team markers): no
     * user-info, and sensitive query parameters dropped entirely — the
     * receiver needs their own credentials.
     */
    fun shareable(url: String): String {
        val parts = Parts.of(url) ?: return url
        val q = parts.query?.split('&')
            ?.filter { it.isNotEmpty() && paramName(it) !in SENSITIVE_PARAMS }
            ?.joinToString("&")
            ?.ifEmpty { null }
        return parts.copy(userInfo = parts.userInfo?.takeIf { it.isEmpty() }, query = q, fragment = null).toString()
    }

    /**
     * Split sensitive query parameters off [url]: returns the URL without
     * them and the removed `k=v&k2=v2` (null when none). Used to keep a
     * pasted `?jwt=…` out of the plain DataStore JSON and out of the
     * editor's URL box; [joinQuery] puts it back for playback.
     */
    fun splitSecretQuery(url: String): Pair<String, String?> {
        val parts = Parts.of(url) ?: return url to null
        val pairs = parts.query?.split('&')?.filter { it.isNotEmpty() } ?: return url to null
        val (secret, plain) = pairs.partition { paramName(it) in SENSITIVE_PARAMS }
        if (secret.isEmpty()) return url to null
        val clean = parts.copy(query = plain.joinToString("&").ifEmpty { null }).toString()
        return clean to secret.joinToString("&")
    }

    /** Append [extraQuery] (from [splitSecretQuery]) to [url]'s query. */
    fun joinQuery(url: String, extraQuery: String?): String {
        if (extraQuery.isNullOrEmpty()) return url
        val parts = Parts.of(url) ?: return url
        val q = listOfNotNull(parts.query?.ifEmpty { null }, extraQuery).joinToString("&")
        return parts.copy(query = q).toString()
    }

    /** True when [url] carries user-info or a sensitive query parameter. */
    fun carriesSecrets(url: String): Boolean {
        val parts = Parts.of(url) ?: return false
        if (!parts.userInfo.isNullOrEmpty()) return true
        return parts.query?.split('&')?.any { paramName(it) in SENSITIVE_PARAMS } == true
    }

    /**
     * RTSP URL with percent-encoded credentials inserted (Media3's RTSP
     * client reads Basic/Digest credentials from the URI user-info).
     * Returns [url] unchanged when [username] is blank.
     */
    fun withCredentials(url: String, username: String?, password: String?): String {
        if (username.isNullOrBlank()) return url
        val parts = Parts.of(url) ?: return url
        val info = encode(username) + (password?.let { ":" + encode(it) } ?: "")
        return parts.copy(userInfo = info).toString()
    }

    /** Host part (no port, no brackets), or null. */
    fun host(url: String): String? {
        val parts = Parts.of(url) ?: return null
        return parts.hostPort.let { hp ->
            if (hp.startsWith("[")) hp.substringAfter('[').substringBefore(']')
            else hp.substringBefore(':')
        }.ifBlank { null }
    }

    /** Explicit port, or null when the URL relies on the scheme default. */
    fun port(url: String): Int? {
        val parts = Parts.of(url) ?: return null
        val hp = parts.hostPort
        val afterHost = if (hp.startsWith("[")) hp.substringAfter(']', "") else hp
        val p = afterHost.substringAfter(':', "")
        return p.toIntOrNull()
    }

    /** Local UDP port from `udp://@:5600`, `udp://0.0.0.0:5600` or `udp://:5600`. */
    fun udpPort(url: String): Int? {
        if (scheme(url) != "udp") return null
        val rest = url.substringAfter("://").substringBefore('/').substringBefore('?')
        val p = rest.substringAfterLast(':', "").toIntOrNull() ?: return null
        return p.takeIf { it in 1..65535 }
    }

    /**
     * Multicast / source host of a UDP URL (`udp://239.1.1.1:5600`), or
     * null for "any local interface" (`@`, empty, `0.0.0.0`).
     */
    fun udpHost(url: String): String? {
        if (scheme(url) != "udp") return null
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?')
        val host = authority.substringBeforeLast(':', "").removePrefix("@").trim('[', ']')
        return host.takeUnless { it.isEmpty() || it == "0.0.0.0" || it == "::" }
    }

    /**
     * MediaMTX (and so OpenTAKServer) re-serves every RTMP publish as RTSP
     * on port 8554 under the same path. `rtmp://192.168.50.1:1935/drone?user=…`
     * → `rtsp://192.168.50.1:8554/drone`. Null when [url] is not RTMP.
     */
    fun rtmpPlaybackHint(url: String, rtspPort: Int = 8554): String? {
        val s = scheme(url) ?: return null
        if (s != "rtmp" && s != "rtmps") return null
        val parts = Parts.of(url) ?: return null
        val host = host(url) ?: return null
        val hostOut = if (host.contains(':')) "[$host]" else host
        return "rtsp://$hostOut:$rtspPort${parts.path}"
    }

    /**
     * OpenTAKServer's `/Marti/api/video` builds its HLS links from the
     * request's own origin, so a client calling the Marti API on 8443 gets
     * `https://host:8443/hls/<path>/index.m3u8?jwt=…`. Its stock nginx
     * config only proxies `/hls/` on 443 (8443 is Marti-only and demands a
     * client certificate), so that link 404s. Point such links at 443.
     * Anything else is returned unchanged.
     */
    fun fixOtsHlsLink(url: String, martiPort: Int = 8443): String {
        val parts = Parts.of(url) ?: return url
        if (parts.scheme.lowercase() != "https") return url
        if (port(url) != martiPort) return url
        if (!parts.path.startsWith("/hls/")) return url
        val host = host(url) ?: return url
        val hostOut = if (host.contains(':')) "[$host]" else host
        return parts.copy(hostPort = hostOut).toString()
    }

    // ------------------------------------------------------------------

    private fun paramName(pair: String): String =
        decode(pair.substringBefore('=')).lowercase()

    private fun maskQueryString(q: String): String =
        q.split('&').joinToString("&") { pair ->
            if (paramName(pair) in SENSITIVE_PARAMS && pair.contains('=')) {
                pair.substringBefore('=') + "=" + MASK
            } else pair
        }

    private fun maskQuery(url: String): String {
        val qi = url.indexOf('?')
        if (qi < 0) return url
        return url.substring(0, qi + 1) + maskQueryString(url.substring(qi + 1))
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * `scheme://[userInfo@]hostPort[path][?query][#fragment]`. User-info is
     * everything before the LAST `@` of the authority, so an unescaped `@`
     * inside a password still splits correctly.
     */
    private data class Parts(
        val scheme: String,
        val userInfo: String?,
        val hostPort: String,
        val path: String,
        val query: String?,
        val fragment: String?,
    ) {
        override fun toString(): String = buildString {
            append(scheme).append("://")
            if (userInfo != null) append(userInfo).append('@')
            append(hostPort).append(path)
            if (query != null) append('?').append(query)
            if (fragment != null) append('#').append(fragment)
        }

        companion object {
            fun of(url: String): Parts? {
                val si = url.indexOf("://")
                if (si <= 0) return null
                val scheme = url.substring(0, si)
                val rest = url.substring(si + 3)
                // The authority ends at the first '/', '?' or '#'. User-info
                // is up to the last '@' inside it. If a '@' instead sits in
                // the path (before any '?'), the password most likely held
                // an unescaped '/': treat everything up to that '@' as
                // user-info. Over-matching is the safe failure here — it
                // only means a literal '@' in a path must be written %40.
                val queryStart = rest.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
                val firstSlash = rest.indexOf('/').let { if (it < 0 || it > queryStart) queryStart else it }
                val authEnd = firstSlash
                var lastAt = if (authEnd == 0) -1 else rest.lastIndexOf('@', authEnd - 1)
                if (lastAt < 0 && authEnd > 0) {
                    val pathAt = rest.lastIndexOf('@', queryStart - 1)
                    if (pathAt > authEnd) lastAt = pathAt
                }
                val userInfo = if (lastAt >= 0) rest.substring(0, lastAt) else null
                val afterInfo = if (lastAt >= 0) rest.substring(lastAt + 1) else rest
                val endAuth = afterInfo.indexOfFirst { it == '/' || it == '?' || it == '#' }
                    .let { if (it < 0) afterInfo.length else it }
                val hostPort = afterInfo.substring(0, endAuth)
                var tail = afterInfo.substring(endAuth)
                var fragment: String? = null
                val hi = tail.indexOf('#')
                if (hi >= 0) { fragment = tail.substring(hi + 1); tail = tail.substring(0, hi) }
                var query: String? = null
                val qi = tail.indexOf('?')
                if (qi >= 0) { query = tail.substring(qi + 1); tail = tail.substring(0, qi) }
                return Parts(scheme, userInfo, hostPort, tail, query, fragment)
            }
        }
    }
}
