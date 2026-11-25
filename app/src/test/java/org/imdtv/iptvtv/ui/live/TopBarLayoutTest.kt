package org.imdtv.iptvtv.ui.live

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.imdtv.iptvtv.ui.Tab
import org.imdtv.iptvtv.ui.TopClock
import org.imdtv.iptvtv.ui.TopNav
import org.imdtv.iptvtv.ui.theme.Inter
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Live TV guide's top bar on a 960×540dp TV (1080p and 720p): logo left, menu pill, time block right-aligned; with
 * every tab, the date and ping, at every Branding logo size, nothing overlaps and the block stays above the header (80dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land")
class TopBarLayoutTest {
    val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }).around(rule)

    // The screen itself at that density (1920×1080 px at 2×, 1280×720 px at 1.33×), so 960dp fills it as on a TV.
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi") @Test fun at1080p() = check(Density(2f, 1f))
    @Config(qualifiers = "w960dp-h540dp-land-tvdpi") @Test fun at720p() = check(Density(4f / 3f, 1f))

    private fun check(density: Density) {
        var scale by mutableStateOf(1f)
        var tabs by mutableStateOf(Tab.entries.toList())
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides density,
                androidx.tv.material3.LocalTextStyle provides androidx.tv.material3.LocalTextStyle.current.copy(fontFamily = Inter)) {
                Box(Modifier.size(960.dp, 540.dp)) {
                    TopBar(null, scale, { TopNav(Tab.LIVE, {}, tabs = tabs, trailing = false, idleAlpha = .45f) },
                        { TopClock(showClock = true, dateUnder = true, pingMs = 29) }, Modifier.padding(start = 24.dp, top = 22.dp, end = 24.dp))
                }
            }
        }
        for (t in listOf(Tab.entries.toList(), Tab.entries.filter { it != Tab.RECORDINGS })) for (s in org.imdtv.iptvtv.data.settings.BRAND_SCALES) {
            tabs = t; scale = s
            rule.waitForIdle()
            val where = "scale=$s tabs=${t.size} @${density.density}"
            val logo = rule.onNodeWithTag("topLogo").getUnclippedBoundsInRoot()
            val nav = rule.onNodeWithTag("topNav").getUnclippedBoundsInRoot()
            val clock = rule.onNodeWithTag("topClock").getUnclippedBoundsInRoot()
            assertTrue("$where: logo ${logo.right} into the menu ${nav.left}", logo.right <= nav.left + .5.dp)
            assertTrue("$where: menu ${nav.right} into the time ${clock.left}", nav.right <= clock.left + .5.dp)
            // tvdpi is 1.33125, not 4/3: that screen is 958.5dp wide, so allow 2dp.
            assertTrue("$where: time not at the right edge (${clock.right})", kotlin.math.abs(clock.right.value - 936f) < 2f)
            assertTrue("$where: top bar below the header line (${clock.bottom}, ${logo.bottom})", clock.bottom.value <= 80f && logo.bottom.value <= 80f && logo.top.value >= 0f)
        }
    }
}
