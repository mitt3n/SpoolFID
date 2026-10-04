package com.spoolfid

import com.spoolfid.spoolman.Spool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    private fun spool(id: Int) = Spool(
        id = id, filamentName = "S$id", vendor = null, material = "PLA", colorHex = "FFFFFF",
        filamentWeight = 1000.0, initialWeight = null, remainingWeight = null, location = null,
        extra = emptyMap(),
    )

    private fun session(spools: Int, copies: Int) =
        Session(queue = (1..spools).map { spool(it + 1) }, copies = copies)

    /** (spool index, tag index, phase) of each state visited by repeatedly advancing. */
    private fun walk(start: Session): List<Triple<Int, Int, Phase>> {
        val steps = mutableListOf<Triple<Int, Int, Phase>>()
        var s = start
        steps += Triple(s.index, s.copy, s.phase)
        while (s.phase != Phase.DONE) {
            s = s.advanced()
            steps += Triple(s.index, s.copy, s.phase)
        }
        return steps
    }

    @Test
    fun twoTagsPerSpoolVisitsEveryTagInOrder() {
        assertEquals(
            listOf(
                Triple(0, 0, Phase.WAITING),
                Triple(0, 1, Phase.WAITING),
                Triple(1, 0, Phase.WAITING),
                Triple(1, 1, Phase.WAITING),
                Triple(1, 1, Phase.DONE),
            ),
            walk(session(spools = 2, copies = 2)),
        )
    }

    @Test
    fun oneTagPerSpoolMovesStraightToTheNextSpool() {
        assertEquals(
            listOf(
                Triple(0, 0, Phase.WAITING),
                Triple(1, 0, Phase.WAITING),
                Triple(2, 0, Phase.WAITING),
                Triple(2, 0, Phase.DONE),
            ),
            walk(session(spools = 3, copies = 1)),
        )
    }

    @Test
    fun tagCountersTrackProgress() {
        val s = session(spools = 3, copies = 2)
        assertEquals(6, s.tagsTotal)
        assertEquals(0, s.tagsDone)
        assertEquals(1, s.advanced().tagsDone)
        assertEquals(2, s.advanced().advanced().tagsDone)
        assertEquals(5, s.advanced().advanced().advanced().advanced().advanced().tagsDone)
    }

    @Test
    fun onLastCopyIsOnlyTrueOnTheFinalTagOfASpool() {
        val first = session(spools = 1, copies = 2)
        assertFalse(first.onLastCopy)
        assertTrue(first.advanced().onLastCopy)
        assertTrue(session(spools = 1, copies = 1).onLastCopy)
    }

    @Test
    fun hasNextReflectsRemainingSpools() {
        val s = session(spools = 2, copies = 1)
        assertTrue(s.hasNext)
        assertFalse(s.advanced().hasNext)
    }

    @Test
    fun skippingAbandonsTheRestOfTheSpoolsTags() {
        val s = session(spools = 3, copies = 2).advanced() // spool 0, tag 1
        val skipped = s.skipped()
        assertEquals(1, skipped.index)
        assertEquals(0, skipped.copy)
        assertEquals(Phase.WAITING, skipped.phase)
    }

    @Test
    fun skippingTheLastSpoolFinishesTheSession() {
        val s = session(spools = 1, copies = 2)
        assertEquals(Phase.DONE, s.skipped().phase)
    }

    @Test
    fun movingOnClearsTransientState() {
        val dirty = session(spools = 2, copies = 2).copy(
            phase = Phase.ERROR, message = "boom", approvedUid = "AABBCCDD",
        )
        for (next in listOf(dirty.advanced(), dirty.skipped())) {
            assertNull(next.message)
            assertNull(next.approvedUid)
            assertNull(next.pendingTag)
            assertEquals(Phase.WAITING, next.phase)
        }
    }

    @Test
    fun currentIsTheSpoolAtTheIndex() {
        val s = session(spools = 3, copies = 2)
        assertEquals(2, s.current.id)
        assertEquals(2, s.advanced().current.id) // second tag of the same spool
        assertEquals(3, s.advanced().advanced().current.id)
    }
}
