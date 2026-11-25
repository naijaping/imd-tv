package org.imdtv.iptvtv.ui.settings

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import org.imdtv.iptvtv.data.settings.HERO_SCALES
import org.imdtv.iptvtv.data.settings.BRAND_SCALES
import org.imdtv.iptvtv.ui.theme.LocalTheme
import kotlinx.coroutines.delay
import coil.compose.AsyncImage
import androidx.tv.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import org.imdtv.iptvtv.R

import org.imdtv.iptvtv.data.settings.ThemeColour
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.imdtv.iptvtv.data.files.BrandLogo
import org.imdtv.iptvtv.data.files.canPickFile
import org.imdtv.iptvtv.data.files.pickFileIntent
import org.imdtv.iptvtv.ui.Field
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import org.imdtv.iptvtv.data.settings.AppSettings
import org.imdtv.iptvtv.data.settings.guideRowChoices
import org.imdtv.iptvtv.data.settings.HeaderSize
import org.imdtv.iptvtv.data.settings.PlayerClock
import org.imdtv.iptvtv.data.settings.DatePlace
import org.imdtv.iptvtv.data.settings.MenuDim
import org.imdtv.iptvtv.data.settings.ThemeId
import org.imdtv.iptvtv.data.settings.TitleSize

private fun AppSettings.glass() = theme == ThemeId.CELESTIAL_GLASS || theme == ThemeId.LIQUID_GLASS

/** Appearance › App: theme, its colour and mode, Date and time ›, Reset appearance. */
@Composable
fun AppearanceAppRows(vm: SettingsViewModel, first: FocusRequester, openers: Map<ChannelPage, FocusRequester>, onOpen: (ChannelPage) -> Unit) {
    val s by vm.settings.collectAsState()
    // Each theme keeps its own colour, mode, rows, header and fade (withTheme).
    ChoiceRow(stringResource(R.string.set_a_theme), stringResource(R.string.set_a_theme_expl), ThemeId.entries, s.theme, { stringResource(it.labelRes) }, Modifier.focusRequester(first), default = AppSettings().theme, onPick = { t -> vm.update { it.withTheme(t) } })
    // Only the two glass themes take a background colour.
    if (s.glass()) {
        ChoiceRow(stringResource(R.string.set_a_theme_colour), stringResource(R.string.set_a_theme_colour_expl, stringResource(s.theme.labelRes)), ThemeColour.entries, s.themeColour, { stringResource(it.labelRes) }, default = AppSettings().themeColour) { c -> vm.update { it.copy(themeColour = c) } }
        ChoiceRow(stringResource(R.string.set_a_theme_mode), stringResource(R.string.set_a_theme_mode_expl), listOf(false, true), s.themeDark, { if (it) stringResource(R.string.set_a_mode_dark) else stringResource(R.string.set_a_mode_light) }, default = AppSettings().themeDark, onPick = { d -> vm.update { it.copy(themeDark = d) } })
    }
    // Dim top menu (every screen with the menu), and how faint.
    SwitchRow(stringResource(R.string.set_d_dim_menu), stringResource(R.string.set_d_dim_menu_expl), s.dimTopMenu) { on -> vm.update { it.copy(dimTopMenu = on) } }
    if (s.dimTopMenu) ChoiceRow(stringResource(R.string.set_d_dim_level), stringResource(R.string.set_d_dim_level_expl), MenuDim.entries, s.menuDim, { stringResource(it.labelRes) },
        default = AppSettings().menuDim) { d -> vm.update { it.copy(menuDim = d) } }
    val dt = ChannelPage.DATE_TIME
    SettingRow(stringResource(dt.titleRes), stringResource(dt.explanationRes), "›", Modifier.focusRequester(openers.getValue(dt))) { onOpen(dt) }
    var confirmReset by remember { mutableStateOf(false) }
    val resetNotice = stringResource(R.string.set_b_appearance_reset)
    SettingRow(stringResource(R.string.set_a_reset_appearance), if (confirmReset) stringResource(R.string.set_a_reset_appearance_confirm) else stringResource(R.string.set_a_reset_appearance_expl), null) {
        if (confirmReset) { vm.update { it.resetAppearance() }; confirmReset = false; vm.notice.value = resetNotice } else confirmReset = true
    }
}

