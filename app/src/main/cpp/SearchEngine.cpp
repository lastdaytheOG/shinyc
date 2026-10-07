#include "SearchEngine.h"
#include <sstream>
#include <cctype>
#include <mutex>
#include <unordered_set>

// =============================================================================
// Constructor / Destructor
// =============================================================================

SearchEngine::SearchEngine() : avgDocLen_(0.0), totalDocLen_(0.0) {}
SearchEngine::~SearchEngine() {}

// =============================================================================
// Posting List Helpers (sorted vector, binary search)
// =============================================================================

void SearchEngine::insertPosting(PostingList& list, uint32_t docIdx) {
    auto pos = std::lower_bound(list.begin(), list.end(), docIdx);
    if (pos == list.end() || *pos != docIdx) {
        list.insert(pos, docIdx);
    }
}

void SearchEngine::removePosting(PostingList& list, uint32_t docIdx) {
    auto pos = std::lower_bound(list.begin(), list.end(), docIdx);
    if (pos != list.end() && *pos == docIdx) {
        list.erase(pos);
    }
}

// =============================================================================
// addDocument
// =============================================================================

void SearchEngine::addDocument(const std::string& docId, const std::string& text) {
    std::unique_lock<std::shared_mutex> lock(mutex_);

    auto words = tokenizeWords(text);
    uint32_t wordCount = static_cast<uint32_t>(words.size());

    // Build word frequency map
    std::unordered_map<std::string, uint16_t> wordFreqs;
    for (const auto& w : words) {
        wordFreqs[w]++;
    }

    // Collect unique trigrams across all words
    std::unordered_set<std::string> docTrigrams;
    for (const auto& w : words) {
        auto tris = generateTrigrams(w);
        for (auto& t : tris) {
            docTrigrams.insert(std::move(t));
        }
    }

    // --- Check if docId already exists (re-index) ---
    auto existingIt = docIdToIndex_.find(docId);
    if (existingIt != docIdToIndex_.end()) {
        uint32_t idx = existingIt->second;
        DocInfo& doc = docs_[idx];

        // Remove old entries from word index
        for (const auto& [word, _] : doc.wordFreqs) {
            auto it = wordIndex_.find(word);
            if (it != wordIndex_.end()) {
                removePosting(it->second, idx);
                if (it->second.empty()) wordIndex_.erase(it);
            }
        }

        // Remove old trigrams (we need to regenerate from old words)
        // Since we don't store old trigrams, rebuild from old wordFreqs
        for (const auto& [word, _] : doc.wordFreqs) {
            auto tris = generateTrigrams(word);
            for (const auto& t : tris) {
                auto it = trigramIndex_.find(t);
                if (it != trigramIndex_.end()) {
                    removePosting(it->second, idx);
                    if (it->second.empty()) trigramIndex_.erase(it);
                }
            }
        }

        // Update running total
        totalDocLen_ -= doc.wordCount;
        totalDocLen_ += wordCount;

        // Update doc info
        doc.wordCount = wordCount;
        doc.wordFreqs = std::move(wordFreqs);

        // Re-insert into word index
        for (const auto& [word, _] : doc.wordFreqs) {
            insertPosting(wordIndex_[word], idx);
        }

        // Re-insert trigrams
        for (const auto& t : docTrigrams) {
            insertPosting(trigramIndex_[t], idx);
        }

        avgDocLen_ = docs_.empty() ? 0.0 : totalDocLen_ / static_cast<double>(docs_.size());
        return;
    }

    // --- New document ---
    uint32_t newIdx = static_cast<uint32_t>(docs_.size());

    DocInfo info;
    info.docId = docId;
    info.wordCount = wordCount;
    info.wordFreqs = std::move(wordFreqs);

    // Insert into word index
    for (const auto& [word, _] : info.wordFreqs) {
        insertPosting(wordIndex_[word], newIdx);
    }

    // Insert trigrams
    for (const auto& t : docTrigrams) {
        insertPosting(trigramIndex_[t], newIdx);
    }

    docs_.push_back(std::move(info));
    docIdToIndex_[docId] = newIdx;

    totalDocLen_ += wordCount;
    avgDocLen_ = totalDocLen_ / static_cast<double>(docs_.size());
}

// =============================================================================
// search — Hybrid: exact word BM25 + trigram fuzzy fallback
// =============================================================================

