package org.imdtv.iptvtv.ui.live

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import org.imdtv.iptvtv.data.db.CategoryEntity
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.data.repo.RailEntry
import org.imdtv.iptvtv.data.repo.RailItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** The category panel reports each highlight (the guide previews it) and keeps focus while it does; OK, Right and Back still act. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class RailPreviewTest {
    val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }).around(rule)

    private fun cat(id: Long, name: String) = RailEntry.Provider(CategoryEntity(id = id, providerId = 1, kind = Kind.LIVE, remoteId = "$id", name = name))
    private val items = listOf(RailItem(RailEntry.Favourites, 3), RailItem(cat(1, "UK Entertainment"), 40), RailItem(cat(2, "UK News"), 12), RailItem(cat(3, "Sport"), 0))

    private val highlights = mutableListOf<String>()
    private val picks = mutableListOf<String>()
    private var closed = 0

    private fun show() = rule.setContent {
        val focus = remember { FocusRequester() }
        Rail(items, "fav", focus, null, keepKey = null, focusKey = null, onFocusKeyDone = {},
            onPick = { picks += it.key }, onLongPress = {}, onMove = {}, onMoveEnd = {}, onClose = { closed++ },
            onHighlight = { highlights += it.key })
        LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; focus.requestFocus() } // as the guide: after the first frame
    }

    @Test fun downReportsEachHighlightAndKeepsFocus() {
        show()
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        assertEquals(listOf("fav", "c1", "c2"), highlights)
        rule.onNodeWithText("UK News").assertIsFocused() // focus stayed in the panel, on the highlight
        assertEquals(emptyList<String>(), picks) // moving picks nothing
    }

    @Test fun rightPicksAndBackCloses() {
        show()
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(listOf("c1"), picks)
        rule.onRoot().performKeyInput { pressKey(Key.Back) }
        rule.waitForIdle()
        assertEquals(1, closed)
    }
}
