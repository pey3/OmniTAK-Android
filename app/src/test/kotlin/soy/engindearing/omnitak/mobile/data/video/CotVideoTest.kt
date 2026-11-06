package soy.engindearing.omnitak.mobile.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTParser
import soy.engindearing.omnitak.mobile.domain.CotBuilders

class CotVideoTest {

    private fun event(detail: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="cam-1" type="b-i-v" time="2026-11-06T10:00:00Z"
               start="2026-11-06T10:00:00Z" stale="2026-11-07T10:00:00Z" how="h-g-i-g-o">
          <point lat="46.5" lon="6.6" hae="400" ce="10" le="10"/>
          <detail><contact callsign="Gate cam"/>$detail</detail>
        </event>
    """.trimIndent()

    @Test fun atakConnectionEntryWithBareHost() {
        val ev = CoTParser.parse(
            event(
                """<__video uid="v1"><ConnectionEntry uid="v1" alias="Gate" protocol="rtsp"
                   address="192.168.50.1" port="8554" path="/gate" networkTimeout="12000"
                   bufferTime="-1" roverPort="-1" rtspReliable="1" ignoreEmbeddedKLV="false"/></__video>""",
            ),
        )
        assertNotNull(ev)
        assertEquals("rtsp://192.168.50.1:8554/gate", ev!!.videoUrl)
        assertEquals("Gate", ev.videoAlias)
    }

    @Test fun urlAttributeOnly() {
        val ev = CoTParser.parse(event("""<__video url="rtsp://cam.local/live"/>"""))
        assertEquals("rtsp://cam.local/live", ev!!.videoUrl)
        assertNull(ev.videoAlias)
    }

    @Test fun legacyOmnitakVideoElement() {
        val ev = CoTParser.parse(
            event("""<video><ConnectionEntry uid="d-VID" protocol="rtsp" address="rtsp://10.0.0.5:554/cam"/></video>"""),
        )
        assertEquals("rtsp://10.0.0.5:554/cam", ev!!.videoUrl)
    }

    @Test fun pathWithoutLeadingSlashAndNoPort() {
        assertEquals(
            "rtsp://h/live",
            CotVideo.resolveUrl(null, mapOf("protocol" to "rtsp", "address" to "h", "path" to "live", "port" to "0")),
        )
    }

    @Test fun peerCredentialsAreDroppedOnReceive() {
        val ev = CoTParser.parse(event("""<__video url="rtsp://admin:hunter2@10.0.0.9/live?token=t"/>"""))
        assertEquals("rtsp://10.0.0.9/live", ev!!.videoUrl)
    }

    @Test fun noVideoDetail() {
        val ev = CoTParser.parse(event(""))
        assertNull(ev!!.videoUrl)
    }

    @Test fun connectionEntryOutsideVideoIsIgnored() {
        val ev = CoTParser.parse(event("""<other><ConnectionEntry address="rtsp://evil/x"/></other>"""))
        assertNull(ev!!.videoUrl)
    }

    @Test fun detailXmlStripsSecretsAndRoundTrips() {
        val detail = CotVideo.detailXml("v1", "Drone & co", "rtsp://user:pw@192.168.50.1:8554/drone?token=abc")
        assertFalse(detail.contains("pw"))
        assertFalse(detail.contains("abc"))
        assertFalse(detail.contains("user"))
        assertTrue(detail.contains("Drone &amp; co"))
        val ev = CoTParser.parse(event(detail))
        assertEquals("rtsp://192.168.50.1:8554/drone", ev!!.videoUrl)
        assertEquals("Drone & co", ev.videoAlias)
    }

    @Test fun builtFeedMarkerParses() {
        val xml = CotVideo.buildFeedMarker(
            uid = "video-1", alias = "Pi cam",
            url = "https://192.0.2.10/hls/drone/index.m3u8?jwt=SECRET",
            lat = 46.5, lon = 6.6, hae = 0.0,
            timeIso = "2026-11-06T10:00:00.000Z", staleIso = "2026-11-07T10:00:00.000Z",
            senderUid = "ANDROID-1",
        )
        assertFalse(xml.contains("SECRET"))
        val ev = CoTParser.parse(xml)!!
        assertEquals(CotVideo.VIDEO_MARKER_TYPE, ev.type)
        assertEquals("Pi cam", ev.callsign)
        assertEquals("https://192.0.2.10:443/hls/drone/index.m3u8", ev.videoUrl)
    }

    @Test fun uasPliCarriesAtakVideoWithoutCredentials() {
        val xml = CotBuilders.buildUasPliEvent(
            uid = "uas-1", callsign = "Mini2", latDeg = 46.5, lonDeg = 6.6, haeMeters = 500.0,
            videoUri = "rtsp://pilot:hunter2@192.168.50.1:8554/drone",
        )
        assertTrue(xml.contains("<__video"))
        assertFalse(xml.contains("hunter2"))
        assertEquals("rtsp://192.168.50.1:8554/drone", CoTParser.parse(xml)!!.videoUrl)
    }

    @Test fun rebuildKeepsVideo() {
        val ev = CoTParser.parse(event("""<__video url="rtsp://cam.local/live"/>"""))!!
        val again = CoTParser.parse(CotBuilders.rebuildEvent(ev, emptyList()))!!
        assertEquals("rtsp://cam.local:554/live", again.videoUrl)
    }
}
