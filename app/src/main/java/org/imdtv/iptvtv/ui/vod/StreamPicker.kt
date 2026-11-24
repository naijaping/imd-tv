package org.imdtv.iptvtv.ui.vod

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.imdtv.iptvtv.R
import org.imdtv.iptvtv.data.addons.AddonStream
import org.imdtv.iptvtv.data.addons.StreamBlock
import org.imdtv.iptvtv.data.addons.StreamGroup
import org.imdtv.iptvtv.ui.MenuFrame
import org.imdtv.iptvtv.ui.MenuItem
import org.imdtv.iptvtv.ui.theme.LocalTheme

/**
 * The streams the add-ons give for a title, grouped by add-on in priority order and filling in as each answers.
 * OK plays one; a stream that can't play here (torrent, YouTube, web page) shows greyed with the reason. Back closes it.
 */
@Composable
fun StreamPicker(vm: VodViewModel) {
    val choice by vm.streamChoice.collectAsState()
    val ch = choice ?: return
    MenuFrame(ch.title, onDismiss = vm::cancelStreams) { first ->
        val all = ch.groups.flatMap { it.streams }
        // The first stream takes focus once one exists (the frame's own first request ran before any had arrived).
        LaunchedEffect(all.isNotEmpty()) { if (all.isNotEmpty()) { withFrameNanos { }; runCatching { first.requestFocus() } } }
        if (ch.auto) Text(stringResource(R.string.vod_streams_auto), fontSize = 12.sp, color = LocalTheme.current.colors.muted, modifier = Modifier.padding(bottom = 6.dp))
        val loading = !ch.asked || ch.groups.any { it.state == StreamGroup.State.LOADING }
        var firstTaken = false
        ch.groups.forEach { g ->
            Text(g.addon + "  ·  " + groupState(g), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LocalTheme.current.colors.muted,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            // Playable first; the add-on's own order within each.
            g.streams.sortedBy { it.playUrl == null }.forEach { s ->
                val m = if (!firstTaken) { firstTaken = true; Modifier.focusRequester(first) } else Modifier
                MenuItem(label(s), m, enabled = s.playUrl != null) { vm.pickStream(s) }
            }
        }
        if (!ch.asked) Text(stringResource(R.string.vod_streams_asking), fontSize = 14.sp, color = LocalTheme.current.colors.muted)
        else if (ch.groups.isEmpty()) Text(stringResource(R.string.vod_streams_no_addons), fontSize = 14.sp, color = LocalTheme.current.colors.muted)
        else if (!loading && all.none { it.playUrl != null }) Text(stringResource(R.string.vod_streams_none), fontSize = 14.sp, color = LocalTheme.current.colors.muted,
            modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun groupState(g: StreamGroup): String = when (g.state) {
    StreamGroup.State.LOADING -> stringResource(R.string.vod_streams_group_loading)
    StreamGroup.State.TIMEOUT -> stringResource(R.string.vod_streams_group_timeout)
    StreamGroup.State.FAILED -> stringResource(R.string.vod_streams_group_failed)
    StreamGroup.State.DONE -> if (g.streams.isEmpty()) stringResource(R.string.vod_streams_group_empty) else g.streams.size.toString()
}

/** "4K · Torrentio" then the add-on's description lines (quality, size, source), and why it can't play if so. */
@Composable
private fun label(s: AddonStream): String {
    val why = when (s.blocked) {
        null -> null
        StreamBlock.TORRENT -> stringResource(R.string.vod_stream_needs_torrent)
        StreamBlock.YOUTUBE -> stringResource(R.string.vod_stream_youtube)
        StreamBlock.EXTERNAL -> stringResource(R.string.vod_stream_external)
        StreamBlock.OTHER -> stringResource(R.string.vod_stream_unsupported)
    }
    return listOfNotNull(s.name.takeIf { it.isNotBlank() }, s.description?.lines()?.filter { it.isNotBlank() }?.take(3)?.joinToString("\n"), why?.let { "($it)" })
        .joinToString("\n")
}
