package com.amar.vault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Clean dual-language string provider for English and Hindi (Devanagari).
 * Designed for fast, offline, localized rendering respecting user's language setting.
 */
enum class AppLanguage(val code: String, val label: String) {
    SYSTEM("system", "System Default"),
    ENGLISH("en", "English"),
    HINDI("hi", "हिन्दी (Hindi)")
}

val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.ENGLISH }

object VaultStrings {

    fun searchNav(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "खोजें"
        else -> "Search"
    }

    fun docsNav(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "दस्तावेज़"
        else -> "Documents"
    }

    fun settingsNav(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "सेटिंग्स"
        else -> "Settings"
    }

    fun searchPlaceholder(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "स्क्रीनशॉट, रसीद, नोट्स खोजें..."
        else -> "Search screenshots, receipts, notes..."
    }

    fun filterAll(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "सभी"
        else -> "All"
    }

    fun filterPhotos(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "फ़ोटो और स्क्रीनशॉट"
        else -> "Photos"
    }

    fun filterDocs(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "दस्तावेज़"
        else -> "Documents"
    }

    fun emptyVaultTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "आपका वॉल्ट अभी खाली है"
        else -> "Your vault is empty"
    }

    fun emptyVaultHint(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "एक पीडीएफ, वर्ड, एक्सेल या नोट जोड़ें। स्क्रीनशॉट और फ़ोटो अपने आप पढ़ लिए जाते हैं।"
        else -> "Add a PDF, Word, Excel or note. Screenshots and photos are read automatically."
    }

    fun addDocuments(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "दस्तावेज़ जोड़ें"
        else -> "Add documents"
    }

    fun readingProgress(lang: AppLanguage, count: Int, percent: Int) = when (lang) {
        AppLanguage.HINDI -> "$count दस्तावेज़ पढ़े जा रहे हैं · $percent%"
        else -> "Reading $count document${if (count == 1) "" else "s"} · $percent%"
    }

    fun vaultGlance(lang: AppLanguage, docs: Int, pics: Int) = when (lang) {
        AppLanguage.HINDI -> "$docs दस्तावेज़ · $pics फ़ोटो"
        else -> "$docs documents · $pics photos"
    }

    fun recentSearches(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "हाल की खोजें"
        else -> "Recent searches"
    }

    fun clear(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "साफ़ करें"
        else -> "Clear"
    }

    fun noResultsTitle(lang: AppLanguage, query: String) = when (lang) {
        AppLanguage.HINDI -> "\"$query\" के लिए कोई परिणाम नहीं"
        else -> "No results for \"$query\""
    }

    fun noResultsHint(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "शब्दों की वर्तनी जांचें या फ़ाइल का नाम खोजें"
        else -> "Check spelling or search for words inside the document"
    }

    fun photosSection(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI -> "फ़ोटो ($count)"
        else -> "Photos ($count)"
    }

    fun docsSection(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI -> "दस्तावेज़ ($count)"
        else -> "Documents ($count)"
    }

    fun partialMatches(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI -> "$count आंशिक परिणाम देखें"
        else -> "Show $count partial match${if (count == 1) "" else "es"}"
    }

    fun openPage(lang: AppLanguage, page: Int) = when (lang) {
        AppLanguage.HINDI -> "पेज $page पर खोलें →"
        else -> "Open on page $page →"
    }

    fun currentlyReading(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "पढ़ा जा रहा है"
        else -> "Reading"
    }

    fun readingPageOf(lang: AppLanguage, page: Int, total: Int, percent: Int) = when (lang) {
        AppLanguage.HINDI -> "पेज $page / $total पढ़ा जा रहा है ($percent%)"
        else -> "Reading page $page of $total ($percent%)"
    }

    fun attentionNeeded(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "कार्रवाई आवश्यक"
        else -> "Attention needed"
    }

    fun passwordProtected(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "पासवर्ड सुरक्षित पीडीएफ"
        else -> "Password protected PDF"
    }

    fun unlockPdf(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "पासवर्ड डालें"
        else -> "Unlock PDF"
    }

    fun indexedDocs(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI -> "तैयार दस्तावेज़ ($count)"
        else -> "Ready in vault ($count)"
    }

    fun directFact(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "⚡ सीधा तथ्य"
        else -> "⚡ Fact"
    }

    fun foundIn(lang: AppLanguage, source: String) = when (lang) {
        AppLanguage.HINDI -> "$source में मिला"
        else -> "Found in $source"
    }

    fun viewSource(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "स्रोत पेज देखें →"
        else -> "View source page →"
    }


    fun appLockTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "ऐप लॉक"
        else -> "App Lock"
    }

    fun appLockDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "वॉल्ट खोलने के लिए फ़िंगरप्रिंट या पिन आवश्यक करें"
        else -> "Require biometric or device PIN to open vault"
    }

    fun photoSearchTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "फ़ोटो खोज"
        else -> "Photo Search"
    }

    fun photoSearchDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "फ़ोटो में क्या है, उससे खोजें (~207 MB डाउनलोड)"
        else -> "Search photos by what is in them (~207 MB download)"
    }

    fun photoSearchEnabled(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "सक्षम है (207 MB)"
        else -> "Ready (207 MB)"
    }

    fun photoSearchDownloading(lang: AppLanguage, percent: Int) = when (lang) {
        AppLanguage.HINDI -> "डाउनलोड हो रहा है: $percent%"
        else -> "Downloading: $percent%"
    }

    fun largerTextTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "बड़ा टेक्स्ट"
        else -> "Larger text"
    }

    fun largerTextDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "आसानी से पढ़ने के लिए बड़ा टेक्स्ट और सरल दृश्य"
        else -> "Larger text and simpler view for easier reading"
    }

    fun languageTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "भाषा"
        else -> "Language"
    }

    fun offlineBadge(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "100% ऑफ़लाइन"
        else -> "100% Offline"
    }

    fun offlineDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "सब कुछ आपके डिवाइस पर प्रोसेस होता है। फ़ोन से कुछ बाहर नहीं जाता।"
        else -> "Everything is processed on your device. Nothing leaves your phone."
    }

    fun totalItems(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI -> "वॉल्ट में कुल आइटम: $count"
        else -> "Items in Vault: $count"
    }
}
