package org.imdtv.iptvtv.ui.settings

import org.imdtv.iptvtv.data.files.BrandLogo
import org.imdtv.iptvtv.R
import android.util.Log
import androidx.room.withTransaction
import android.app.Application
import android.net.Uri
import org.imdtv.iptvtv.data.files.SharedFile
import org.imdtv.iptvtv.data.files.listSharedFiles
import org.imdtv.iptvtv.data.logos.PackException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.imdtv.iptvtv.IptvApp
import org.imdtv.iptvtv.data.backup.BPlaylist
import org.imdtv.iptvtv.data.backup.Backup
import org.imdtv.iptvtv.data.backup.BackupException
import org.imdtv.iptvtv.data.backup.decodeBackup
import org.imdtv.iptvtv.data.backup.encodeBackup
import org.imdtv.iptvtv.data.backup.listBackups
import org.imdtv.iptvtv.data.backup.readBackup
import org.imdtv.iptvtv.data.backup.writeBackup
import org.imdtv.iptvtv.data.catalogue.countryLabel
import org.imdtv.iptvtv.data.catalogue.displayName
import org.imdtv.iptvtv.data.catalogue.languageOf
import org.imdtv.iptvtv.data.catalogue.parseStripList
import org.imdtv.iptvtv.data.catalogue.cleaned
import org.imdtv.iptvtv.data.catalogue.tvLanguage
import org.imdtv.iptvtv.data.db.CategoryCount
import org.imdtv.iptvtv.data.db.CategoryEntity
import org.imdtv.iptvtv.data.db.CustomGroupEntity
import org.imdtv.iptvtv.data.db.EpgSourceEntity
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.epg.clearGuide
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.data.repo.CategoryRepository
import org.imdtv.iptvtv.data.settings.AppSettings
import org.imdtv.iptvtv.data.settings.LogoKind
import org.imdtv.iptvtv.data.settings.GuideKind
import org.imdtv.iptvtv.work.EpgRefreshWorker

