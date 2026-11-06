package soy.engindearing.omnitak.mobile.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MartiVideoParserTest {

    /** Shape returned by OpenTAKServer 1.7.x `GET /Marti/api/video`. */
    private val otsJson = """
        {"videoConnections":[
          {"active":true,"alias":"drone","thumbnail":"","classification":"",
           "feeds":[{"uuid":"0b5c…","active":true,"alias":"drone",
             "url":"https://192.0.2.10:8443/hls/drone/index.m3u8?jwt=eyJhbGciOi.payload.sig",
             "order":0,"macAddress":null,"roverPort":"-1","ignoreEmbeddedKLV":"False",
             "source":"https://192.0.2.10:8443/hls/drone/index.m3u8","networkTimeout":"10000",
             "bufferTime":"None","rtspReliable":"1","thumbnail":"","classification":"",
             "latitude":"","longitude":"","fov":"","heading":"","range":"","width":0,"height":0,"bitrate":0}],
           "uuid":"0b5c…"},
          {"active":true,"alias":"cam2","feeds":[{"uuid":"x","active":true,"alias":"cam2",
             "url":"https://192.0.2.10:8443/hls/cam2/index.m3u8?jwt=t2"}],"uuid":"y"}
        ]}
    """.trimIndent()

    @Test fun parsesOpenTakServerJson() {
        val feeds = MartiVideoParser.parseJson(otsJson, "srv1")
        assertEquals(2, feeds.size)
        val d = feeds[0]
        assertEquals("drone", d.alias)
        assertEquals(VideoProtocol.HLS, d.protocol)
        assertEquals(VideoFeedOrigin.SERVER, d.origin)
        assertEquals("srv1", d.serverId)
        // OTS's 8443 link is moved to 443, where nginx actually serves /hls/.
        assertEquals("https://192.0.2.10/hls/drone/index.m3u8?jwt=eyJhbGciOi.payload.sig", d.url)
        // Keyed by alias: OTS mints new uuids on every call.
        assertEquals("srv:srv1:drone", d.id)
        assertEquals(feeds.map { it.id }, MartiVideoParser.parseJson(otsJson, "srv1").map { it.id })
    }

    @Test fun skipsInactiveAndUnplayableFeeds() {
        val body = """
            {"videoConnections":[
              {"active":false,"alias":"off","feeds":[{"alias":"off","url":"rtsp://h/off"}]},
              {"active":true,"alias":"c","feeds":[
                {"active":false,"alias":"a","url":"rtsp://h/a"},
                {"active":true,"alias":"b","url":"srt://h:9000"},
                {"active":true,"alias":"ok","url":"rtsp://h:8554/ok"}]}
            ]}
        """.trimIndent()
        val feeds = MartiVideoParser.parseJson(body, "s")
        assertEquals(listOf("ok"), feeds.map { it.alias })
        assertEquals(VideoProtocol.RTSP, feeds[0].protocol)
    }

    @Test fun toleratesGarbageAndOtherEnvelopes() {
        assertTrue(MartiVideoParser.parseJson("", "s").isEmpty())
        assertTrue(MartiVideoParser.parseJson("<html>502</html>", "s").isEmpty())
        assertTrue(MartiVideoParser.parseJson("{\"videoConnections\":{}}", "s").isEmpty())
        val bare = """[{"alias":"x","feeds":[{"url":"rtsp://h/x"}]}]"""
        assertEquals(1, MartiVideoParser.parseJson(bare, "s").size)
    }

    /** Shape returned by `GET /Marti/vcm` (ATAK Video Connection Manager). */
    private val vcmXml = """
        <videoConnections>
          <feed>
            <protocol>rtsp</protocol><alias>drone</alias><uid>abc-123</uid>
            <address>192.0.2.10</address><port>8554</port><roverPort>-1</roverPort>
            <ignoreEmbeddedKLV>false</ignoreEmbeddedKLV><preferredMacAddress/>
            <preferredInterfaceAddress/><path>drone</path><buffer/>
            <timeout>10000</timeout><rtspReliable>1</rtspReliable>
          </feed>
          <feed>
            <protocol>rtsp</protocol><alias>full</alias>
            <address>rtsp://192.0.2.10:8554/full</address><port>8554</port><path>/full</path>
          </feed>
          <feed><protocol>rtsp</protocol><alias>noaddr</alias></feed>
        </videoConnections>
    """.trimIndent()

    @Test fun parsesVcmXml() {
        val feeds = MartiVideoParser.parseXml(vcmXml, "srv1")
        assertEquals(listOf("drone", "full"), feeds.map { it.alias })
        assertEquals("rtsp://192.0.2.10:8554/drone", feeds[0].url)
        assertEquals("rtsp://192.0.2.10:8554/full", feeds[1].url)
        assertTrue(feeds.all { it.origin == VideoFeedOrigin.SERVER && it.rtspOverTcp })
    }

    @Test fun xmlSkipsInactiveAndMapsRawToUdp() {
        val xml = """
            <videoConnections>
              <feed><active>false</active><protocol>rtsp</protocol><alias>off</alias><address>h</address></feed>
              <feed><protocol>raw</protocol><alias>mc</alias><address>239.10.0.1</address><port>5600</port></feed>
            </videoConnections>
        """.trimIndent()
        val feeds = MartiVideoParser.parseXml(xml, "s")
        assertEquals(listOf("mc"), feeds.map { it.alias })
        assertEquals("udp://239.10.0.1:5600", feeds[0].url)
        assertEquals(VideoProtocol.UDP_MPEGTS, feeds[0].protocol)
    }

    @Test fun xmlGarbageIsEmpty() {
        assertTrue(MartiVideoParser.parseXml("not xml <<", "s").isEmpty())
        assertTrue(MartiVideoParser.parseXml("", "s").isEmpty())
    }
}
