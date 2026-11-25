package org.imdtv.iptvtv.ui

import org.imdtv.iptvtv.ui.theme.glass
import org.imdtv.iptvtv.ui.theme.LocalGlassBackdrop
import org.imdtv.iptvtv.ui.theme.LocalTheme

import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Icon
import kotlinx.coroutines.delay
import org.imdtv.iptvtv.R
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun keepOrFirst(current: org.imdtv.iptvtv.data.db.CategoryEntity?, list: List<org.imdtv.iptvtv.data.db.CategoryEntity>, preferId: Long? = null) =
    list.firstOrNull { it.id == current?.id } ?: list.firstOrNull { it.id == preferId } ?: list.firstOrNull()

enum class Tab(val label: String, @androidx.annotation.DrawableRes val icon: Int, @androidx.annotation.StringRes val labelRes: Int) {
    SEARCH("Search", R.drawable.ic_tab_search, R.string.live_tab_search), LIVE("Live", R.drawable.ic_tab_live, R.string.live_tab_live), MOVIES("Movies", R.drawable.ic_tab_movies, R.string.live_tab_movies),
    SERIES("Series", R.drawable.ic_tab_series, R.string.live_tab_series), RECORDINGS("Recordings", R.drawable.ic_tab_recordings, R.string.live_tab_recordings), SETTINGS("Settings", R.drawable.ic_tab_settings, R.string.live_tab_settings),
}

/** The main menu's entries: Recordings only while recording is switched on. */
fun visibleTabs(recordingOn: Boolean): List<Tab> = Tab.entries.filter { it != Tab.RECORDINGS || recordingOn }

/** A D-pad focusable, OK-clickable box that tells its content whether it has focus. */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; if (focused) onFocused() } }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) { content(focused) }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TopNav(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier, selectedFocus: FocusRequester? = null, showClock: Boolean = true,
           showDate: Boolean = false, pingMs: Int? = null, tabs: List<Tab> = visibleTabs(false),
           /** Unselected items' opacity while focus is elsewhere (Dim top menu); 1 = never dimmed. */ idleAlpha: Float = 1f,
           /** False: the clock and ping aren't in the row (the Live TV guide shows them at the top right itself). */ trailing: Boolean = true) {
    // Dim top menu: fades in when focus comes up into the menu (~180 ms) and out when it leaves; nothing runs in between.
    var hasFocus by remember { mutableStateOf(false) }
    val dim by androidx.compose.animation.core.animateFloatAsState(if (hasFocus) 1f else idleAlpha, androidx.compose.animation.core.tween(180), label = "menuDim")
    val liquid = LocalGlassBackdrop.current != null
    // Entering the menu from below lands on the current tab, not the nearest one.
    Row(
        modifier.onFocusChanged { hasFocus = it.hasFocus }.focusProperties { enter = { selectedFocus ?: FocusRequester.Default } }.focusGroup()
            // A rounded see-through pill (Liquid Glass: its glass pill instead).
            .then(if (liquid) Modifier.glass(RoundedCornerShape(22.dp)).padding(5.dp)
                else Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0x1AFFFFFF)).padding(horizontal = 5.dp, vertical = 4.dp)),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            FocusBox(onClick = { onSelect(t) }, modifier = if (t == selected && selectedFocus != null) Modifier.focusRequester(selectedFocus) else Modifier) { f ->
                val fg = when { f -> Color.Black; t == selected -> Color.White; else -> LocalTheme.current.colors.muted }
                Row(
                    // The selected tab stays clear when the rest dims.
                    Modifier.graphicsLayer { alpha = if (t == selected || f) 1f else dim }.clip(RoundedCornerShape(15.dp))
                        .background(when { f -> Color.White; t == selected -> Color(0x1FFFFFFF); else -> Color.Transparent })
                        .padding(horizontal = 13.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(t.icon), contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
                    Text("  " + stringResource(t.labelRes), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
                }
            }
        }
        if (trailing) {
            // The time stays readable when the menu dims: never below 85%.
            Box(Modifier.graphicsLayer { alpha = maxOf(dim, .85f) }) { if (showClock || showDate) NavClock(showClock, showDate) }
            pingMs?.let { Text(pingLabel(it), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LocalTheme.current.colors.muted, modifier = Modifier.padding(start = 14.dp, end = 8.dp)) }
        }
    }
}