/** Appearance › Live TV: guide rows, header size, preview fade (glass themes), multi-line names, title size. */
@Composable
fun AppearanceLiveRows(vm: SettingsViewModel, first: FocusRequester) {
    val s by vm.settings.collectAsState()
    val f = Modifier.focusRequester(first)
    // Scrolls: with the header sizes and Brand logo the page is taller than the screen, and focus only moves to rows it can show.
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (s.glass()) {
        val rowChoices = guideRowChoices(s.headerSize, s.theme == ThemeId.LIQUID_GLASS)
        ChoiceRow(stringResource(R.string.set_a_guide_rows), stringResource(R.string.set_a_guide_rows_expl), rowChoices, s.guideRows, { "$it" }, f, default = rowChoices.first()) { n -> vm.update { it.copy(guideRows = n) } }
        ChoiceRow(stringResource(R.string.set_a_header_size), stringResource(R.string.set_a_header_size_expl), HeaderSize.entries, s.headerSize, { stringResource(it.labelRes) }, default = AppSettings().headerSize) { h -> vm.update { it.withHeader(h) } }
        if (s.theme == ThemeId.CELESTIAL_GLASS) {
            SwitchRow(stringResource(R.string.set_a_preview_fade), stringResource(R.string.set_a_preview_fade_expl), s.previewFade) { on -> vm.update { it.copy(previewFade = on) } }
        }
        HeroSizeRows(vm, s)
        BrandLogoRow(vm, s)
    }
    SwitchRow(stringResource(R.string.set_a_live_indicator), stringResource(R.string.set_a_live_indicator_expl), s.showLiveIndicator, if (s.glass()) Modifier else f) { on -> vm.update { it.copy(showLiveIndicator = on) } }
    SwitchRow(stringResource(R.string.set_d_hero_progress), stringResource(R.string.set_d_hero_progress_expl), s.heroProgress) { on -> vm.update { it.copy(heroProgress = on) } }
    SwitchRow(stringResource(R.string.set_a_multi_line), stringResource(R.string.set_a_multi_line_expl), s.multiLineGuide) { on -> vm.update { it.copy(multiLineGuide = on) } }
    ChoiceRow(stringResource(R.string.set_a_title_size), stringResource(R.string.set_a_title_size_live_expl), TitleSize.entries, s.titleSize, { stringResource(it.labelRes) }, default = AppSettings().titleSize, onPick = { v -> vm.update { it.copy(titleSize = v) } })
    ChoiceRow(stringResource(R.string.set_a_epg_text_size), stringResource(R.string.set_a_epg_text_size_expl), org.imdtv.iptvtv.data.settings.EpgTextSize.entries, s.epgTextSize, { stringResource(it.labelRes) }, default = AppSettings().epgTextSize, onPick = { v -> vm.update { it.copy(epgTextSize = v) } })
    }
}

/** Appearance › Movies & Series: rows and its own title size. */
@Composable
fun AppearanceVodRows(vm: SettingsViewModel, first: FocusRequester) {
    val s by vm.settings.collectAsState()
    ChoiceRow(stringResource(R.string.set_a_vod_rows), stringResource(R.string.set_a_vod_rows_expl), listOf(false, true), s.vodTwoRows, { if (it) stringResource(R.string.set_a_vod_rows_2) else stringResource(R.string.set_a_vod_rows_1) }, Modifier.focusRequester(first), default = AppSettings().vodTwoRows, onPick = { v -> vm.update { it.copy(vodTwoRows = v) } })
    ChoiceRow(stringResource(R.string.set_a_title_size), stringResource(R.string.set_a_title_size_vod_expl), TitleSize.entries, s.vodTitleSize, { stringResource(it.labelRes) }, default = AppSettings().vodTitleSize, onPick = { v -> vm.update { it.copy(vodTitleSize = v) } })
}

