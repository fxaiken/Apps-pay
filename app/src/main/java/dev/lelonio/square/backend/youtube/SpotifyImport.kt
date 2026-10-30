package dev.lelonio.square.backend.youtube

import android.content.Context
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * FRAME MUSIC: a Spotify playlist or album, brought over by its link alone.
 *
 * Spotify's embed page is public and carries the list as JSON — titles and
 * artists, no account, no developer app. Each song is then looked up on
 * YouTube Music and the result kept on the phone as a playlist of its own.
 */
object SpotifyEmbed {
    class Page(val name: String, val coverUrl: String?, val tracks: List<Pair<String, String>>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Blocking. [kind] is "playlist" or "album". */
    fun read(kind: String, id: String): Page {
        val connection = java.net.URL("https://open.spotify.com/embed/$kind/$id").openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("User-Agent", BROWSER)
        connection.setRequestProperty("Accept-Language", "en")
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        val html = try {
            if (connection.responseCode !in 200..299) error("Spotify answered ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: error("This Spotify page could not be read")
        val root = json.parseToJsonElement(data)
        val entity = find(root) { it is JsonObject && it["trackList"] is JsonArray } as? JsonObject
            ?: error("This Spotify list is empty or private")
        val tracks = (entity["trackList"] as JsonArray).mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            val title = row.text("title") ?: return@mapNotNull null
            title to (row.text("subtitle") ?: "")
        }
        val cover = ((entity["coverArt"] as? JsonObject)?.get("sources") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.text("url") }
            ?.firstOrNull()
        return Page(
            name = entity.text("name") ?: entity.text("title") ?: "Spotify",
            coverUrl = cover,
            tracks = tracks,
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun find(element: JsonElement, match: (JsonElement) -> Boolean): JsonElement? {
        if (match(element)) return element
        return when (element) {
            is JsonObject -> element.values.firstNotNullOfOrNull { find(it, match) }
            is JsonArray -> element.firstNotNullOfOrNull { find(it, match) }
            else -> null
        }
    }

    private const val BROWSER =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
}

/** Playlists that live only on the phone: the ones brought over from Spotify. */
object LocalPlaylists {
    const val ID_PREFIX = "FRAMEMUSIC_IMPORT_"

    @Serializable
    data class Stored(
        val id: String,
        val name: String,
        val artworkUrl: String? = null,
        val tracks: List<CatalogTrack> = emptyList(),
    )

    private const val PREFS = "frame_local_playlists"
    private const val KEY = "lists"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun uriOf(id: String) = "${YouTubeBackend.PLAYLIST_PREFIX}$id"

    fun owns(playlistId: String) = playlistId.startsWith(ID_PREFIX)

    fun all(): List<Stored> = runCatching {
        val raw = prefs?.getString(KEY, null) ?: return emptyList()
        json.decodeFromString(ListSerializer(Stored.serializer()), raw)
    }.getOrDefault(emptyList())

    fun tracks(playlistId: String): List<CatalogTrack> = all().firstOrNull { it.id == playlistId }?.tracks.orEmpty()

    @Synchronized
    fun save(list: Stored) = write(listOf(list) + all().filterNot { it.id == list.id })

    @Synchronized
    fun delete(playlistId: String) = write(all().filterNot { it.id == playlistId })

    fun asPlaylists(): List<CatalogPlaylist> = all().map {
        CatalogPlaylist(uri = uriOf(it.id), name = it.name, artworkUrl = it.artworkUrl)
    }

    private fun write(lists: List<Stored>) {
        prefs?.edit()?.putString(KEY, json.encodeToString(ListSerializer(Stored.serializer()), lists))?.apply()
    }
}
