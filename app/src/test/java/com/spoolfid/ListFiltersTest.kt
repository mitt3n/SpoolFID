package com.spoolfid

import com.spoolfid.spoolman.Spool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListFiltersTest {
    private val catalog = MaterialCatalog()

    private fun spool(
        id: Int = 7,
        material: String? = "PLA",
        color: String? = "FF0000",
        weight: Double? = 1000.0,
        remaining: Double? = 800.0,
        location: String? = null,
        tags: Int = 0,
        archived: Boolean = false,
        multi: Boolean = false,
        unlinked: Int = 0,
    ) = Spool(
        id = id, filamentName = "Test", vendor = null, material = material, colorHex = color, filamentWeight = weight,
        initialWeight = weight, remainingWeight = remaining, location = location, extra = emptyMap(), multiColor = multi,
        tags = List(tags) { com.spoolfid.spoolman.SpoolTag("0$id$it", "creality") }, tagsSupported = true,
        legacyTagCount = unlinked, archived = archived,
    )

    // ---- problems ----

    @Test
    fun aGoodSpoolHasNoProblems() {
        assertTrue(catalog.problems(spool()).isEmpty())
    }

    @Test
    fun spoolOneIsAProblem() {
        assertEquals(listOf("Spool #1 is treated as 'no ID' by the printer"), catalog.problems(spool(id = 1)))
    }

    @Test
    fun anUnknownMaterialIsAProblemButADroppedSuffixIsNot() {
        assertEquals(1, catalog.problems(spool(material = "Wood")).size)
        assertEquals(1, catalog.problems(spool(material = null)).size)
        assertTrue(catalog.problems(spool(material = "PLA+")).isEmpty())
    }

    @Test
    fun colorAndWeightProblemsAreReported() {
        assertEquals(1, catalog.problems(spool(color = null)).size)
        assertEquals(1, catalog.problems(spool(multi = true)).size)
        assertEquals(listOf("800 g is written on the tag as 750 g"), catalog.problems(spool(weight = 800.0)))
        assertTrue("an exact bucket is fine", catalog.problems(spool(weight = 500.0)).isEmpty())
    }

    // ---- low remaining ----

    @Test
    fun lowMeansUnderAFifthLeft() {
        assertTrue(spool(remaining = 150.0).isLow)
        assertFalse(spool(remaining = 200.0).isLow)
        assertFalse("unknown is not low", spool(remaining = null).isLow)
        assertNull(spool(remaining = null).fractionLeft)
        assertEquals(0.8, spool().fractionLeft!!, 1e-9)
    }

    // ---- the extra filters ----

    @Test
    fun noFiltersMatchEverything() {
        assertFalse(ExtraFilters().active)
        assertTrue(ExtraFilters().matches(spool(), emptyList()))
    }

    @Test
    fun filtersNarrowTogether() {
        val s = spool(material = "PETG", location = "Shelf A", remaining = 100.0)
        assertTrue(ExtraFilters(low = true, material = "petg", location = "shelf a").matches(s, emptyList()))
        assertFalse(ExtraFilters(low = true, material = "PLA").matches(s, emptyList()))
        assertFalse(ExtraFilters(location = "Shelf B").matches(s, emptyList()))
        assertFalse(ExtraFilters(attention = true).matches(s, emptyList()))
        assertTrue(ExtraFilters(attention = true).matches(s, listOf("something")))
    }

    // ---- the tag audit ----

    @Test
    fun theAuditFindsMissingTags() {
        val items = TagAudit.check(listOf(spool(id = 5, tags = 1), spool(id = 6, tags = 2), spool(id = 4)), required = 2)
        assertEquals(listOf(4 to "Has 0 of 2 tags", 5 to "Has 1 of 2 tags"), items.map { it.spool.id to it.issue })
    }

    @Test
    fun theAuditFlagsTagsFromVersionOneThatAreNotLinked() {
        val items = TagAudit.check(listOf(spool(id = 9, unlinked = 2)), required = 2)
        assertEquals(listOf("2 tags from version 1.0 not linked in Spoolman"), items.map { it.issue })
    }

    @Test
    fun anArchivedSpoolOnlyMattersIfItStillHoldsTags() {
        val items = TagAudit.check(listOf(spool(id = 8, archived = true), spool(id = 9, archived = true, tags = 1)), required = 2)
        assertEquals(listOf(9 to "Archived but still holds 1 tag"), items.map { it.spool.id to it.issue })
    }

    @Test
    fun tagsOnSpoolOneAreFlagged() {
        val items = TagAudit.check(listOf(spool(id = 1, tags = 2)), required = 2)
        assertEquals(listOf("Tagged as spool #1, which the printer ignores"), items.map { it.issue })
    }

    @Test
    fun aFullyTaggedLibraryHasNothingToFix() {
        assertTrue(TagAudit.check(listOf(spool(id = 5, tags = 2), spool(id = 6, tags = 3)), required = 2).isEmpty())
    }
}
