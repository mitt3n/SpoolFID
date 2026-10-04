package com.spoolfid.spoolman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagLinkTest {
    private fun spool(id: Int) = Spool(
        id = id, filamentName = "S$id", vendor = null, material = "PLA", colorHex = "FFFFFF",
        filamentWeight = 1000.0, initialWeight = null, remainingWeight = null, location = null,
        extra = emptyMap(), tags = listOf(SpoolTag("AA11BB22", "creality")), tagsSupported = true,
    )

    @Test
    fun aTagLinkedToTheSpoolItNamesIsLinked() {
        val s = spool(42)
        assertEquals(TagLinkStatus.Linked(s), TagLink.classify(tagSpoolId = 42, linked = s, namedSpool = s))
    }

    @Test
    fun aTagLinkedToADifferentSpoolIsAMismatch() {
        val linked = spool(7)
        val status = TagLink.classify(tagSpoolId = 42, linked = linked, namedSpool = spool(42))
        assertEquals(TagLinkStatus.Mismatch(42, linked), status)
    }

    @Test
    fun aLinkedTagThatNamesNoSpoolIsAMismatch() {
        val linked = spool(7)
        assertEquals(TagLinkStatus.Mismatch(null, linked), TagLink.classify(null, linked, null))
    }

    @Test
    fun aTagThatNamesASpoolSpoolmanHasntLinkedIsNotLinked() {
        val named = spool(42)
        assertEquals(TagLinkStatus.NotLinked(42, named), TagLink.classify(42, null, named))
    }

    @Test
    fun aTagNamingASpoolThatDoesntExistIsNotLinkedWithNoSpool() {
        assertEquals(TagLinkStatus.NotLinked(42, null), TagLink.classify(42, null, null))
    }

    @Test
    fun aTagNobodyKnowsIsUnknown() {
        assertEquals(TagLinkStatus.Unknown, TagLink.classify(null, null, null))
    }

    @Test
    fun theLinkedSpoolWinsOverTheNamedOneInAMismatch() {
        val status = TagLink.classify(42, spool(7), spool(42))
        assertTrue(status is TagLinkStatus.Mismatch && status.linked.id == 7)
    }
}
