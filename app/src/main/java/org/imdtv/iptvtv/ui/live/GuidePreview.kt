package org.imdtv.iptvtv.ui.live

import androidx.compose.ui.res.stringResource
import org.imdtv.iptvtv.R

import androidx.compose.ui.graphics.drawscope.clipPath
import org.imdtv.iptvtv.ui.theme.LocalGlassBackdrop
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import org.imdtv.iptvtv.ui.theme.LocalTheme
import org.imdtv.iptvtv.ui.theme.ThemeColors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.BlendMode
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import org.imdtv.iptvtv.data.settings.LogoKind
import org.imdtv.iptvtv.data.settings.HeaderSize
import org.imdtv.iptvtv.ui.ChannelLogo
import kotlinx.coroutines.flow.first
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.repo.GuideProgramme
import org.imdtv.iptvtv.ui.clockTime
import org.imdtv.iptvtv.ui.duration

/** Draws the guide background as it appears at [origin] of a [screen]-sized backdrop, into a bitmap of [size]. */
internal fun backgroundBitmap(colors: ThemeColors, density: androidx.compose.ui.unit.Density, size: Size, origin: Offset, screenSize: Size): ImageBitmap {
    val screen = if (screenSize.width > 0f && screenSize.height > 0f) screenSize else size // not measured yet on the first frame
    val img = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
    CanvasDrawScope().draw(density, androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), size) {
        translate(-origin.x, -origin.y) {
            drawRect(Brush.linearGradient(colors.bg, end = Offset(screen.width, screen.height)), size = screen)
            drawRect(Brush.radialGradient(listOf(Color(0x5978A0FF), Color.Transparent), center = Offset(screen.width * .85f, screen.height * .1f), radius = screen.width * .68f), size = screen)
        }
    }
    return img
}

/** FADE blends the picture into the background (Glass); NONE is a plain box (Plus); RIM a glass edge (Liquid Glass). */
enum class PreviewEdge { FADE, NONE, RIM }

