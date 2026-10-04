package com.spoolfid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagFilterTest {
    /** Which tag counts (0..3) a filter lets through, with [required] tags needed per spool. */
    private fun passing(filter: TagFilter, required: Int) = (0..3).filter { filter.matches(it, required) }

    @Test
    fun allShowsEverything() {
        assertEquals(listOf(0, 1, 2, 3), passing(TagFilter.ALL, required = 2))
    }

    @Test
    fun toDoShowsSpoolsStillNeedingTags() {
        assertEquals(listOf(0, 1), passing(TagFilter.TO_DO, required = 2))
        assertEquals(listOf(0), passing(TagFilter.TO_DO, required = 1))
    }

    @Test
    fun untaggedShowsOnlySpoolsWithNoTags() {
        assertEquals(listOf(0), passing(TagFilter.UNTAGGED, required = 2))
        assertEquals(listOf(0), passing(TagFilter.UNTAGGED, required = 1))
    }

    @Test
    fun partlyTaggedIsOnlyTheGapBetweenNoneAndEnough() {
        assertEquals(listOf(1), passing(TagFilter.PARTIAL, required = 2))
        assertEquals(listOf(1, 2), passing(TagFilter.PARTIAL, required = 3))
        assertTrue("with one tag required nothing is partly tagged", passing(TagFilter.PARTIAL, required = 1).isEmpty())
    }

    @Test
    fun fullyTaggedNeedsEveryRequiredTag() {
        assertEquals(listOf(2, 3), passing(TagFilter.TAGGED, required = 2))
        assertEquals(listOf(1, 2, 3), passing(TagFilter.TAGGED, required = 1))
    }

    @Test
    fun everySpoolLandsInExactlyOneOfUntaggedPartialAndFullyTagged() {
        for (required in 1..3) for (count in 0..4) {
            val hits = listOf(TagFilter.UNTAGGED, TagFilter.PARTIAL, TagFilter.TAGGED).count { it.matches(count, required) }
            assertEquals("count=$count required=$required", 1, hits)
        }
    }

    @Test
    fun toDoIsTheComplementOfFullyTagged() {
        for (required in 1..3) for (count in 0..4) {
            assertEquals(!TagFilter.TAGGED.matches(count, required), TagFilter.TO_DO.matches(count, required))
        }
        assertFalse(TagFilter.TO_DO.matches(2, 2))
    }
}
