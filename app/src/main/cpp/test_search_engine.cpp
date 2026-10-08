// Tests of the keyword engine on its own, without the app around it.
//
// Not part of the app: built and run on a connected emulator or phone by
// tools/vault-check/engine_test.py. The words are given as the Kotlin side gives them:
// lowercased, a space between them.

#include <atomic>
#include <iostream>
#include <string>
#include <thread>
#include <vector>
#include "SearchEngine.h"

static int passed = 0;
static int failed = 0;

using Ids = std::vector<std::string>;

static std::string shown(const Ids& ids) {
    std::string out = "[";
    for (size_t i = 0; i < ids.size(); ++i) out += (i ? ", " : "") + ids[i];
    return out + "]";
}

static void check(const std::string& name, bool ok, const std::string& detail = "") {
    if (ok) {
        ++passed;
    } else {
        ++failed;
        std::cout << "  FAILED: " << name << (detail.empty() ? "" : "  " + detail) << "\n";
    }
}

static void same(const std::string& name, const Ids& got, const Ids& expected) {
    check(name, got == expected, "got " + shown(got) + ", expected " + shown(expected));
}

static bool has(const Ids& ids, const std::string& id) {
    for (const auto& each : ids) if (each == id) return true;
    return false;
}

static void knownWords() {
    SearchEngine e;
    e.addDocument("long", u"the invoice was sent with a long covering letter about many other things");
    e.addDocument("short", u"invoice sent");
    e.addDocument("twice", u"invoice sent invoice");
    e.addDocument("other", u"a letter about nothing");
    same("more of the word first, then the shorter item", e.search(u"invoice"), {"twice", "short", "long"});
    same("a word nothing has finds nothing", e.search(u"qqqq"), {});
    same("nothing typed finds nothing", e.search(u"   "), {});
    same("any of the words", e.search(u"invoice nothing"), {"other", "twice", "short", "long"});
    same("no more than asked for", e.search(u"invoice", 2), {"twice", "short"});
}

static void equalsGoToTheOneAddedFirst() {
    SearchEngine e;
    e.addDocument("page-9", u"total amount due");
    e.addDocument("page-10", u"total amount due");
    e.addDocument("page-2", u"total amount due");
    same("in the order added", e.search(u"amount"), {"page-9", "page-10", "page-2"});
    e.addDocument("page-9", u"total amount due");
    same("an item added again keeps its place", e.search(u"amount"), {"page-9", "page-10", "page-2"});
}

static void otherScripts() {
    SearchEngine e;
    e.addDocument("hindi", u"सरकारी कामकाज में जाए");
    e.addDocument("french", u"résumé de la société");
    same("a Devanagari word", e.search(u"जाए"), {"hindi"});
    same("a word with accents", e.search(u"résumé"), {"french"});
    // Two letters and a vowel sign of "sarkari": found inside the word, as "brenda" in "brendan".
    same("part of a Devanagari word", e.search(u"सरका"), {"hindi"});
}

static void aWordNoItemHasAsAWord() {
    SearchEngine e;
    e.addDocument("dedication", u"and my nicolette avi to brendan and ellen and barbara");
    e.addDocument("pieces", u"bren rend enda break current standard calendar agenda");
    e.addDocument("near", u"the brenta canal");
    same("found inside a longer word, and not by loose pieces of it", e.search(u"brenda"), {"dedication"});
    same("the beginning of a word, as it is typed", e.search(u"nicol"), {"dedication"});
    same("the end of a word", e.search(u"lette"), {"dedication"});

    SearchEngine typo;
    typo.addDocument("newsletter", u"this week in zylaude code");
    typo.addDocument("pieces", u"zylab results and laudx figures");
    typo.addDocument("far", u"zyloud storage");
    same("a near spelling, and not what only shares pieces", typo.search(u"zylaudde"), {"newsletter"});
    same("two letters off for a long word", typo.search(u"zilaudi"), {"newsletter"});
    same("three letters off is not near", typo.search(u"zilaudixx"), {});

    SearchEngine small;
    small.addDocument("cart", u"cart");
    small.addDocument("cat", u"cat");
    small.addDocument("coat", u"coat");
    same("one letter off for a short word", small.search(u"cant"), {"cart", "cat"});
    same("two letters are too few to guess from", small.search(u"xt"), {});

    SearchEngine roots;
    roots.addDocument("book", u"book a table");
    roots.addDocument("execute", u"execute the plan");
    same("the word it was made from", roots.search(u"booking"), {"book"});
    same("less its final e", roots.search(u"executing"), {"execute"});
    same("with its last letter doubled", roots.search(u"planned"), {"execute"});
}

static void aWordThatIsThereBringsNoOthers() {
    SearchEngine e;
    e.addDocument("says-it", u"claude code");
    e.addDocument("near", u"cloud storage and a clause");
    e.addDocument("longer", u"claudette colbert");
    same("only the items that say it", e.search(u"claude"), {"says-it"});

    SearchEngine parts;
    parts.addDocument("inside", u"brendan");
    parts.addDocument("near", u"brenta");
    same("a word found inside another brings no near spellings", parts.search(u"brenda"), {"inside"});
}

