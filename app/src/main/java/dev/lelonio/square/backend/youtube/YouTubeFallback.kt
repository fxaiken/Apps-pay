package dev.lelonio.square.backend.youtube

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * FRAME MUSIC: another copy of a song YouTube will not play.
 *
 * A song can be blocked in a country, age-gated, taken down or tied to an
 * account. The same song is almost always on YouTube more than once — the
 * artist's upload, a topic channel, a lyric video — so when the one asked for
 * cannot be opened, the player asks for the next best match instead of
 * stopping. Titles are remembered as tracks are listed, since by the time the
 * player resolves a song it only has the id.
 */
object YouTubeFallback {
    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun remember(videoId: String, title: String, artist: String) {
        if (videoId.isNotBlank() && title.isNotBlank()) names[videoId] = "$title $artist".trim()
    }

    /** Up to three other video ids for the same song, best first. Blocking. */
    fun alternatives(videoId: String): List<String> {
        val query = names[videoId] ?: return emptyList()
        return runCatching {
            YouTubeStreams.ensureNewPipe()
            val search = ServiceList.YouTube.getSearchExtractor(query, listOf("videos"), "")
            search.fetchPage()
            search.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { item -> item.url.substringAfter("v=", "").substringBefore('&').takeIf { it.isNotBlank() } }
                .filter { it != videoId }
                .distinct()
                .take(3)
        }.onFailure { android.util.Log.w("FrameFallback", "search failed: $it") }
            .getOrDefault(emptyList())
    }
}
