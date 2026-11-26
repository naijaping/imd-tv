package org.imdtv.iptvtv.ui.live

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.imdtv.iptvtv.IptvApp
import org.imdtv.iptvtv.MainActivity
import org.imdtv.iptvtv.data.db.CategoryEntity
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.db.ProviderType
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.data.model.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real app on a seeded playlist (12 categories): Live TV guide → category panel → Up past the first category.
 * Before the fix focus went to the top menu's Live with the panel still open; Down then put it on a guide row behind
 * the panel, which swallowed every key (Back too): the app looked frozen.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class RailTopFreezeTest {
    val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() = runBlocking {
            val app = ApplicationProvider.getApplicationContext<IptvApp>()
            runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
            val c = app.container
            val p = c.db.providerDao().insert(ProviderEntity(name = "Test", type = ProviderType.M3U, url = "http://127.0.0.1:9/x.m3u", username = null, password = null,
                userAgent = null, streamFormat = StreamFormat.TS, epgOverrideUrls = emptyList(), lastSync = System.currentTimeMillis()))
            c.db.categoryDao().upsert((1..12).map { CategoryEntity(id = it.toLong(), providerId = p, kind = Kind.LIVE, remoteId = "$it", name = "Category $it", sortOrder = it) })
            c.db.channelDao().upsert((1..60).map { ChannelEntity(id = it.toLong(), providerId = p, categoryId = ((it - 1) % 12 + 1).toLong(), remoteId = "$it", name = "Channel $it",
                logo = null, number = it, tvgId = null, streamUrl = "http://127.0.0.1:9/$it.ts") })
            c.providerRepository.setActive(p)
        }
    }).around(rule)

    /** The focused node: its text (merged) and test tag. */
    private fun focused(): String = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes()
        .joinToString { n -> (n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "") + "|" + (n.config.getOrNull(SemanticsProperties.TestTag) ?: "") }

    /** Waits for [cond] in real time too: the app's startup work runs on background threads, which waitUntil alone outpaced. */
    private fun waitFor(ms: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            runCatching { rule.waitUntil(250) { cond() } }
            Thread.sleep(20)
        }
        assertTrue("timed out; focus: ${focused()}", cond())
    }

    /** To the top window: a menu's dialog while one is open. */
    private fun press(k: Key, times: Int = 1) = repeat(times) { rule.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(k) }; rule.waitForIdle() }
    private fun panelOpen() = rule.onAllNodes(SemanticsMatcher("panel") { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == rule.activity.getString(org.imdtv.iptvtv.R.string.live_categories_header) } == true }, true)
        .fetchSemanticsNodes().isNotEmpty()

    /** A held OK: its first repeat is the long press. */
    private fun longOk() {
        rule.runOnUiThread {
            val t = android.os.SystemClock.uptimeMillis()
            val c = android.view.KeyEvent.KEYCODE_DPAD_CENTER
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, c, 0))
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(t, t + 500, android.view.KeyEvent.ACTION_DOWN, c, 1))
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(t, t + 600, android.view.KeyEvent.ACTION_UP, c, 0))
        }
        rule.waitForIdle()
    }

    private fun start() {
        waitFor(90_000) { rule.onAllNodes(SemanticsMatcher("row") { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Channel 1" } == true }, true).fetchSemanticsNodes().isNotEmpty() }
        press(Key.DirectionLeft) // Left on now (no catch-up): opens the panel on the category showing, the first
        assertTrue(focused(), focused().startsWith("Category 1,"))
    }

    /** One app run (its settings store is per process), the scenarios in turn. */
    @Test fun panelKeepsFocusAndTheMenuChangesNothing() {
        start()
        // 1. Up on the first category (held: many presses) stays there; the panel stays open.
        press(Key.DirectionUp, 15)
        assertTrue("focus left the panel: ${focused()}", focused().startsWith("Category 1,"))
        assertTrue(panelOpen())
        // Down through every category to the last; more Down stays there (no wrap); back up to the first.
        press(Key.DirectionDown, 20)
        assertTrue(focused(), focused().startsWith("Category 12,"))
        press(Key.DirectionUp, 11)
        assertTrue(focused(), focused().startsWith("Category 1,"))
        // 2. Back closes it onto the grid, which takes keys again.
        press(Key.Back)
        assertFalse(panelOpen())
        waitFor(15_000) { focused().contains("Channel 1,") }
        press(Key.DirectionDown)
        waitFor(15_000) { focused().contains("Channel 13,") }
        press(Key.DirectionUp)
        // 3. A category's long-press menu takes focus without closing the panel; Back returns to the panel.
        press(Key.DirectionLeft); press(Key.DirectionDown)
        longOk()
        waitFor(15_000) { rule.onAllNodes(SemanticsMatcher("menu") { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Category 2") && it.text.contains("·") } == true }, true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the menu closed the panel", panelOpen())
        // Back on the menu (test key input doesn't reach a dialog's Back, so through the dialog itself).
        rule.runOnUiThread { org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed() }
        rule.waitForIdle()
        assertTrue(panelOpen())
        waitFor(15_000) { focused().startsWith("Category 2,") }
        // 4. Right picks the highlighted category; so does OK.
        press(Key.DirectionRight)
        assertFalse(panelOpen())
        waitFor(15_000) { focused().contains("Channel 2,") }
        press(Key.DirectionLeft); press(Key.DirectionDown); press(Key.DirectionCenter)
        assertFalse(panelOpen())
        waitFor(15_000) { focused().contains("Channel 3,") }
        // 5. Up from the top row reaches the top menu, as designed; OK on Live while on Live changes nothing.
        press(Key.DirectionUp)
        assertEquals("Live|menu_LIVE", focused())
        val app = androidx.lifecycle.ViewModelProvider(rule.activity)[org.imdtv.iptvtv.ui.AppViewModel::class.java]
        val stack = app.stack.value
        press(Key.DirectionCenter)
        assertTrue("Live re-selected the screen", stack === app.stack.value)
        assertEquals("Live|menu_LIVE", focused())
        press(Key.DirectionDown)
        waitFor(15_000) { focused().contains("Channel 3,") }
        press(Key.DirectionDown)
        waitFor(15_000) { focused().contains("Channel 15,") }
    }
}
