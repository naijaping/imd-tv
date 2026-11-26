package org.imdtv.iptvtv.ui

import org.imdtv.iptvtv.data.source.uiText
import org.imdtv.iptvtv.R
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.imdtv.iptvtv.IptvApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.imdtv.iptvtv.data.catalogue.displayName
import org.imdtv.iptvtv.data.catalogue.parseStripList
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.settings.AppSettings
import org.imdtv.iptvtv.data.db.ProviderType
import org.imdtv.iptvtv.data.model.StreamFormat
import org.imdtv.iptvtv.data.source.XtreamAuthException
import org.imdtv.iptvtv.ui.vod.VodItem
import org.imdtv.iptvtv.work.EpgRefreshWorker

sealed interface Screen {
    data object Loading : Screen
    /** Add a playlist, or edit [editId]. */
    data class Setup(val editId: Long? = null) : Screen
    data class Settings(val group: org.imdtv.iptvtv.ui.settings.SettingsGroup = org.imdtv.iptvtv.ui.settings.SettingsGroup.PLAYLISTS) : Screen
    data object Live : Screen
    data object Vod : Screen
    data object Search : Screen
    data class Detail(val item: VodItem) : Screen
    /** [recordingId]: a recording is playing; its position is saved on Back. */
    data class Player(val title: String, val live: Boolean, val recordingId: Long? = null) : Screen
    data object Recordings : Screen
}

enum class RootBack { FOCUS_MENU, SHOW_HINT, EXIT }

/**
 * Back at a root screen: first press jumps to the top menu, the next shows the exit prompt, the one after exits.
 * With [confirmExit] off, Back at a root exits at once.
 */
fun rootBack(top: Screen, menuFocused: Boolean, hintShowing: Boolean, confirmExit: Boolean) = when {
    !confirmExit || (top != Screen.Live && top != Screen.Vod && top != Screen.Search && top != Screen.Recordings) -> RootBack.EXIT
    !menuFocused -> RootBack.FOCUS_MENU
    !hintShowing -> RootBack.SHOW_HINT
    else -> RootBack.EXIT
}

/** The screen a top menu tab opens. */
fun tabRoot(t: Tab): Screen = when (t) { Tab.LIVE -> Screen.Live; Tab.SEARCH -> Screen.Search; Tab.RECORDINGS -> Screen.Recordings; else -> Screen.Vod }

/** [t] is the tab showing, at its own screen with nothing over it: selecting it again changes nothing. */
fun tabShowing(t: Tab, current: Tab, stack: List<Screen>): Boolean = t != Tab.SETTINGS && t == current && stack == listOf(tabRoot(t))

/** The screens at launch; the Player's title is filled in once the channel has loaded. */
fun startStack(hasProvider: Boolean, s: AppSettings, lastChannelExists: Boolean): List<Screen> = when {
    !hasProvider -> listOf(Screen.Setup())
    s.lastChannelOnStart && s.lastLiveChannelId != null && lastChannelExists -> listOf(Screen.Live, Screen.Player("", live = true))
    else -> listOf(Screen.Live)
}

data class FormState(val busy: Boolean = false, val error: String? = null, val result: String? = null, val progress: String? = null)

