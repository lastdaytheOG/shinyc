package com.amar.vault.indexing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeeperReadsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file() = File(folder.root, "deeper-reads.txt")

    @Test
    fun aScreenPutOffWaitsUntilItIsReadInFull() {
        val reads = DeeperReads(file())

        reads.mark("b", deeper = true)
        reads.mark("a", deeper = true)
        reads.mark("c", deeper = false)

        assertEquals(listOf("b", "a"), reads.waiting())
        reads.mark("b", deeper = false)
        assertEquals(listOf("a"), reads.waiting())
    }

    @Test
    fun theListOutlivesTheApp() {
        DeeperReads(file()).apply {
            mark("first", deeper = true)
            mark("second", deeper = true)
            mark("first", deeper = false)
        }

        assertEquals(listOf("second"), DeeperReads(file()).waiting())
    }

    @Test
    fun onlyTheFirstScreenPutOffAsksForTheWorkToBeStarted() {
        val reads = DeeperReads(file())

        assertTrue(reads.mark("a", deeper = true))
        assertFalse(reads.mark("b", deeper = true))
        assertFalse(reads.mark("a", deeper = true))
        assertFalse(reads.mark("c", deeper = false))
        reads.mark("a", deeper = false)
        reads.mark("b", deeper = false)
        assertTrue(reads.mark("d", deeper = true))
    }

    @Test
    fun aScreenPutOffTwiceIsWaitingOnce() {
        val reads = DeeperReads(file())

        reads.mark("a", deeper = true)
        reads.mark("a", deeper = true)

        assertEquals(1, reads.size())
    }

    @Test
    fun withoutAFileNothingIsWaiting() {
        assertEquals(0, DeeperReads(file()).size())
    }
}
