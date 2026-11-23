package org.imdtv.iptvtv.ui.live

import org.imdtv.iptvtv.data.settings.HERO_SCALES
import org.imdtv.iptvtv.data.settings.HeaderSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The header plan: one stacked layout, sizes shrink to fit; see HeroLayoutTest for the rendered check. */
class HeroFitTest {
    private val glass = mapOf(HeaderSize.LARGE to 204f, HeaderSize.MEDIUM to 134f, HeaderSize.SMALL to 74f)

    @Test fun defaultsAreUntouched() {
        for ((size, room) in glass) assertEquals(1f, planHero(HeroBlock.of(size), 1f, 1f, true, true, room).logo)
    }

    @Test fun largeAndMediumKeepProgressAtDefault() {
        assertTrue(planHero(HeroBlock.of(HeaderSize.LARGE), 1f, 1f, true, true, 204f).progress)
        assertTrue(planHero(HeroBlock.of(HeaderSize.MEDIUM), 1f, 1f, true, true, 134f).progress)
    }

    @Test fun unboundedKeepsTheChoice() {
        val p = planHero(HeroBlock.of(HeaderSize.LARGE), 2f, 2f, true, true, Float.POSITIVE_INFINITY)
        assertEquals(HeroPlan(2f, 2f, 2, true), p)
    }

    @Test fun everyCombinationFits() {
        for ((size, room) in glass) for (l in HERO_SCALES) for (t in HERO_SCALES) {
            val b = HeroBlock.of(size)
            val p = planHero(b, l, t, true, true, room)
            assertTrue("$size $l $t", heroHeight(b, p.logo, p.title, p.titleLines, p.progress, 0) <= room)
            assertTrue(p.logo <= l && p.title <= t)
        }
    }

    @Test fun shrinksBothTogether() {
        val p = planHero(HeroBlock.of(HeaderSize.MEDIUM), 2f, 2f, true, true, 134f)
        assertTrue(p.logo < 2f && p.title < 2f)
        assertEquals(p.logo, p.title, .001f)
    }
}
