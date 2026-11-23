package org.imdtv.iptvtv.ui.live

import androidx.compose.ui.res.stringResource
import org.imdtv.iptvtv.R

import org.imdtv.iptvtv.ui.theme.glass
import org.imdtv.iptvtv.ui.theme.LocalGlassBackdrop
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import org.imdtv.iptvtv.ui.theme.LocalTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawWithCache
import androidx.tv.material3.Text
import org.imdtv.iptvtv.ui.Spinner
import org.imdtv.iptvtv.data.catalogue.displayName
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.settings.HeaderSize
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import coil.compose.AsyncImage
import org.imdtv.iptvtv.data.files.BrandLogo

@Composable
fun LiveGuideScreen(vm: LiveViewModel, nav: @Composable () -> Unit, onFullScreen: (ChannelEntity) -> Unit) {
    val state = rememberGuideState(vm)
    val rail by vm.rail.collectAsState()
    val entry by vm.entry.collectAsState()
    val updating by vm.guideUpdating.collectAsState()
    val loadingChannels by vm.listingLoading.collectAsState()
    val settings = vm.settings.collectAsState().value
    // Header size: everything below the nav moves up by [shift]; the grid gains rows of about the same height.
    val shift = org.imdtv.iptvtv.data.settings.headerShiftDp(settings.headerSize).dp

    var screen by remember { mutableStateOf(Size.Zero) }
    val colors = LocalTheme.current.colors // read here so a theme change re-rasterises the cached background
    val backdrop = LocalGlassBackdrop.current // Liquid Glass: same layout, glass panels over its backdrop
    val inset = if (backdrop != null) 12.dp else 0.dp
    // Liquid's fixed hero panel ends at 312dp - shift (plus its 6dp shadow): the "◀ category" line sits below that.
    val gridTop = (if (backdrop != null) 334.dp else 306.dp) - shift
    val rows = settings.guideRows // already counts the header size (AppSettings.withHeader)
    // Liquid's framed preview, also Glass's when Preview fade is off: 16:9, as tall as the hero panel.
    val boxed = backdrop != null || !settings.previewFade
    val boxH = 232.dp - shift
    Box(Modifier.fillMaxSize().onSizeChanged { screen = Size(it.width.toFloat(), it.height.toFloat()) }.drawWithCache {
        // The Chromecast GPU is the bottleneck: rasterise the full-screen gradients once, then just blit them.
        val img = backdrop?.sharp ?: backgroundBitmap(colors, this, size, Offset.Zero, size)
        onDrawBehind { drawImage(img) }
    }) {
        Preview(vm, visible = state.previewing, screen = screen,
            edge = if (boxed) PreviewEdge.RIM else PreviewEdge.FADE, // rounded like the rest of the glass themes
            // Fade: the preview reaches the top, where the shifted nav's clock now runs; the badge goes below it.
            badgeTop = if (boxed) 30.dp else 58.dp,
            // Boxed: a smaller framed preview clear of the description panel; top and height match the hero panel,
            // so the nav pill never overlaps it. Fade: full corner preview, shrunk with the header.
            modifier = Modifier.align(Alignment.TopEnd).then(
                if (boxed) Modifier.padding(top = 80.dp, end = 30.dp).size(boxH * 16 / 9, boxH)
                else Modifier.size((300.dp - shift) * 53 / 30, 300.dp - shift)))
        BrandAndNav(settings.brandLogoPath, settings.brandLogoScale, nav, Modifier.padding(start = 24.dp, top = 22.dp, end = 24.dp))
        // Medium/Small: the hero runs up to the preview, so the description fits beside the title.
        val previewW = if (boxed) boxH * 16 / 9 + 30.dp else (300.dp - shift) * 53 / 30
        // Celestial Glass with the faded preview: Sky Glass style, text across ~60% of the screen over the faded video.
        val sky = backdrop == null && !boxed
        GuideHero(vm, Modifier.padding(start = 24.dp, top = 80.dp)
            .then(if (sky) Modifier.width(575.dp) else if (settings.headerSize == HeaderSize.LARGE) Modifier.width(450.dp) else Modifier.padding(end = previewW + 24.dp).fillMaxWidth())
            // Liquid Glass: a fixed-size panel, so moving focus doesn't re-rasterise it for each description length.
            .then(if (backdrop != null) Modifier.height(232.dp - shift) else Modifier).glass().padding(inset), settings.headerSize,
            // Room above the "◀ category" line (Liquid: its panel less the padding): larger header sizes shrink to fit it.
            maxHeight = if (backdrop != null) 232.dp - shift - inset * 2 else gridTop - 18.dp - 80.dp - 4.dp,
            logoScale = settings.heroLogoScale, titleScale = settings.heroTitleScale)

        if (rail?.isEmpty() == true) {
            Text(stringResource(R.string.live_all_hidden), fontSize = 16.sp,
                color = Color.White, modifier = Modifier.padding(start = 24.dp, top = 380.dp))
        }
        Row(Modifier.padding(start = 24.dp, top = gridTop - 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "◀ ${entry?.titleText().orEmpty()}", fontSize = 11.sp, color = Color.White, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(colors.cellDim).padding(horizontal = 7.dp, vertical = 2.dp),
            )
            Text(stringResource(R.string.live_press_left), fontSize = 11.sp, color = colors.muted)
            if (loadingChannels || updating) {
                Spacer(Modifier.width(16.dp)); Spinner(size = 11.dp, color = colors.muted)
                Text(stringResource(if (loadingChannels) R.string.live_loading_channels else R.string.live_updating_guide), fontSize = 11.sp, color = colors.muted)
            }
        }

        // Glass and Liquid: the grid runs off the right edge and stops 12dp above the bottom, so the last row's border shows; GuideGrid splits it into 5–9 whole rows.
        val grid = (if (backdrop != null) LIQUID_GRID else GLASS_GRID).copy(gap = if (rows > 5) 2.dp else 4.dp)
        GuideGrid(vm, state, grid, onFullScreen, onLeftAtStart = { state.railOpen = true }, rows = rows,
            modifier = Modifier.padding(start = 24.dp, top = gridTop, bottom = 12.dp).fillMaxSize().glass(blur = false).padding(inset)) // no backdrop patch on the grid: the performance gate (ledger)
        if (state.railOpen) CategoryRail(vm, state)
        GuideMenus(vm, state)
    }
}