/** Navigation stack, provider setup form and the shared player. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as IptvApp).container
    private fun str(@androidx.annotation.StringRes id: Int, vararg a: Any): String = getApplication<Application>().getString(id, *a)
    val player get() = c.playerManager

    val stack = MutableStateFlow<List<Screen>>(listOf(Screen.Loading))
    val tab = MutableStateFlow(Tab.LIVE)
    val form = MutableStateFlow(FormState())
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    /** Set once at launch when the app opens full screen on the last channel; the activity hands it to LiveViewModel. */
    val startChannel = MutableStateFlow<ChannelEntity?>(null)
    private var hasProvider = false

    init {
        // EPG schedule follows the interval setting (re-enqueued with UPDATE when it changes).
        viewModelScope.launch {
            c.settings.settings.map { it.epgUpdateIntervalHours }.distinctUntilChanged()
                .collect { EpgRefreshWorker.schedule(getApplication(), it) }
        }
        viewModelScope.launch {
            c.settings.settings.map { it.playlistUpdateHours }.distinctUntilChanged()
                .collect { org.imdtv.iptvtv.work.CatalogueSyncWorker.schedule(getApplication(), it) }
        }
        viewModelScope.launch {
            // Off: still collapse a backlog of queued runs left by 0.5.1 and older.
            EpgRefreshWorker.runNow(getApplication(), org.imdtv.iptvtv.work.GuideUpdateReason.APP_START, automatic = true, backlogOnly = !c.settings.settings.first().epgUpdateOnStart)
            // Besides the schedule, only a playlist with no guide data at all gets one now (its first download).
            val empty = c.db.providerDao().liveIds().filter { !c.db.epgDao().hasProgrammes(it) }
            if (empty.isNotEmpty()) EpgRefreshWorker.runNow(getApplication(), org.imdtv.iptvtv.work.GuideUpdateReason.FIRST_GUIDE, automatic = true)
        }
        viewModelScope.launch {
            val active = c.providerRepository.activeProviderId.first()
            hasProvider = active != null
            val s = c.settings.settings.first()
            // Must belong to the active playlist: the id may be left over from one since switched or deleted.
            val last = s.lastLiveChannelId?.let { c.db.channelDao().get(it) }?.takeIf { it.providerId == active }
            val start = startStack(hasProvider, s, last != null)
            if (start.last() is Screen.Player && last != null) {
                startChannel.value = last
                stack.value = listOf(Screen.Live, Screen.Player(last.displayName(org.imdtv.iptvtv.data.catalogue.channelStrip(c.db.providerDao().get(last.providerId)?.nameStrip, s.removeNameSymbols)), live = true))
            } else {
                stack.value = start
            }
        }
    }

    private fun push(s: Screen) { stack.value = stack.value + s }

    fun selectTab(t: Tab) {
        if (t == Tab.SETTINGS) { push(Screen.Settings()); return }
        // OK on the tab already showing (Live while on Live): nothing at all, no reload, no stopping the preview.
        if (tabShowing(t, tab.value, stack.value)) return
        if (t != Tab.LIVE) player.stop() // the live preview shouldn't keep playing behind Movies/Series
        tab.value = t
        stack.value = listOf(tabRoot(t))
    }

    fun addPlaylist() { form.value = FormState(); push(Screen.Setup()) }

    fun editPlaylist(id: Long) { form.value = FormState(); push(Screen.Setup(id)) }

    suspend fun provider(id: Long): ProviderEntity? = c.db.providerDao().get(id)

    fun open(item: VodItem) = push(Screen.Detail(item))

    /** Gamestation: an XMB Settings item opens Settings on that group. */
    fun openSettings(group: org.imdtv.iptvtv.ui.settings.SettingsGroup) = push(Screen.Settings(group))

    fun playFullScreen(title: String, live: Boolean) = push(Screen.Player(title, live))

    /** A finished (or stopped) recording, from where it was left. */
    fun playRecording(r: org.imdtv.iptvtv.recording.RecordingEntity) {
        val location = r.location ?: return
        player.playRecording(location, r.positionMs)
        push(Screen.Player(r.title, live = false, recordingId = r.id))
    }

    /** Returns false at a root screen, so the activity can finish. */
    fun back(): Boolean {
        val top = stack.value.last()
        if (stack.value.size == 1) return false
        // Live full screen returns to the guide with the same stream still in the preview.
        if (top is Screen.Player && top.recordingId != null) {
            val p = player.player
            val at = p.currentPosition.takeIf { p.duration <= 0 || it < p.duration - 30_000 } ?: 0 // watched to the end: start over next time
            viewModelScope.launch { c.recordings.setPosition(top.recordingId, at) }
        }
        if (top is Screen.Player && !top.live) player.stop()
        stack.value = stack.value.dropLast(1)
        // A channel played from search: there is no preview to return to.
        if (top is Screen.Player && stack.value.last() == Screen.Search) player.stop()
        // Catch-up opened from live full screen: back to the live stream.
        if (top is Screen.Player && !top.live && (stack.value.last() as? Screen.Player)?.live == true) resumeLive()
        return true
    }

    /** Set by the activity: plays the live channel again (see [back]). */
    var resumeLive: () -> Unit = {}

    /** A blank [name] keeps the automatic one: the URL's host. */
    fun save(type: ProviderType, url: String, username: String, password: String, epgUrls: String, userAgent: String, editing: ProviderEntity? = null, name: String = "") {
        val trimmed = url.trim().trimEnd('/')
        if (Uri.parse(trimmed).host.isNullOrEmpty()) {
            form.value = FormState(error = str(R.string.set_b_enter_full_url))
            return
        }
        if (type == ProviderType.STALKER && !org.imdtv.iptvtv.data.source.isValidMac(username)) {
            form.value = FormState(error = str(R.string.set_b_enter_mac_pairs))
            return
        }
        form.value = FormState(busy = true)
        viewModelScope.launch {
            var login = username.trim().ifEmpty { null }?.let { if (type == ProviderType.STALKER) it.uppercase() else it }
            var pass = password.ifEmpty { null }
            var address = trimmed
            if (type == ProviderType.DISPATCHARR) {
                // Signs in with the Dispatcharr login and keeps the XC login it gives (DispatcharrAuth); editing with the
                // password left blank and the same username keeps the saved one.
                address = c.dispatcharr.base(trimmed) ?: trimmed
                val keep = editing != null && password.isEmpty() && (login == null || login == editing.username)
                if (keep) { login = editing!!.username; pass = editing.password }
                else try {
                    form.value = form.value.copy(progress = str(R.string.set_b_dp_signing_in))
                    c.dispatcharr.xcLogin(address, username, password).let { login = it.username; pass = it.password }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.w("AppViewModel", "Dispatcharr sign-in failed: $e")
                    form.value = FormState(error = loadError(e) + if (editing != null) str(R.string.set_b_not_saved) else "")
                    return@launch
                }
            }
            val agent = userAgent.trim().ifEmpty { null }
            if (editing != null) { saveEdit(editing, editing.copy(name = name.trim().ifEmpty { Uri.parse(address).host ?: editing.name }, url = address, username = login, password = pass, userAgent = agent)); return@launch }
            val entity = ProviderEntity(
                name = name.trim().ifEmpty { Uri.parse(address).host!! }, type = type, url = address,
                username = login, password = pass,
                userAgent = agent, streamFormat = StreamFormat.TS,
                epgOverrideUrls = epgUrls.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() },
                lastSync = null,
            )
            var id: Long? = null
            try {
                id = c.providerRepository.add(entity)
                val r = c.providerRepository.syncNow(id) { step -> form.value = form.value.copy(progress = step) }
                c.providerRepository.setActive(id)
                hasProvider = true
                EpgRefreshWorker.runNow(getApplication(), org.imdtv.iptvtv.work.GuideUpdateReason.PLAYLIST_ADDED)
                form.value = FormState(result = r.summary() + str(R.string.set_b_guide_loading))
            } catch (e: Exception) {
                // Don't leave a half-added provider behind; the user will retry with corrected details.
                id?.let { c.providerRepository.remove(it) }
                android.util.Log.w("AppViewModel", "Add ${type.name} playlist failed", e) // for Send logs on the form
                form.value = FormState(error = loadError(e))
            }
        }
    }

    /** Saves the edit and re-syncs with it; if the playlist no longer loads, the old details are put back. */
    private suspend fun saveEdit(old: ProviderEntity, new: ProviderEntity) {
        c.providerRepository.update(new)
        try {
            val r = c.providerRepository.syncNow(new.id) { step -> form.value = form.value.copy(progress = step) }
            c.epgMatcher.rematch(new.id)
            if (settings.value.epgUpdateOnPlaylistChange) EpgRefreshWorker.runNow(getApplication(), org.imdtv.iptvtv.work.GuideUpdateReason.PLAYLIST_CHANGED)
            form.value = FormState(result = r.summary())
        } catch (e: Exception) {
            c.providerRepository.update(old)
            android.util.Log.w("AppViewModel", "Edit ${new.type.name} playlist failed", e)
            form.value = FormState(error = loadError(e) + str(R.string.set_b_not_saved))
        }
    }

    private fun loadError(e: Exception) = when (e) {
        is XtreamAuthException, is org.imdtv.iptvtv.data.source.DispatcharrException -> str(R.string.set_b_login_failed, e.uiText(getApplication()) ?: "")
        is org.imdtv.iptvtv.data.source.StalkerException -> str(R.string.set_b_portal_login_failed, e.uiText(getApplication()) ?: "")
        else -> str(R.string.set_b_load_failed, e.uiText(getApplication()) ?: e::class.simpleName ?: "")
    }


    /** Deleting the active playlist restarts at Live on the next one, or at first-run setup if none is left. */
    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            val wasActive = c.providerRepository.activeProviderId.first() == id
            if (wasActive) player.stop()
            c.providerRepository.remove(id)
            c.logoPack.remove(id) // its unpacked pack folder
            c.logos.pruneCustom() // hand-picked logos of its channels
            hasProvider = c.providerRepository.activeProviderId.first() != null
            if (wasActive) {
                form.value = FormState() // first-run setup must not show the last add/edit result
                tab.value = Tab.LIVE
                stack.value = startStack(hasProvider, settings.value, lastChannelExists = false)
            }
        }
    }

    /** Switches the active playlist and restarts at its Live guide (the Live/VOD/search screens follow the active id). */
    fun usePlaylist(id: Long) {
        viewModelScope.launch {
            player.stop()
            c.providerRepository.setActive(id) // stays in Settings: the playlist page's switch shows it took
        }
    }

    fun done() { form.value = FormState(); tab.value = Tab.LIVE; stack.value = listOf(Screen.Live) }

}
