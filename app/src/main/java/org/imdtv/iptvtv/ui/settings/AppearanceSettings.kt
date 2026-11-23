package org.imdtv.iptvtv.ui.settings

import androidx.compose.ui.res.stringResource
import org.imdtv.iptvtv.R

import org.imdtv.iptvtv.data.settings.ThemeColour
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
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
    if (s.glass()) {
        val rowChoices = guideRowChoices(s.headerSize, s.theme == ThemeId.LIQUID_GLASS)
        ChoiceRow(stringResource(R.string.set_a_guide_rows), stringResource(R.string.set_a_guide_rows_expl), rowChoices, s.guideRows, { "$it" }, f, default = rowChoices.first()) { n -> vm.update { it.copy(guideRows = n) } }
        ChoiceRow(stringResource(R.string.set_a_header_size), stringResource(R.string.set_a_header_size_expl), HeaderSize.entries, s.headerSize, { stringResource(it.labelRes) }, default = AppSettings().headerSize) { h -> vm.update { it.withHeader(h) } }
        if (s.theme == ThemeId.CELESTIAL_GLASS) {
            SwitchRow(stringResource(R.string.set_a_preview_fade), stringResource(R.string.set_a_preview_fade_expl), s.previewFade) { on -> vm.update { it.copy(previewFade = on) } }
        }
        BrandLogoRow(vm, s)
    }
    SwitchRow(stringResource(R.string.set_a_live_indicator), stringResource(R.string.set_a_live_indicator_expl), s.showLiveIndicator) { on -> vm.update { it.copy(showLiveIndicator = on) } }
    SwitchRow(stringResource(R.string.set_a_multi_line), stringResource(R.string.set_a_multi_line_expl), s.multiLineGuide, if (s.glass()) Modifier else f) { on -> vm.update { it.copy(multiLineGuide = on) } }
    ChoiceRow(stringResource(R.string.set_a_title_size), stringResource(R.string.set_a_title_size_live_expl), TitleSize.entries, s.titleSize, { stringResource(it.labelRes) }, default = AppSettings().titleSize, onPick = { v -> vm.update { it.copy(titleSize = v) } })
    ChoiceRow(stringResource(R.string.set_a_epg_text_size), stringResource(R.string.set_a_epg_text_size_expl), org.imdtv.iptvtv.data.settings.EpgTextSize.entries, s.epgTextSize, { stringResource(it.labelRes) }, default = AppSettings().epgTextSize, onPick = { v -> vm.update { it.copy(epgTextSize = v) } })
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
    SwitchRow(stringResource(R.string.set_a_date), stringResource(R.string.set_a_date_expl), s.showDate) { on -> vm.update { it.copy(showDate = on) } }
    SwitchRow(stringResource(R.string.set_a_clock_24h), stringResource(R.string.set_a_clock_24h_expl), s.clock24h) { on -> vm.update { it.copy(clock24h = on) } }
    ChoiceRow(stringResource(R.string.set_a_player_clock), stringResource(R.string.set_a_player_clock_expl), PlayerClock.entries, s.playerClock, { stringResource(it.labelRes) }, default = AppSettings().playerClock) { c ->
        vm.update { it.copy(playerClock = c) }
    }
}

private const val IMAGE = "image/*"

/**
 * Brand logo: Choose image opens a file manager (the pick is copied into app storage, see BrandLogo); a TV without one,
 * or whose picker won't start, gets an image address box instead. Reset to default brings back the app's logo.
 */
@Composable
private fun BrandLogoRow(vm: SettingsViewModel, s: AppSettings) {
    val context = LocalContext.current
    val status by vm.brandStatus.collectAsState()
    var urlMode by remember { mutableStateOf(!canPickFile(context, IMAGE)) }
    var editing by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("https://") }
    val fieldFocus = remember { FocusRequester() }
    val chooseFocus = remember { FocusRequester() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> r.data?.data?.let(vm::setBrandLogo) }
    val custom = BrandLogo.existing(s.brandLogoPath) != null
    fun choose() {
        if (urlMode) { editing = true; return }
        // Google TV builds without DocumentsUI: no activity takes the chooser; fall back to an address.
        try { pick.launch(pickFileIntent(IMAGE)) } catch (e: android.content.ActivityNotFoundException) { urlMode = true; editing = true }
    }
    fun closeUrl() { runCatching { chooseFocus.requestFocus() }; editing = false }
    BackHandler(enabled = editing) { closeUrl() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingRow(stringResource(R.string.set_a_brand_logo),
            status ?: if (urlMode) stringResource(R.string.set_a_brand_logo_expl_url) else stringResource(R.string.set_a_brand_logo_expl),
            if (custom) stringResource(R.string.set_a_brand_logo_custom) else stringResource(R.string.set_a_brand_logo_default)) { choose() }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillButton(stringResource(R.string.set_a_brand_logo_choose), Modifier.focusRequester(chooseFocus)) { choose() }
            if (custom) PillButton(stringResource(R.string.set_a_brand_logo_reset)) { vm.resetBrandLogo() }
        }
        if (editing) {
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { fieldFocus.requestFocus() } }
            Field(stringResource(R.string.set_a_brand_logo_url), url, KeyboardType.Uri, modifier = Modifier.focusRequester(fieldFocus)) { url = it }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                PillButton(stringResource(R.string.set_a_save)) { vm.setBrandLogoUrl(url); closeUrl() }
                PillButton(stringResource(R.string.set_a_cancel)) { closeUrl() }
            }
        }
    }
}
