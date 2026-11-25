package org.imdtv.iptvtv.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.repo.GuideProgramme
import org.imdtv.iptvtv.data.settings.HERO_SCALES
import org.imdtv.iptvtv.data.settings.HeaderSize
import org.imdtv.iptvtv.ui.theme.Inter
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The guide header rendered for real (Inter, the app's 960×540dp canvas) at every Header size × Header channel logo
 * × Header title size, Glass and Liquid heights, short and long titles, at 1080p and 720p densities:
 * always logo → title → description → meta line, stacked on one left edge, inside the header's height.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land")
class HeroLayoutTest {
    val rule = createComposeRule()
    /** The compose rule's host activity, registered here instead of shipping ui-test-manifest's into the debug APK. */
    @get:Rule val chain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }).around(rule)

    private val now = 1_700_000_000_000L
    private val ch = ChannelEntity(id = 1, providerId = 1, categoryId = null, remoteId = "1", name = "BBC One FHD", logo = null, number = 101, tvgId = null, streamUrl = "http://x")
    private val desc = "10/12. A recovery truck smashes into a mother and daughter's SUV. A moped claim raises suspicions of an organised scam. " +
        "Could the claimant's own footage give the game away? Meanwhile a lorry driver is caught out on the motorway."
    private val short = "Dashcam Justice"
    private val long = "Patrick Christys Tonight Late Edition: The Big Debate, Live From Westminster With Guests And Your Calls"

    private class Case(val size: HeaderSize, val logo: Float, val title: Float, val room: Float, val name: String)

    // The screen itself at that density (1920×1080 px at 2×, 1280×720 px at 1.33×), so 960dp fills it as on a TV.
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi") @Test fun every_size_at_1080p() = check(Density(2f, 1f))
    @Config(qualifiers = "w960dp-h540dp-land-tvdpi") @Test fun every_size_at_720p() = check(Density(4f / 3f, 1f))

    private fun check(density: Density) {
        var case by mutableStateOf(Case(HeaderSize.LARGE, 1f, 1f, 204f, short))
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides density,
                androidx.tv.material3.LocalTextStyle provides androidx.tv.material3.LocalTextStyle.current.copy(fontFamily = Inter)) {
                Box(Modifier.size(960.dp, 540.dp)) {
                    val c = case
                    Hero(ch, "BBC One FHD", listOf(GuideProgramme(c.name, desc, null, now - 4 * 60_000, now + 26 * 60_000)), now, emptyList(),
                        listOf("FULL HD", "25 FPS"), Modifier.padding(start = 24.dp, top = 80.dp).width(575.dp).testTag("hero"),
                        c.size, logoScale = c.logo, titleScale = c.title, maxHeight = c.room.dp)
                }
            }
        }
        var checked = 0
        // Room above the "◀ category" line: Glass (Large/Medium/Small) and Liquid Glass.
        val rooms = mapOf(HeaderSize.LARGE to listOf(204f, 208f), HeaderSize.MEDIUM to listOf(134f, 138f), HeaderSize.SMALL to listOf(74f, 78f))
        for (size in HeaderSize.entries) for (room in rooms.getValue(size)) for (logo in HERO_SCALES) for (title in HERO_SCALES) for (name in listOf(short, long)) {
            case = Case(size, logo, title, room, name)
            rule.waitForIdle()
            val where = "$size room=$room logo=$logo title=$title ${if (name == long) "long" else "short"} @${density.density}"
            val top = bounds("hero")!!.top
            val logoB = bounds("heroLogo")!!
            val titleB = bounds("heroTitle")!!
            val metaB = bounds("heroMeta")!!
            val descB = bounds("heroDesc")
            assertTrue("$where: no progress bar in the header", bounds("heroProgress") == null)
            // Logo, title, description, meta line (the reference order).
            val parts = listOfNotNull(logoB, titleB, descB, metaB)
            // Stacked in order, never side by side.
            for ((a, b) in parts.zipWithNext()) assertTrue("$where: overlap/order ${a.bottom} > ${b.top}", b.top >= a.bottom - .5.dp)
            // One left edge for logo, title and meta line.
            for (p in listOf(titleB, metaB)) assertTrue("$where: left ${p.left} != ${logoB.left}", kotlin.math.abs((p.left - logoB.left).value) < .6f)
            // Inside the header: nothing reaches the "◀ category" line, nothing crosses its width.
            for (p in parts) {
                assertTrue("$where: bottom ${p.bottom - top} > $room", (p.bottom - top).value <= room + .6f)
                assertTrue("$where: right ${p.right}", p.right.value <= 24f + 575f + .6f)
            }
            // Large and Medium keep a description at the default sizes; it's never more than 2 lines.
            if (logo == 1f && title == 1f && size != HeaderSize.SMALL) assertTrue("$where: desc", descB != null)
            descB?.let { assertTrue("$where: desc ${it.bottom - it.top} tall", (it.bottom - it.top).value <= 2 * 19f + 7f + .6f) }
            checked++
        }
        assertTrue(checked == 3 * 2 * HERO_SCALES.size * HERO_SCALES.size * 2)
    }

    private fun bounds(tag: String): DpRect? =
        if (rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) null
        else rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
}