/** Appearance › Date and time. */
@Composable
fun DateTimeRows(vm: SettingsViewModel, first: FocusRequester) {
    val s by vm.settings.collectAsState()
    SwitchRow(stringResource(R.string.set_a_clock), stringResource(R.string.set_a_clock_expl), s.showDateTime, Modifier.focusRequester(first)) { on -> vm.update { it.copy(showDateTime = on) } }
    // Show date: Off / Under the time / In the Today cell (null = off; other screens show it beside the time either way).
    val place = s.datePlace.takeIf { s.showDate }
    ChoiceRow(stringResource(R.string.set_d_show_date), stringResource(R.string.set_d_show_date_expl), listOf(null, DatePlace.UNDER_TIME, DatePlace.TODAY_CELL), place,
        { stringResource(when (it) { null -> R.string.set_d_date_off; DatePlace.UNDER_TIME -> R.string.set_d_date_under; DatePlace.TODAY_CELL -> R.string.set_d_date_today }) },
        default = AppSettings().datePlace) { p -> vm.update { if (p == null) it.copy(showDate = false) else it.copy(showDate = true, datePlace = p) } }
    // Time format: Follow the TV (null) / 24-hour (true) / 12-hour (false).
    val format = s.clock24h.takeIf { !s.clockFollowsDevice }
    ChoiceRow(stringResource(R.string.set_d_time_format), stringResource(R.string.set_d_time_format_expl), listOf(null, true, false), format,
        { stringResource(when (it) { null -> R.string.set_d_time_device; true -> R.string.set_d_time_24; false -> R.string.set_d_time_12 }) }, default = null) { h ->
        vm.update { if (h == null) it.copy(clockFollowsDevice = true) else it.copy(clockFollowsDevice = false, clock24h = h) }
    }
    ChoiceRow(stringResource(R.string.set_a_player_clock), stringResource(R.string.set_a_player_clock_expl), PlayerClock.entries, s.playerClock, { stringResource(it.labelRes) }, default = AppSettings().playerClock) { c ->
        vm.update { it.copy(playerClock = c) }
    }
}

private const val IMAGE = "image/*"

/** "0.75×", "1×", "1.5×". */
private fun scaleLabel(f: Float) = (if (f % 1f == 0f) f.toInt().toString() else f.toString()) + "×"

/** Header channel logo / Header title size and their reset (the guide header's top left). */
@Composable
private fun HeroSizeRows(vm: SettingsViewModel, s: AppSettings) {
    val d = AppSettings()
    ChoiceRow(stringResource(R.string.set_a_hero_logo_size), stringResource(R.string.set_a_hero_logo_size_expl), HERO_SCALES, s.heroLogoScale, { scaleLabel(it) }, default = d.heroLogoScale) { v -> vm.update { it.copy(heroLogoScale = v) } }
    ChoiceRow(stringResource(R.string.set_a_hero_title_size), stringResource(R.string.set_a_hero_title_size_expl), HERO_SCALES, s.heroTitleScale, { scaleLabel(it) }, default = d.heroTitleScale) { v -> vm.update { it.copy(heroTitleScale = v) } }
    // Greyed (still focusable, so the list scrolls past it) while both are at their default.
    SettingRow(stringResource(R.string.set_a_hero_reset), stringResource(R.string.set_a_hero_reset_expl), null,
        enabled = s.heroLogoScale != d.heroLogoScale || s.heroTitleScale != d.heroTitleScale) {
        vm.update { it.copy(heroLogoScale = d.heroLogoScale, heroTitleScale = d.heroTitleScale) }
    }
}

/**
 * Brand logo: a Logo image URL box (typed with the remote's on-screen keyboard), a preview of it, Apply and Reset to
 * default. Apply downloads it into app storage (BrandLogo); a failure says why and keeps the logo in use.
 * Choose from files shows only when an app can really take the request (canPickFile), so Android TV's "You don't have
 * an app that can do that" never appears.
 */