@Composable
fun Preview(vm: LiveViewModel, visible: Boolean, screen: Size, modifier: Modifier, edge: PreviewEdge = PreviewEdge.FADE,
            /** The LIVE badge's distance from the top (Glass's full-corner preview moves it below the nav). */ badgeTop: androidx.compose.ui.unit.Dp = 30.dp) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val showBadge = vm.settings.collectAsState().value.showLiveIndicator
    val colors = LocalTheme.current.colors
    Box(modifier.size(530.dp, 300.dp).onGloballyPositioned { origin = it.positionInRoot() }) {
        // SurfaceView (PlayerView default): video goes to a hardware overlay, so playing it doesn't redraw the UI.
        if (visible) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT // the video surface isn't clipped to the box: zoom would spill a 4:3 picture outside it
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    vm.player.attach(this)
                } },
                onRelease = { vm.player.detach(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Nothing playing: the highlighted programme's picture instead of a blank box; playback replaces it.
        // Under the fade/rim below, so the header text beside it stays readable.
        val playing = visible && vm.previewingId.collectAsState().value != null
        if (!playing) PreviewArt(vm, Modifier.fillMaxSize())
        // Fade the picture into the background (mockup: left and bottom edges) by drawing the matching patch of
        // background over it with a transparency gradient. Static, so it's rasterised once.
        // RIM: the video surface can't be clipped, so the backdrop is painted over the four corners outside the
        // rounded frame (one cached bitmap), then the glass edge. Celestial Glass (no backdrop) paints its gradient there.
        val backdrop = LocalGlassBackdrop.current
        if (edge == PreviewEdge.RIM) Box(Modifier.fillMaxSize().drawWithCache {
            val bg = if (backdrop == null) backgroundBitmap(colors, this, size, origin, screen) else null
            val r = 16.dp.toPx()
            val frame = androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, r, r)) }
            val corners = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(corners), size) {
                clipPath(frame, androidx.compose.ui.graphics.ClipOp.Difference) {
                    if (bg != null) drawImage(bg) else translate(-origin.x, -origin.y) { drawImage(backdrop!!.sharp) }
                }
            }
            onDrawBehind { drawImage(corners) }
        }.border(1.dp, Brush.linearGradient(listOf(Color(0xB3FFFFFF), Color(0x26FFFFFF))), RoundedCornerShape(16.dp)))
        if (edge == PreviewEdge.FADE) Box(Modifier.fillMaxSize().drawWithCache {
            val bg = backgroundBitmap(colors, this, size, origin, screen)
            val left = ImageBitmap(size.width.toInt(), size.height.toInt())
            CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(left), size) {
                drawImage(bg)
                drawRect(Brush.horizontalGradient(0f to Color.Black, .45f to Color.Transparent), blendMode = BlendMode.DstIn)
            }
            val bottom = ImageBitmap(size.width.toInt(), size.height.toInt())
            CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(bottom), size) {
                drawImage(bg)
                drawRect(Brush.verticalGradient(.6f to Color.Transparent, 1f to Color.Black), blendMode = BlendMode.DstIn)
            }
            onDrawBehind { drawImage(left); drawImage(bottom) }
        })
        // Off: not composed at all, so nothing is left in its place.
        if (showBadge) Row(
            Modifier.align(Alignment.TopEnd).padding(top = badgeTop, end = 45.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0x59000000)).padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFFF3B3B)))
            Text(stringResource(R.string.live_badge_live), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

@Composable
fun Hero(ch: ChannelEntity?, name: String, progs: List<GuideProgramme>, now: Long, logoSource: List<LogoKind>, badges: List<String>, modifier: Modifier,
         size: HeaderSize = HeaderSize.LARGE, /** The highlighted programme's time (a later one after Right). */ at: Long = now,
         /** Header channel logo / Header title size (Appearance › Live TV): scale those two only. */ logoScale: Float = 1f, titleScale: Float = 1f,
         /** The height the header has above the "◀ category" line: big sizes shrink to fit it, the description loses lines. */
         maxHeight: Dp = Dp.Infinity) {
    if (ch == null) return
    val cur = progs.firstOrNull { it.start <= at && at < it.stop }
    val later = cur != null && cur.start > now
    val aired = cur != null && cur.stop <= now // catch-up: a past programme highlighted
    // One stacked layout at every Header size (logo, title, meta line, description, progress bar), as Large;
    // Medium and Small scale the block down (HeroBlock.of) to fit their height.
    val b = HeroBlock.of(size)
    val plan = planHero(b, logoScale, titleScale, hasProgress = cur != null && !later && !aired, hasDesc = cur?.desc != null, maxHeight.value)
    Column(modifier.heightIn(max = maxHeight)) {
        Box(Modifier.size((b.logoH * 60 / 43 * plan.logo).dp, (b.logoH * plan.logo).dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF)).testTag("heroLogo"),
            contentAlignment = Alignment.Center) {
            ChannelLogo(ch, logoSource, Modifier.fillMaxSize().padding((b.logoH / 43 * 4 * plan.logo).dp))
        }
        // Under the logo, same left edge; at most two lines (when they fit), then "…".
        Text(cur?.title ?: name, fontSize = (b.titleSp * plan.title).sp, color = Color.White, maxLines = plan.titleLines,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = b.gapTitle.dp, bottom = b.gapMeta.dp).testTag("heroTitle"))
        Row(Modifier.testTag("heroMeta"), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (cur != null) {
                Text(duration(cur.stop - cur.start).let { dur ->
                    val at = clockTime(cur.start)
                    if (later) stringResource(R.string.live_hero_starts_at, dur, at) else if (aired) stringResource(R.string.live_hero_aired_at, dur, at) else stringResource(R.string.live_hero_started_at, dur, at)
                } +
                    (if (aired && org.imdtv.iptvtv.ui.dayDate(cur.start) != org.imdtv.iptvtv.ui.dayDate(now)) " · ${org.imdtv.iptvtv.ui.dayDate(cur.start)}" else "") + if (badges.isNotEmpty()) " ·" else "",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
            } else {
                // One line: a long name must not wrap "No guide information" down over the timeline.
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Text(stringResource(R.string.live_no_guide_info), fontSize = 13.sp, color = LocalTheme.current.colors.muted, maxLines = 1, softWrap = false)
            }
            badges.forEach { badge ->
                Text(badge, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.border(1.dp, Color(0xB3FFFFFF), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
        // Weighted, so it's measured last: it gets the lines the logo, title and progress bar leave (up to 3).
        cur?.desc?.let {
            FittedLines(it, 3, b.gapDesc.dp, if (maxHeight != Dp.Infinity) Modifier.weight(1f, fill = false) else Modifier) // unbounded: a weight would get no room
        }
        if (plan.progress) {
            val f = ((now - cur!!.start).toFloat() / (cur.stop - cur.start)).coerceIn(0f, 1f)
            Box(Modifier.padding(top = 9.dp).size(210.dp, 2.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x24FFFFFF)).testTag("heroProgress")) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Color.White))
            }
        }
    }
}

/** The description: at most [maxLines], fewer when the space left is shorter, ending in "…"; nothing when not one fits. */
@Composable
private fun FittedLines(text: String, maxLines: Int, top: Dp, modifier: Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val lines = if (constraints.hasBoundedHeight) ((maxHeight - top) / 19.dp).toInt().coerceAtMost(maxLines) else maxLines
        if (lines > 0) Text(text, fontSize = 14.sp, lineHeight = 19.sp, color = Color(0xFFEAF0FF), maxLines = lines,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = top).testTag("heroDesc"))
    }
}
