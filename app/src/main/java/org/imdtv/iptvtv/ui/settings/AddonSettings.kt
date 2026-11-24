package org.imdtv.iptvtv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import org.imdtv.iptvtv.R
import org.imdtv.iptvtv.data.addons.AddonEntry
import org.imdtv.iptvtv.data.addons.AddonUrls
import org.imdtv.iptvtv.data.source.StremioAddon
import org.imdtv.iptvtv.data.source.StremioCatalog
import org.imdtv.iptvtv.ui.Field
import org.imdtv.iptvtv.ui.Spinner
import org.imdtv.iptvtv.ui.theme.LocalTheme

/**
 * Settings › Addons: Add addon (paste a manifest URL, Check, preview, Install), Play the first stream, then the installed
 * add-ons in priority order (OK opens one's page). Addresses show masked: configured ones may hold a personal token.
 */
@Composable
fun AddonRows(vm: SettingsViewModel, first: Modifier, focusUrl: String?, onFocusDone: () -> Unit, onOpen: (String) -> Unit) {
    val s by vm.settings.collectAsState()
    val entries by vm.addonEntries.collectAsState()
    val check by vm.addonCheck.collectAsState()
    var text by remember { mutableStateOf("") }
    val installFocus = remember { FocusRequester() }
    val rowFocus = remember(focusUrl) { FocusRequester() }
    Text(stringResource(R.string.set_c_addons_intro), fontSize = 13.sp, color = LocalTheme.current.colors.muted, modifier = Modifier.padding(bottom = 4.dp))
    Field(stringResource(R.string.set_c_addon_url), text, KeyboardType.Uri, modifier = first, openOnOk = true) { text = it }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PillButton(stringResource(R.string.set_c_addon_check)) { vm.checkAddon(text) }
        if (check != null) PillButton(stringResource(R.string.set_a_cancel)) { vm.cancelAddonCheck() }
    }
    when (val c = check) {
        null -> Unit
        SettingsViewModel.AddonCheck.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(size = 16.dp); Text("  " + stringResource(R.string.set_c_addon_checking), fontSize = 14.sp, color = Color.White)
        }
        is SettingsViewModel.AddonCheck.Failed -> Text(c.message, fontSize = 14.sp, color = Color(0xFFFF8A80))
        is SettingsViewModel.AddonCheck.Preview -> {
            AddonPreview(c.addon)
            LaunchedEffect(c) { withFrameNanos { }; runCatching { installFocus.requestFocus() } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton(stringResource(R.string.set_c_addon_install), Modifier.focusRequester(installFocus)) { vm.installChecked(); text = "" }
                PillButton(stringResource(R.string.set_a_cancel)) { vm.cancelAddonCheck() }
            }
        }
    }
    SwitchRow(stringResource(R.string.set_c_autoplay_stream), stringResource(R.string.set_c_autoplay_stream_expl), s.autoPlayFirstStream) { on ->
        vm.update { it.copy(autoPlayFirstStream = on) }
    }
    Text(stringResource(R.string.set_c_installed), fontSize = 15.sp, color = Color.White, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
    if (entries.isEmpty()) Text(stringResource(R.string.set_c_none_installed), fontSize = 14.sp, color = LocalTheme.current.colors.muted)
    entries.forEachIndexed { i, e ->
        val back = e.installed.url == focusUrl
        if (back) LaunchedEffect(Unit) { withFrameNanos { }; runCatching { rowFocus.requestFocus() }; onFocusDone() }
        SettingRow("${i + 1}. ${e.full.name}", summary(e),
            if (e.installed.enabled) stringResource(R.string.set_b_on_arrow) else stringResource(R.string.set_b_off_arrow),
            if (back) Modifier.focusRequester(rowFocus) else Modifier) { onOpen(e.installed.url) }
    }
}

@Composable
private fun summary(e: AddonEntry): String = listOfNotNull(e.full.version.takeIf { it.isNotBlank() }?.let { "v$it" }, AddonUrls.mask(e.installed.url),
    stringResource(R.string.set_c_n_catalogs, e.addon.catalogs.size, e.full.catalogs.size), resources(e.full)).joinToString("  ·  ")

/** "Catalogs, details, streams, subtitles": what the add-on answers. */
@Composable
private fun resources(a: StremioAddon): String? {
    val names = mapOf("catalog" to R.string.set_c_res_catalog, "meta" to R.string.set_c_res_meta, "stream" to R.string.set_c_res_stream, "subtitles" to R.string.set_c_res_subtitles)
    return a.resourceNames.mapNotNull { names[it] }.map { stringResource(it) }.joinToString(", ").ifEmpty { null }
}

