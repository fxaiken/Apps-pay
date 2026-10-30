package dev.lelonio.square.backend.youtube

/**
 * FRAME MUSIC: the song and artist hiding in an upload's title.
 *
 * SoundCloud uploads and fan videos name themselves "Artist - Song (Official
 * Video) [HD] ft. Someone", with the uploader as the artist. Lyrics sites know
 * the song as "Song" by "Artist", so the title is taken apart before asking.
 */
object LyricsTitle {
    private val noise = Regex(
        """\s*[(\[]\s*(official|oficial|lyric|lyrics|letra|audio|áudio|video|vídeo|visuali[sz]er|hd|hq|4k|explicit|clean|prod\.?|remaster(ed)?|live|ao vivo)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val featuring = Regex("""\s+(ft\.?|feat\.?|featuring|part\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val dashes = Regex("""\s+[-–—|]\s+""")

    /** A cleaner (title, artist), or null when there is nothing to clean. */
    fun clean(title: String, artist: String): Pair<String, String>? {
        var song = noise.replace(title, "").trim()
        var by = artist
        val parts = dashes.split(song, limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            by = parts[0].trim()
            song = parts[1].trim()
        }
        song = featuring.replace(noise.replace(song, ""), "").trim().trim('"', '“', '”')
        by = featuring.replace(by, "").trim()
        if (song.isBlank()) return null
        return (song to by).takeIf { it != (title to artist) }
    }
}
