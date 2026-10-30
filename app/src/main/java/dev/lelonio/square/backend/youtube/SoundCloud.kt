package dev.lelonio.square.backend.youtube

import android.content.Context
import android.util.Base64
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * FRAME MUSIC: SoundCloud beside YouTube Music.
 *
 * Search and streams come through NewPipeExtractor's SoundCloud service, the
 * same library the YouTube side already uses. A SoundCloud track lives under the
 * YouTube backend's own scheme, so every part of the app that asks "whose is
 * this?" hands it to the YouTube player; the player then asks [streamUrl] for
 * its HLS playlist.
 *
 * SoundCloud has no account here, so what the listener keeps is kept on the
 * phone: a playlist named SoundCloud in the library, which liking a SoundCloud
 * song or adding it to any playlist puts it in.
 */
object SoundCloud {
    /** "ytmusic:sctrack:" + the track's page URL, base64url without padding. */
    const val TRACK_PREFIX = "ytmusic:sctrack:"

    /** The local playlist's id, as the YouTube backend sees a playlist id. */
    const val LIBRARY_ID = "FRAMEMUSIC_SOUNDCLOUD"
    const val LIBRARY_URI = "${YouTubeBackend.PLAYLIST_PREFIX}$LIBRARY_ID"

    private const val PREFS = "frame_soundcloud"
    private const val KEY_SAVED = "saved"
    private const val ARTWORK = "https://a-v2.sndcdn.com/assets/images/sc-icons/ios-a62dfc8fe7.png"

    private val json = Json { ignoreUnknownKeys = true }
    private val streams = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun owns(uri: String) = uri.startsWith(TRACK_PREFIX)

    fun uriOf(pageUrl: String): String = TRACK_PREFIX + Base64.encodeToString(
        pageUrl.toByteArray(),
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )

    fun pageUrlOf(uri: String): String = String(
        Base64.decode(uri.removePrefix(TRACK_PREFIX).substringBefore('#'), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
    )

    /** Tracks for [query]; empty when SoundCloud does not answer. Blocking. */
    fun search(query: String, limit: Int = 12): List<CatalogTrack> = runCatching {
        YouTubeStreams.ensureNewPipe()
        val extractor = ServiceList.SoundCloud.getSearchExtractor(query, listOf("tracks"), "")
        extractor.fetchPage()
        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .take(limit)
            .map { item ->
                CatalogTrack(
                    uri = uriOf(item.url),
                    name = item.name,
                    artist = item.uploaderName.orEmpty(),
                    album = "SoundCloud",
                    durationMs = item.duration.coerceAtLeast(0) * 1000L,
                    artworkUrl = item.thumbnails.maxByOrNull { it.width }?.url,
                )
            }
    }.onFailure { android.util.Log.w("FrameSoundCloud", "search failed: $it") }
        .getOrDefault(emptyList())

    /**
     * The HLS playlist to play [uri] from, MP3 before Opus. Kept for half an
     * hour: the URLs are signed and do not last much longer. Blocking.
     */
    fun streamUrl(uri: String): String {
        val key = uri.substringBefore('#')
        val now = android.os.SystemClock.elapsedRealtime()
        streams[key]?.takeIf { now - it.first < STREAM_TTL_MS }?.let { return it.second }
        YouTubeStreams.ensureNewPipe()
        val info = StreamInfo.getInfo(ServiceList.SoundCloud, pageUrlOf(key))
        val hls = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.HLS }
        val chosen = hls.firstOrNull { it.format?.name.equals("MP3", ignoreCase = true) }
            ?: hls.firstOrNull()
            ?: error("SoundCloud has no stream for this track")
        streams[key] = now to chosen.content
        return chosen.content
    }

    /** The songs kept from SoundCloud, newest first. */
    fun saved(): List<CatalogTrack> = runCatching {
        val raw = prefs?.getString(KEY_SAVED, null) ?: return emptyList()
        json.decodeFromString(ListSerializer(CatalogTrack.serializer()), raw)
    }.getOrDefault(emptyList())

    fun isSaved(uri: String) = saved().any { it.uri == uri }

    @Synchronized
    fun save(track: CatalogTrack) {
        val kept = listOf(track) + saved().filterNot { it.uri == track.uri }
        write(kept)
    }

    @Synchronized
    fun remove(uri: String) = write(saved().filterNot { it.uri == uri })

    private fun write(tracks: List<CatalogTrack>) {
        prefs?.edit()?.putString(KEY_SAVED, json.encodeToString(ListSerializer(CatalogTrack.serializer()), tracks))?.apply()
    }

    /** The library's SoundCloud playlist, or null while nothing is kept. */
    fun libraryPlaylist(): CatalogPlaylist? = saved().takeIf { it.isNotEmpty() }?.let { tracks ->
        CatalogPlaylist(
            uri = LIBRARY_URI,
            name = "SoundCloud",
            artworkUrl = tracks.firstOrNull()?.artworkUrl ?: ARTWORK,
        )
    }

    /** The list a writable-playlist picker offers, always, so there is somewhere to add to. */
    fun pickerPlaylist(): CatalogPlaylist = CatalogPlaylist(
        uri = LIBRARY_URI,
        name = "SoundCloud",
        artworkUrl = saved().firstOrNull()?.artworkUrl ?: ARTWORK,
    )

    /** Tracks seen in search, so a save by URI alone still has a title and a cover. */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, CatalogTrack>()

    fun remember(tracks: List<CatalogTrack>) = tracks.forEach { seen[it.uri] = it }

    fun known(uri: String): CatalogTrack? = seen[uri] ?: saved().firstOrNull { it.uri == uri }

    private const val STREAM_TTL_MS = 30 * 60 * 1000L
}
