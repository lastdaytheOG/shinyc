package com.amar.vault

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SavedArchitectureTest {

    private lateinit var db: VaultDatabase
    private lateinit var vaultDao: VaultDao
    private lateinit var stashDao: StashItemDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vaultDao = db.vaultDao()
        stashDao = db.stashItemDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun String.sha256(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(this.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun test1_Stage1CaptureLatencyAndConstraints() = runBlocking {
        // Warm up the coroutine dispatcher and Room's suspend-transaction machinery
        // once so the measurement reflects steady-state capture cost rather than
        // one-time JVM/coroutine class-loading (~60ms cold on a first call).
        db.withTransaction { }

        // Measure execution time of a simulated Stage 1 capture
        val url = "https://example.com/recipe"
        val hash = url.sha256()
        val category = "Recipes"

        val start = System.currentTimeMillis()
        db.withTransaction {
            val vaultItem = VaultItem(
                id = UUID.randomUUID().toString(),
                uri = url,
                ocrText = "",
                lang = "en",
                itemType = ItemType.LINK,
                timestamp = System.currentTimeMillis(),
                sourceFile = "example.com",
                contentHash = hash,
                sourceApp = "Chrome",
                title = "Example Recipe"
            )
            vaultDao.insert(vaultItem)
                
            val stashItem = StashItem(
                id = UUID.randomUUID().toString(),
                vaultItemId = vaultItem.id,
                category = category,
                savedAt = System.currentTimeMillis(),
                sourceApp = "Chrome"
            )
            stashDao.insertOrUpdate(stashItem)
        }
        
        val duration = System.currentTimeMillis() - start
        println("EVIDENCE: Stage 1 Capture completed in ${duration}ms")
        // A warmed in-memory capture is single-digit ms; 250ms leaves ample headroom
        // for CI jitter while still catching a gross regression (e.g. accidental
        // blocking network/disk I/O on the capture path).
        assertTrue("Capture should be fast (was ${duration}ms)", duration < 250)
    }

    @Test
    fun test2_DuplicateBehaviour_ScenarioA() = runBlocking {
        // Scenario A: Share same URL -> Recipes -> Share same URL again -> Recipes
        val url = "https://example.com/a"
        val hash = url.sha256()
        val vaultId = UUID.randomUUID().toString()

        // Share 1
        db.withTransaction {
            vaultDao.insert(VaultItem(id = vaultId, uri = url, ocrText = "", lang = "en", itemType = ItemType.LINK, timestamp = 1000L, sourceFile = "", contentHash = hash))
            stashDao.insertOrUpdate(StashItem(id = UUID.randomUUID().toString(), vaultItemId = vaultId, category = "Recipes", savedAt = 1000L, sourceApp = ""))
        }

        // Share 2
        db.withTransaction {
            val existingVault = vaultDao.findByContentHash(hash)
            assertNotNull(existingVault)
            stashDao.insertOrUpdate(StashItem(id = UUID.randomUUID().toString(), vaultItemId = existingVault!!.id, category = "Recipes", savedAt = 2000L, sourceApp = ""))
        }

        // Verify
        val vaultItems = vaultDao.getAllItems().first()
        val stashItems = stashDao.getStashItemsByType("SAVED").first()

        println("EVIDENCE Scenario A: VaultItems count = ${vaultItems.size}")
        println("EVIDENCE Scenario A: StashItems count = ${stashItems.size}")
        println("EVIDENCE Scenario A: StashItem savedAt = ${stashItems.first().savedAt}")

        assertEquals(1, vaultItems.size)
        assertEquals(1, stashItems.size)
        assertEquals(2000L, stashItems.first().savedAt)
    }

    @Test
    fun test3_DuplicateBehaviour_ScenarioB() = runBlocking {
        // Scenario B: Share same URL -> Recipes -> Learning
        val url = "https://example.com/b"
        val hash = url.sha256()
        val vaultId = UUID.randomUUID().toString()

        // Share 1
        vaultDao.insert(VaultItem(id = vaultId, uri = url, ocrText = "", lang = "en", itemType = ItemType.LINK, timestamp = 1000L, sourceFile = "", contentHash = hash))
        stashDao.insertOrUpdate(StashItem(id = UUID.randomUUID().toString(), vaultItemId = vaultId, category = "Recipes", savedAt = 1000L, sourceApp = ""))

        // Share 2
        val existingVault = vaultDao.findByContentHash(hash)!!
        stashDao.insertOrUpdate(StashItem(id = UUID.randomUUID().toString(), vaultItemId = existingVault.id, category = "Learning", savedAt = 2000L, sourceApp = ""))

        // Verify
        val stashItems = stashDao.getStashItemsByType("SAVED").first()
        println("EVIDENCE Scenario B: StashItems count = ${stashItems.size}")
        
        assertEquals(2, stashItems.size)
        val categories = stashItems.map { it.category }.toSet()
        assertTrue(categories.contains("Recipes") && categories.contains("Learning"))
    }

    @Test
    fun test4_ForeignKeyIntegrity() = runBlocking {
        val vaultId = UUID.randomUUID().toString()
        vaultDao.insert(VaultItem(id = vaultId, uri = "test", ocrText = "", lang = "en", itemType = ItemType.LINK, timestamp = 1000L, sourceFile = "", contentHash = "hash"))
        stashDao.insertOrUpdate(StashItem(id = UUID.randomUUID().toString(), vaultItemId = vaultId, category = "Test", savedAt = 1000L, sourceApp = ""))

        var stashItems = stashDao.getStashItemsByType("SAVED").first()
        assertEquals(1, stashItems.size)

        // Delete VaultItem
        vaultDao.deleteById(vaultId)

        // Verify Cascade
        stashItems = stashDao.getStashItemsByType("SAVED").first()
        println("EVIDENCE Foreign Key Cascade: StashItems count after parent deletion = ${stashItems.size}")
        assertEquals(0, stashItems.size)
    }

    @Test
    fun test5_Stage2Failure_DoesNotAffectStash() = runBlocking {
        // Stage 1
        val vaultId = UUID.randomUUID().toString()
        vaultDao.insert(VaultItem(id = vaultId, uri = "test2", ocrText = "basic", lang = "en", itemType = ItemType.LINK, timestamp = 1000L, sourceFile = "", contentHash = "hash2", title = "Basic Title"))
        stashDao.insertOrUpdate(StashItem(id = UUID.randomUUID().toString(), vaultItemId = vaultId, category = "Test", savedAt = 1000L, sourceApp = ""))

        // Simulate Stage 2 OCR failure (Worker crashes, does nothing to the DB)
        // Verify VaultItem and StashItem still exist and are queryable
        val stashItems = stashDao.getStashItemsByType("SAVED").first()
        println("EVIDENCE Stage 2 Failure: Basic Title visible = ${stashItems.first().title}")
        assertEquals(1, stashItems.size)
        assertEquals("Basic Title", stashItems.first().title)
    }
}
