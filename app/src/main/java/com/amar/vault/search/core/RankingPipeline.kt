package com.amar.vault.search.core

import com.amar.vault.search.models.SearchDocument

interface SearchScoreComponent {
    fun score(document: SearchDocument, queryTokens: Set<String>): Double
}

object TextScore : SearchScoreComponent {
    override fun score(document: SearchDocument, queryTokens: Set<String>): Double {
        if (queryTokens.isEmpty()) return 0.0
        var score = 0.0
        
        // Exact match in title is heavily weighted
        val normalizedTitle = SearchNormalizer.normalize(document.title)
        val titleTokens = SearchTokenizer.tokenize(normalizedTitle)
        
        queryTokens.forEach { queryToken ->
            if (titleTokens.contains(queryToken)) score += 50.0
            else if (document.tokens.contains(queryToken)) score += 10.0
        }
        
        return score
    }
}

object TagScore : SearchScoreComponent {
    override fun score(document: SearchDocument, queryTokens: Set<String>): Double {
        var score = 0.0
        val normalizedTags = document.tags.map { SearchNormalizer.normalize(it) }.flatMap { SearchTokenizer.tokenize(it) }
        
        queryTokens.forEach { queryToken ->
            if (normalizedTags.contains(queryToken)) score += 30.0
        }
        return score
    }
}

object MetadataScore : SearchScoreComponent {
    override fun score(document: SearchDocument, queryTokens: Set<String>): Double {
        var score = 0.0
        val metaString = "${document.domain.orEmpty()} ${document.creator.orEmpty()} ${document.platform.orEmpty()}"
        val metaTokens = SearchTokenizer.tokenize(SearchNormalizer.normalize(metaString))
        
        queryTokens.forEach { queryToken ->
            if (metaTokens.contains(queryToken)) score += 20.0
        }
        return score
    }
}

object FutureOCRScore : SearchScoreComponent {
    override fun score(document: SearchDocument, queryTokens: Set<String>): Double {
        // Placeholder for future OCR scoring logic
        return 0.0
    }
}

object RankingPipeline {
    private val scorers = listOf(TextScore, TagScore, MetadataScore, FutureOCRScore)
    
    fun calculateFinalScore(document: SearchDocument, queryTokens: Set<String>): Double {
        return scorers.sumOf { it.score(document, queryTokens) }
    }
}
