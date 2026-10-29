package com.universalir

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test through the real activity: startup seeding, Home and the Add-device flow.
 * Assumes onboarding was completed or skips it when shown.
 */
@RunWith(AndroidJUnit4::class)
class NavigationAndRtlTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun str(id: Int) = rule.activity.getString(id)

    @Test
    fun startupReachesHomeAndOpensAddDevice() {
        rule.waitUntil(90_000) {
            runCatching { rule.onNodeWithText(str(R.string.action_skip)).assertIsDisplayed() }.isSuccess ||
                runCatching { rule.onNodeWithText(str(R.string.home_your_devices)).assertIsDisplayed() }.isSuccess ||
                runCatching { rule.onNodeWithText(str(R.string.empty_devices_title)).assertIsDisplayed() }.isSuccess
        }
        runCatching { rule.onNodeWithText(str(R.string.action_skip)).performClick() }
        rule.waitUntil(60_000) { rule.onAllNodes(androidx.compose.ui.test.hasText(str(R.string.home_add_device))).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(androidx.compose.ui.test.hasText(str(R.string.home_add_device)))[0].performClick()
        rule.waitUntil(60_000) { rule.onAllNodes(androidx.compose.ui.test.hasText(str(R.string.cat_ac))).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(str(R.string.cat_ac)).performClick()
        rule.waitUntil(60_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Tadiran")).fetchSemanticsNodes().isNotEmpty() }
    }
}
