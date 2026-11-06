package soy.engindearing.omnitak.mobile.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFeedUrlsTest {

    @Test fun detectsProtocolFromScheme() {
        assertEquals(VideoProtocol.RTSP, VideoFeedUrls.detectProtocol("rtsp://192.168.50.1:8554/drone"))
        assertEquals(VideoProtocol.RTSP, VideoFeedUrls.detectProtocol("RTSPS://cam/x"))
        assertEquals(VideoProtocol.HLS, VideoFeedUrls.detectProtocol("https://192.0.2.10/hls/drone/index.m3u8"))
        assertEquals(VideoProtocol.RTMP, VideoFeedUrls.detectProtocol("rtmp://192.168.50.1:1935/drone"))
        assertEquals(VideoProtocol.UDP_MPEGTS, VideoFeedUrls.detectProtocol("udp://@:5600"))
        assertNull(VideoFeedUrls.detectProtocol("192.168.50.1:8554/drone"))
        assertNull(VideoFeedUrls.detectProtocol("srt://host:9000"))
    }

    @Test fun splitsCredentialsOutOfUrl() {
        val (clean, user, pass) = VideoFeedUrls.splitCredentials("rtsp://alice:s3cr%40t@192.168.50.1:8554/test")
        assertEquals("rtsp://192.168.50.1:8554/test", clean)
        assertEquals("alice", user)
        assertEquals("s3cr@t", pass)
    }

    @Test fun splitHandlesUnescapedAtInPassword() {
        val (clean, user, pass) = VideoFeedUrls.splitCredentials("rtsp://bob:p@ss@cam.local/live")
        assertEquals("rtsp://cam.local/live", clean)
        assertEquals("bob", user)
        assertEquals("p@ss", pass)
    }

    @Test fun splitWithoutCredentialsIsIdentity() {
        val url = "rtsp://cam.local:554/stream?x=1"
        assertEquals(Triple(url, null, null), VideoFeedUrls.splitCredentials(url))
    }

    @Test fun passwordWithSlashIsStillTreatedAsSecret() {
        val url = "rtsp://u:pa/ss@cam.local:554/live"
        val (clean, user, pass) = VideoFeedUrls.splitCredentials(url)
        assertEquals("rtsp://cam.local:554/live", clean)
        assertEquals("u", user)
        assertEquals("pa/ss", pass)
        assertFalse(VideoFeedUrls.redact(url).contains("pa/ss"))
        assertFalse(VideoFeedUrls.shareable(url).contains("ss"))
        assertTrue(VideoFeedUrls.carriesSecrets(url))
    }

    @Test fun encodedAtInPathIsNotUserInfo() {
        val url = "https://host/hls/a%40b/index.m3u8"
        assertEquals(Triple(url, null, null), VideoFeedUrls.splitCredentials(url))
        assertEquals("host", VideoFeedUrls.host(url))
    }

    @Test fun atSignInQueryIsNotUserInfo() {
        val url = "https://host?e=a@b/c"
        assertEquals("host", VideoFeedUrls.host(url))
        assertEquals(Triple(url, null, null), VideoFeedUrls.splitCredentials(url))
    }

    @Test fun udpAnyInterfaceMarkerIsNotACredential() {
        assertFalse(VideoFeedUrls.carriesSecrets("udp://@:5600"))
        assertEquals("udp://@:5600", VideoFeedUrls.redact("udp://@:5600"))
        assertEquals("udp://@:5600", VideoFeedUrls.shareable("udp://@:5600"))
        assertNull(VideoFeedUrls.udpHost("udp://@:5600"))
        assertEquals("239.1.2.3", VideoFeedUrls.udpHost("udp://239.1.2.3:5600"))
        assertNull(VideoFeedUrls.udpHost("udp://0.0.0.0:5600"))
    }

    @Test fun secretQuerySplitAndJoin() {
        val (clean, q) = VideoFeedUrls.splitSecretQuery("https://h/hls/d/index.m3u8?a=1&jwt=T&api_key=K")
        assertEquals("https://h/hls/d/index.m3u8?a=1", clean)
        assertEquals("jwt=T&api_key=K", q)
        assertEquals("https://h/hls/d/index.m3u8?a=1&jwt=T&api_key=K", VideoFeedUrls.joinQuery(clean, q))
        assertEquals("https://h/x?jwt=T", VideoFeedUrls.joinQuery("https://h/x", "jwt=T"))
        assertEquals("rtsp://h/x" to null, VideoFeedUrls.splitSecretQuery("rtsp://h/x"))
    }

    @Test fun redactMasksFragment() {
        assertEquals("https://h/x#***", VideoFeedUrls.redact("https://h/x#access_token=abc"))
    }

    @Test fun redactMasksUserInfoAndTokens() {
        val r = VideoFeedUrls.redact("https://u:pw@192.0.2.10:443/hls/drone/index.m3u8?jwt=eyJabc.def&x=1")
        assertEquals("https://***@192.0.2.10:443/hls/drone/index.m3u8?jwt=***&x=1", r)
        assertFalse(r.contains("eyJ"))
        assertFalse(r.contains("pw"))
    }

    @Test fun redactMasksDjiStyleQueryCredentials() {
        val r = VideoFeedUrls.redact("rtmp://192.168.50.1:1935/drone?user=cobra&pass=hunter2")
        assertEquals("rtmp://192.168.50.1:1935/drone?user=***&pass=***", r)
    }

    @Test fun shareableDropsSecretsEntirely() {
        assertEquals(
            "https://192.0.2.10/hls/drone/index.m3u8?x=1",
            VideoFeedUrls.shareable("https://a:b@192.0.2.10/hls/drone/index.m3u8?jwt=T&x=1"),
        )
        assertEquals("rtsp://h:8554/p", VideoFeedUrls.shareable("rtsp://h:8554/p?token=abc"))
    }

    @Test fun carriesSecretsDetection() {
        assertTrue(VideoFeedUrls.carriesSecrets("rtsp://a:b@h/p"))
        assertTrue(VideoFeedUrls.carriesSecrets("https://h/p?JWT=x"))
        assertFalse(VideoFeedUrls.carriesSecrets("rtsp://h:8554/p?tcp=1"))
    }

    @Test fun withCredentialsPercentEncodes() {
        assertEquals(
            "rtsp://us%20er:p%40ss%3Aw@h:8554/p",
            VideoFeedUrls.withCredentials("rtsp://h:8554/p", "us er", "p@ss:w"),
        )
        assertEquals("rtsp://h/p", VideoFeedUrls.withCredentials("rtsp://h/p", null, "x"))
    }

    @Test fun credentialsRoundTrip() {
        val url = VideoFeedUrls.withCredentials("rtsp://h:8554/p", "user", "p@ss/w:rd")
        val (clean, u, p) = VideoFeedUrls.splitCredentials(url)
        assertEquals("rtsp://h:8554/p", clean)
        assertEquals("user", u)
        assertEquals("p@ss/w:rd", p)
    }

    @Test fun hostAndPort() {
        assertEquals("192.168.50.1", VideoFeedUrls.host("rtsp://192.168.50.1:8554/drone"))
        assertEquals(8554, VideoFeedUrls.port("rtsp://192.168.50.1:8554/drone"))
        assertNull(VideoFeedUrls.port("https://h/x"))
        assertEquals("fe80::1", VideoFeedUrls.host("rtsp://[fe80::1]:554/x"))
        assertEquals(554, VideoFeedUrls.port("rtsp://[fe80::1]:554/x"))
    }

    @Test fun udpPortForms() {
        assertEquals(5600, VideoFeedUrls.udpPort("udp://@:5600"))
        assertEquals(5000, VideoFeedUrls.udpPort("udp://0.0.0.0:5000"))
        assertEquals(5010, VideoFeedUrls.udpPort("udp://:5010"))
        assertNull(VideoFeedUrls.udpPort("udp://@:0"))
        assertNull(VideoFeedUrls.udpPort("udp://@:70000"))
        assertNull(VideoFeedUrls.udpPort("rtsp://h:5600"))
    }

    @Test fun rtmpHintPointsAtMediaMtxRtsp() {
        assertEquals(
            "rtsp://192.168.50.1:8554/drone",
            VideoFeedUrls.rtmpPlaybackHint("rtmp://192.168.50.1:1935/drone?user=u&pass=p"),
        )
        assertEquals("rtsp://h:8554/live/cam", VideoFeedUrls.rtmpPlaybackHint("rtmp://h/live/cam"))
        assertNull(VideoFeedUrls.rtmpPlaybackHint("rtsp://h/x"))
    }

    @Test fun otsHlsLinkMovesFrom8443To443() {
        assertEquals(
            "https://192.0.2.10/hls/drone/index.m3u8?jwt=T",
            VideoFeedUrls.fixOtsHlsLink("https://192.0.2.10:8443/hls/drone/index.m3u8?jwt=T"),
        )
        // Not an OTS HLS link: untouched.
        val other = "https://192.0.2.10:8443/Marti/api/video"
        assertEquals(other, VideoFeedUrls.fixOtsHlsLink(other))
        val already = "https://192.0.2.10/hls/drone/index.m3u8"
        assertEquals(already, VideoFeedUrls.fixOtsHlsLink(already))
    }

    @Test fun validation() {
        assertNull(VideoFeedUrls.validate("rtsp://h:8554/p", VideoProtocol.RTSP))
        assertNotNull(VideoFeedUrls.validate("", VideoProtocol.RTSP))
        assertNotNull(VideoFeedUrls.validate("https://h/x", VideoProtocol.RTSP))
        assertNotNull(VideoFeedUrls.validate("rtsp://h/x y", VideoProtocol.RTSP))
        assertNull(VideoFeedUrls.validate("https://h/hls/x/index.m3u8", VideoProtocol.HLS))
        assertNotNull(VideoFeedUrls.validate("http://192.168.50.1:8888/drone/index.m3u8", VideoProtocol.HLS))
        assertNull(VideoFeedUrls.validate("http://127.0.0.1:8888/drone/index.m3u8", VideoProtocol.HLS))
        assertNull(VideoFeedUrls.validate("udp://@:5600", VideoProtocol.UDP_RAW_H264))
        assertNotNull(VideoFeedUrls.validate("udp://@:abc", VideoProtocol.UDP_MPEGTS))
        // RTMP is never playable directly; the message must not leak credentials.
        val rtmp = VideoFeedUrls.validate("rtmp://h:1935/d?user=u&pass=hunter2", VideoProtocol.RTMP)
        assertNotNull(rtmp)
        assertFalse(rtmp!!.contains("hunter2"))
    }
}
