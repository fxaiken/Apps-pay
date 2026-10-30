package dev.lelonio.square.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.MicrophoneStage
import com.adamglin.phosphoricons.regular.MusicNote
import com.adamglin.phosphoricons.regular.Playlist
import com.adamglin.phosphoricons.regular.PlusCircle
import com.adamglin.phosphoricons.regular.VinylRecord
import com.adamglin.phosphoricons.regular.Waveform
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim

/** One of the rows under the pinned covers: what it lists, and how to get there. */
internal class LibraryShortcut(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/**
 * The top of Apple Music's library: up to six pinned or recent records in a
 * grid of three, then a row for each kind of thing the library holds, each
 * with its glyph in the accent and a chevron, then the "Recently added"
 * heading the list below carries on from.
 */
@Composable
internal fun AppleLibraryTop(
    covers: List<CatalogPlaylist>,
    /** Which of [covers] are artists, drawn round as Apple draws them. */
    isArtist: (CatalogPlaylist) -> Boolean,
    onOpen: (CatalogPlaylist) -> Unit,
    shortcuts: List<LibraryShortcut>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        covers.take(6).chunked(3).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { item ->
                    val round = isArtist(item)
                    Column(
                        Modifier
                            .weight(1f)
                            .pressable({ onOpen(item) }, pressedScale = 0.96f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .then(if (round) Modifier.clip(CircleShape) else Modifier),
                        ) {
                            dev.lelonio.square.ui.components.Artwork(
                                url = item.artworkUrl,
                                title = item.name,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                corner = if (round) 0.dp else 8.dp,
                                decodeSize = 140.dp,
                            )
                        }
                        Text(
                            item.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        shortcuts.forEach { shortcut ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(shortcut.onClick, pressedScale = 0.99f)
                    .padding(vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    shortcut.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    shortcut.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                )
                Icon(
                    PhosphorIcons.Regular.CaretRight,
                    contentDescription = null,
                    tint = InkDim,
                    modifier = Modifier.size(16.dp),
                )
            }
            Box(
                Modifier
                    .padding(start = 40.dp)
                    .fillMaxWidth()
                    .height(0.6.dp)
                    .background(InkDim.copy(alpha = 0.25f)),
            )
        }

        Text(
            stringResource(R.string.recently_added),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Ink,
            modifier = Modifier.padding(top = 24.dp, bottom = 6.dp),
        )
    }
}

/** The glyphs for the shortcut rows, kept beside the composable that draws them. */
internal object LibraryGlyphs {
    val playlists get() = PhosphorIcons.Regular.Playlist
    val artists get() = PhosphorIcons.Regular.MicrophoneStage
    val albums get() = PhosphorIcons.Regular.VinylRecord
    val songs get() = PhosphorIcons.Regular.MusicNote
    val podcasts get() = PhosphorIcons.Regular.Waveform
    val add get() = PhosphorIcons.Regular.PlusCircle
}
