package org.imdtv.iptvtv.work

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Updating guide…" only for updates the user waits for; automatic triggers never stack. */
class GuideUpdateReasonTest {
    @Test fun onlyWaitedForUpdatesShowTheMessage() {
        assertEquals(setOf(GuideUpdateReason.FIRST_GUIDE, GuideUpdateReason.PLAYLIST_ADDED, GuideUpdateReason.USER_REFRESH,
            GuideUpdateReason.SETTINGS_CHANGE, GuideUpdateReason.RESTORE), GuideUpdateReason.entries.filter { it.visible }.toSet())
    }

    @Test fun automaticTriggersAreIgnoredWhileOneIsQueuedOrRunning() {
        // Nothing queued: an automatic trigger keeps whatever exists (KEEP), so it never replaces a running update.
        assertEquals(ExistingWorkPolicy.KEEP, EpgRefreshWorker.policy(emptyList(), automatic = true))
        assertEquals(ExistingWorkPolicy.KEEP, EpgRefreshWorker.policy(listOf(WorkInfo.State.RUNNING), automatic = true))
        // App start with "Update when the app starts" off: nothing at all.
        assertNull(EpgRefreshWorker.policy(emptyList(), automatic = true, backlogOnly = true))
        // A user's change while one is queued: that queued run already reads the change.
        assertNull(EpgRefreshWorker.policy(listOf(WorkInfo.State.ENQUEUED), automatic = false))
    }
}
