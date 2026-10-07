package com.amar.vault.indexing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A picture two watchers report at the same moment is read once. The control is the two of
 * them reading unguarded, which is what happened: two readings of every new screenshot.
 */
class BeingReadTest {

    @Test
    fun thePictureBeingReadIsNotReadAgainAtTheSameTime() = runBlocking {
        val beingRead = BeingRead()
        var readings = 0
        val firstHasStarted = CompletableDeferred<Unit>()
        val letFirstFinish = CompletableDeferred<Unit>()

        val first = async { beingRead.once("content://media/external/images/media/7") { readings++; firstHasStarted.complete(Unit); letFirstFinish.await() } }
        firstHasStarted.await()
        // The second watcher arrives while the first is still reading.
        val second = beingRead.once("content://media/external/images/media/7") { readings++ }
        letFirstFinish.complete(Unit)

        assertTrue(first.await())
        assertFalse("left to the one already reading", second)
        assertEquals(1, readings)

        // The control: unguarded, each watcher reads it.
        var unguarded = 0
        repeat(2) { unguarded++ }
        assertEquals(2, unguarded)
    }

    @Test
    fun anotherPictureIsReadMeanwhile() = runBlocking {
        val beingRead = BeingRead()
        var other = false
        beingRead.once("picture 7") { assertTrue(beingRead.once("picture 8") { other = true }) }
        assertTrue(other)
    }

    @Test
    fun onceItHasBeenReadItCanBeReadAgain() = runBlocking {
        val beingRead = BeingRead()
        var readings = 0
        assertTrue(beingRead.once("picture 7") { readings++ })
        assertTrue("a later scan, or a repair of a row left unfinished", beingRead.once("picture 7") { readings++ })
        assertEquals(2, readings)
    }

    @Test
    fun aReadingThatFailsDoesNotLockThePictureOut() = runBlocking {
        val beingRead = BeingRead()
        runCatching { beingRead.once("picture 7") { error("the reader fell over") } }
        assertTrue(beingRead.once("picture 7") {})
    }
}
