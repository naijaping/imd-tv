package org.imdtv.iptvtv.ui

import org.imdtv.iptvtv.ui.live.railLeftUnexpectedly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabShowingTest {
    @Test fun theTabShowingAtItsOwnScreenIsANoOp() {
        assertTrue(tabShowing(Tab.LIVE, Tab.LIVE, listOf(Screen.Live)))
        assertTrue(tabShowing(Tab.MOVIES, Tab.MOVIES, listOf(Screen.Vod)))
        assertFalse(tabShowing(Tab.SERIES, Tab.MOVIES, listOf(Screen.Vod))) // same screen, other kind: switches
        assertFalse(tabShowing(Tab.LIVE, Tab.LIVE, listOf(Screen.Live, Screen.Player("x", live = true)))) // back to the guide
        assertFalse(tabShowing(Tab.LIVE, Tab.MOVIES, listOf(Screen.Vod)))
        assertFalse(tabShowing(Tab.SETTINGS, Tab.LIVE, listOf(Screen.Live))) // Settings always opens
    }

    @Test fun onlyAnUnexpectedLossClosesThePanel() {
        assertTrue(railLeftUnexpectedly(open = true, menuOpen = false, moving = false))
        assertFalse(railLeftUnexpectedly(open = true, menuOpen = true, moving = false))  // its long-press menu
        assertFalse(railLeftUnexpectedly(open = true, menuOpen = false, moving = true))  // moving a category
        assertFalse(railLeftUnexpectedly(open = false, menuOpen = false, moving = false)) // closing anyway
    }
}