@Composable
private fun BrandLogoRow(vm: SettingsViewModel, s: AppSettings) {
    val context = LocalContext.current
    val colors = LocalTheme.current.colors
    val status by vm.brandStatus.collectAsState()
    var canPick by remember { mutableStateOf(canPickFile(context, IMAGE)) }
    var draft by remember(s.brandLogoUrl) { mutableStateOf(s.brandLogoUrl.orEmpty()) }
    val fieldFocus = remember { FocusRequester() }
    val applyFocus = remember { FocusRequester() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> r.data?.data?.let(vm::setBrandLogo) }
    val current = BrandLogo.existing(s.brandLogoPath)
    // The preview follows the box once typing pauses and the text is an address; until then it shows the logo in use.
    var previewUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(draft) { delay(600); previewUrl = BrandLogo.validUrl(draft)?.toString() }
    var previewFailed by remember(previewUrl) { mutableStateOf(false) }
    val badUrl = stringResource(R.string.set_a_brand_logo_bad_url)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingRow(stringResource(R.string.set_a_brand_logo), stringResource(R.string.set_a_brand_logo_expl),
            if (current != null) stringResource(R.string.set_a_brand_logo_custom) else stringResource(R.string.set_a_brand_logo_default)) {
            runCatching { fieldFocus.requestFocus() }
        }
        // Size first: it applies to the default logo and a custom one alike, straight away.
        val d = AppSettings().brandLogoScale
        ChoiceRow(stringResource(R.string.set_a_brand_logo_size), stringResource(R.string.set_a_brand_logo_size_expl), BRAND_SCALES, s.brandLogoScale, { scaleLabel(it) }, default = d) { v ->
            vm.update { it.copy(brandLogoScale = v) }
        }
        SettingRow(stringResource(R.string.set_a_brand_logo_size_reset), stringResource(R.string.set_a_brand_logo_size_reset_expl), null, enabled = s.brandLogoScale != d) {
            vm.update { it.copy(brandLogoScale = d) }
        }
        Field(stringResource(R.string.set_a_brand_logo_url), draft, KeyboardType.Uri, modifier = Modifier.focusRequester(fieldFocus), openOnOk = true) { draft = it }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The guide's slot, on the guide's background: the same Fit and left alignment.
            Box(Modifier.size(200.dp, 48.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(colors.bg)).padding(horizontal = 8.dp, vertical = 6.dp)) {
                val m = Modifier.fillMaxSize()
                val url = previewUrl?.takeIf { it != s.brandLogoUrl || current == null }
                when {
                    url != null -> AsyncImage(url, null, m, alignment = Alignment.CenterStart, contentScale = ContentScale.Fit,
                        onError = { previewFailed = true }, onSuccess = { previewFailed = false })
                    current != null -> AsyncImage(current, null, m, alignment = Alignment.CenterStart, contentScale = ContentScale.Fit)
                    else -> Image(painterResource(R.drawable.logo), null, m, alignment = Alignment.CenterStart, contentScale = ContentScale.Fit)
                }
            }
            Text(status ?: if (previewFailed) stringResource(R.string.set_a_brand_logo_preview_failed) else stringResource(R.string.set_a_brand_logo_preview),
                fontSize = 13.sp, color = if (status != null || previewFailed) Color.White else colors.muted, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            PillButton(stringResource(R.string.set_a_brand_logo_apply), Modifier.focusRequester(applyFocus)) {
                if (BrandLogo.validUrl(draft) == null) vm.brandStatus.value = badUrl else vm.setBrandLogoUrl(draft)
            }
            if (current != null) PillButton(stringResource(R.string.set_a_brand_logo_reset)) {
                runCatching { applyFocus.requestFocus() } // this button goes: focus must not fall out of the page
                vm.resetBrandLogo(); draft = ""
            }
            if (canPick) PillButton(stringResource(R.string.set_a_brand_logo_choose)) {
                try { pick.launch(pickFileIntent(IMAGE)) }
                catch (e: android.content.ActivityNotFoundException) { runCatching { applyFocus.requestFocus() }; canPick = false }
            }
        }
    }
}
