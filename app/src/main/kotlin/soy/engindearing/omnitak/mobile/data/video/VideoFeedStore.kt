package soy.engindearing.omnitak.mobile.data.video

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.videoFeedDataStore by preferencesDataStore(name = "video_feeds")

/**
 * Per-feed password storage keyed by [VideoFeed.id]. Same split as the TAK
 * server list ([soy.engindearing.omnitak.mobile.data.CredentialStore]): the
 * feed list JSON in DataStore never carries a password.
 */
interface FeedCredentialStore {
    /** False when the Keystore-backed store could not be created. */
    val available: Boolean
    fun password(feedId: String): String?
    /** Sensitive query parameters (`jwt=…&token=…`) split off the feed URL. */
    fun secretQuery(feedId: String): String?
    /** Null values delete the corresponding entry. */
    fun store(feedId: String, password: String?, secretQuery: String?)
    /** Drop entries for feeds no longer in [liveFeedIds]. */
    fun prune(liveFeedIds: Set<String>)
}

/**
 * [EncryptedSharedPreferences] (AES256-GCM values under a Keystore master
 * key) in its own file, so feed secrets never share a namespace with TAK
 * server credentials.
 *
 * Unlike [soy.engindearing.omnitak.mobile.data.SecureCredentialStore] there
 * is NO plaintext fallback when the Keystore is unavailable: a feed password
 * is then simply not persisted (the editor says so) — re-typing a camera
 * password beats leaving it readable on disk.
 */
class SecureFeedCredentialStore(context: Context) : FeedCredentialStore {

    // Lazy: building the Keystore master key is slow, and the first read
    // happens on the DataStore collector (background), not on the main thread.
    private val prefs: SharedPreferences? by lazy { open(context) }

    private fun open(context: Context): SharedPreferences? = runCatching {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure {
        Log.e(TAG, "Keystore unavailable — feed passwords will not be persisted", it)
    }.getOrNull()

    override val available: Boolean get() = prefs != null

    override fun password(feedId: String): String? = prefs?.getString(PW_PREFIX + feedId, null)

    override fun secretQuery(feedId: String): String? = prefs?.getString(Q_PREFIX + feedId, null)

    override fun store(feedId: String, password: String?, secretQuery: String?) {
        val p = prefs ?: return
        p.edit().apply {
            if (password != null) putString(PW_PREFIX + feedId, password) else remove(PW_PREFIX + feedId)
            if (secretQuery != null) putString(Q_PREFIX + feedId, secretQuery) else remove(Q_PREFIX + feedId)
        }.apply()
    }

    override fun prune(liveFeedIds: Set<String>) {
        val p = prefs ?: return
        val stale = p.all.keys.filter { key ->
            val id = when {
                key.startsWith(PW_PREFIX) -> key.removePrefix(PW_PREFIX)
                key.startsWith(Q_PREFIX) -> key.removePrefix(Q_PREFIX)
                else -> return@filter false
            }
            id !in liveFeedIds
        }
        if (stale.isEmpty()) return
        p.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    private companion object {
        const val TAG = "SecureFeedCredStore"
        const val FILE_NAME = "video_feed_credentials"
        const val PW_PREFIX = "pw."
        const val Q_PREFIX = "q."
    }
}

/** Persistence contract for manual video feeds; faked in tests. */
interface VideoFeedStoreApi {
    val feeds: Flow<List<VideoFeed>>
    suspend fun save(list: List<VideoFeed>)
}

/**
 * Manual video feeds as one JSON blob in Preferences DataStore (the
 * [soy.engindearing.omnitak.mobile.data.TAKServerStore] pattern), with
 * passwords split out into a [FeedCredentialStore]. Only
 * [VideoFeedOrigin.MANUAL] feeds are written: server feeds are re-fetched
 * (their URLs embed short-lived tokens) and CoT feeds live with their marker.
 */
class VideoFeedStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val credentials: FeedCredentialStore,
) : VideoFeedStoreApi {

    constructor(context: Context) : this(
        context.applicationContext.videoFeedDataStore,
        SecureFeedCredentialStore(context),
    )

    private val key = stringPreferencesKey("feeds_json")

    /** False when the Keystore is unusable: feed passwords then live in memory only. */
    val passwordsPersisted: Boolean get() = credentials.available

    override val feeds: Flow<List<VideoFeed>> = dataStore.data.map { prefs ->
        decodeFeeds(prefs[key]).let { rehydrateFeedSecrets(it, credentials) }
    }

    override suspend fun save(list: List<VideoFeed>) {
        val raw = encodeFeeds(stripFeedSecrets(list, credentials))
        dataStore.edit { it[key] = raw }
    }

    /** Insert or replace by id. */
    suspend fun upsert(feed: VideoFeed) {
        val current = feeds.first()
        val next = if (current.any { it.id == feed.id }) current.map { if (it.id == feed.id) feed else it }
        else current + feed
        save(next)
    }

    suspend fun delete(id: String) {
        save(feeds.first().filterNot { it.id == id })
    }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encodeFeeds(list: List<VideoFeed>): String = json.encodeToString(list)

        fun decodeFeeds(raw: String?): List<VideoFeed> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<VideoFeed>>(raw) }.getOrElse { emptyList() }
        }
    }
}

/**
 * Keep MANUAL feeds only and move every secret into [credentials]: the
 * password, plus sensitive query parameters (`?jwt=…`, `?token=…`) split
 * off the URL. Credentials pasted into the URL go to the username/password
 * fields first, so no secret survives in the serialised `url`. Without a
 * Keystore the secrets are dropped, never written in clear.
 */
internal fun stripFeedSecrets(list: List<VideoFeed>, credentials: FeedCredentialStore): List<VideoFeed> {
    val manual = list.filter { it.origin == VideoFeedOrigin.MANUAL }.map(::normalizeCredentials)
    val split = manual.map { feed ->
        val (url, secretQuery) = VideoFeedUrls.splitSecretQuery(feed.url)
        Triple(feed, url, secretQuery)
    }
    if (credentials.available) {
        split.forEach { (feed, _, q) -> credentials.store(feed.id, feed.password, q) }
        credentials.prune(manual.map { it.id }.toSet())
    }
    return split.map { (feed, url, _) -> feed.copy(url = url, password = null) }
}

/** Reverse of [stripFeedSecrets]. */
internal fun rehydrateFeedSecrets(list: List<VideoFeed>, credentials: FeedCredentialStore): List<VideoFeed> {
    if (!credentials.available) return list
    return list.map { feed ->
        val pw = credentials.password(feed.id)
        val q = credentials.secretQuery(feed.id)
        if (pw == null && q == null) feed
        else feed.copy(password = pw ?: feed.password, url = VideoFeedUrls.joinQuery(feed.url, q))
    }
}

/** Move `user:pass@` from the URL into the dedicated fields (explicit fields win). */
internal fun normalizeCredentials(feed: VideoFeed): VideoFeed {
    val (clean, user, pass) = VideoFeedUrls.splitCredentials(feed.url)
    if (user == null && pass == null) return feed
    return feed.copy(
        url = clean,
        username = feed.username?.takeIf { it.isNotBlank() } ?: user,
        password = feed.password ?: pass,
    )
}
