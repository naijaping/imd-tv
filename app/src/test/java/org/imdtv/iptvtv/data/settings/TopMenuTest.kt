package org.imdtv.iptvtv.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopMenuTest {
    private val ids = listOf("SEARCH", "LIVE", "MOVIES", "SERIES", "RECORDINGS", "SETTINGS")

    @Test fun defaultsAndNewItems() {
        assertEquals(ids, TopMenu.normalized(emptyList(), ids).map { it.id })
        // Saved order kept; an id the app no longer has is dropped, a repeat ignored, items new to the app go last.
        val saved = listOf(TopMenuEntry("SETTINGS", "Setup"), TopMenuEntry("GONE"), TopMenuEntry("LIVE", iconOnly = true), TopMenuEntry("SETTINGS"))
        val n = TopMenu.normalized(saved, ids)
        assertEquals(listOf("SETTINGS", "LIVE", "SEARCH", "MOVIES", "SERIES", "RECORDINGS"), n.map { it.id })
        assertEquals("Setup", n[0].name); assertEquals(true, n[1].iconOnly); assertEquals(TopMenuEntry("SEARCH"), n[2])
        // Settings can never go missing: it's always added back.
        assertEquals("SETTINGS", TopMenu.normalized(listOf(TopMenuEntry("LIVE")), ids).last().id)
    }

    @Test fun names() {
        assertEquals("My TV", TopMenu.cleanName("  My   TV  "))
        assertNull(TopMenu.cleanName("   "))
        assertNull(TopMenu.cleanName(null))
        assertEquals(16, TopMenu.cleanName("A very long menu name indeed")!!.length)
        assertEquals("A very long menu", TopMenu.cleanName("A very long menu name indeed"))
    }

    @Test fun moveAndStore() {
        val l = TopMenu.normalized(emptyList(), ids)
        assertEquals(listOf("LIVE", "SEARCH"), TopMenu.move(l, "LIVE", -1).take(2).map { it.id })
        assertEquals(l, TopMenu.move(l, "SEARCH", -1)) // already first
        assertEquals("SETTINGS", TopMenu.move(l, "SETTINGS", 1).last().id)
        val custom = TopMenu.move(l, "SETTINGS", -5).map { if (it.id == "LIVE") it.copy(name = "TV", iconOnly = true) else it }
        assertEquals(custom, TopMenu.decode(TopMenu.encode(custom)))
        assertEquals(emptyList<TopMenuEntry>(), TopMenu.decode("not json"))
    }
}
