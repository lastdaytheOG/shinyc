#include "SearchEngine.h"

#include <algorithm>
#include <cmath>
#include <mutex>

namespace {

using Word = std::u16string_view;

/** Ends each word in the block of words. It is a separator, so no word has one in it. */
constexpr char16_t WORD_END = u'\n';

inline bool isSeparator(char16_t c) { return c <= u' '; }

template <typename F>
void forEachWord(Word text, F&& f) {
    const size_t n = text.size();
    size_t i = 0;
    while (i < n) {
        while (i < n && isSeparator(text[i])) ++i;
        const size_t start = i;
        while (i < n && !isSeparator(text[i])) ++i;
        if (i > start) f(text.substr(start, i - start));
    }
}

uint64_t hashOf(Word word) {
    uint64_t h = 1469598103934665603ull;  // FNV-1a
    for (char16_t c : word) {
        h ^= c;
        h *= 1099511628211ull;
    }
    return h ^ (h >> 29);
}

uint32_t lettersOf(Word word) {
    uint32_t bits = 0;
    for (char16_t c : word) bits |= 1u << (c & 31);
    return bits;
}

inline int bitsSet(uint32_t v) { return __builtin_popcount(v); }

/**
 * True iff [query] can be turned into [text] with at most [max] letters added, dropped or
 * changed. The same sum as FuzzyMatcher.withinDistance on the Kotlin side, which gives up the
 * same way once no row can come in under the limit.
 */
bool within(Word query, Word text, int max, std::vector<int>& prev, std::vector<int>& curr) {
    const size_t n = text.size();
    prev.resize(n + 1);
    curr.resize(n + 1);
    for (size_t j = 0; j <= n; ++j) prev[j] = static_cast<int>(j);
    for (size_t i = 1; i <= query.size(); ++i) {
        curr[0] = static_cast<int>(i);
        int rowMin = curr[0];
        const char16_t qc = query[i - 1];
        for (size_t j = 1; j <= n; ++j) {
            const int cost = qc == text[j - 1] ? 0 : 1;
            const int v = std::min({prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost});
            curr[j] = v;
            if (v < rowMin) rowMin = v;
        }
        if (rowMin > max) return false;
        prev.swap(curr);
    }
    return prev[n] <= max;
}

/** The endings a root is looked for under; FuzzyMatcher.ENDINGS on the Kotlin side. */
constexpr Word ENDINGS[] = {
    u"ing", u"ings", u"ed", u"er", u"ers", u"ion", u"ions", u"ation", u"ations",
    u"ment", u"ments", u"ness", u"able", u"ity", u"ities", u"ful", u"less",
};

}  // namespace

// =============================================================================
// The words
// =============================================================================

bool SearchEngine::isSaid(const Term& term) const {
    if (term.postings.empty()) return false;
    if (dead_ == 0) return true;
    for (const Posting& p : term.postings) {
        if (docs_[p.doc].live) return true;
    }
    return false;
}

uint32_t SearchEngine::findTerm(Word word) const {
    if (slots_.empty()) return NONE;
    const size_t mask = slots_.size() - 1;
    for (size_t i = hashOf(word) & mask;; i = (i + 1) & mask) {
        const uint32_t slot = slots_[i];
        if (slot == 0) return NONE;
        if (textOf(terms_[slot - 1]) == word) return slot - 1;
    }
}

uint32_t SearchEngine::internTerm(Word word) {
    const uint32_t known = findTerm(word);
    if (known != NONE) return known;

    if ((terms_.size() + 1) * 2 > slots_.size()) growSlots();
    const uint32_t id = static_cast<uint32_t>(terms_.size());
    Term term;
    term.offset = static_cast<uint32_t>(arena_.size());
    term.length = static_cast<uint32_t>(word.size());
    term.letters = lettersOf(word);
    arena_.append(word);
    arena_.push_back(WORD_END);
    terms_.push_back(std::move(term));

    const size_t mask = slots_.size() - 1;
    size_t i = hashOf(word) & mask;
    while (slots_[i] != 0) i = (i + 1) & mask;
    slots_[i] = id + 1;
    return id;
}

void SearchEngine::growSlots() {
    const size_t size = slots_.empty() ? 1024 : slots_.size() * 2;
    slots_.assign(size, 0);
    const size_t mask = size - 1;
    for (uint32_t id = 0; id < terms_.size(); ++id) {
        size_t i = hashOf(textOf(terms_[id])) & mask;
        while (slots_[i] != 0) i = (i + 1) & mask;
        slots_[i] = id + 1;
    }
}

// =============================================================================
// Adding and removing items
// =============================================================================

