package soy.engindearing.omnitak.mobile.data.video

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VideoFeedStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After fun tearDown() = scope.cancel()

    private class FakeCreds(override val available: Boolean = true) : FeedCredentialStore {
        val map = mutableMapOf<String, String>()
        val queries = mutableMapOf<String, String>()
        override fun password(feedId: String) = map[feedId]
        override fun secretQuery(feedId: String) = queries[feedId]
        override fun store(feedId: String, password: String?, secretQuery: String?) {
            if (password == null) map.remove(feedId) else map[feedId] = password
            if (secretQuery == null) queries.remove(feedId) else queries[feedId] = secretQuery
        }
        override fun prune(liveFeedIds: Set<String>) {
            map.keys.retainAll(liveFeedIds)
            queries.keys.retainAll(liveFeedIds)
        }
    }

    private fun store(creds: FeedCredentialStore) = VideoFeedStore(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "video_feeds.preferences_pb") }),
        creds,
    )

    private val cam = VideoFeed(
        id = "f1", alias = "Gate", url = "rtsp://192.168.50.1:8554/gate",
        protocol = VideoProtocol.RTSP, username = "alice", password = "s3cret",
    )

    @Test fun passwordNeverReachesTheJson() {
        val creds = FakeCreds()
        val json = VideoFeedStore.encodeFeeds(stripFeedSecrets(listOf(cam), creds))
        assertFalse(json.contains("s3cret"))
        assertTrue(json.contains("alice"))
        assertEquals("s3cret", creds.map["f1"])
    }

    @Test fun roundTripRehydratesPassword() = runBlocking {
        val creds = FakeCreds()
        val s = store(creds)
        s.save(listOf(cam))
        val back = s.feeds.first()
        assertEquals(listOf(cam), back)
    }

    @Test fun credentialsInUrlMoveToFields() {
        val creds = FakeCreds()
        val pasted = cam.copy(url = "rtsp://bob:pw%21@192.168.50.1:8554/gate", username = null, password = null)
        val stored = stripFeedSecrets(listOf(pasted), creds).single()
        assertEquals("rtsp://192.168.50.1:8554/gate", stored.url)
        assertEquals("bob", stored.username)
        assertNull(stored.password)
        assertEquals("pw!", creds.map["f1"])
    }

    @Test fun onlyManualFeedsArePersisted() = runBlocking {
        val creds = FakeCreds()
        val s = store(creds)
        val server = cam.copy(id = "srv:x:drone", origin = VideoFeedOrigin.SERVER, url = "https://h/hls/d/index.m3u8?jwt=T")
        s.save(listOf(cam, server))
        assertEquals(listOf("f1"), s.feeds.first().map { it.id })
    }

    @Test fun deletePrunesSavedPassword() = runBlocking {
        val creds = FakeCreds()
        val s = store(creds)
        s.save(listOf(cam, cam.copy(id = "f2", password = "other")))
        s.delete("f1")
        assertEquals(listOf("f2"), s.feeds.first().map { it.id })
        assertFalse(creds.map.containsKey("f1"))
        assertEquals("other", creds.map["f2"])
    }

    @Test fun upsertReplacesById() = runBlocking {
        val s = store(FakeCreds())
        s.save(listOf(cam))
        s.upsert(cam.copy(alias = "Main gate"))
        s.upsert(cam.copy(id = "f3", alias = "New"))
        assertEquals(listOf("Main gate", "New"), s.feeds.first().map { it.alias })
    }

    @Test fun noKeystoreMeansNoPasswordOnDisk() = runBlocking {
        val creds = FakeCreds(available = false)
        val s = store(creds)
        s.save(listOf(cam))
        assertNull(s.feeds.first().single().password)
        assertFalse(s.passwordsPersisted)
        val raw = File(tmp.root, "video_feeds.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("s3cret"))
    }

    @Test fun tokenInQueryIsEncryptedNotInJson() = runBlocking {
        val creds = FakeCreds()
        val s = store(creds)
        val hls = cam.copy(id = "h1", url = "https://cam/hls/x/index.m3u8?jwt=SECRETTOKEN&a=1",
            protocol = VideoProtocol.HLS, username = null, password = null)
        val json = VideoFeedStore.encodeFeeds(stripFeedSecrets(listOf(hls), FakeCreds()))
        assertFalse(json.contains("SECRETTOKEN"))
        s.save(listOf(hls))
        val raw = File(tmp.root, "video_feeds.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("SECRETTOKEN"))
        assertEquals("jwt=SECRETTOKEN", creds.queries["h1"])
        assertEquals("https://cam/hls/x/index.m3u8?a=1&jwt=SECRETTOKEN", s.feeds.first().single().url)
    }

    @Test fun corruptJsonIsEmpty() {
        assertTrue(VideoFeedStore.decodeFeeds("{nope").isEmpty())
        assertTrue(VideoFeedStore.decodeFeeds(null).isEmpty())
    }
}
