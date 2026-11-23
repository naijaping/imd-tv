package org.imdtv.iptvtv.ui.live

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import org.imdtv.iptvtv.data.catalogue.displayName
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.logos.shownLogos
import org.imdtv.iptvtv.ui.ChannelLogo
import org.imdtv.iptvtv.ui.theme.LocalTheme

/** How long focus must rest on a channel before its picture loads: a fast scroll starts no downloads. */
internal const val ART_DEBOUNCE_MS = 275L

/** What the empty preview shows: the highlighted programme's picture, else the channel's logo, else names. */
internal data class PreviewArtKey(val channel: ChannelEntity, val title: String?, val image: String?)

/**
 * The empty preview (nothing playing): the highlighted programme's image from the guide, else the channel logo
 * centred on a blurred, dimmed copy of itself, else the channel name and programme title. Waits [ART_DEBOUNCE_MS]
 * after focus moves; Coil cancels a request whose image leaves the screen and caches the rest (memory and disk).
 * The content sits right of centre and high, clear of the preview's faded left and bottom edges where the header text is.
 */
@Composable
internal fun PreviewArt(vm: LiveViewModel, modifier: Modifier = Modifier) {
    val focused by vm.focused.collectAsState()
    val channels by vm.channels.collectAsState()
    val programmes by vm.programmes.collectAsState()
    val now by vm.now.collectAsState()
    val cursor by vm.cursor.collectAsState()
    val inPast by vm.inPast.collectAsState()
    val ch = channels.find { it.id == focused?.id } ?: focused // the list has the renamed copy
    val at = Timeline.cursorAt(cursor, now, inPast)
    val prog = ch?.let { c -> programmes[c.id]?.firstOrNull { it.start <= at && at < it.stop } }
    val target = ch?.let { PreviewArtKey(it, prog?.title, prog?.icon) }
    var shown by remember { mutableStateOf(target) }
    // A new target restarts the wait (the old one is cancelled): only where focus rests gets loaded.
    LaunchedEffect(target) { if (target != shown) { delay(ART_DEBOUNCE_MS); shown = target } }
    Crossfade(shown, modifier, animationSpec = tween(350), label = "previewArt") { key -> if (key != null) ArtFor(vm, key) }
}

@Composable
private fun ArtFor(vm: LiveViewModel, key: PreviewArtKey) {
    val logoSource by vm.logoSource.collectAsState()
    val strip by vm.strip.collectAsState()
    val context = LocalContext.current
    var imageFailed by remember(key.image) { mutableStateOf(false) }
    val logos = remember(key.channel, logoSource) { shownLogos(key.channel, logoSource) }
    val name = key.channel.displayName(strip)
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(LocalTheme.current.colors.bg))) {
        when {
            key.image != null && !imageFailed -> {
                // Until it loads (and if it fails) the logo below shows; the picture fades in over it.
                if (logos.isNotEmpty()) LogoArt(key.channel, logoSource, name, key.title)
                AsyncImage(ImageRequest.Builder(context).data(key.image).crossfade(300).build(), null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop, onError = { imageFailed = true })
            }
            logos.isNotEmpty() -> LogoArt(key.channel, logoSource, name, key.title)
            else -> Names(name, key.title, Modifier.align(ART_CENTRE).fillMaxWidth(.6f), 22, 15)
        }
        // Dims the picture towards the header text (left) and the guide (bottom), under the preview's own fade.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0x99000000), .5f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.55f to Color.Transparent, 1f to Color(0x99000000))))
    }
}

/** Where the art's own content sits: right of centre and a little high, the preview's clearest part. */
private val ART_CENTRE = BiasAlignment(.3f, -.25f)

/** The channel logo on a blurred (Android 12+; dimmed only before) enlargement of itself; the names while it loads or if it can't. */
@Composable
private fun LogoArt(ch: ChannelEntity, logoSource: List<org.imdtv.iptvtv.data.settings.LogoKind>, name: String, title: String?) {
    Box(Modifier.fillMaxSize()) {
        if (Build.VERSION.SDK_INT >= 31) {
            ChannelLogo(ch, logoSource, Modifier.fillMaxSize().blur(40.dp).alpha(.35f), fallback = {})
        }
        Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        ChannelLogo(ch, logoSource, Modifier.align(ART_CENTRE).size(160.dp, 104.dp), fallback = { Names(name, title, Modifier.fillMaxSize(), 16, 12) })
    }
}

/** No picture and no logo (or not yet): the channel's name over the programme's title, centred. */
@Composable
private fun Names(channel: String, title: String?, modifier: Modifier, nameSp: Int, titleSp: Int) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(channel, fontSize = nameSp.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        title?.let { Text(it, fontSize = titleSp.sp, color = Color(0xCCFFFFFF), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
    }
}
