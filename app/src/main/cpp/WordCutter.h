#pragma once

#include <string>

/**
 * Cuts text into the words the keyword engine indexes: runs of letters, combining marks and
 * digits, in lower case, with one space between them.
 *
 * Which characters those are, and what the lower case of each is, is not known here. The
 * Kotlin side says, once, for every UTF-16 code unit (KeywordWords.table, built from Java's
 * own character data) — so a word is cut here exactly as KeywordWords.of cuts it there, in
 * any script, and at the speed of a table look-up. Until it has said, only A–Z, a–z and 0–9
 * are known, which is all the engine's own tests need.
 */
class WordCutter {
public:
    static constexpr size_t TABLE_SIZE = 65536;

    WordCutter() {
        for (char16_t c = u'a'; c <= u'z'; ++c) table_[c] = c;
        for (char16_t c = u'A'; c <= u'Z'; ++c) table_[c] = static_cast<char16_t>(c + 32);
        for (char16_t c = u'0'; c <= u'9'; ++c) table_[c] = c;
    }

    /** [table] has TABLE_SIZE entries: a code unit's lower case when it is part of a word,
     *  0 when it only separates words. */
    void install(const char16_t* table) {
        for (size_t i = 0; i < TABLE_SIZE; ++i) table_[i] = table[i];
    }

    /** Turns [text] into its words, in place. */
    void cut(std::u16string& text) const {
        size_t out = 0;
        bool wordEnded = false;
        for (const char16_t c : text) {
            const char16_t lowered = table_[c];
            if (lowered != 0) {
                if (wordEnded) {
                    text[out++] = u' ';
                    wordEnded = false;
                }
                text[out++] = lowered;
            } else if (out > 0) {
                wordEnded = true;
            }
        }
        text.resize(out);
    }

private:
    char16_t table_[TABLE_SIZE] = {};
};