/** Its own composable, so the minute tick recomposes only this Text, not the nav. */
@Composable
private fun NavClock(showClock: Boolean, showDate: Boolean) {
    val now = minuteClock()
    Text(listOfNotNull(clockTime(now).takeIf { showClock }, dayDate(now).takeIf { showDate }).joinToString(" "), fontSize = 12.sp, fontWeight = FontWeight.Medium,
        color = Color.White, modifier = Modifier.padding(start = 14.dp, end = 8.dp))
}

/** The time now, changing on each new minute (one wake-up a minute, not a timer per frame). */
@Composable
fun minuteClock(): Long = produceState(System.currentTimeMillis()) {
    while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); value = System.currentTimeMillis() }
}.value

/**
 * The Live TV guide's top right: the time, right-aligned; under it the date (smaller, dimmer) with Show date › Under the
 * time, then the connection ping with Show ping. Never dimmed with the menu.
 */
@Composable
fun TopClock(showClock: Boolean, dateUnder: Boolean, pingMs: Int?, modifier: Modifier = Modifier) {
    val now = minuteClock()
    val shadow = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x99000000), androidx.compose.ui.geometry.Offset(0f, 1f), 4f))
    androidx.compose.foundation.layout.Column(modifier, horizontalAlignment = Alignment.End) {
        if (showClock) Text(clockTime(now), fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = Color.White, style = shadow)
        if (dateUnder) Text(dayDate(now), fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xCCFFFFFF), style = shadow)
        pingMs?.let { Text(pingLabel(it), fontSize = 10.sp, lineHeight = 13.sp, color = LocalTheme.current.colors.muted, style = shadow) }
    }
}

// shortcut: global, not state; screens pick it up on their next recomposition (clock ticks each minute)
@Volatile var use24Hour = true

private val clock = DateTimeFormatter.ofPattern("h.mma", Locale.UK)
private val clock24 = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
fun clockTime(t: Long): String =
    (if (use24Hour) clock24 else clock).format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())).lowercase()

private val day = DateTimeFormatter.ofPattern("EEE d/M", Locale.UK)

/** Day and month names in the app's language (English keeps the UK forms). MainActivity sets it on every (re)start. */
var namesLocale: Locale = Locale.UK
/** "Fri 13/11". */
fun dayDate(t: Long): String = day.withLocale(namesLocale).format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))

/** "12 ms", or "offline" when the last check failed (-1). */
@Composable
fun pingLabel(ms: Int): String = if (ms < 0) stringResource(R.string.live_ping_offline) else stringResource(R.string.live_ping_ms, ms)

fun duration(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

private val OK_KEYS = setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)

/** OK held: the first auto-repeat (or a long-press flagged event) of an OK key going down. */
fun isLongOk(e: android.view.KeyEvent): Boolean =
    e.keyCode in OK_KEYS && e.action == android.view.KeyEvent.ACTION_DOWN && (e.repeatCount == 1 || e.isLongPress)

/** Long OK runs [action] and swallows the rest of that press (repeats and key-up), so the normal click doesn't fire too. */
fun Modifier.onLongOk(action: () -> Unit): Modifier = composed {
    var swallowing by remember { mutableStateOf(false) }
    onPreviewKeyEvent { k ->
        val e = k.nativeKeyEvent
        when {
            isLongOk(e) -> { swallowing = true; action(); true }
            swallowing && e.keyCode in OK_KEYS -> { if (e.action == android.view.KeyEvent.ACTION_UP) swallowing = false; true }
            else -> false
        }
    }
}

/** The app's logo (res/drawable-xhdpi/logo.png, rendered from art/mark.svg). */
@Composable
fun AppLogo(modifier: Modifier = Modifier) =
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(org.imdtv.iptvtv.R.drawable.logo), contentDescription = null, modifier = modifier)

/** The small bottom information bar ("Press ↩ to exit application", "5 channels hidden"); the caller places and times it. */
@Composable
fun InfoBar(text: AnnotatedString, modifier: Modifier = Modifier) = Text(text, fontSize = 14.sp, color = Color.White,
    modifier = modifier.background(Color(0xCC1A1C24), RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 9.dp))

@Composable
fun InfoBar(text: String, modifier: Modifier = Modifier) = InfoBar(AnnotatedString(text), modifier)
