package com.pdfpaper.app.ui.safety

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import com.pdfpaper.app.ui.viewer.ExternalLinkDialog
import com.pdfpaper.app.ui.viewer.ExternalLinkRequest
import com.pdfpaper.safety.LinkSafety
import com.pdfpaper.safety.RiskLevel
import com.pdfpaper.safety.SafetyCategory
import com.pdfpaper.safety.SafetyFinding
import com.pdfpaper.safety.SafetyLocation
import com.pdfpaper.safety.SafetyReport
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T49: the safety UI never hides a finding behind an automatic action and the
 * link prompt always names the real domain.
 */
@RunWith(AndroidJUnit4::class)
class SafetyUiDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bannerShowsTheRiskAndOpensTheDetailsScreen() {
        var opened = false
        var dismissed = false
        val report = SafetyReport(
            risk = RiskLevel.HIGH,
            findings = listOf(
                SafetyFinding(
                    id = "js|1",
                    category = SafetyCategory.JAVASCRIPT,
                    severity = RiskLevel.HIGH,
                    title = "Embedded JavaScript",
                    explanation = "The file contains JavaScript.",
                    location = SafetyLocation(pageIndex = 0, objectNumber = 7)
                )
            )
        )
        compose.setContent {
            PDFPaperTheme {
                SafetyBanner(
                    report = report,
                    onOpenDetails = { opened = true },
                    onDismiss = { dismissed = true }
                )
            }
        }

        compose.onNodeWithTag(UiTags.SAFETY_BANNER).assertIsDisplayed()
        compose.onNodeWithText("Safety check: high risk").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SAFETY_BANNER_DETAILS).performClick()
        assertTrue("Details must open the report", opened)
        compose.onNodeWithTag(UiTags.SAFETY_BANNER_DISMISS).performClick()
        assertTrue("Dismiss must hide the banner", dismissed)
    }

    @Test
    fun externalLinkPromptNamesTheRealDomainAndOffersCopy() {
        var copied = false
        val safety = LinkSafety(
            uri = "https://bit.ly/abc",
            scheme = "https",
            host = "bit.ly",
            displayDomain = "bit.ly",
            visibleText = "bank.example.com",
            warnings = listOf(
                "This is a link shortener.",
                "The visible text names bank.example.com, but the link really goes to bit.ly."
            ),
            risk = RiskLevel.HIGH
        )
        compose.setContent {
            PDFPaperTheme {
                ExternalLinkDialog(
                    request = ExternalLinkRequest(uri = safety.uri, safety = safety),
                    onDismiss = {},
                    onCopy = { copied = true },
                    onOpen = {}
                )
            }
        }

        compose.onNodeWithTag(UiTags.EXTERNAL_LINK_DIALOG).assertIsDisplayed()
        compose.onNodeWithText("Real domain: bit.ly").assertIsDisplayed()
        compose.onNodeWithText("Visible link text: bank.example.com").assertIsDisplayed()
        compose.onNodeWithText("• This is a link shortener.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.EXTERNAL_LINK_COPY).performClick()
        assertTrue("Copy link must copy without opening the address", copied)
    }
}