/** The hero reads focus, guide and stream format itself, so a key press recomposes only it and the rows, not the screen. */
@Composable
private fun GuideHero(vm: LiveViewModel, modifier: Modifier, size: HeaderSize, maxHeight: androidx.compose.ui.unit.Dp, logoScale: Float, titleScale: Float) {
    val channels by vm.channels.collectAsState()
    val programmes by vm.programmes.collectAsState()
    val now by vm.now.collectAsState()
    val focused by vm.focused.collectAsState()
    val strip by vm.strip.collectAsState()
    val logoSource by vm.logoSource.collectAsState()
    val previewingId by vm.previewingId.collectAsState()
    val formats by vm.player.formats.collectAsState()
    val cursor by vm.cursor.collectAsState()
    val hero = channels.find { it.id == focused?.id } ?: focused // the list has the renamed copy
    Hero(hero, hero?.displayName(strip).orEmpty(), programmes[focused?.id].orEmpty(), now, logoSource, heroBadges(focused?.id, previewingId, formats), modifier, size,
        at = Timeline.cursorAt(cursor, now, vm.inPast.collectAsState().value), logoScale = logoScale, titleScale = titleScale, maxHeight = maxHeight)
}

/** The brand logo slot at 1× (Branding logo size scales both): at most this wide (less when the nav needs the room), this tall. */
private val BRAND_LOGO_MAX = 200.dp
private val BRAND_LOGO_HEIGHT = 32.dp
/** The slot's tallest: centred on the nav (centre ~35dp down), it stays clear of the screen top and the header at 80dp. */
private val BRAND_LOGO_TALLEST = 64.dp
private val BRAND_LOGO_GAP = 12.dp

/**
 * Top left: the brand logo, then the nav to its right. The logo takes what the nav leaves, up to [BRAND_LOGO_MAX], so
 * the clock and ping at the nav's end stay on screen; the row is the nav's height, so the bar is as tall as before.
 * The logo isn't focusable: Up from the guide still enters the nav on the selected tab.
 */
@Composable
private fun BrandAndNav(path: String?, scale: Float, nav: @Composable () -> Unit, modifier: Modifier) {
    Layout(content = { BrandLogoImage(path); Box { nav() } }, modifier) { m, c ->
        val navP = m[1].measure(Constraints(maxHeight = c.maxHeight)) // its natural width
        val gap = BRAND_LOGO_GAP.roundToPx()
        val w = (c.maxWidth - navP.width - gap).coerceIn(0, (BRAND_LOGO_MAX * scale).roundToPx())
        val logoP = m[0].measure(Constraints.fixed(w, (BRAND_LOGO_HEIGHT * scale).coerceAtMost(BRAND_LOGO_TALLEST).roundToPx()))
        val navX = if (w > 0) w + gap else 0
        layout(c.constrainWidth(navX + navP.width), c.constrainHeight(navP.height)) {
            logoP.place(0, (navP.height - logoP.height) / 2) // centred on the nav; may reach a little above and below it
            navP.place(navX, 0)
        }
    }
}

/** The chosen image scaled to fit (aspect kept), left-aligned; the app's logo by default or when the copy is gone. */
@Composable
private fun BrandLogoImage(path: String?) {
    val file = remember(path) { BrandLogo.existing(path) }
    val m = Modifier.fillMaxSize()
    if (file == null) Image(painterResource(R.drawable.logo), null, m, alignment = Alignment.CenterStart, contentScale = ContentScale.Fit)
    else AsyncImage(file, null, m, error = painterResource(R.drawable.logo), alignment = Alignment.CenterStart, contentScale = ContentScale.Fit)
}
