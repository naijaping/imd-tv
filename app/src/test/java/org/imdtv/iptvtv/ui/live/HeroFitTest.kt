package org.imdtv.iptvtv.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Header channel logo / Header title size at their largest: the header still fits above the "◀ category" line. */
class HeroFitTest {
    // A 960×540dp TV (1080p and 720p alike): Glass 204dp above the category line (less the header shift), Liquid 208dp.
    private val glassRoom = mapOf("LARGE" to 204f, "MEDIUM" to 134f, "SMALL" to 74f)
    private val liquidRoom = mapOf("LARGE" to 208f, "MEDIUM" to 138f, "SMALL" to 78f)
    private val titleSp = mapOf("LARGE" to 31f, "MEDIUM" to 31f * .85f, "SMALL" to 31f * .7f)

    private fun block(size: String, sky: Boolean): HeroBlock {
        val rowTop = size == "SMALL" || sky && size == "MEDIUM"
        val skySmall = sky && size == "SMALL"
        return HeroBlock(if (skySmall) 36f else 43f, titleSp.getValue(size), !rowTop, if (rowTop) (if (skySmall) 2f else 5f) else 14f,
            progress = size != "SMALL", descLine = if (size == "LARGE" || sky) 19f + (if (sky && size != "LARGE") 2f else 7f) else 0f)
    }

    @Test fun defaultsAreUntouched() {
        assertEquals(HeroScales(1f, 1f), fitHeroScales(1f, 1f, block("LARGE", false), 204f))
        assertEquals(HeroScales(.75f, .75f), fitHeroScales(.75f, .75f, block("SMALL", true), 74f))
    }

    @Test fun roomyHeaderKeepsTheChoice() {
        assertEquals(HeroScales(2f, 2f), fitHeroScales(2f, 2f, block("LARGE", false), Float.POSITIVE_INFINITY))
        assertEquals(HeroScales(1.5f, 1f), fitHeroScales(1.5f, 1f, block("LARGE", false), 204f))
    }

    @Test fun largestSizesFitEveryHeader() {
        for ((rooms, name) in listOf(glassRoom to "glass", liquidRoom to "liquid")) for (size in listOf("LARGE", "MEDIUM", "SMALL")) for (sky in listOf(false, true)) {
            val b = block(size, sky)
            val room = rooms.getValue(size)
            val f = fitHeroScales(2f, 2f, b, room)
            // Fits, unless even 1× doesn't (then nothing grew).
            assertTrue("$name $size sky=$sky: ${heroHeight(b, f.logo, f.title)} > $room",
                heroHeight(b, f.logo, f.title) <= room || f == HeroScales(1f, 1f))
            assertTrue(f.logo >= 1f && f.title >= 1f)
        }
    }

    @Test fun shrinksBothTogether() {
        val f = fitHeroScales(2f, 2f, block("SMALL", true), 74f)
        assertTrue(f.logo < 2f && f.title < 2f)
        assertEquals(f.logo, f.title, .001f)
    }
}