data class CategoryRow(val category: CategoryEntity, val count: Int)
data class CountryRow(val country: String?, val label: String, val shown: Boolean)
data class LanguageRow(val language: String, val countries: List<CountryRow>)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as IptvApp).container
    val plex = PlexAddFlow(c, viewModelScope)
    val jellyfin = JellyfinAddFlow(c, viewModelScope)
    val stremio = StremioAddFlow(c, viewModelScope)
    val deletingPlaylists = c.providerRepository.removing
    val sync get() = c.sync
    private val categoryRepo = CategoryRepository(c.db)

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun update(change: (AppSettings) -> AppSettings) { viewModelScope.launch { c.settings.update(change) } }

    // --- Recording (Settings › Playback › Recording) ---
    fun recordingOff() { viewModelScope.launch { c.settings.update { it.copy(recordingOn = false) }; c.recordings.turnOff() } }
    suspend fun recordingFreeBytes(s: AppSettings): Long? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.recordingStore(s)?.freeBytes() }
    suspend fun testShare(s: AppSettings): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { org.imdtv.iptvtv.recording.testShare(c.smbSettings(s)) }
    private fun str(@androidx.annotation.StringRes id: Int, vararg a: Any): String = getApplication<Application>().getString(id, *a)
    fun notice(text: String) { c.recordings.prompt.value = org.imdtv.iptvtv.recording.Prompt.Notice(text) }

    val guideUpdating: StateFlow<Boolean> = EpgRefreshWorker.updating(app).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** A second press while one runs used to REPLACE (cancel and restart) it. */
    fun refreshGuide() { if (!guideUpdating.value) EpgRefreshWorker.runNow(getApplication(), org.imdtv.iptvtv.work.GuideUpdateReason.USER_REFRESH) }
    /** Re-links only; nothing is downloaded. */
    fun setBorrowSiblingGuide(on: Boolean) = launchFor { p ->
        c.settings.update { it.copy(borrowSiblingGuide = on) }
        val m = c.epgMatcher.rematch(p, borrow = on)
        android.util.Log.i("IptvEpg", "Provider $p: ${m.matched} matched (${m.borrowed} borrowed), ${m.unmatched} unmatched")
    }
    /** A short message at the foot of Settings, e.g. after Reset appearance. */
    val notice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val parental get() = c.parental

    /** EPG sources › an open-source file On/Off; the refresh re-matches without (or with) it. */
    fun setGuideOn(url: String, on: Boolean) {
        viewModelScope.launch { c.settings.update { it.copy(guidesOff = if (on) it.guidesOff - url else it.guidesOff + url) }; EpgRefreshWorker.runNow(getApplication(), org.imdtv.iptvtv.work.GuideUpdateReason.SETTINGS_CHANGE) }
    }
    fun setGuideOrder(order: List<GuideKind>) {
        viewModelScope.launch { c.settings.update { it.copy(guideOrder = order) }; EpgRefreshWorker.runNow(getApplication(), org.imdtv.iptvtv.work.GuideUpdateReason.SETTINGS_CHANGE) }
    }

    // --- Playlists and TV guide ---

    val providers: StateFlow<List<ProviderEntity>> = c.db.providerDao().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun sources(providerId: Long): Flow<List<EpgSourceEntity>> = c.db.epgDao().observeSources(providerId)

    /** The active playlist's EPG sources (TV guide page). */
    val activeSources: StateFlow<List<EpgSourceEntity>> by lazy { perProvider(emptyList()) { sources(it) } } // providerId is declared below

    fun renameSource(id: Long, name: String) { viewModelScope.launch { c.db.epgDao().renameSource(id, name.trim().ifEmpty { null }) } }
    fun setOffset(id: Long, minutes: Int) { viewModelScope.launch { c.db.epgDao().setOffset(id, minutes) } }
    fun addSource(providerId: Long, url: String) {
        viewModelScope.launch { c.providerRepository.addEpgSource(providerId, url.trim(), null); EpgRefreshWorker.runNow(getApplication(), org.imdtv.iptvtv.work.GuideUpdateReason.SETTINGS_CHANGE) }
    }
    fun removeSource(providerId: Long, id: Long) {
        viewModelScope.launch { c.providerRepository.removeEpgSource(id); c.epgMatcher.rematch(providerId) }
    }

    fun clearGuideData() { viewModelScope.launch { clearGuide(c.db) } }

    /** The playlist page Edit was opened from; Settings reopens it when it comes back. */
    var playlistToReopen: Long? = null

    /** Progress, then the result line, of "Update playlist now", per playlist. */
    val playlistUpdate = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val updating = mutableSetOf<Long>()
    /** Asked for again while updating (e.g. Movies then Series switched on): the running update read the old switches. */
    private val again = mutableSetOf<Long>()

    /** Turns loading [kind] from [p] on (then updates the playlist so it's fetched now) or off (removed at once). */
    fun setLoads(p: org.imdtv.iptvtv.data.db.ProviderEntity, kind: Kind, on: Boolean) {
        viewModelScope.launch {
            // The row passed in can be stale after a quick second toggle: start from what's saved.
            val saved = c.db.providerDao().get(p.id)?.contentOff ?: p.contentOff
            val off = saved.split(',').filter { it.isNotEmpty() && it != kind.name } + listOfNotNull(kind.name.takeIf { !on })
            if (on) { c.db.providerDao().setContentOff(p.id, off.joinToString(",")); updatePlaylist(p.id) }
            else try { c.providerRepository.stopLoading(p.id, kind, off.joinToString(",")) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("SettingsViewModel", "Removing $kind failed", e) // the next update removes it; never crash the app
            }
        }
    }

    fun setEnabled(id: Long, on: Boolean) { viewModelScope.launch { c.db.providerDao().setEnabled(id, on) } }

    fun updatePlaylist(id: Long) {
        if (!updating.add(id)) { again += id; return } // runs once more when this one ends, not two full syncs at once
        fun show(text: String) { playlistUpdate.value = playlistUpdate.value + (id to text) }
        show(str(R.string.set_b_updating))
        viewModelScope.launch {
            try {
                val r = c.providerRepository.syncNow(id) { show(it) }
                c.epgMatcher.rematch(id)
                if (settings.value.epgUpdateOnPlaylistChange) EpgRefreshWorker.runNow(getApplication(), org.imdtv.iptvtv.work.GuideUpdateReason.PLAYLIST_CHANGED)
                show(r.summary())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                show(str(R.string.set_b_update_failed, e.message ?: e::class.simpleName ?: ""))
            } finally {
                updating.remove(id)
                if (again.remove(id)) updatePlaylist(id)
            }
        }
    }

    // --- Channels & categories ---

    val providerId: StateFlow<Long?> = c.providerRepository.activeProviderId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private fun <T> perProvider(empty: T, f: (Long) -> Flow<T>): StateFlow<T> =
        providerId.flatMapLatest { it?.let(f) ?: flowOf(empty) }.stateIn(viewModelScope, SharingStarted.Eagerly, empty)
    private fun launchFor(block: suspend (Long) -> Unit) {
        viewModelScope.launch { block(providerId.filterNotNull().first()) }
    }

    val categoryKind = MutableStateFlow(Kind.LIVE)

    val categoryRows: StateFlow<List<CategoryRow>> = perProvider(emptyList()) { p ->
        categoryKind.flatMapLatest { k ->
            val counts: Flow<List<CategoryCount>> = when (k) {
                Kind.LIVE -> c.db.channelDao().observeCounts(p)
                Kind.MOVIE -> c.db.movieDao().observeCounts(p)
                Kind.SERIES -> c.db.seriesDao().observeCounts(p)
            }
            val strip = c.db.providerDao().observe(p).map { parseStripList(it?.categoryStrip.orEmpty()) }
            combine(c.db.categoryDao().observeAll(p, k), counts, strip) { cats, n, s ->
                val byId = n.associate { it.categoryId to it.n }
                cats.map { CategoryRow(it.cleaned(s), byId[it.id] ?: 0) }
            }
        }
    }

    fun setHidden(cat: CategoryEntity, hidden: Boolean) { viewModelScope.launch { c.db.categoryDao().setHidden(cat.id, hidden) } }
    fun channelsIn(categoryId: Long) = c.db.channelDao().observeAllByCategory(categoryId)
    fun mergeCounts(categoryId: Long) = c.db.channelDao().observeMergeCounts(categoryId).map { l -> l.associate { it.id to it.n } }
    suspend fun mergedMembers(head: org.imdtv.iptvtv.data.db.ChannelEntity) = org.imdtv.iptvtv.data.repo.ChannelMerge.members(c.db.channelDao(), head)
    suspend fun setMerged(member: org.imdtv.iptvtv.data.db.ChannelEntity, head: org.imdtv.iptvtv.data.db.ChannelEntity, on: Boolean) =
        if (on) org.imdtv.iptvtv.data.repo.ChannelMerge.merge(c.db, member, head) else org.imdtv.iptvtv.data.repo.ChannelMerge.unmergeOne(c.db, member)
    fun setChannelsHidden(ids: List<Long>, hidden: Boolean) { viewModelScope.launch { c.db.channelDao().setHiddenForIds(ids, hidden) } }
    fun setAllHidden(hidden: Boolean) = launchFor { c.db.categoryDao().setHiddenForKind(it, categoryKind.value, hidden) }
    // One move at a time: quick presses each read the order the last one wrote.
    private val moveLock = kotlinx.coroutines.sync.Mutex()
    fun move(cat: CategoryEntity, delta: Int) = launchFor { p -> moveLock.withLock { categoryRepo.move(p, cat.kind, cat.id, delta) } }
    // --- Sort by (issue #9), as the guide's long-press menu ---
    private fun strip() = org.imdtv.iptvtv.data.catalogue.channelStrip(nameStrip.value, settings.value.removeNameSymbols)
    fun sortOf(e: org.imdtv.iptvtv.data.repo.RailEntry): Flow<org.imdtv.iptvtv.data.db.SortBy> =
        providerId.filterNotNull().flatMapLatest { org.imdtv.iptvtv.data.repo.ChannelSort.observe(c.db, c.settings, it, e) }
    /** [list] in [e]'s Sort by order, as the guide shows it. */
    fun sorted(list: List<org.imdtv.iptvtv.data.db.ChannelEntity>, by: org.imdtv.iptvtv.data.db.SortBy) = org.imdtv.iptvtv.data.repo.ChannelSort.sorted(list, by, strip())
    fun setSort(e: org.imdtv.iptvtv.data.repo.RailEntry, by: org.imdtv.iptvtv.data.db.SortBy) =
        launchFor { p -> moveLock.withLock { org.imdtv.iptvtv.data.repo.ChannelSort.set(c.db, c.settings, p, e, by, strip()) } }
    fun sortOnce(e: org.imdtv.iptvtv.data.repo.RailEntry) =
        launchFor { p -> moveLock.withLock { org.imdtv.iptvtv.data.repo.ChannelSort.sortOnce(c.db, c.settings, p, e, strip()) } }

    fun moveChannel(ch: org.imdtv.iptvtv.data.db.ChannelEntity, delta: Int) {
        val cat = ch.categoryId ?: return
        viewModelScope.launch { moveLock.withLock { categoryRepo.moveChannel(cat, ch.id, delta) } }
    }

    /** Languages A–Z ("Other" last), each with its countries; a country is shown when any of its categories is. */
    val languages: StateFlow<List<LanguageRow>> = perProvider(emptyList()) { p ->
        c.db.categoryDao().observeAllKinds(p).map { cats ->
            cats.groupBy { it.country }
                .map { (code, list) -> CountryRow(code, countryLabel(code), list.any { !it.hidden }) }
                .groupBy { languageOf(it.country) }
                .map { (lang, list) -> LanguageRow(lang, list.sortedBy { it.label }) }
                .sortedWith(compareBy({ it.language == "Other" }, { it.language }))
        }
    }

    val tvLanguage: String = tvLanguage()

    /** On: show only the TV's language (and categories with none detected); new categories at sync follow it. Off: show all. */
    fun setMatchTvLanguage(on: Boolean) = launchFor { p ->
        c.settings.update { it.copy(matchTvLanguage = on) }
        categoryRepo.matchLanguage(p, if (on) tvLanguage else null)
    }

    fun setCountriesShown(countries: List<String?>, shown: Boolean) = launchFor { p ->
        val dao = c.db.categoryDao()
        countries.filterNotNull().takeIf { it.isNotEmpty() }?.let { dao.setHiddenForCountries(p, it, !shown) }
        if (null in countries) dao.setHiddenForNoCountry(p, !shown)
    }

    val nameStrip: StateFlow<String> = perProvider("") { p -> c.db.providerDao().observe(p).map { it?.nameStrip.orEmpty() } }

    private var logoMatch: kotlinx.coroutines.Job? = null
    /** True while open-source logos are being matched (spinner on the Channel logos rows). */
    val logoMatching = MutableStateFlow(false)
    fun setLogoOrder(order: List<LogoKind>) {
        val had = LogoKind.OPEN_SOURCE in settings.value.logoOrder
        update { it.copy(logoOrder = order) }
        // builtinLogo doesn't depend on the order: match only when open-source logos are newly in use.
        if (LogoKind.OPEN_SOURCE in order && !had && logoMatch?.isActive != true) logoMatch = viewModelScope.launch {
            logoMatching.value = true
            try { providerId.filterNotNull().first().let { c.logos.refreshBuiltin(it) } } finally { logoMatching.value = false }
        }
    }

    // Logo pack (Task 21)
    val packCount: StateFlow<Int> by lazy { perProvider(0) { c.logoPack.observeCount(it) } }
    val packStatus = MutableStateFlow<String?>(null)
    val zipFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    fun refreshZips() { viewModelScope.launch { zipFiles.value = listSharedFiles(getApplication(), setOf("zip")) } }
    fun importPack(uri: Uri, name: String?) = launchFor { p ->
        if (packStatus.value == str(R.string.set_b_importing)) return@launchFor  // a second OK while one runs
        if (name?.endsWith(".zip", ignoreCase = true) != true) { packStatus.value = str(R.string.set_b_not_zip); return@launchFor }
        packStatus.value = str(R.string.set_b_importing)
        packStatus.value = try {
            val r = c.logoPack.import(p) { getApplication<Application>().contentResolver.openInputStream(uri) ?: throw PackException(str(R.string.set_b_zip_unreadable)) }
            str(R.string.set_b_logo_matched, r.matched, r.images)
        } catch (e: PackException) {
            if (e.res != 0) str(e.res) else e.message
        } catch (e: java.io.IOException) {
            str(R.string.set_b_zip_unreadable)
        }
    }
    fun removePack() = launchFor { c.logoPack.remove(it); packStatus.value = null }

    // --- Settings › Addons (Stremio add-ons by manifest URL) ---

    /** Add addon: checking the pasted address, why it failed, or the manifest to preview before installing. */
    sealed interface AddonCheck {
        data object Checking : AddonCheck
        data class Failed(val message: String) : AddonCheck
        data class Preview(val addon: org.imdtv.iptvtv.data.source.StremioAddon, val raw: String) : AddonCheck
    }
    val addonCheck = MutableStateFlow<AddonCheck?>(null)
    val addonEntries: StateFlow<List<org.imdtv.iptvtv.data.addons.AddonEntry>> = c.addons.entries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Refresh manifest's result per add-on URL: null while running, then a message. */
    val addonStatus = MutableStateFlow<Map<String, String?>>(emptyMap())
    private var checking: kotlinx.coroutines.Job? = null

    private fun addonError(e: Exception) = if (e is org.imdtv.iptvtv.data.addons.AddonException) getApplication<Application>().getString(e.resId, *e.args.toTypedArray())
        else str(R.string.set_c_addon_unreachable)

    fun checkAddon(text: String) {
        checking?.cancel()
        addonCheck.value = AddonCheck.Checking
        checking = viewModelScope.launch {
            addonCheck.value = try { c.addons.check(text).let { (a, raw) -> AddonCheck.Preview(a, raw) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.i("IptvAddons", "Add-on check failed: ${e.message}"); AddonCheck.Failed(addonError(e)) } // never the URL
        }
    }
    fun cancelAddonCheck() { checking?.cancel(); addonCheck.value = null }
    fun installChecked() {
        val p = addonCheck.value as? AddonCheck.Preview ?: return
        viewModelScope.launch { c.addons.install(p.addon, p.raw); addonCheck.value = null; notice.value = str(R.string.set_c_addon_installed, p.addon.name) }
    }
    fun removeAddon(url: String) = viewModelScope.launch { c.addons.remove(url) }
    fun moveAddon(url: String, delta: Int) = viewModelScope.launch { c.addons.move(url, delta) }
    fun setAddonEnabled(url: String, on: Boolean) = viewModelScope.launch { c.addons.setEnabled(url, on) }
    fun setAddonCatalog(url: String, key: String, on: Boolean) = viewModelScope.launch { c.addons.setCatalog(url, key, on) }
    fun refreshAddon(url: String) {
        if (addonStatus.value.containsKey(url) && addonStatus.value[url] == null) return // already refreshing
        addonStatus.value = addonStatus.value + (url to null)
        viewModelScope.launch {
            val msg = try { c.addons.refresh(url); str(R.string.set_c_addon_refreshed) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { addonError(e) }
            addonStatus.value = addonStatus.value + (url to msg)
        }
    }

    // Brand logo (Appearance › Live TV): the guide's top-left image.
    /** Under the Brand logo row: "Saving…", or why the last try failed (the current logo is kept); null once saved. */
    val brandStatus = MutableStateFlow<String?>(null)
    /** A picked file (content or file URI). */
    fun setBrandLogo(uri: Uri) = saveBrandLogo(null) { getApplication<Application>().contentResolver.openInputStream(uri) ?: throw java.io.IOException("unreadable") }
    /** Logo image URL › Apply: downloaded (20 s at most), checked to be an image, then kept in app storage. */
    fun setBrandLogoUrl(url: String) {
        val u = BrandLogo.validUrl(url) ?: run { brandStatus.value = str(R.string.set_a_brand_logo_bad_url); return }
        saveBrandLogo(u.toString()) {
            val r = brandHttp.newCall(okhttp3.Request.Builder().url(u).build()).execute()
            if (!r.isSuccessful) { r.close(); throw BrandLogo.HttpFailure(r.code) }
            r.body?.byteStream() ?: throw java.io.IOException("empty")
        }
    }
    private val brandHttp by lazy { c.http.newBuilder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build() }
    private fun saveBrandLogo(url: String?, open: () -> java.io.InputStream) {
        if (brandStatus.value == str(R.string.set_a_brand_logo_saving)) return // a second OK while one runs
        brandStatus.value = str(R.string.set_a_brand_logo_saving)
        viewModelScope.launch {
            brandStatus.value = try {
                val path = BrandLogo.save(getApplication(), open)
                c.settings.update { it.copy(brandLogoPath = path, brandLogoUrl = url) }
                null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("IptvBrand", "Brand logo not saved: $e")
                when (e) {
                    is BrandLogo.HttpFailure -> str(R.string.set_a_brand_logo_http, e.code)
                    is BrandLogo.NotImage -> str(R.string.set_a_brand_logo_not_image)
                    is BrandLogo.TooLarge -> str(R.string.set_a_brand_logo_too_large)
                    is java.io.InterruptedIOException -> str(R.string.set_a_brand_logo_timeout) // call/read timeout
                    is java.net.UnknownHostException, is java.net.ConnectException -> str(R.string.set_a_brand_logo_unreachable)
                    else -> str(R.string.set_a_brand_logo_failed)
                }
            }
        }
    }
    fun resetBrandLogo() = viewModelScope.launch {
        c.settings.update { it.copy(brandLogoPath = null, brandLogoUrl = null) }
        BrandLogo.clear(getApplication())
        brandStatus.value = null
    }

    fun setNameStrip(s: String) = launchFor { c.db.providerDao().setNameStrip(it, s); remerge(listOf(it)) } // clean-up rules decide merges too

    /** Settings › Channels › Merge channels and its Merge options. Changing what decides a merge re-runs every playlist's. */
    val merging: StateFlow<Boolean> = org.imdtv.iptvtv.data.repo.ChannelMerge.running.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setMergeChannels(on: Boolean) = remergeAfter { it.copy(mergeChannels = on) }
    fun setMergeAcross(on: Boolean) = remergeAfter { it.copy(mergeAcross = on) }
    fun setMergeBy(by: org.imdtv.iptvtv.data.settings.MergeBy) = remergeAfter { it.copy(mergeBy = by) } // decides which row is kept
    /** Merge options › Playlists: one playlist's own switch. */
    fun setPlaylistMerge(id: Long, on: Boolean) = viewModelScope.launch {
        c.db.providerDao().setMergeOn(id, on)
        remerge(listOf(id))
    }
    private fun remergeAfter(f: (AppSettings) -> AppSettings) = viewModelScope.launch {
        c.settings.update(f)
        remerge(c.db.providerDao().ids())
    }
    private suspend fun remerge(ids: List<Long>) {
        val s = c.settings.settings.first()
        ids.forEach { org.imdtv.iptvtv.data.repo.ChannelMerge.applyAuto(c.db, it, org.imdtv.iptvtv.data.repo.MergeOptions(s.mergeChannels, s.mergeAcross, s.mergeBy == org.imdtv.iptvtv.data.settings.MergeBy.RESOLUTION)) }
    }

    /** The clean-up text being typed (null when not editing), so the preview follows it before Save. */
    val stripDraft = MutableStateFlow<String?>(null)

    /** First 8 channels whose shown name the clean-up list changes, as before → after. */
    val stripPreview: StateFlow<List<Pair<String, String>>> = perProvider(emptyList()) { p ->
        val names = flow { emit(c.db.channelDao().byProvider(p).filter { it.customName == null }.map { it.name }) }
        combine(names, nameStrip, stripDraft) { all, saved, draft ->
            val s = draft ?: saved
            val strip = parseStripList(s)
            if (strip.isEmpty()) emptyList()
            else all.asSequence().map { it to displayName(it, null, strip) }.filter { it.first != it.second }.take(8).toList()
        }.flowOn(Dispatchers.Default)
    }

    // --- Category name clean-up (same rules and page as the channel one) ---

    val categoryStrip: StateFlow<String> = perProvider("") { p -> c.db.providerDao().observe(p).map { it?.categoryStrip.orEmpty() } }
    fun setCategoryStrip(s: String) = launchFor { c.db.providerDao().setCategoryStrip(it, s) }
    val categoryStripDraft = MutableStateFlow<String?>(null)

    /** First 8 category names (every kind) the clean-up list changes, as before → after. */
    val categoryStripPreview: StateFlow<List<Pair<String, String>>> = perProvider(emptyList()) { p ->
        val names = flow { emit(c.db.categoryDao().allKinds(p).map { it.name }.distinct()) }
        combine(names, categoryStrip, categoryStripDraft) { all, saved, draft ->
            val strip = parseStripList(draft ?: saved)
            if (strip.isEmpty()) emptyList()
            else all.asSequence().map { it to displayName(it, null, strip) }.filter { it.first != it.second }.take(8).toList()
        }.flowOn(Dispatchers.Default)
    }

    val groups: StateFlow<List<CustomGroupEntity>> = perProvider(emptyList()) { c.db.customGroupDao().observe(it) }

    fun createGroup(name: String) = launchFor { c.db.customGroupDao().create(it, name.trim()) }
    fun renameGroup(g: CustomGroupEntity, name: String) { viewModelScope.launch { c.db.customGroupDao().rename(g.id, name.trim()) } }
    fun deleteGroup(g: CustomGroupEntity) { viewModelScope.launch { c.db.customGroupDao().delete(g.id) } }
    /** Saves your categories' order as shown (their sortOrder becomes the position). */
    fun saveGroupOrder(order: List<CustomGroupEntity>) {
        viewModelScope.launch { c.db.withTransaction { order.forEachIndexed { n, it -> c.db.customGroupDao().setSortOrder(it.id, n) } } }
    }

    /** Favourites (null) or one of your categories: its channels as shown, hidden ones left out. */
    private val favouriteChannels = perProvider(emptyList()) { c.db.channelDao().observeFavourites(it) }
    fun orderedChannels(groupId: Long?): kotlinx.coroutines.flow.Flow<List<org.imdtv.iptvtv.data.db.ChannelEntity>> =
        if (groupId == null) favouriteChannels else c.db.customGroupDao().observeChannels(groupId)

    /** Saves the channel order as shown (sortOrder becomes the position). */
    fun saveChannelOrder(groupId: Long?, order: List<org.imdtv.iptvtv.data.db.ChannelEntity>) {
        viewModelScope.launch { c.db.withTransaction { order.forEachIndexed { n, ch ->
            if (groupId == null) c.db.favouriteDao().setSortOrder(ch.providerId, Kind.LIVE, ch.id, n) else c.db.customGroupDao().setMemberOrder(groupId, ch.id, n)
        } } }
    }

    fun setGroupPlacement(g: CustomGroupEntity, p: org.imdtv.iptvtv.data.db.GroupPlacement) {
        viewModelScope.launch { c.db.customGroupDao().setPlacement(g.id, p) }
    }

    // --- Backup & restore (spec §7–8) ---

    val backupStatus = MutableStateFlow<String?>(null)
    val backupFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    /** The chosen backup, decoded, while logins are asked for; null otherwise. */
    val pendingRestore = MutableStateFlow<Backup?>(null)
    val restoreRunning = MutableStateFlow(false)

    fun backUpNow() {
        if (restoreRunning.value) return
        backupStatus.value = str(R.string.set_b_saving)
        viewModelScope.launch {
            backupStatus.value = try { writeBackup(getApplication(), kotlinx.coroutines.withContext(Dispatchers.Default) { encodeBackup(c.backup.create()) }, settings.value.backupFolder) }
            catch (e: Exception) { str(R.string.set_b_backup_save_failed, e.message ?: e::class.simpleName ?: "") }
        }
    }

    fun refreshBackups() { viewModelScope.launch { backupFiles.value = listBackups(getApplication(), settings.value.backupFolder) } }
    fun setBackupFolder(path: String?) = update { it.copy(backupFolder = path) }

    /** An unreadable or newer file only shows its message; nothing changes. */
    fun openBackup(f: SharedFile) {
        viewModelScope.launch {
            try {
                val text = readBackup(getApplication(), f.uri)
                pendingRestore.value = kotlinx.coroutines.withContext(Dispatchers.Default) { decodeBackup(text) }
                backupStatus.value = null
            } catch (e: BackupException) {
                backupStatus.value = e.localized(getApplication())
            } catch (e: java.io.IOException) {
                backupStatus.value = str(R.string.set_b_backup_unreadable)
            }
        }
    }

    /** Already on this box with a login: restore keeps that login instead of asking again. */
    fun hasSavedLogin(p: BPlaylist) = providers.value.any {
        it.type.name == p.type && when (it.type) {
            org.imdtv.iptvtv.data.db.ProviderType.XTREAM, org.imdtv.iptvtv.data.db.ProviderType.STALKER, org.imdtv.iptvtv.data.db.ProviderType.DISPATCHARR -> it.url == p.url && !it.username.isNullOrBlank()
            org.imdtv.iptvtv.data.db.ProviderType.M3U -> org.imdtv.iptvtv.data.security.m3uBackupUrl(it.url) == p.url // same playlist, login in its address
            org.imdtv.iptvtv.data.db.ProviderType.PLEX, org.imdtv.iptvtv.data.db.ProviderType.JELLYFIN, org.imdtv.iptvtv.data.db.ProviderType.STREMIO -> it.url == p.url && !it.password.isNullOrBlank()
        }
    }

    fun cancelRestore() { if (!restoreRunning.value) pendingRestore.value = null }

    /** [logins] by playlist index; a playlist that needs a login and has none here was skipped by the user. */
    fun restore(b: Backup, logins: Map<Int, Pair<String, String>>) {
        if (restoreRunning.value) return
        restoreRunning.value = true
        viewModelScope.launch {
          try {
            var applied = 0
            var skipped = 0
            val failed = mutableListOf<String>()
            b.playlists.forEachIndexed { i, pl ->
                val login = logins[i]
                if (needsLogin(pl) && login == null && !hasSavedLogin(pl)) return@forEachIndexed
                backupStatus.value = str(R.string.set_b_restoring, pl.name)
                try {
                    // Dispatcharr: the login typed is its own; the playlist keeps the XC login it gives.
                    val xc = if (login != null && pl.type == org.imdtv.iptvtv.data.db.ProviderType.DISPATCHARR.name)
                        c.dispatcharr.xcLogin(pl.url, login.first, login.second).let { it.username to it.password } else login
                    val r = c.backup.restorePlaylist(pl, xc?.first, xc?.second) { backupStatus.value = str(R.string.set_b_playlist_progress, pl.name, it) }
                    c.db.providerDao().all().find { it.url == pl.url && it.type.name == pl.type }?.let { c.epgMatcher.rematch(it.id) }
                    applied += r.applied; skipped += r.skipped
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    failed += "${pl.name} (${(e as? BackupException)?.localized(getApplication()) ?: e.message ?: e::class.simpleName})"
                }
            }
            c.backup.restoreSettings(b.settings)
            EpgRefreshWorker.runNow(getApplication(), org.imdtv.iptvtv.work.GuideUpdateReason.RESTORE)
            backupStatus.value = if (failed.isEmpty()) str(R.string.set_b_restored, applied, skipped)
                else str(R.string.set_b_restored_failed, applied, skipped, failed.joinToString())
            pendingRestore.value = null
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            backupStatus.value = str(R.string.set_b_restore_failed, e.message ?: e::class.simpleName ?: "")
          } finally {
            restoreRunning.value = false // else Restore stays blocked until the app restarts
          }
        }
    }
}

/** Xtream and Stalker (MAC) always; M3U when its backed-up address had its login taken out. */
fun needsLogin(p: BPlaylist) = p.type == org.imdtv.iptvtv.data.db.ProviderType.XTREAM.name || p.type == org.imdtv.iptvtv.data.db.ProviderType.STALKER.name ||
    p.type == org.imdtv.iptvtv.data.db.ProviderType.DISPATCHARR.name ||
    (p.type == org.imdtv.iptvtv.data.db.ProviderType.M3U.name && org.imdtv.iptvtv.data.security.hasLoginPlaceholders(p.url))
