package org.imdtv.iptvtv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import org.imdtv.iptvtv.R
import org.imdtv.iptvtv.data.settings.TopMenu
import org.imdtv.iptvtv.data.settings.TopMenuEntry
import org.imdtv.iptvtv.ui.Field
import org.imdtv.iptvtv.ui.FocusBox
import org.imdtv.iptvtv.ui.Tab
import org.imdtv.iptvtv.ui.theme.LocalTheme

/**
 * Settings › Top menu: every item in its order. OK on an item picks it up (gold, ↕): Up/Down (or Left/Right) move it,
 * the menu following at once; OK drops it, Back puts it back. Beside each: Rename, its style (icon + name or icon
 * only) and Reset name. Then Set all to…, and Reset top menu. Settings is renamable but always in the menu.
 */
@Composable
fun TopMenuRows(vm: SettingsViewModel, first: Modifier) {
    val s by vm.settings.collectAsState()
    val saved = TopMenu.normalized(s.topMenu, Tab.entries.map { it.name })
    fun save(list: List<TopMenuEntry>) = vm.update { it.copy(topMenu = list) }
    var moving by remember { mutableStateOf<String?>(null) }
    // While an item is picked up the page moves its own copy (saves land a moment later; fast presses must not move
    // from an old order), saving each step so the menu follows.
    var draft by remember { mutableStateOf<List<TopMenuEntry>?>(null) }
    val items = draft ?: saved
    fun step(id: String, d: Int) { val n = TopMenu.move(items, id, d); draft = n; save(n) }
    var before by remember { mutableStateOf<List<TopMenuEntry>>(emptyList()) } // the order to go back to on Back
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val movingFocus = remember { FocusRequester() }
    BackHandler(enabled = moving != null) { save(before); moving = null; draft = null }
    // The moved item keeps focus as it changes place.
    LaunchedEffect(moving, items.indexOfFirst { it.id == moving }) { if (moving != null) { withFrameNanos { }; runCatching { movingFocus.requestFocus() } } }

    Text(stringResource(R.string.set_e_intro), fontSize = 13.sp, color = LocalTheme.current.colors.muted, modifier = Modifier.padding(bottom = 4.dp))
    SettingRow(stringResource(R.string.set_e_all_named), stringResource(R.string.set_e_all_named_expl), null, first) { save(items.map { it.copy(iconOnly = false) }) }
    SettingRow(stringResource(R.string.set_e_all_icons), stringResource(R.string.set_e_all_icons_expl), null) { save(items.map { it.copy(iconOnly = true) }) }
    Text(stringResource(R.string.set_e_items), fontSize = 15.sp, color = Color.White, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
    items.forEachIndexed { i, e ->
        val tab = Tab.entries.first { it.name == e.id }
        val default = stringResource(tab.labelRes)
        val name = e.name ?: default
        val isMoving = moving == e.id
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FocusBox(onClick = {
                if (isMoving) { moving = null; draft = null } // drop: already saved step by step
                else if (moving == null) { before = items; draft = items; moving = e.id; renaming = null }
            }, modifier = Modifier.weight(1f).then(if (isMoving) Modifier.focusRequester(movingFocus) else Modifier).onPreviewKeyEvent { k ->
                if (!isMoving || k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (k.key) {
                    Key.DirectionUp, Key.DirectionLeft -> { step(e.id, -1); true }
                    Key.DirectionDown, Key.DirectionRight -> { step(e.id, 1); true }
                    else -> false
                }
            }) { f ->
                val hi = f || isMoving
                val fg = if (hi) Color.Black else Color.White
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (isMoving) MOVING else if (f) Color.White else Color(0x14FFFFFF)).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isMoving) "↕" else "${i + 1}.", fontSize = 15.sp, color = fg, modifier = Modifier.width(24.dp))
                    Icon(painterResource(tab.icon), null, tint = fg, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, fontSize = 16.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            isMoving -> stringResource(R.string.set_e_moving)
                            tab == Tab.RECORDINGS && !s.recordingOn -> stringResource(R.string.set_e_recordings_off)
                            else -> stringResource(if (e.iconOnly) R.string.set_e_icon_only else R.string.set_e_icon_name) +
                                (if (e.name != null) "  ·  " + stringResource(R.string.set_e_renamed_from, default) else "")
                        }, fontSize = 12.sp, color = if (hi) Color(0xFF555555) else LocalTheme.current.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (moving == null) {
                PillButton(stringResource(R.string.set_e_rename)) { renaming = if (renaming == e.id) null else e.id }
                PillButton(stringResource(if (e.iconOnly) R.string.set_e_show_name else R.string.set_e_show_icon_only)) {
                    save(items.map { if (it.id == e.id) it.copy(iconOnly = !it.iconOnly) else it })
                }
                if (e.name != null) PillButton(stringResource(R.string.set_e_reset_name)) { save(items.map { if (it.id == e.id) it.copy(name = null) else it }) }
            }
        }
        if (renaming == e.id) RenameBox(name, default, onSave = { typed ->
            // Blank, or the default name typed again: back to the (translated) default.
            val n = TopMenu.cleanName(typed)?.takeIf { it != default }
            save(items.map { if (it.id == e.id) it.copy(name = n) else it }); renaming = null
        }) { renaming = null }
    }
    SettingRow(stringResource(R.string.set_e_reset), if (confirmReset) stringResource(R.string.set_e_reset_confirm) else stringResource(R.string.set_e_reset_expl), null) {
        if (confirmReset) { save(emptyList()); confirmReset = false; moving = null; renaming = null } else confirmReset = true
    }
}

/** The rename box: filled with the current name, at most [TopMenu.MAX_NAME] characters; Save or Cancel (Back too). */
@Composable
private fun RenameBox(current: String, default: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val field = remember { FocusRequester() }
    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { field.requestFocus() } }
    Column(Modifier.padding(start = 24.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Field(stringResource(R.string.set_e_name_label, TopMenu.MAX_NAME, default), text, KeyboardType.Text, modifier = Modifier.focusRequester(field)) {
            text = it.take(TopMenu.MAX_NAME)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillButton(stringResource(R.string.set_a_save)) { onSave(text) }
            PillButton(stringResource(R.string.set_a_cancel)) { onCancel() }
        }
    }
}
