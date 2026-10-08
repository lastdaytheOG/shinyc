#pragma once

#include <cstdint>
#include <shared_mutex>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

/**
 * The keyword engine: which items say a word, ranked by BM25. In memory only; the app fills
 * it from the database when it starts.
 *
 * It does not decide what a word is. It is handed words already cut and lowercased, with a
 * space between them (WordCutter does that, by the table KeywordWords on the Kotlin side gives
 * it), as UTF-16 like the strings they came from — so a word here is the same thing, letter
 * for letter, as a word in the rest of the app, in any script.
 *
 * What it keeps:
 *   - every different word once, one after another in one block of memory;
 *   - for each word, the items that have it and how often (in the order the items were added);
 *   - for each item, its id and how many words it has.
 *
 * A query word that is in no item as a word is not left unanswered, and is not guessed at
 * from loose letters either. It is looked for, in this order, as
 *   1. part of a longer word ("brenda" in "brendan", "invo" in "invoice");
 *   2. failing that, a near spelling — the app's own rule for one (FuzzyMatcher): one letter
 *      off for a word of up to four letters, two for a longer one — or the word it was made
 *      from ("book" for "booking").
 * Those other words then count together as the query word, at half weight.
 *
 * An item can be removed. Its place is only marked empty at first; the lists are cleaned of
 * such places once there are enough of them to be worth one pass over everything. Ranking
 * never sees a removed item: counts are taken from the items that are there.
 *
 * Thread safety: std::shared_mutex (many searches at once, one writer).
 */
class SearchEngine {
public:
    /** The most items one search returns; the Kotlin caller trims to its own budget
     *  (VaultConfig.Retrieval.BM25_BUDGET × BM25_DOC_OVERFETCH must stay within it). */
    static constexpr size_t MAX_RESULTS = 300;

    struct Stats {
        uint64_t items;         // items that can be found
        uint64_t words;         // different words kept
        uint64_t entries;       // (word, item) pairs kept, removed items' included
        uint64_t removedKept;   // removed items whose entries have not been cleaned out yet
    };

    /** Adds an item, or replaces it when [docId] is already there. */
    void addDocument(const std::string& docId, std::u16string_view words);

    /** Returns false when there was no such item. */
    bool removeDocument(const std::string& docId);

    /** Item ids, best first; at most [limit], and never more than MAX_RESULTS. */
    std::vector<std::string> search(std::u16string_view words, size_t limit = MAX_RESULTS) const;

    void clear();

    Stats stats() const;

private:
    static constexpr double K1 = 1.2;
    static constexpr double B = 0.75;
    /** What a word counts for when the item does not have it as typed (see the class note). */
    static constexpr double OTHER_WORD_WEIGHT = 0.5;
    /** The shortest query word looked for inside longer words, and for near spellings. */
    static constexpr size_t MIN_PART = 2;
    static constexpr size_t MIN_NEAR = 3;
    /** The shortest word a query word may be said to be made from ("book" for "booking"). */
    static constexpr size_t MIN_ROOT = 4;
    /** Removed items are cleaned out when there are this many, and half as many as are left. */
    static constexpr uint32_t SWEEP_AT = 1024;
    static constexpr uint32_t NONE = 0xFFFFFFFFu;

    struct Posting {
        uint32_t doc;
        uint32_t count;
    };

    struct Term {
        uint32_t offset;    // where its text starts in arena_
        uint32_t length;
        uint32_t letters;   // one bit per letter it has, for ruling out near spellings fast
        std::vector<Posting> postings;  // by doc, ascending
    };

    struct Doc {
        std::string id;
        uint32_t length;    // how many words it has
        uint32_t order;     // ties in rank go to the item added first; kept when it is replaced
        bool live;
    };

    /** Reused by one search for the items a query word is in. */
    struct Scratch {
        std::vector<Posting> found;
        std::vector<uint32_t> counts;
        std::vector<uint32_t> docs;
    };

    std::u16string arena_;                 // every word, each followed by a line break
    std::vector<Term> terms_;              // in the order of arena_
    std::vector<uint32_t> slots_;          // open-addressing hash of terms_: term index + 1
    std::vector<Doc> docs_;
    std::unordered_map<std::string, uint32_t> docOf_;   // items that are there
    uint32_t live_ = 0;
    uint32_t dead_ = 0;
    uint32_t nextOrder_ = 0;
    uint64_t entries_ = 0;
    double totalLength_ = 0.0;             // of the items that are there
    std::vector<uint32_t> adding_;         // the words of the item being added

    mutable std::shared_mutex mutex_;

    std::u16string_view textOf(const Term& term) const {
        return std::u16string_view(arena_).substr(term.offset, term.length);
    }
    bool isSaid(const Term& term) const;
    uint32_t findTerm(std::u16string_view word) const;
    uint32_t internTerm(std::u16string_view word);
    void growSlots();

    uint32_t removeLocked(const std::string& docId);
    void sweep();

    void termsContaining(std::u16string_view word, std::vector<uint32_t>& out) const;
    void nearSpellings(std::u16string_view word, std::vector<uint32_t>& out) const;
    void rootsOf(std::u16string_view word, std::vector<uint32_t>& out) const;
    void score(const std::vector<uint32_t>& terms, double weight,
               std::vector<double>& scores, Scratch& scratch) const;
};
