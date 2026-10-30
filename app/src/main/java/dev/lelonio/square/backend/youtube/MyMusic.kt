package dev.lelonio.square.backend.youtube

import android.content.Context
import android.net.Uri
import android.util.Base64
import dev.lelonio.square.data.CatalogTrack

/**
 * FRAME MUSIC: songs the listener adds from their own files.
 *
 * Picked through the system's file picker, so no storage permission is asked
 * for: the picker hands back a lasting grant for each file. They are kept as a
 * playlist on the phone ("My music") and played by the YouTube player, which
 * already reads files as well as the network.
 */
object MyMusic {
    const val TRACK_PREFIX = "ytmusic:mine:"
    const val PLAYLIST_ID = "${LocalPlaylists.ID_PREFIX}MINE"

    fun owns(uri: String) = uri.startsWith(TRACK_PREFIX)

    fun contentUriOf(uri: String): Uri = Uri.parse(
        String(Base64.decode(uri.removePrefix(TRACK_PREFIX).substringBefore('#'), FLAGS)),
    )

    private fun uriOf(content: Uri) = TRACK_PREFIX + Base64.encodeToString(content.toString().toByteArray(), FLAGS)

    /** Adds [files] to the "My music" list, newest first. Blocking. */
    fun add(context: Context, files: List<Uri>, listName: String) {
        if (files.isEmpty()) return
        val resolver = context.contentResolver
        val added = files.mapNotNull { file ->
            runCatching {
                resolver.takePersistableUriPermission(file, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { describe(context, file) }.getOrNull()
        }
        val existing = LocalPlaylists.all().firstOrNull { it.id == PLAYLIST_ID }
        val tracks = (added + existing?.tracks.orEmpty()).distinctBy { it.uri }
        LocalPlaylists.save(
            LocalPlaylists.Stored(
                id = PLAYLIST_ID,
                name = listName,
                artworkUrl = existing?.artworkUrl,
                tracks = tracks,
            ),
        )
    }

    private fun describe(context: Context, file: Uri): CatalogTrack {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, file)
            val name = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: displayName(context, file)
            return CatalogTrack(
                uri = uriOf(file),
                name = name,
                artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun displayName(context: Context, file: Uri): String =
        context.contentResolver.query(file, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.substringBeforeLast('.')
            ?: file.lastPathSegment.orEmpty()

    private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
}