void SearchEngine::addDocument(const std::string& docId, Word words) {
    std::unique_lock<std::shared_mutex> lock(mutex_);

    // An item that is added again keeps its place among equals.
    const uint32_t keptOrder = removeLocked(docId);
    const uint32_t doc = static_cast<uint32_t>(docs_.size());

    adding_.clear();
    forEachWord(words, [&](Word word) { adding_.push_back(internTerm(word)); });
    const uint32_t length = static_cast<uint32_t>(adding_.size());

    // Each word once, with how often the item has it. The item is the newest, so its entry
    // goes at the end of each word's list and the lists stay in order.
    std::sort(adding_.begin(), adding_.end());
    for (size_t i = 0; i < adding_.size();) {
        size_t j = i;
        while (j < adding_.size() && adding_[j] == adding_[i]) ++j;
        terms_[adding_[i]].postings.push_back({doc, static_cast<uint32_t>(j - i)});
        ++entries_;
        i = j;
    }

    docs_.push_back({docId, length, keptOrder != NONE ? keptOrder : nextOrder_++, true});
    docOf_[docId] = doc;
    ++live_;
    totalLength_ += length;
}

bool SearchEngine::removeDocument(const std::string& docId) {
    std::unique_lock<std::shared_mutex> lock(mutex_);
    return removeLocked(docId) != NONE;
}

/** Returns the removed item's place among equals, or NONE when there was no such item. */
uint32_t SearchEngine::removeLocked(const std::string& docId) {
    const auto found = docOf_.find(docId);
    if (found == docOf_.end()) return NONE;
    Doc& doc = docs_[found->second];
    const uint32_t order = doc.order;
    doc.live = false;
    totalLength_ -= doc.length;
    docOf_.erase(found);
    --live_;
    ++dead_;
    if (dead_ >= SWEEP_AT && dead_ >= live_ / 2) sweep();
    return order;
}

/** One pass over everything: takes the removed items, and their entries, out for good. */
void SearchEngine::sweep() {
    std::vector<uint32_t> movedTo(docs_.size(), NONE);
    uint32_t next = 0;
    for (uint32_t i = 0; i < docs_.size(); ++i) {
        if (!docs_[i].live) continue;
        movedTo[i] = next;
        if (next != i) docs_[next] = std::move(docs_[i]);
        ++next;
    }
    docs_.resize(next);
    for (auto& entry : docOf_) entry.second = movedTo[entry.second];

    entries_ = 0;
    for (Term& term : terms_) {
        auto keep = term.postings.begin();
        for (const Posting& p : term.postings) {
            if (movedTo[p.doc] != NONE) *keep++ = {movedTo[p.doc], p.count};
        }
        term.postings.erase(keep, term.postings.end());
        // A word no item has any more keeps its place in the block; it is passed over.
        if (term.postings.empty()) std::vector<Posting>().swap(term.postings);
        entries_ += term.postings.size();
    }
    dead_ = 0;
}

void SearchEngine::clear() {
    std::unique_lock<std::shared_mutex> lock(mutex_);
    std::u16string().swap(arena_);
    std::vector<Term>().swap(terms_);
    std::vector<uint32_t>().swap(slots_);
    std::vector<Doc>().swap(docs_);
    docOf_.clear();
    live_ = 0;
    dead_ = 0;
    nextOrder_ = 0;
    entries_ = 0;
    totalLength_ = 0.0;
}

SearchEngine::Stats SearchEngine::stats() const {
    std::shared_lock<std::shared_mutex> lock(mutex_);
    return {live_, terms_.size(), entries_, dead_};
}

// =============================================================================
// A query word that no item has as a word
// =============================================================================

/** The words [word] is part of. One pass over the block of words; a word is listed once. */
void SearchEngine::termsContaining(Word word, std::vector<uint32_t>& out) const {
    const Word all(arena_);
    size_t at = all.find(word);
    while (at != Word::npos) {
        // The word this position is in: the last one that starts at or before it.
        const auto after = std::upper_bound(
            terms_.begin(), terms_.end(), at,
            [](size_t position, const Term& term) { return position < term.offset; });
        const uint32_t id = static_cast<uint32_t>(after - terms_.begin()) - 1;
        const Term& term = terms_[id];
        if (isSaid(term)) out.push_back(id);
        at = all.find(word, static_cast<size_t>(term.offset) + term.length);
    }
}

void SearchEngine::nearSpellings(Word word, std::vector<uint32_t>& out) const {
    const int max = word.size() <= 4 ? 1 : 2;
    const int length = static_cast<int>(word.size());
    const uint32_t letters = lettersOf(word);
    std::vector<int> prev, curr;
    for (uint32_t id = 0; id < terms_.size(); ++id) {
        const Term& term = terms_[id];
        if (term.length < MIN_NEAR) continue;
        const int longer = static_cast<int>(term.length) - length;
        if (longer > max || -longer > max) continue;
        // A letter one of them has and the other has not costs a change each.
        if (bitsSet(letters & ~term.letters) > max || bitsSet(term.letters & ~letters) > max) continue;
        if (!within(word, textOf(term), max, prev, curr)) continue;
        if (isSaid(term)) out.push_back(id);
    }
}

