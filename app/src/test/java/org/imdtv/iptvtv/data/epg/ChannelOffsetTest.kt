package org.imdtv.iptvtv.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Channel menu › Guide options › Guide time offset: the value in use, and the list it's marked in. */
class ChannelOffsetTest {
    @Test fun readsLikeTheMenu() {
        assertEquals("No offset", offsetText(0, "No offset"))
        assertEquals("+1h", offsetText(60, "No offset"))
        assertEquals("-30m", offsetText(-30, "No offset"))
        assertEquals("+1h 15m", offsetText(75, "No offset"))
        assertEquals("-12h", offsetText(-720, "No offset"))
    }

    @Test fun inUseIsTheOverrideElseTheGuides() {
        assertEquals(0, ChannelOffset(null, 0).inUse)    // none anywhere: No offset
        assertEquals(60, ChannelOffset(null, 60).inUse)  // the guide source's
        assertEquals(-30, ChannelOffset(-30, 60).inUse)  // the channel's own (set here or synced) wins
        assertEquals(0, ChannelOffset(0, 60).inUse)
    }

    @Test fun aCustomValueIsListedInItsPlace() {
        val steps = offsetChoices(0)
        assertEquals(49, steps.size); assertEquals(-720, steps.first()); assertEquals(720, steps.last())
        assertEquals(steps, offsetChoices(60)) // a preset: listed once
        val custom = offsetChoices(75)    // +1h 15m (e.g. from Settings › EPG in 15-minute steps, or a sync)
        assertEquals(50, custom.size)
        assertEquals(listOf(60, 75, 90), custom.subList(custom.indexOf(60), custom.indexOf(60) + 3))
        assertTrue(custom == custom.sorted())
    }
}
