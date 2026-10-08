package com.amar.vault.retrieval

import com.amar.vault.VaultDatabase
import com.amar.vault.VaultLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicLong

/**
 * Fills the keyword engine from the database when the app starts, and knows when that is done.
 *
 * The engine is kept in memory only and built again at every start. Whether to save it to a
 * file instead was decided by measuring the fill (2026-10-08, on the emulator, with
 * `KeywordIndexMeasureDeviceTest`; a phone will differ):
 *
 *     rows in the vault     as it was          as it is
 *     946 (the emulator's)  0.22 – 0.32 s      0.04 – 0.09 s
 *     10,000                3.2 – 3.6 s        0.5 – 0.9 s
 *     50,000                17 – 19 s          2.6 – 3.8 s
 *
 * Most of the old time was the engine itself, and what was done to each row's text on the way
 * to it. With that gone, a fill is short enough at any size this app is likely to see that a
 * saved copy is not worth what it risks: a copy is wrong from the moment a row changes without
 * the file being written again, nothing would show that it was, and making sure means either
 * reading every row anyway or having the database keep track of every change. Built afresh,
 * the engine cannot disagree with the database. Should a vault ever grow far past 50,000
 * rows, saving it is the next step, and the numbers above are where to start from.
 *
 * The rows are read a batch at a time in the order they were stored, so that a large vault is
 * never held in memory whole, and are indexed while the next batch is being read.
 *
 * A search that arrives while the fill is running waits for it ([awaitFilled]) rather than
 * answer from half an index: what a query lists must not depend on how soon after opening
 * the app it was typed.
 */
class KeywordIndexFill(private val db: VaultDatabase, private val index: Bm25Index) {

    /**
     * What a fill did. [ids] are the rows it read, in the order stored. Reading and indexing
     * go on side by side, so [totalMs], the time from start to end, is less than their sum.
     */
    class Report(val ids: List<String>, val readMs: Long, val indexMs: Long, val totalMs: Long) {
        val items: Int get() = ids.size
    }

    @Volatile private var running: CompletableDeferred<Unit>? = null

    /** The last fill; null until one has finished. */
    @Volatile var lastReport: Report? = null
        private set

    /**
     * Starts the fill in [scope]. From the moment this returns, [awaitFilled] waits for it —
     * so it is called before anything can search.
     */
    fun start(scope: CoroutineScope): Deferred<Report?> {
        val gate = CompletableDeferred<Unit>()
        running = gate
        return scope.async(Dispatchers.IO) {
            try {
                fill()
            } catch (e: Throwable) {
                // Search still works from the database (the scan lanes); it is not held up.
                VaultLog.e("KeywordIndex", "The keyword index could not be filled", e)
                null
            } finally {
                gate.complete(Unit)
            }
        }
    }

    /** Returns when the fill that was started has ended; at once when none is running. */
    suspend fun awaitFilled() {
        running?.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun fill(): Report = coroutineScope {
        val started = System.nanoTime()
        val dao = db.vaultDao()
        val readNs = AtomicLong()
        // One coroutine reads the rows while this one indexes them; it reads at most two
        // batches ahead.
        val batches = produce(Dispatchers.IO, capacity = 2) {
            var after = 0L
            while (true) {
                val t = System.nanoTime()
                val rows = dao.searchableDataAfter(after, BATCH)
                readNs.addAndGet(System.nanoTime() - t)
                if (rows.isEmpty()) break
                send(rows)
                after = rows.last().rowId
            }
        }
        val texts = KeywordText.ForManyRows()
        val ids = ArrayList<String>()
        var indexNs = 0L
        for (rows in batches) {
            val t = System.nanoTime()
            for (row in rows) {
                index.addDocument(row.id, row.ocrText, texts.besidesTheText(row))
                ids.add(row.id)
            }
            indexNs += System.nanoTime() - t
        }
        val report = Report(ids, readNs.get() / 1_000_000, indexNs / 1_000_000,
            (System.nanoTime() - started) / 1_000_000)
        lastReport = report
        VaultLog.i("KeywordIndex", "filled: ${report.items} items in ${report.totalMs} ms " +
            "(reading ${report.readMs} ms, indexing ${report.indexMs} ms, side by side)")
        report
    }

    private companion object {
        /** Rows read at a time. A row's text is about 1,000 characters; a batch is about 1 MB. */
        const val BATCH = 500
    }
}
