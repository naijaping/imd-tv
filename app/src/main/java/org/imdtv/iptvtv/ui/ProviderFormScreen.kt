package org.imdtv.iptvtv.ui

import org.imdtv.iptvtv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.db.ProviderType

/** Add a playlist, or edit [editId]'s URL, login and user agent (its EPG sources are managed in Settings). */
@Composable
fun ProviderFormScreen(vm: AppViewModel, editId: Long? = null) {
    // Wait for the playlist being edited so the fields start filled in.
    val editing by produceState<ProviderEntity?>(null, editId) { value = editId?.let { vm.provider(it) } }
    if (editId != null && editing == null) return
    key(editing?.id) { ProviderForm(vm, editing) }
}

@Composable
private fun ProviderForm(vm: AppViewModel, editing: ProviderEntity?) {
    val state by vm.form.collectAsState()
    var type by rememberSaveable { mutableStateOf(editing?.type ?: ProviderType.XTREAM) }
    var url by rememberSaveable { mutableStateOf(editing?.url.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(editing?.username.orEmpty()) }
    // Dispatcharr saves the XC password, not what was typed: editing, the box starts blank and blank keeps the login.
    var pass by rememberSaveable { mutableStateOf(if (editing?.type == ProviderType.DISPATCHARR) "" else editing?.password.orEmpty().substringBefore('\n')) }
    // Stalker keeps "serial\ndeviceId\ndeviceId2\nmodel" in the password column (StalkerLogin); the form shows them as separate fields.
    val stalkerEdit = editing?.takeIf { it.type == ProviderType.STALKER }?.let { org.imdtv.iptvtv.data.source.StalkerLogin.from(it.username, it.password) }
    var deviceId by rememberSaveable { mutableStateOf(stalkerEdit?.deviceId.orEmpty()) }
    var deviceId2 by rememberSaveable { mutableStateOf(stalkerEdit?.deviceId2.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(stalkerEdit?.model.orEmpty()) }
    var epg by rememberSaveable { mutableStateOf("") }
    var agent by rememberSaveable { mutableStateOf(editing?.userAgent.orEmpty()) }

    var name by rememberSaveable { mutableStateOf(editing?.name.orEmpty()) }
    var sendLogs by remember { mutableStateOf(false) }
    val ready = state.result != null
    val next = remember { FocusRequester() }
    val save = remember { FocusRequester() }
    // What was typed may never have been saved (a failed add is removed), so the scrubber is given it directly.
    if (sendLogs) org.imdtv.iptvtv.ui.settings.SendLogsPage(listOf(user, pass, deviceId, deviceId2)) { sendLogs = false; runCatching { save.requestFocus() } }
    val phoneForm: @Composable (Modifier) -> Unit = { mod ->
        // Secrets are never sent to the phone (plain HTTP on the LAN): editing, they start blank and blank keeps them.
        val edit = editing != null
        val keep = stringResource(R.string.set_b_phone_blank_keeps)
        fun f(key: String, label: String, value: String, secret: Boolean, vararg t: ProviderType) =
            org.imdtv.iptvtv.data.support.PhoneField(key, if (secret && edit) "$label ($keep)" else label, value, secret, t.map { it.name }.toSet())
        val x = ProviderType.XTREAM; val m = ProviderType.M3U; val st = ProviderType.STALKER; val d = ProviderType.DISPATCHARR
        val fields = listOf(
            f("xurl", stringResource(R.string.set_b_server_url), url.takeIf { type == x }.orEmpty(), false, x),
            f("durl", stringResource(R.string.set_b_server_url), url.takeIf { type == d }.orEmpty(), false, d),
            f("murl", stringResource(R.string.set_b_playlist_url), url.takeIf { type == m }.orEmpty(), edit, m), // an M3U address often holds the login
            f("surl", stringResource(R.string.set_b_portal_url), url.takeIf { type == st }.orEmpty(), false, st),
            f("user", stringResource(R.string.set_b_username), user.takeIf { type == x }.orEmpty(), false, x),
            f("pass", stringResource(R.string.set_b_password), "", true, x),
            f("duser", stringResource(R.string.set_b_username), user.takeIf { type == d }.orEmpty(), false, d),
            f("dpass", stringResource(R.string.set_b_dp_password_or_key), "", true, d),
            f("mac", stringResource(R.string.set_b_mac_form), "", true, st),
            f("serial", stringResource(R.string.set_b_serial_optional), "", true, st),
            f("deviceId", stringResource(R.string.set_b_device_id), "", true, st),
            f("deviceId2", stringResource(R.string.set_b_device_id2), "", true, st),
            f("model", stringResource(R.string.set_b_stb_model), model, false, st),
        ) + (if (edit) emptyList() else listOf(f("epg", stringResource(R.string.set_b_epg_urls), epg, false, m))) + listOf(
            f("agent", stringResource(R.string.set_b_user_agent), agent, false),
            f("name", stringResource(R.string.set_b_name_optional), name, false),
        )
        org.imdtv.iptvtv.ui.settings.PhoneFormQr(if (edit) "editplaylist" else "addplaylist",
            if (editing == null) stringResource(R.string.set_b_add_playlist) else stringResource(R.string.set_b_edit_playlist, editing.name),
            if (edit) emptyList() else FORM_TYPES.map { it.name }, type.name, fields,
            onSubmit = { form ->
                if (state.busy) return@PhoneFormQr
                fun get(k: String) = form[k]?.trim().orEmpty()
                fun secret(k: String, old: String) = get(k).ifEmpty { if (edit) old else "" }
                if (!edit) type = FORM_TYPES.find { it.name == form["type"] } ?: type
                when (type) {
                    ProviderType.XTREAM -> { url = get("xurl"); user = get("user"); pass = secret("pass", pass) }
                    ProviderType.DISPATCHARR -> { url = get("durl"); user = get("duser"); pass = secret("dpass", pass) }
                    ProviderType.M3U -> { url = secret("murl", url); epg = get("epg") }
                    ProviderType.PLEX, ProviderType.JELLYFIN, ProviderType.STREMIO -> Unit // added under Media servers, never by this form
                    ProviderType.STALKER -> { url = get("surl"); user = secret("mac", user); pass = secret("serial", pass)
                        deviceId = secret("deviceId", deviceId); deviceId2 = secret("deviceId2", deviceId2); model = get("model") }
                }
                agent = get("agent"); name = get("name")
                runCatching { save.requestFocus() } // one OK on Save from here
            }, mod)
    }
    LaunchedEffect(ready) { if (ready) runCatching { next.requestFocus() } } // Save has gone: focus Watch/Done, not the top
    // One 1080p screen (960×540 dp): labels sit beside the fields, progress and Watch on the right.
    // Scrolls only when the Name box is open on the tallest (Stalker) form.
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (editing == null) stringResource(R.string.set_b_add_playlist) else stringResource(R.string.set_b_edit_playlist, editing.name), style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            // Loading: every field, type button, Name and Save ignore OK (editing mid-load could crash), dimmed to show it.
            Column(Modifier.width(560.dp).alpha(if (state.busy) .45f else 1f).onPreviewKeyEvent { e ->
                state.busy && e.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
            }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editing == null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FORM_TYPES.forEach { t ->
                        Button(onClick = { type = t }) { Text((if (t == type) "● " else "○ ") + t.name) }
                    }
                }
                FormField(stringResource(when (type) { ProviderType.XTREAM, ProviderType.DISPATCHARR -> R.string.set_b_server_url; ProviderType.M3U -> R.string.set_b_playlist_url; ProviderType.PLEX, ProviderType.JELLYFIN, ProviderType.STREMIO -> R.string.set_b_server_url; ProviderType.STALKER -> R.string.set_b_portal_url }),
                    url, KeyboardType.Uri) { url = it }
                if (type == ProviderType.STALKER) {
                    FormField(stringResource(R.string.set_b_mac_form), user, KeyboardType.Password) { user = it }
                    FormField(stringResource(R.string.set_b_serial_optional), pass, KeyboardType.Password) { pass = it }
                    FormField(stringResource(R.string.set_b_device_id), deviceId, KeyboardType.Password) { deviceId = it }
                    FormField(stringResource(R.string.set_b_device_id2), deviceId2, KeyboardType.Password) { deviceId2 = it }
                    FormField(stringResource(R.string.set_b_stb_model), model, KeyboardType.Password) { model = it }
                } else if (type.xtream) {
                    // The password keyboard has the number row many providers' usernames need.
                    FormField(stringResource(R.string.set_b_username), user, KeyboardType.Password) { user = it }
                    FormField(stringResource(when {
                        type == ProviderType.XTREAM -> R.string.set_b_password
                        editing != null -> R.string.set_b_dp_password_keep
                        else -> R.string.set_b_dp_password_or_key
                    }), pass, KeyboardType.Password, secret = true) { pass = it }
                } else if (editing == null) {
                    FormField(stringResource(R.string.set_b_epg_urls), epg, KeyboardType.Uri) { epg = it }
                }
                FormField(stringResource(R.string.set_b_user_agent), agent) { agent = it }
                org.imdtv.iptvtv.ui.settings.EditRow(stringResource(R.string.set_b_name_optional), stringResource(R.string.set_b_name_expl),
                    // Down goes to Save: the nearest-below search picked "Refresh guide now".
                    modifier = Modifier.focusProperties { if (!ready) down = save },
                    value = name.ifBlank { stringResource(R.string.set_b_automatic) }, initial = name, saveLabel = stringResource(R.string.set_b_confirm)) { name = it } // "Save" here read as the form's Save (user, 2026-11-15)
                if (!ready) {
                    // The buttons stay while busy: if Save left the screen, focus fell to the top and the error showed off screen.
                    Button(onClick = {
                        if (state.busy) return@Button
                        val secret = if (type == ProviderType.STALKER) org.imdtv.iptvtv.data.source.StalkerLogin(user, pass, deviceId, deviceId2, model).packed().orEmpty() else pass
                        vm.save(type, url, user, secret, epg, agent, editing, name)
                    }, Modifier.fillMaxWidth().focusRequester(save)) {
                        Text(stringResource(R.string.set_b_save), fontSize = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    }
                    // Below Save, not above it: a line above pushed Save off the bottom of the Stalker form.
                    if (!state.busy) state.error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, maxLines = 3) }
                }
                // Greyed out (not pressable) until the playlist has loaded; Back still leaves. "Finish", not "Done": beside Save, Done read as saving.
                // A failed add / edit: the same button sends the logs instead (Watch / Finish would be greyed out anyway).
                val failed = !ready && !state.busy && state.error != null
                Button(onClick = { if (failed) sendLogs = true else if (editing == null) vm.done() else vm.back() },
                    Modifier.fillMaxWidth().padding(top = 4.dp).focusRequester(next), enabled = ready || failed) {
                    Text(when { failed -> stringResource(R.string.set_b_send_logs); editing == null -> stringResource(R.string.set_b_watch); else -> stringResource(R.string.set_b_finish) }, fontSize = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
            }
            Column(Modifier.width(240.dp).padding(top = if (editing == null) 56.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                // Not once loaded: nothing left to fill in.
                if (!ready) phoneForm(Modifier.fillMaxWidth())
                if (state.busy) {
                    Spinner(size = 32.dp)
                    Text(state.progress ?: stringResource(R.string.set_b_loading_playlist), color = Color.White, fontSize = 14.sp)
                }
                state.result?.let { Text(it, color = Color.White, fontSize = 14.sp) }
            }
        }
    }
}

/** A [Field] with its label on the left, so the form fits one screen. */
@Composable
private fun FormField(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, onChange: (String) -> Unit) {
    // A secret gets Show / Hide on its right (Right from the field), so what's typed can be checked.
    var shown by remember { mutableStateOf(false) }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.width(170.dp))
        Column(Modifier.weight(1f)) { Field("", value, keyboard, secret && !shown, openOnOk = true, onChange = onChange) }
        if (secret) org.imdtv.iptvtv.ui.FocusBox(onClick = { shown = !shown }, modifier = Modifier.padding(start = 10.dp)) { f ->
            Text(stringResource(if (shown) R.string.set_b_hide else R.string.set_b_show), fontSize = 14.sp, color = if (f) Color.Black else Color.White,
                modifier = Modifier.width(90.dp).border(2.dp, if (f) Color.White else Color.Gray, RoundedCornerShape(8.dp))
                    .background(if (f) Color.White else Color.Transparent, RoundedCornerShape(8.dp)).padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
fun Field(
    label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false,
    modifier: Modifier = Modifier, openOnOk: Boolean = false, onChange: (String) -> Unit,
) {
    // openOnOk: read-only until OK, so D-pad can pass through a form without a keyboard on every field.
    var editing by remember { mutableStateOf(!openOnOk) }
    val keyboardCtl = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = LocalFocusManager.current
    Column {
        if (label.isNotEmpty()) Text(label, color = Color.LightGray)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = !label.startsWith("EPG"), readOnly = !editing,
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = androidx.tv.material3.LocalTextStyle.current.merge(TextStyle(color = Color.White, fontSize = 20.sp)),
            cursorBrush = SolidColor(Color.White),
            // A text field keeps Up/Down as cursor moves, which traps a D-pad: use them to leave the field.
            modifier = modifier.onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> { focus.moveFocus(FocusDirection.Down); true }
                    Key.DirectionUp -> { focus.moveFocus(FocusDirection.Up); true }
                    // Not typing: Right reaches what's beside the field (a password's Show), not the cursor.
                    Key.DirectionRight -> if (editing) false else focus.moveFocus(FocusDirection.Right)
                    Key.DirectionCenter, Key.Enter -> if (editing) false else { editing = true; keyboardCtl?.show(); true }
                    else -> false
                }
            }.onFocusChanged { if (openOnOk && !it.isFocused) editing = false }.fillMaxWidth()
                .border(2.dp, if (focused) Color.White else Color.Gray, RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
    }
}

/** The types this form adds (Plex and Jellyfin are added under Settings › Media servers). */
private val FORM_TYPES = listOf(ProviderType.XTREAM, ProviderType.M3U, ProviderType.STALKER, ProviderType.DISPATCHARR)
