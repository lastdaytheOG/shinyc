package com.amar.vault.vectors

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.VaultDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SensitiveAndLearningTest {

    private lateinit var db: VaultDatabase
    private var now = 1_000_000_000_000L

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), VaultDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    @Test
    fun codesCardsAndPasswordsAreHidden() {
        assertEquals("•••• is your one time password. Do not share.", Sensitive.mask("482913 is your one time password. Do not share."))
        assertEquals("OTP for login: ••••", Sensitive.mask("OTP for login: 7731"))
        assertTrue(Sensitive.mask("card 4111 1111 1111 1111 expires").contains(Sensitive.HIDDEN))
        assertEquals("password: ••••", Sensitive.mask("password: hunter22"))
        assertEquals("Total Rs 1,250.00 PNR 2468135790", Sensitive.mask("Total Rs 1,250.00 PNR 2468135790"))
        assertFalse(Sensitive.isSensitive("Consumer No. 4471029384, units 312"))
    }

    @Test
    fun aSearchThatFoundNothingThenAnOpenWithinAMinuteTeachesAnAlias() = runBlocking {
        val memory = AliasMemory(db) { now }
        memory.searched("light bill")
        now += 20_000
        memory.searched("electricity bill march")
        now += 5_000
        memory.opened("electricity bill march", "doc-7", "POSSIBLE")
        val alias = db.aliasDao().all().single()
        assertEquals("light bill", alias.term)
        assertEquals("electricity march", alias.standsFor)
        assertEquals(listOf("electricity march"), memory.expand("light bill"))
    }

    @Test
    fun aSlowFollowUpTeachesNothing() = runBlocking {
        val memory = AliasMemory(db) { now }
        memory.searched("light bill")
        now += 5 * 60_000
        memory.searched("electricity bill")
        memory.opened("electricity bill", "doc-7", "POSSIBLE")
        assertTrue(db.aliasDao().all().isEmpty())
    }

    @Test
    fun opensAreCappedAndFade() = runBlocking {
        val memory = AliasMemory(db) { now }
        repeat(3) { memory.searched("rent"); memory.opened("rent", "doc-1", "POSSIBLE") }
        assertEquals(AliasMemory.MAX_OPEN_WEIGHT, memory.openedBefore("rent").getValue("doc-1"), 0.001f)
        now += (60.0 * 24 * 3_600_000).toLong()
        assertTrue(memory.openedBefore("rent").getValue("doc-1") < AliasMemory.MAX_OPEN_WEIGHT)
        now += AliasMemory.OPENS_KEPT_MS
        assertTrue(memory.openedBefore("rent").isEmpty())
    }
}
