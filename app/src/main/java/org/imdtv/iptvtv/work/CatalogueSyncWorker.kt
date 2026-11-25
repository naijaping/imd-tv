package org.imdtv.iptvtv.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import org.imdtv.iptvtv.IptvApp
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes

/** On launch and every Update playlists every hours: re-syncs catalogues that old, then rematches their EPG links. */
class CatalogueSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // The launch run and the periodic one may start together: one at a time, so the second finds the playlists fresh.
    override suspend fun doWork(): Result = running.withLock { work() }

    private suspend fun work(): Result {
        val c = (applicationContext as IptvApp).container
        var failed = false
        var synced = false
        // 30 min early still counts: the periodic run may start a little before the period is up.
        val maxAge = c.settings.settings.first().playlistUpdateHours.hours - 30.minutes
        for (p in c.db.providerDao().all()) {
            // "Update when the playlist changes": only when it did — its live channels or its guide addresses. New
            // channels match from the guide already on the box (rematch below); a same-as-before sync downloads nothing.
            val before = fingerprint(c, p.id)
            runCatching { c.providerRepository.syncIfStale(p.id, maxAge) }
                .onSuccess { if (it != null) { c.epgMatcher.rematch(p.id); if (fingerprint(c, p.id) != before) synced = true } }
                .onFailure { failed = true; Log.w(TAG, "Catalogue sync failed for provider ${p.id}", it) }
        }
        if (synced && c.settings.settings.first().epgUpdateOnPlaylistChange)
            EpgRefreshWorker.runNow(applicationContext, org.imdtv.iptvtv.work.GuideUpdateReason.PLAYLIST_CHANGED, automatic = true)
        return if (failed) Result.retry() else Result.success()
    }

    /** What the guide depends on in a playlist: its live channels' ids and its guide addresses. */
    private suspend fun fingerprint(c: org.imdtv.iptvtv.AppContainer, providerId: Long) =
        c.db.channelDao().sortedRemoteIds(providerId).hashCode() to c.providerRepository.playlistGuideUrls(providerId)

    companion object {
        const val TAG = "IptvSync"
        private val running = kotlinx.coroutines.sync.Mutex()

        /** UPDATE keeps the period's timing but applies a changed interval. */
        fun schedule(context: Context, hours: Int) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "catalogue-sync-periodic", androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                androidx.work.PeriodicWorkRequestBuilder<CatalogueSyncWorker>(hours.toLong(), java.util.concurrent.TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
            )
        }

        fun runOnLaunch(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "catalogue-sync", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CatalogueSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