/**
 * The words [word] is made from by putting an ending on: the word itself ("book", booking),
 * the word less its final e ("execute", executing), or with its last letter doubled ("plan",
 * planned). FuzzyMatcher.isRootOf on the Kotlin side.
 */
void SearchEngine::rootsOf(Word word, std::vector<uint32_t>& out) const {
    const auto add = [&](Word root) {
        if (root.size() < MIN_ROOT) return;
        const uint32_t id = findTerm(root);
        if (id != NONE && isSaid(terms_[id])) out.push_back(id);
    };
    std::u16string withE;
    for (Word ending : ENDINGS) {
        if (word.size() < ending.size() || word.substr(word.size() - ending.size()) != ending) continue;
        const size_t stem = word.size() - ending.size();
        add(word.substr(0, stem));
        withE.assign(word.substr(0, stem));
        withE.push_back(u'e');
        add(withE);
        if (stem >= 2 && word[stem - 1] == word[stem - 2]) add(word.substr(0, stem - 1));
    }
}

// =============================================================================
// Searching
// =============================================================================

/**
 * Adds to [scores] what the items earn for one query word, which [terms] stand for. Several
 * count as one word: an item's count of it is its count of them all, and the word is as rare
 * as the items that have any of them are few.
 */
void SearchEngine::score(const std::vector<uint32_t>& terms, double weight,
                         std::vector<double>& scores, Scratch& scratch) const {
    std::vector<Posting>& found = scratch.found;
    found.clear();
    if (terms.size() == 1) {
        for (const Posting& p : terms_[terms[0]].postings) {
            if (docs_[p.doc].live) found.push_back(p);
        }
    } else {
        if (scratch.counts.size() < docs_.size()) scratch.counts.assign(docs_.size(), 0);
        scratch.docs.clear();
        for (uint32_t id : terms) {
            for (const Posting& p : terms_[id].postings) {
                if (!docs_[p.doc].live) continue;
                if (scratch.counts[p.doc] == 0) scratch.docs.push_back(p.doc);
                scratch.counts[p.doc] += p.count;
            }
        }
        for (uint32_t doc : scratch.docs) {
            found.push_back({doc, scratch.counts[doc]});
            scratch.counts[doc] = 0;
        }
    }
    if (found.empty()) return;

    const double N = static_cast<double>(live_);
    const double df = static_cast<double>(found.size());
    const double idf = std::log((N - df + 0.5) / (df + 0.5) + 1.0) * weight;
    const double avgLength = totalLength_ / N;
    for (const Posting& p : found) {
        const double tf = static_cast<double>(p.count);
        const double length = static_cast<double>(docs_[p.doc].length);
        const double tfNorm = (tf * (K1 + 1.0)) / (tf + K1 * (1.0 - B + B * (length / avgLength)));
        scores[p.doc] += idf * tfNorm;
    }
}

std::vector<std::string> SearchEngine::search(Word words, size_t limit) const {
    std::shared_lock<std::shared_mutex> lock(mutex_);
    if (live_ == 0) return {};

    std::vector<double> scores(docs_.size(), 0.0);
    std::vector<uint32_t> terms;
    Scratch scratch;
    bool any = false;

    forEachWord(words, [&](Word word) {
        terms.clear();
        double weight = 1.0;
        const uint32_t exact = findTerm(word);
        if (exact != NONE && isSaid(terms_[exact])) {
            terms.push_back(exact);
        } else {
            weight = OTHER_WORD_WEIGHT;
            if (word.size() >= MIN_PART) termsContaining(word, terms);
            if (terms.empty() && word.size() >= MIN_NEAR) {
                nearSpellings(word, terms);
                rootsOf(word, terms);
                std::sort(terms.begin(), terms.end());
                terms.erase(std::unique(terms.begin(), terms.end()), terms.end());
            }
        }
        if (terms.empty()) return;
        score(terms, weight, scores, scratch);
        any = true;
    });
    if (!any) return {};

    std::vector<std::pair<uint32_t, double>> ranked;
    ranked.reserve(256);
    for (uint32_t doc = 0; doc < scores.size(); ++doc) {
        if (scores[doc] > 0.0) ranked.emplace_back(doc, scores[doc]);
    }
    const size_t top = std::min({ranked.size(), limit, MAX_RESULTS});
    // Equal scores go to the item added first, so the first N of a deeper read are exactly
    // what a shallower read returns, and a document's earlier page comes before a later one.
    std::partial_sort(
        ranked.begin(), ranked.begin() + top, ranked.end(),
        [this](const auto& a, const auto& b) {
            return a.second != b.second ? a.second > b.second
                                        : docs_[a.first].order < docs_[b.first].order;
        });

    std::vector<std::string> ids;
    ids.reserve(top);
    for (size_t i = 0; i < top; ++i) ids.push_back(docs_[ranked[i].first].id);
    return ids;
}
