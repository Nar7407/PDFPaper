package com.pdfpaper.engine.stamp

import com.pdfpaper.engine.create.imageBytes
import com.pdfpaper.engine.graphics.PdfColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T23 acceptance: the stamp library is data — grouped designs carrying an id,
 * a label, colours and a layout that a CLI or a picker lists without any
 * hard-coded markup.
 */
class StampLibraryTest {

    @Test
    fun groupsAreOrderedAndPopulated() {
        val groups = StampCatalogue().groups()
        assertEquals(
            listOf(StampGroup.DYNAMIC, StampGroup.SIGN_HERE, StampGroup.STANDARD_BUSINESS, StampGroup.CUSTOM),
            groups.map { it.first }
        )
        assertEquals(
            listOf("Dynamic", "Sign here", "Standard business", "Custom"),
            groups.map { it.first.label }
        )
        for ((group, stamps) in groups) {
            if (group == StampGroup.CUSTOM) {
                assertTrue(stamps.isEmpty(), "a fresh catalogue has no custom stamps")
            } else {
                assertTrue(stamps.isNotEmpty(), "$group is empty")
            }
        }
    }

    @Test
    fun designsCarryValidData() {
        val stamps = StampLibrary.builtIn
        assertTrue(stamps.size >= 12, "the library should cover all four groups generously")
        assertEquals(stamps.size, stamps.map { it.id.lowercase() }.toSet().size, "duplicate built-in id")
        for (design in stamps) {
            assertTrue(design.id.isNotBlank(), "blank id")
            assertTrue(design.label.isNotBlank(), "${design.id} has a blank label")
            assertTrue(design.word.isNotBlank(), "${design.id} has no word")
            assertTrue(design.aspect in 0.5..8.0, "${design.id} aspect ${design.aspect}")
            for (color in listOfNotNull(design.textColor, design.borderColor, design.fillColor)) {
                for (component in listOf(color.red, color.green, color.blue)) {
                    assertTrue(component in 0.0..1.0, "${design.id} colour component $component")
                }
            }
            if (design.dynamic) {
                assertEquals(StampGroup.DYNAMIC, design.group, "${design.id} is dynamic outside the Dynamic group")
            }
        }
    }

    @Test
    fun approvedIsDynamicWithAStandardName() {
        val approved = assertNotNull(StampLibrary.find("approved"))
        assertTrue(approved.dynamic)
        assertEquals(StampGroup.DYNAMIC, approved.group)
        assertEquals("Approved", approved.standardName)
        assertEquals("APPROVED", approved.word)
        assertEquals(approved, StampLibrary.find("APPROVED"))
        assertNull(StampLibrary.find("nope"))
    }

    @Test
    fun catalogueAddsAndOverridesCustomStamps() {
        val override = CustomStamp.text("approved", "Mine", "OK", textColor = PdfColor(0.0, 0.4, 0.0)).getOrThrow()
        val logo = CustomStamp.image("logo", "Our logo", imageBytes("png", 4, 2)).getOrThrow()
        val catalogue = StampCatalogue(listOf(override, logo))

        val found = assertNotNull(catalogue.find("approved"))
        assertEquals(StampGroup.CUSTOM, found.group)
        assertEquals("Mine", found.label)
        assertEquals(override, catalogue.custom("approved"))
        assertNull(catalogue.custom("paid"))

        val custom = catalogue.groups().first { it.first == StampGroup.CUSTOM }.second
        assertEquals(listOf("approved", "logo"), custom.map { it.id })
        assertEquals(2.0, custom[1].aspect)
    }
}
