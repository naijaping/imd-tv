package org.imdtv.iptvtv.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.imdtv.iptvtv.IptvApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.imdtv.iptvtv.data.epg.EpgImporter
import org.imdtv.iptvtv.data.epg.epgErrorStatus
import org.imdtv.iptvtv.data.epg.epgOkStatus
import org.imdtv.iptvtv.data.epg.selectSources
import org.imdtv.iptvtv.data.db.ORIGIN_EPGSHARE
import org.imdtv.iptvtv.data.settings.GuideKind
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Why a guide update runs. [visible]: the user is waiting for it (asked for it, changed a guide setting, added a
 * playlist, or there's no guide yet), so the TV guide shows "Updating guide…"; the others run quietly.
 */
enum class GuideUpdateReason(val visible: Boolean) {
    /** The "Update the guide every" schedule. */ SCHEDULE(false),
    /** App start with "Update when the app starts" on. */ APP_START(false),
    /** A playlist with no guide data at all (its first download; a failed one is tried again at the next start). */ FIRST_GUIDE(true),
    /** The playlist's channels or guide addresses changed, with "Update when the playlist changes" on. */ PLAYLIST_CHANGED(false),
    /** A playlist was just added. */ PLAYLIST_ADDED(true),
    /** Settings › TV guide: Update now. */ USER_REFRESH(true),
    /** A guide setting changed (source added, a file on or off, the guide order): only that change needs it. */ SETTINGS_CHANGE(true),
    /** A backup was restored. */ RESTORE(true),
    /** A sync from the master added playlists. */ SYNC_NEW_PLAYLIST(false),
}

/** Imports every EPG source of every provider, then rematches channels to guide entries. */
class EpgRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val reason = inputData.getString(REASON) ?: GuideUpdateReason.SCHEDULE.name
        // One update at a time: the schedule and a one-off are separate WorkManager jobs and could overlap.
        if (!running.compareAndSet(false, true)) { debugLog("skipped ($reason): another update is running"); return Result.success() }
        val started = System.currentTimeMillis()
        debugLog("started ($reason)")
        try {
            return work().also { debugLog("finished ($reason) in ${System.currentTimeMillis() - started} ms: $it") }
        } finally { running.set(false) }
    }

    private suspend fun work(): Result {
        val c = (applicationContext as IptvApp).container
        val settings = c.settings.settings.first()
        val mode = settings.guideOrder
        val active = c.providerRepository.activeProviderId.first()
        var attempted = 0
        var failed = 0
        for (p in c.db.providerDao().all()) {
            if (p.type.mediaServer) {
                // No live channels. 0.7.0 could make Plex active, which gave it open-source guide files to import: drop them.
                c.db.epgDao().sources(p.id).forEach { s ->
                    c.db.programmeDao().deleteForSource(s.id); c.db.epgDao().deleteLinksForSource(s.id); c.db.epgDao().deleteSource(s.id)
                }
                continue
            }
            // epgshare01 files follow the active playlist's shown countries; other playlists keep theirs as they are.
            if (GuideKind.OPEN_SOURCE in mode && p.id == active) {
                runCatching { c.epgShare.sync(p.id) }.onFailure { e ->
                    if (e is CancellationException) throw e // a stopped run must not write "Job was cancelled" as a status
                    Log.w(EpgImporter.TAG, "epgshare01 list failed", e)
                    c.db.epgDao().sources(p.id).filter { it.origin == ORIGIN_EPGSHARE }.forEach { c.db.epgDao().setStatus(it.id, epgErrorStatus(e)) }
                }
            }
            val all = c.db.epgDao().sources(p.id)
            // A guide picked by hand stays fed even when its kind isn't in the order.
            val picked = c.db.epgDao().links(p.id).filter { it.manual }.mapTo(HashSet()) { it.sourceId }
            val sources = (selectSources(all, mode, c.providerRepository.playlistGuideUrls(p.id), settings.guidesOff) + all.filter { it.id in picked }).distinctBy { it.id }
            // Open-source mode keeps epgshare01 files for the active playlist only: another one would lose all its links.
            if (sources.isEmpty() && p.id != active) continue
            for (s in sources) {
                attempted++
                // One bad source must not block the others; keep its previous guide (importer is atomic).
                runCatching { c.epgImporter.import(s, settings.epgDaysToKeep, settings.epgPastDays) }
                    .onSuccess { c.db.epgDao().setStatus(s.id, epgOkStatus(applicationContext, System.currentTimeMillis(), it, ZoneId.systemDefault())) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        failed++
                        Log.w(EpgImporter.TAG, "EPG import failed for ${EpgImporter.logUrl(s.url)}", it)
                        c.db.epgDao().setStatus(s.id, epgErrorStatus(it))
                    }
            }
            val t = System.currentTimeMillis()
            val m = c.epgMatcher.rematch(p.id, mode)
            Log.i(EpgImporter.TAG, "Provider ${p.id}: ${m.matched} matched (${m.borrowed} borrowed), ${m.unmatched} unmatched in ${System.currentTimeMillis() - t} ms")
        }
        return if (shouldRetry(attempted, failed)) Result.retry() else Result.success()
    }

    companion object {
        /** A dead feed among good ones waits for the next period instead of re-importing every source. */
        fun shouldRetry(attempted: Int, failed: Int) = attempted > 0 && failed == attempted
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** One guide update at a time in this process (the app's only one). */
        private val running = java.util.concurrent.atomic.AtomicBoolean(false)
        private const val REASON = "reason"
        /** Tag of a run the user is waiting for ([GuideUpdateReason.visible]). */
        private const val VISIBLE = "guide-visible"
        const val LOG_TAG = "IptvGuideUpdate"

        /** Debug builds only: why each guide update was asked for, ran, or was left out. */
        fun debugLog(msg: String) { if (org.imdtv.iptvtv.BuildConfig.DEBUG) runCatching { Log.i(LOG_TAG, msg) } }

        /** UPDATE keeps the period's timing but applies a changed interval. */
        fun schedule(context: Context, intervalHours: Int) {
            debugLog("schedule: every $intervalHours h")
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "epg-refresh", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<EpgRefreshWorker>(intervalHours.toLong(), TimeUnit.HOURS).setConstraints(network)
                    .setInputData(androidx.work.workDataOf(REASON to GuideUpdateReason.SCHEDULE.name)).build(),
            )
        }

        /**
         * True while a refresh runs (or waits for the network after Update now). [visibleOnly]: only the ones the user
         * is waiting for (the TV guide's "Updating guide…"); the Settings page shows every one.
         */
        fun updating(context: Context, visibleOnly: Boolean = false): kotlinx.coroutines.flow.Flow<Boolean> =
            WorkManager.getInstance(context).getWorkInfosFlow(androidx.work.WorkQuery.fromUniqueWorkNames(listOf(NOW, "epg-refresh")))
                // The periodic job is ENQUEUED between runs, so only a one-off counts while it waits.
                .map { infos -> infos.filter { !visibleOnly || VISIBLE in it.tags }.any { it.state == WorkInfo.State.RUNNING ||
                    (it.state == WorkInfo.State.ENQUEUED && it.periodicityInfo == null) } }
                .distinctUntilChanged()

        /**
         * Runs a refresh now. Never cancels one already running: [automatic] triggers (app start, daily playlist sync)
         * leave a running or queued one alone; user changes queue one more run after it, so their change is included.
         * [backlogOnly]: only collapse a backlog (app start with "Update on start" off).
         */
        fun runNow(context: Context, reason: GuideUpdateReason, automatic: Boolean = false, backlogOnly: Boolean = false) {
            val wm = WorkManager.getInstance(context)
            val infos = wm.getWorkInfosForUniqueWork(NOW)
            infos.addListener({
                val states = runCatching { infos.get().map { it.state } }.getOrDefault(emptyList())
                val p = policy(states, automatic, backlogOnly)
                debugLog("asked ($reason): " + (p?.name ?: "ignored, one is already queued or running"))
                p?.let {
                    wm.enqueueUniqueWork(NOW, it, OneTimeWorkRequestBuilder<EpgRefreshWorker>().setConstraints(network)
                        .setInputData(androidx.work.workDataOf(REASON to reason.name))
                        .apply { if (reason.visible) addTag(VISIBLE) }.build())
                }
            }, Runnable::run)
        }

        private const val NOW = "epg-refresh-now"

        /**
         * A run still waiting reads the settings when it starts, so it already includes the latest change: appending one
         * per change (0.5.1 and older) queued runs that re-imported every guide back to back (stutter on slower boxes).
         * Such a backlog is replaced by a single run; null = nothing to enqueue.
         */
        fun policy(states: List<WorkInfo.State>, automatic: Boolean, backlogOnly: Boolean = false): ExistingWorkPolicy? {
            val waiting = states.count { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }
            return when {
                waiting > 1 -> ExistingWorkPolicy.REPLACE
                backlogOnly -> null
                automatic -> ExistingWorkPolicy.KEEP
                waiting == 1 -> null
                else -> ExistingWorkPolicy.APPEND_OR_REPLACE
            }
        }
    }
}