/** The manifest before installing: logo, name and version, description, types, what it gives, its catalogs. */
@Composable
private fun AddonPreview(a: StremioAddon) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            a.logo?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.size(48.dp).padding(end = 10.dp)) }
            Column {
                Text(a.name + (a.version.takeIf { it.isNotBlank() }?.let { "  v$it" } ?: ""), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(AddonUrls.mask(a.manifestUrl), fontSize = 12.sp, color = LocalTheme.current.colors.muted)
            }
        }
        a.description?.let { Text(it, fontSize = 13.sp, color = Color.White, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        if (a.types.isNotEmpty()) Text(stringResource(R.string.set_c_types, a.types.joinToString(", ")), fontSize = 12.sp, color = LocalTheme.current.colors.muted)
        resources(a)?.let { Text(stringResource(R.string.set_c_gives, it), fontSize = 12.sp, color = LocalTheme.current.colors.muted) }
        if (a.allCatalogs.isNotEmpty()) Text(stringResource(R.string.set_c_catalogs, a.allCatalogs.take(10).joinToString(", ") { "${it.name} (${it.type})" } +
            if (a.allCatalogs.size > 10) stringResource(R.string.set_c_and_more, a.allCatalogs.size - 10) else ""), fontSize = 12.sp, color = LocalTheme.current.colors.muted)
        if (!a.hasResource("stream")) Text(stringResource(R.string.set_c_no_streams_note), fontSize = 12.sp, color = Color(0xFFE5A00D))
    }
}

/** One installed add-on: on/off, move up/down (priority), refresh its manifest, its catalogs, remove. Back closes. */
@Composable
fun AddonPage(vm: SettingsViewModel, url: String, modifier: Modifier, first: FocusRequester, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val entries by vm.addonEntries.collectAsState()
    val status by vm.addonStatus.collectAsState()
    val i = entries.indexOfFirst { it.installed.url == url }
    val e = entries.getOrNull(i)
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    var confirmRemove by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (e == null) { Text(stringResource(R.string.set_c_removed), fontSize = 15.sp, color = Color.White, modifier = Modifier.focusRequester(first)); return@Column }
        Text(e.full.name, fontSize = 20.sp, color = Color.White, modifier = Modifier.padding(top = 6.dp))
        Text(summary(e), fontSize = 12.sp, color = LocalTheme.current.colors.muted, modifier = Modifier.padding(bottom = 12.dp))
        SwitchRow(stringResource(R.string.set_c_use_addon), stringResource(R.string.set_c_use_addon_expl), e.installed.enabled, Modifier.focusRequester(first)) { on -> vm.setAddonEnabled(url, on) }
        SettingRow(stringResource(R.string.set_c_move_up), stringResource(R.string.set_c_move_expl, i + 1, entries.size), null, enabled = i > 0) { vm.moveAddon(url, -1) }
        SettingRow(stringResource(R.string.set_c_move_down), stringResource(R.string.set_c_move_expl, i + 1, entries.size), null, enabled = i < entries.lastIndex) { vm.moveAddon(url, 1) }
        val st = status[url]
        SettingRow(stringResource(R.string.set_c_refresh), if (status.containsKey(url)) st ?: stringResource(R.string.set_c_addon_checking) else stringResource(R.string.set_c_refresh_expl),
            null, busy = status.containsKey(url) && st == null) { vm.refreshAddon(url) }
        if (e.full.allCatalogs.isNotEmpty()) Text(stringResource(R.string.set_c_catalogs_header), fontSize = 15.sp, color = Color.White, modifier = Modifier.padding(top = 10.dp))
        e.full.allCatalogs.forEach { c ->
            SwitchRow("${c.name} (${c.type})", catalogUse(c), c.key !in e.installed.off) { on -> vm.setAddonCatalog(url, c.key, on) }
        }
        SettingRow(stringResource(R.string.set_c_remove), if (confirmRemove) stringResource(R.string.set_c_remove_confirm) else stringResource(R.string.set_c_remove_expl), null) {
            if (confirmRemove) { vm.removeAddon(url); onClose() } else confirmRemove = true
        }
    }
}

/** What a catalog does here: a row, search only, or neither (it needs a choice the app doesn't ask for). */
@Composable
private fun catalogUse(c: StremioCatalog): String = when {
    c.kind == null -> stringResource(R.string.set_c_cat_other_type)
    c.browse -> stringResource(R.string.set_c_cat_row) + if (c.search) stringResource(R.string.set_c_cat_and_search) else ""
    c.search -> stringResource(R.string.set_c_cat_search_only)
    else -> stringResource(R.string.set_c_cat_needs_choice)
}