std::vector<std::string> SearchEngine::search(const std::string& query) {
    std::shared_lock<std::shared_mutex> lock(mutex_);

    if (docs_.empty()) return {};

    auto queryWords = tokenizeWords(query);
    if (queryWords.empty()) return {};

    const double N = static_cast<double>(docs_.size());
    const size_t docCount = docs_.size();

    // Flat score array — cache-friendly, O(1) access, no hashing overhead
    std::vector<double> scores(docCount, 0.0);
    bool hasScores = false;

    for (const auto& qword : queryWords) {
        // --- Try exact whole-word match first ---
        auto wordIt = wordIndex_.find(qword);
        if (wordIt != wordIndex_.end() && !wordIt->second.empty()) {
            // Full BM25 scoring on whole-word match
            const PostingList& postings = wordIt->second;
            double df = static_cast<double>(postings.size());
            double idf = std::log((N - df + 0.5) / (df + 0.5) + 1.0);

            for (uint32_t docIdx : postings) {
                const DocInfo& doc = docs_[docIdx];
                auto tfIt = doc.wordFreqs.find(qword);
                if (tfIt == doc.wordFreqs.end()) continue;
                double tf = static_cast<double>(tfIt->second);
                double docLen = static_cast<double>(doc.wordCount);

                double tfNorm = (tf * (K1 + 1.0)) /
                                (tf + K1 * (1.0 - B + B * (docLen / avgDocLen_)));

                scores[docIdx] += idf * tfNorm;
                hasScores = true;
            }
        } else {
            // --- Fuzzy fallback: trigram matching ---
            auto trigrams = generateTrigrams(qword);
            if (trigrams.empty()) continue;

            // Count how many of the query word's trigrams each doc matches
            // Use flat vector for trigramHits too
            std::vector<uint32_t> trigramHits(docCount, 0);
            std::vector<uint32_t> candidates; // track which docs got hits

            for (const auto& tri : trigrams) {
                auto triIt = trigramIndex_.find(tri);
                if (triIt == trigramIndex_.end()) continue;
                for (uint32_t docIdx : triIt->second) {
                    if (trigramHits[docIdx] == 0) {
                        candidates.push_back(docIdx);
                    }
                    trigramHits[docIdx]++;
                }
            }

            // Score based on fraction of trigrams matched
            double totalTrigrams = static_cast<double>(trigrams.size());
            for (uint32_t docIdx : candidates) {
                double matchRatio = static_cast<double>(trigramHits[docIdx]) / totalTrigrams;
                if (matchRatio < 0.4) continue;
                double fuzzyScore = TRIGRAM_BOOST * matchRatio * matchRatio;
                scores[docIdx] += fuzzyScore;
                hasScores = true;
            }
        }
    }

    if (!hasScores) return {};

    // Collect only non-zero scored docs, then partial sort
    std::vector<std::pair<uint32_t, double>> ranked;
    ranked.reserve(256); // reasonable initial capacity
    for (uint32_t i = 0; i < docCount; ++i) {
        if (scores[i] > 0.0) {
            ranked.emplace_back(i, scores[i]);
        }
    }

    if (ranked.empty()) return {};

    size_t topN = std::min(ranked.size(), MAX_RESULTS);
    std::partial_sort(
        ranked.begin(),
        ranked.begin() + topN,
        ranked.end(),
        // Equal scores fall back to insertion order, so the first N of a deeper read are
        // exactly what a shallower read returns.
        [](const auto& a, const auto& b) {
            return a.second != b.second ? a.second > b.second : a.first < b.first;
        }
    );

    std::vector<std::string> results;
    results.reserve(topN);
    for (size_t i = 0; i < topN; ++i) {
        results.push_back(docs_[ranked[i].first].docId);
    }
    return results;
}

// =============================================================================
// clear
// =============================================================================

void SearchEngine::clear() {
    std::unique_lock<std::shared_mutex> lock(mutex_);
    docs_.clear();
    docIdToIndex_.clear();
    wordIndex_.clear();
    trigramIndex_.clear();
    avgDocLen_ = 0.0;
    totalDocLen_ = 0.0;
}

// =============================================================================
// Tokenization
// =============================================================================

std::string SearchEngine::toLowerASCII(const std::string& s) {
    std::string result;
    result.reserve(s.size());
    for (unsigned char c : s) {
        if (c >= 'A' && c <= 'Z') {
            result.push_back(static_cast<char>(c + 32));
        } else {
            result.push_back(static_cast<char>(c));
        }
    }
    return result;
}

bool SearchEngine::isASCIIPunctuation(char c) {
    unsigned char uc = static_cast<unsigned char>(c);
    if (uc >= 128) return false;
    if (std::isalnum(uc)) return false;
    if (uc == ' ' || uc == '\t' || uc == '\n' || uc == '\r') return false;
    return true;
}

std::vector<std::string> SearchEngine::tokenizeWords(const std::string& text) {
    std::string cleaned = toLowerASCII(text);
    for (char& c : cleaned) {
        if (isASCIIPunctuation(c)) c = ' ';
    }

    std::vector<std::string> words;
    std::istringstream stream(cleaned);
    std::string word;
    while (stream >> word) {
        if (!word.empty()) {
            words.push_back(std::move(word));
        }
    }
    return words;
}

std::vector<std::string> SearchEngine::generateTrigrams(const std::string& word) {
    std::vector<std::string> trigrams;
    size_t len = word.size();

    if (len < 3) {
        // For short words (1-2 bytes), use the word itself as a "trigram"
        // so that short words can still match exactly
        trigrams.push_back(word);
        return trigrams;
    }

    trigrams.reserve(len - 2);
    for (size_t i = 0; i <= len - 3; ++i) {
        trigrams.push_back(word.substr(i, 3));
    }
    return trigrams;
}