static void theWordAsTypedCountsMost() {
    SearchEngine e;
    e.addDocument("part", u"taxonomy");
    e.addDocument("word", u"income");
    e.addDocument("filler-1", u"alpha beta");
    e.addDocument("filler-2", u"gamma delta");
    // Each is in one item of the same length; "taxo" is only part of a word.
    same("before a longer word that has it", e.search(u"taxo income"), {"word", "part"});
}

static void removing() {
    const std::vector<std::pair<std::string, std::u16string>> items = {
        {"a", u"rent receipt for march"}, {"b", u"rent agreement"}, {"c", u"march past"},
        {"d", u"receipt of goods and rent"}, {"e", u"nothing to see"},
    };
    SearchEngine with, without;
    for (const auto& item : items) with.addDocument(item.first, item.second);
    for (const auto& item : items) if (item.first != "b") without.addDocument(item.first, item.second);

    check("removes an item that is there", with.removeDocument("b"));
    check("and says so when it is not", !with.removeDocument("b"));
    check("it is not found", !has(with.search(u"rent"), "b"));
    // Counts are of the items that are there: the same order as if it had never been added.
    for (const char16_t* query : {u"rent", u"receipt", u"march", u"rent receipt march", u"agreement", u"agree"}) {
        same("ranked as if it had never been added", with.search(query), without.search(query));
    }
    check("counted out", with.stats().items == 4 && with.stats().removedKept == 1);

    SearchEngine only;
    only.addDocument("one", u"brenda");
    only.addDocument("two", u"brendan");
    only.removeDocument("one");
    same("a word only a removed item had is no longer a word of the vault", only.search(u"brenda"), {"two"});

    SearchEngine replaced;
    replaced.addDocument("x", u"old words here");
    replaced.addDocument("x", u"new words here");
    same("an item added again is found by its new words", replaced.search(u"new"), {"x"});
    same("and not by the old ones", replaced.search(u"old"), {});
    check("and is one item", replaced.stats().items == 1);
}

static void cleaningOut() {
    // Enough removals for the engine to clean its lists; the answers must not change.
    SearchEngine with, without;
    const int count = 6000;
    for (int i = 0; i < count; ++i) {
        std::u16string words = u"common";
        const std::string n = std::to_string(i);
        words += u" w";
        for (char c : n) words.push_back(static_cast<char16_t>(c));
        if (i % 7 == 0) words += u" seventh seventh";
        if (i % 3 == 0) words += u" third";
        with.addDocument("doc-" + n, words);
        if (i % 2 == 1) without.addDocument("doc-" + n, words);
    }
    for (int i = 0; i < count; i += 2) with.removeDocument("doc-" + std::to_string(i));
    check("the removed items were cleaned out", with.stats().removedKept < 1024 && with.stats().items == 3000);
    for (const char16_t* query : {u"seventh", u"third", u"common", u"w4201", u"w4200", u"seventh third", u"w42"}) {
        same("the same answers after cleaning", with.search(query), without.search(query));
    }
    check("at most 300", with.search(u"common").size() == SearchEngine::MAX_RESULTS);

    with.clear();
    same("nothing after clear", with.search(u"common"), {});
    check("empty after clear", with.stats().items == 0 && with.stats().words == 0);
    with.addDocument("again", u"common ground");
    same("usable after clear", with.search(u"common"), {"again"});
}

static void searchingWhileAdding() {
    SearchEngine e;
    std::atomic<bool> done{false};
    std::atomic<int> answers{0};
    std::vector<std::thread> readers;
    for (int r = 0; r < 3; ++r) {
        readers.emplace_back([&] {
            while (!done) {
                e.search(u"common w1 sevnth");
                ++answers;
            }
        });
    }
    for (int i = 0; i < 4000; ++i) {
        const std::string n = std::to_string(i % 1500);
        std::u16string words = u"common seventh w";
        for (char c : n) words.push_back(static_cast<char16_t>(c));
        e.addDocument("doc-" + n, words);
        if (i % 5 == 0) e.removeDocument("doc-" + std::to_string((i * 7) % 1500));
    }
    done = true;
    for (auto& reader : readers) reader.join();
    check("searches ran alongside the writer", answers > 0);
    check("and the engine is whole", e.search(u"common").size() == SearchEngine::MAX_RESULTS);
}

int main() {
    knownWords();
    equalsGoToTheOneAddedFirst();
    otherScripts();
    aWordNoItemHasAsAWord();
    aWordThatIsThereBringsNoOthers();
    theWordAsTypedCountsMost();
    removing();
    cleaningOut();
    searchingWhileAdding();
    std::cout << passed << " passed, " << failed << " failed\n";
    return failed == 0 ? 0 : 1;
}
