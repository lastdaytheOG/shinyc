package com.amar.vault.benchmark

import com.amar.vault.retrieval.RetrievalRequest
import com.amar.vault.retrieval.RetrievalResult
import com.amar.vault.retrieval.RetrievalService
import com.amar.vault.retrieval.RetrievalStage
import com.amar.vault.retrieval.RetrievalTuning
import kotlinx.coroutines.flow.toList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BenchRun(private val retrieval: RetrievalService) {

    suspend fun run(cases: List<JSONObject>, tuning: RetrievalTuning, out: File, label: String, warmUp: List<String> = WARM_UP) {
        for (q in warmUp) retrieval.retrieve(request(q, tuning))
        out.parentFile?.mkdirs()
        out.bufferedWriter().use { w ->
            for (c in cases) {
                val query = c.getString("query")
                val t0 = System.nanoTime()
                var trace: com.amar.vault.retrieval.RetrievalTrace? = null
                val updates = retrieval.retrieveProgressive(request(query, tuning).copy(traceSink = { trace = it })).toList()
                val finalMs = (System.nanoTime() - t0) / 1e6
                val first = updates.firstOrNull { it.result.items.isNotEmpty() || it.result.photos.isNotEmpty() }
                val result = updates.last { it.stage == RetrievalStage.FINAL }.result
                val row = line(c.getString("id"), label, result, first?.elapsedMs, finalMs)
                trace?.let { t -> row.put("lanes", JSONObject(t.lanes.associate { it.lane to JSONArray(it.candidateIds.take(50)) })) }
                w.write(row.toString())
                w.newLine()
            }
        }
    }

    private fun request(query: String, tuning: RetrievalTuning): RetrievalRequest {
        val r = RetrievalRequest.forResultList(query)
        return r.copy(tuning = tuning.copy(relaxEmptyFilter = true))
    }

    private fun line(id: String, label: String, result: RetrievalResult, firstMs: Long?, finalMs: Double): JSONObject {
        val listed = result.items.map { it.parentDocumentId ?: it.id }.distinct()
        return JSONObject()
            .put("id", id).put("config", label)
            .put("items", JSONArray(listed))
            .put("photos", JSONArray(result.photos.map { it.item.id }))
            .put("photoScores", JSONArray(result.photos.map { it.score.toDouble() }))
            .put("groups", BenchGroups.of(result))
            .put("verdict", result.verdict.name)
            .put("keywordWordsSaid", result.keywordWordsSaid).put("keywordWords", result.keywordWords)
            .put("photoMargins", JSONArray(result.photos.map { it.margin.toDouble() }))
            .put("meaning", BenchGroups.meaning(result))
            .put("similarSpellingsOnly", result.similarSpellingsOnly)
            .put("filterDropped", result.filterDropped)
            .put("firstMs", firstMs ?: JSONObject.NULL)
            .put("finalMs", Math.round(finalMs * 10) / 10.0)
    }

    companion object {
        val WARM_UP = listOf("warm up the photo models", "receipt", "बिल")
    }
}

object BenchGroups {
    fun of(result: RetrievalResult): JSONArray = JSONArray(result.groups.map { g ->
        JSONObject().put("kind", g.kind.name).put("verdict", g.verdict.name).put("ids", JSONArray(g.ids))
    })

    fun meaning(result: RetrievalResult): Any {
        val m = result.meaning ?: return JSONObject.NULL
        return JSONObject()
            .put("verdict", m.verdict.name).put("coverage", m.coverage.toDouble()).put("timedOut", m.timedOut)
            .put("mean", m.mean.toDouble()).put("deviation", m.deviation.toDouble())
            .put("shown", JSONArray(m.shown.map { it.item.parentDocumentId ?: it.item.id }))
            .put("closest", JSONArray(m.closest.take(30).map {
                JSONObject().put("id", it.item.parentDocumentId ?: it.item.id).put("row", it.item.id)
                    .put("score", it.score.toDouble()).put("margin", it.margin.toDouble()).put("standing", it.standing.toDouble())
            }))
    }
}
