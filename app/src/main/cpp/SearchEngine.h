#pragma once

#include <string>
#include <vector>
#include <unordered_map>
#include <shared_mutex>
#include <cstdint>
#include <algorithm>
#include <cmath>

/**
 * High-performance in-memory BM25 Search Engine v2.
 *
 * Architecture: HYBRID WORD + TRIGRAM index.
 *   - Primary index: whole lowercased words (fast exact matching, tiny footprint)
 *   - Fuzzy index: character trigrams (activated only when a query word has
 *     zero whole-word hits, giving typo tolerance on demand)
 *
 * This eliminates the n-gram explosion: the trigram index stores only
 * posting lists (doc sets), NOT per-doc term frequencies. Trigram matches
 * get a flat relevance bonus rather than full BM25 scoring.
 *
 * Memory optimizations:
 *   - Posting lists are sorted vectors, not unordered_sets (~3x smaller)
 *   - Per-doc term freqs stored only for whole words
 *   - avgDocLen maintained incrementally (no O(N) recalc)
 *
 * Thread safety: std::shared_mutex (concurrent reads, exclusive writes).
 * UTF-8: All strings treated as UTF-8 bytes. Lowercasing is ASCII-only.
 */
class SearchEngine {
public:
    SearchEngine();
    ~SearchEngine();

    void addDocument(const std::string& docId, const std::string& text);
    std::vector<std::string> search(const std::string& query);
    void clear();

private:
    static constexpr double K1 = 1.2;
    static constexpr double B  = 0.75;
    // Chunk hits returned per query. The Kotlin caller trims to its own budget; it reads this
    // deep only to collapse a document's many chunks to one result
    // (VaultConfig.Retrieval.BM25_BUDGET × BM25_DOC_OVERFETCH must stay within it).
    static constexpr size_t MAX_RESULTS = 300;
    static constexpr double TRIGRAM_BOOST = 0.3;

    struct DocInfo {
        std::string docId;
        uint32_t wordCount;
        std::unordered_map<std::string, uint16_t> wordFreqs;
    };

    using PostingList = std::vector<uint32_t>;

    std::vector<DocInfo> docs_;
    std::unordered_map<std::string, uint32_t> docIdToIndex_;
    std::unordered_map<std::string, PostingList> wordIndex_;
    std::unordered_map<std::string, PostingList> trigramIndex_;
    double avgDocLen_;
    double totalDocLen_;

    mutable std::shared_mutex mutex_;

    static std::string toLowerASCII(const std::string& s);
    static bool isASCIIPunctuation(char c);
    static std::vector<std::string> tokenizeWords(const std::string& text);
    static std::vector<std::string> generateTrigrams(const std::string& word);
    static void insertPosting(PostingList& list, uint32_t docIdx);
    static void removePosting(PostingList& list, uint32_t docIdx);
};
