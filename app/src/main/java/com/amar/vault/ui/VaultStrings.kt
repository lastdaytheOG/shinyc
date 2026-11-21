package com.amar.vault.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.amar.vault.indexing.DocumentImport
import com.amar.vault.indexing.ImportFailure
import com.amar.vault.indexing.ImportOrigin
import com.amar.vault.indexing.ImportState

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
        AppLanguage.HINDI -> "स्क्रीनशॉट, रसीदें, नोट्स खोजें…"
        else -> "Search screenshots, receipts, notes…"
    }

    fun searching(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "खोजा जा रहा है…"
        else -> "Searching…"
    }

    fun filterAll(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "सभी"
        else -> "All"
    }

    fun filterPhotos(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "फ़ोटो"
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
        AppLanguage.HINDI -> "कोई PDF, Word, Excel या नोट जोड़ें। स्क्रीनशॉट और फ़ोटो अपने आप पढ़े जाते हैं।"
        else -> "Add a PDF, Word, Excel file or a note. Screenshots and photos are read on their own."
    }

    fun addDocuments(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "दस्तावेज़ जोड़ें"
        else -> "Add documents"
    }

    fun vaultGlance(lang: AppLanguage, docs: Int, pics: Int) = when (lang) {
        AppLanguage.HINDI -> "$docs दस्तावेज़ · $pics फ़ोटो"
        else -> "$docs documents · $pics photos"
    }

    fun recentSearches(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "हाल की खोजें"
        else -> "Recent searches"
    }

    fun recentDocuments(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "हाल के दस्तावेज़"
        else -> "Recent documents"
    }

    fun pagesCount(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI -> "$count पेज"
        else -> "$count page${if (count == 1) "" else "s"}"
    }

    fun documentLabel(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "दस्तावेज़"
        else -> "Document"
    }

    fun noResultsTitle(lang: AppLanguage, query: String) = when (lang) {
        AppLanguage.HINDI -> "\"$query\" के लिए कुछ नहीं मिला"
        else -> "Nothing found for \"$query\""
    }

    fun noResultsHint(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "वर्तनी जाँचें, या दस्तावेज़ के भीतर के शब्द खोजें।"
        else -> "Check the spelling, or search for words inside the document."
    }

    fun similarSpellingsNote(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "ये आपके टाइप किए शब्दों से मिलती-जुलती वर्तनी के शब्दों से मिले हैं।"
        else -> "These were found by words spelt nearly like the ones you typed."
    }

    fun photosSection(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI -> "फ़ोटो ($count)"
        else -> "Photos ($count)"
    }

    fun meaningSection(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI -> "अर्थ से मिलते-जुलते ($count)"
        else -> "Related by meaning ($count)"
    }

    fun meaningNote(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "इनमें आपके शब्द नहीं हैं; ये उसी बात के बारे में हैं।"
        else -> "These don't contain your words; they are about the same thing."
    }

    fun nothingYet(lang: AppLanguage, percent: Int) = when (lang) {
        AppLanguage.HINDI -> "अभी कुछ नहीं मिला। आपकी तिजोरी का $percent% हिस्सा अर्थ के लिए पढ़ा जा चुका है।"
        else -> "Nothing found yet. $percent% of your vault has been read for meaning so far."
    }

    fun docsSection(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI -> "दस्तावेज़ ($count)"
        else -> "Documents ($count)"
    }

    fun partialMatches(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI -> "$count आंशिक मिलान दिखाएँ"
        else -> "Show $count partial match${if (count == 1) "" else "es"}"
    }

    fun partialMatchesNote(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "दूसरे शब्दों के भीतर, या मिलती-जुलती वर्तनी से मिला:"
        else -> "Found inside other words, or spelt nearly the same:"
    }

    fun filedUnderLine(lang: AppLanguage, tag: String) = when (lang) {
        AppLanguage.HINDI -> "\"$tag\" के अंतर्गत रखा गया"
        else -> "Listed under \"$tag\""
    }

    fun pageBadge(lang: AppLanguage, page: Int) = when (lang) {
        AppLanguage.HINDI -> "पेज $page"
        else -> "Page $page"
    }

    fun openPage(lang: AppLanguage, page: Int) = when (lang) {
        AppLanguage.HINDI -> "पेज $page पर खोलें"
        else -> "Open on page $page"
    }

    fun readingNow(lang: AppLanguage, name: String) = when (lang) {
        AppLanguage.HINDI -> "$name पढ़ा जा रहा है…"
        else -> "Reading $name…"
    }

    fun readingNowMore(lang: AppLanguage, name: String, more: Int) = when (lang) {
        AppLanguage.HINDI -> "$name पढ़ा जा रहा है · और $more बाकी…"
        else -> "Reading $name and $more more…"
    }

    fun currentlyReading(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "पढ़ा जा रहा है"
        else -> "Reading"
    }

    fun attentionNeeded(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "ध्यान देना ज़रूरी है"
        else -> "Attention needed"
    }

    fun failedToRead(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "इन्हें पढ़ा नहीं जा सका"
        else -> "Could not be read"
    }

    fun missingFiles(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI ->
            if (count == 1) "1 फ़ाइल अब पहले की जगह नहीं है"
            else "$count फ़ाइलें अब पहले की जगह नहीं हैं"
        else -> "$count file${if (count == 1) "" else "s"} moved or deleted"
    }

    fun missingFilesHint(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "पहले से पढ़ा हुआ टेक्स्ट अब भी खोजा जा सकता है।"
        else -> "The text already read can still be searched."
    }

    fun lockedPdfDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "पासवर्ड से सुरक्षित PDF — पढ़ने के लिए पासवर्ड डालें।"
        else -> "Password-protected PDF — type its password to read it."
    }

    fun passwordLabel(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "पासवर्ड"
        else -> "Password"
    }

    fun unlock(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "खोलें"
        else -> "Unlock"
    }

    fun tryAgain(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "फिर से कोशिश करें"
        else -> "Try again"
    }

    fun indexedDocs(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI -> "तैयार ($count)"
        else -> "Ready ($count)"
    }

    fun removalTitle(lang: AppLanguage, name: String) = when (lang) {
        AppLanguage.HINDI -> "\"$name\" को वॉल्ट से हटाएँ?"
        else -> "Remove \"$name\" from your vault?"
    }

    fun removalBodyDocument(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "इसका टेक्स्ट अब खोज में नहीं मिलेगा। फ़ाइल आपके फ़ोन पर रहेगी, और आप उसे दोबारा जोड़ सकते हैं।"
        else -> "Its text will no longer be found by search. The file itself stays on your phone, and you can add it again."
    }

    fun removalBodyPicture(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "यह अब खोज में नहीं मिलेगा। फ़ोटो खुद आपके फ़ोन पर रहेगी।"
        else -> "It will no longer be found by search. The picture itself stays on your phone."
    }

    fun removalBodyOther(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "यह अब खोज में नहीं मिलेगा।"
        else -> "It will no longer be found by search."
    }

    fun remove(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "हटाएँ"
        else -> "Remove"
    }

    fun cancel(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "रद्द करें"
        else -> "Cancel"
    }

    fun importProgress(lang: AppLanguage, import: DocumentImport): String {
        val pages = import.pageCount
        return when (import.state) {
            ImportState.WAITING -> when {
                import.waitsForCharger -> when (lang) {
                    AppLanguage.HINDI ->
                        "${import.pagesDone} में से $pages पेज पढ़े गए। जो पढ़ा गया है वह अब खोजा जा सकता है। बाकी चार्ज पर पढ़ा जाएगा।"
                    else ->
                        "Read ${import.pagesDone} of $pages pages. What is read can be searched. The rest is read while the phone charges."
                }
                import.readsRestNow -> when (lang) {
                    AppLanguage.HINDI -> "बाकी पढ़ा जा रहा है"
                    else -> "Reading the rest"
                }
                else -> when (lang) {
                    AppLanguage.HINDI -> "अपनी बारी का इंतज़ार"
                    else -> "Waiting its turn"
                }
            }
            ImportState.READING -> when {
                pages != null && import.pagesDone > 0 -> when (lang) {
                    AppLanguage.HINDI -> "पेज ${import.pagesDone} / $pages पढ़ा जा रहा है। जो पढ़ा गया है वह अब खोजा जा सकता है।"
                    else -> "Reading page ${import.pagesDone} of $pages. What is read can be searched already."
                }
                pages != null -> when (lang) {
                    AppLanguage.HINDI -> "पढ़ा जा रहा है: $pages पेज"
                    else -> "Reading: $pages page${if (pages == 1) "" else "s"}"
                }
                else -> when (lang) {
                    AppLanguage.HINDI -> "पढ़ा जा रहा है"
                    else -> "Reading"
                }
            }
            ImportState.DONE -> when {
                pages != null -> when (lang) {
                    AppLanguage.HINDI -> "पढ़ लिया: $pages पेज"
                    else -> "Read: $pages page${if (pages == 1) "" else "s"}"
                }
                else -> when (lang) {
                    AppLanguage.HINDI -> "पढ़ लिया"
                    else -> "Read"
                }
            }
            ImportState.ALREADY_THERE -> when (lang) {
                AppLanguage.HINDI -> "पहले से वॉल्ट में है"
                else -> "Already in your vault"
            }
            ImportState.FAILED -> importWhatHappened(lang, import.failure ?: ImportFailure.OTHER, import.failureDetail)
        }
    }

    fun importWhatHappened(lang: AppLanguage, failure: ImportFailure, detail: String?): String = when (failure) {
        ImportFailure.FILE_GONE -> when (lang) {
            AppLanguage.HINDI -> "फ़ाइल खोली नहीं जा सकी। आपके चुनने के बाद वह हट गई, बदल गई या कहीं और चली गई।"
            else -> "The file could not be opened. It was moved, renamed or deleted after you chose it."
        }
        ImportFailure.LOCKED -> when (lang) {
            AppLanguage.HINDI ->
                if (detail == DocumentImport.WRONG_PASSWORD) "यह पासवर्ड इस PDF को नहीं खोल सका।"
                else "यह PDF पासवर्ड से बंद है।"
            else ->
                if (detail == DocumentImport.WRONG_PASSWORD) "That password did not open this PDF."
                else "This PDF is locked with a password."
        }
        ImportFailure.NOT_A_DOCUMENT -> when (lang) {
            AppLanguage.HINDI ->
                (if (detail.isNullOrBlank()) "इस किस्म की फ़ाइल नहीं पढ़ी जा सकती।" else ".$detail फ़ाइल नहीं पढ़ी जा सकती।") +
                    " ऐप PDF, Word (.docx), Excel (.xlsx) और EPUB पढ़ता है।"
            else ->
                (if (detail.isNullOrBlank()) "This kind of file cannot be read." else "A .$detail file cannot be read.") +
                    " The app reads PDF, Word (.docx), Excel (.xlsx) and EPUB."
        }
        ImportFailure.DAMAGED -> when (lang) {
            AppLanguage.HINDI -> "फ़ाइल टूटी-फूटी या अधूरी है, इसलिए पढ़ी नहीं जा सकी।"
            else -> "The file is damaged or incomplete, so it could not be read."
        }
        ImportFailure.NO_TEXT -> when (lang) {
            AppLanguage.HINDI -> "इसमें कोई शब्द नहीं मिला। यह वॉल्ट में सिर्फ़ अपने नाम से है।"
            else -> "No words were found in it. It is in your vault under its name only."
        }
        ImportFailure.TOO_BIG -> when (lang) {
            AppLanguage.HINDI -> "फ़ाइल इस फ़ोन की खाली मेमोरी के लिए बहुत बड़ी है।"
            else -> "The file is too big for the memory this phone has free."
        }
        ImportFailure.NO_SPACE -> when (lang) {
            AppLanguage.HINDI -> "फ़ोन की स्टोरेज भर गई है, इसलिए उसका टेक्स्ट सेव नहीं हुआ।"
            else -> "The phone's storage is full, so its text could not be saved."
        }
        ImportFailure.OTHER -> when (lang) {
            AppLanguage.HINDI -> "पढ़ा नहीं जा सका" + (if (detail.isNullOrBlank()) "।" else ": $detail")
            else -> "It could not be read" + (if (detail.isNullOrBlank()) "." else ": $detail")
        }
    }

    fun importWhatToDo(lang: AppLanguage, failure: ImportFailure, origin: ImportOrigin): String = when (failure) {
        ImportFailure.FILE_GONE -> when (lang) {
            AppLanguage.HINDI ->
                if (origin == ImportOrigin.SHARED) "इसे दोबारा ऐप में शेयर करें।"
                else "इसे अपनी मौजूदा जगह से दोबारा चुनें।"
            else ->
                if (origin == ImportOrigin.SHARED) "Share it to the app again."
                else "Choose it again from where it is now."
        }
        ImportFailure.LOCKED -> when (lang) {
            AppLanguage.HINDI -> "दस्तावेज़ जोड़ें से खोलें और उसका पासवर्ड डालें।"
            else -> "Open Add documents and type its password."
        }
        ImportFailure.NOT_A_DOCUMENT -> when (lang) {
            AppLanguage.HINDI -> "उसे PDF के रूप में सेव करें और PDF जोड़ें।"
            else -> "Save it as a PDF and add the PDF."
        }
        ImportFailure.DAMAGED -> when (lang) {
            AppLanguage.HINDI -> "देखें कि वह दूसरे ऐप में खुलती है या नहीं। अगर वह डाउनलोड की गई थी, तो उसे फिर से डाउनलोड करें और नई कॉपी जोड़ें।"
            else -> "See if it opens in another app. If it was downloaded, download it again and add the new copy."
        }
        ImportFailure.NO_TEXT -> when (lang) {
            AppLanguage.HINDI -> "अगर यह स्कैन है, तो तेज़ या सीधा स्कैन पढ़ा जा सकता है।"
            else -> "If it is a scan, a sharper or straighter scan may be readable."
        }
        ImportFailure.TOO_BIG -> when (lang) {
            AppLanguage.HINDI -> "दूसरे ऐप बंद करके फिर से कोशिश करें, या फ़ाइल को छोटे हिस्सों में बाँटें।"
            else -> "Close other apps and tap Try again, or split the file into smaller parts."
        }
        ImportFailure.NO_SPACE -> when (lang) {
            AppLanguage.HINDI -> "कुछ जगह खाली करें, फिर फिर से कोशिश करें।"
            else -> "Free some space, then tap Try again."
        }
        ImportFailure.OTHER -> when (lang) {
            AppLanguage.HINDI -> "फिर से कोशिश करें। अगर वैसा ही नाकाम रहे, तो हो सकता है कि यह फ़ाइल ऐप न पढ़ सके।"
            else -> "Tap Try again. If it fails the same way, the file may be one the app cannot read."
        }
    }

    fun appLockTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "ऐप लॉक"
        else -> "App Lock"
    }

    fun appLockDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "वॉल्ट खोलने के लिए फ़िंगरप्रिंट या फ़ोन का पिन ज़रूरी होगा"
        else -> "Require your fingerprint or phone PIN to open the vault"
    }

    fun unlockVault(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "वॉल्ट खोलें"
        else -> "Unlock vault"
    }

    fun vaultLocked(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "वॉल्ट बंद है"
        else -> "Vault locked"
    }

    fun photoSearchTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "फ़ोटो खोज"
        else -> "Photo search"
    }

    fun photoSearchDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "फ़ोटो में क्या है, उसी से खोजें (~207 MB डाउनलोड)"
        else -> "Search photos by what is in them (~207 MB download)"
    }

    fun ready(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "तैयार"
        else -> "Ready"
    }

    fun downloadPhotoModel(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "फ़ोटो खोज मॉडल डाउनलोड करें (~207 MB)"
        else -> "Download photo search model (~207 MB)"
    }

    fun downloadingFile(lang: AppLanguage, file: String, percent: Int) = when (lang) {
        AppLanguage.HINDI -> "$file डाउनलोड हो रहा है · $percent%"
        else -> "Downloading $file · $percent%"
    }

    fun verifying(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "जाँच कर सक्रिय किया जा रहा है…"
        else -> "Verifying and activating…"
    }

    fun downloadFailed(lang: AppLanguage, reason: String) = when (lang) {
        AppLanguage.HINDI -> "डाउनलोड नहीं हुआ: $reason"
        else -> "Download failed: $reason"
    }

    fun retryDownload(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "फिर से डाउनलोड करें"
        else -> "Retry download"
    }

    fun meaningModelTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "अर्थ से खोज मॉडल (~585 MB)"
        else -> "Meaning search model (~585 MB)"
    }

    fun meaningModelDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "पन्नों को उनके अर्थ से ढूँढता है, अंग्रेज़ी और हिन्दी में।"
        else -> "Finds pages by their meaning, in English and Hindi."
    }

    fun openModelPage(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "मॉडल डाउनलोड पेज खोलें"
        else -> "Open the model download page"
    }

    fun largerTextTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "बड़ा टेक्स्ट"
        else -> "Larger text"
    }

    fun largerTextDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "आसानी से पढ़ने के लिए बड़ा टेक्स्ट और सरल दृश्य"
        else -> "Larger text and a simpler view for easier reading"
    }

    fun languageTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "भाषा"
        else -> "Language"
    }

    fun languageSystem(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "सिस्टम"
        else -> "System"
    }

    fun offlineDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "सब कुछ आपके डिवाइस पर ही प्रोसेस होता है। फ़ोन से कुछ भी बाहर नहीं जाता।"
        else -> "Everything is processed on your device. Nothing leaves your phone."
    }

    fun totalItems(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI -> "वॉल्ट में कुल आइटम: $count"
        else -> "Items in the vault: $count"
    }

    fun versionLine(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "संस्करण 1.0 · पूरी तरह ऑफ़लाइन"
        else -> "Version 1.0 · Fully offline"
    }

    fun developerModeTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "डेवलपर मोड"
        else -> "Developer mode"
    }

    fun developerModeDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "जाँच के लिए भीतरी औज़ार खोलता है"
        else -> "Unlocks internal tools for testing"
    }

    fun openDevTools(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "डेवलपर औज़ार खोलें"
        else -> "Open developer tools"
    }

    fun sectionSecurity(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "सुरक्षा"
        else -> "Security"
    }

    fun sectionSearchModels(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "खोज के मॉडल"
        else -> "Search models"
    }

    fun sectionDisplay(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "दिखावट और भाषा"
        else -> "Display & language"
    }

    fun sectionAbout(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "ऐप और गोपनीयता"
        else -> "About & privacy"
    }

    fun sectionDeveloper(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "डेवलपर"
        else -> "Developer"
    }

    fun detailsSection(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "जानकारी"
        else -> "Details"
    }

    fun noDetails(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "इस आइटम की कोई जानकारी नहीं मिली।"
        else -> "No details were found for this item."
    }

    fun showStoredText(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "पढ़ा हुआ टेक्स्ट दिखाएँ"
        else -> "Show the text read from it"
    }

    fun hideStoredText(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "टेक्स्ट छिपाएँ"
        else -> "Hide the text"
    }

    fun onboardingTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "अमर वॉल्ट में आपका स्वागत है"
        else -> "Welcome to Amar Vault"
    }

    fun onboardingSubtitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "आपकी फ़ाइलें, आपके फ़ोन पर। एक खोज बॉक्स में सब कुछ। कुछ भी फ़ोन से बाहर नहीं जाता।"
        else -> "Your files, on your phone. Everything from one search box. Nothing leaves it."
    }

    fun onboardingQuestion(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "क्या पढ़ा जाए?"
        else -> "What should be read?"
    }

    fun onboardingChangeNote(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "आप इसे कभी भी सेटिंग्स में बदल सकते हैं।"
        else -> "You can change this anytime in Settings."
    }

    fun allPhotos(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "सभी फ़ोटो"
        else -> "All photos"
    }

    fun photosOnDevice(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI -> "डिवाइस पर $count फ़ोटो"
        else -> "$count photos on the device"
    }

    fun screenshotsChoice(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "स्क्रीनशॉट"
        else -> "Screenshots"
    }

    fun cameraChoice(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "कैमरा"
        else -> "Camera"
    }

    fun whatsappChoice(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "WhatsApp इमेज"
        else -> "WhatsApp images"
    }

    fun downloadsChoice(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "डाउनलोड"
        else -> "Downloads"
    }

    fun documentsChoice(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "दस्तावेज़"
        else -> "Documents"
    }

    fun documentsChoiceHint(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "PDF, Word, Excel, EPUB — आप अगले कदम में चुनेंगे"
        else -> "PDF, Word, Excel, EPUB — you pick them next"
    }

    fun photosCount(lang: AppLanguage, count: Int) = when (lang) {
        AppLanguage.HINDI -> "$count फ़ोटो"
        else -> "$count photos"
    }

    fun estimateTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "अनुमानित समय"
        else -> "Estimated time"
    }

    fun estimateLine(lang: AppLanguage, photos: Int, minutes: Int) = when (lang) {
        AppLanguage.HINDI -> "$photos फ़ोटो · लगभग $minutes मिनट"
        else -> "$photos photos · about $minutes min"
    }

    fun estimateNote(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "बैकग्राउंड में चलता है — इस बीच आप दूसरे ऐप इस्तेमाल कर सकते हैं।"
        else -> "Runs in the background — you can use other apps meanwhile."
    }

    fun startReading(lang: AppLanguage, photos: Int) = when (lang) {
        AppLanguage.HINDI -> "पढ़ना शुरू करें ($photos फ़ोटो)"
        else -> "Start reading ($photos photos)"
    }

    fun getStarted(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "शुरू करें"
        else -> "Get started"
    }

    fun skipForNow(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "अभी छोड़ें"
        else -> "Skip for now"
    }

    fun back(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "वापस"
        else -> "Back"
    }

    fun close(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "बंद करें"
        else -> "Close"
    }

    fun clearSearch(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "खोज साफ़ करें"
        else -> "Clear search"
    }

    fun deleteSearch(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "यह खोज हटाएँ"
        else -> "Delete this search"
    }

    fun takeBackReading(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "यह पढ़ाई हटाएँ"
        else -> "Take back this reading"
    }

    fun photoContentDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "फ़ोटो"
        else -> "Photo"
    }

    fun documentContentDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "दस्तावेज़"
        else -> "Document"
    }

    fun pageOf(lang: AppLanguage, page: Int, total: Int) = when (lang) {
        AppLanguage.HINDI -> "पेज $page / $total"
        else -> "Page $page of $total"
    }

    fun matchOnThisPage(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "इस पेज पर शब्द मिले"
        else -> "Words found on this page"
    }

    fun foundOnPageNote(lang: AppLanguage, query: String, page: Int) = when (lang) {
        AppLanguage.HINDI -> "\"$query\" पेज $page पर मिला"
        else -> "\"$query\" found on page $page"
    }

    fun passwordHint(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "बड़े अक्षर मायने रखते हैं। e-Aadhaar के लिए यह आपके नाम के पहले 4 अक्षर बड़े अक्षरों में हैं, उसके बाद आपका जन्म वर्ष।"
        else -> "Capitals matter. For e-Aadhaar it is the first 4 letters of your name in capitals, then your birth year."
    }

    fun show(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "दिखाएँ"
        else -> "Show"
    }

    fun hide(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "छिपाएँ"
        else -> "Hide"
    }

    fun open(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "खोलें"
        else -> "Open"
    }

    fun pictureCannotOpen(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "यह तस्वीर नहीं खुल रही। हो सकता है इसे हटा दिया गया हो, कहीं और रखा गया हो, या ऐप के पास इसे देखने की अनुमति न रही हो।"
        else -> "This picture can't be opened. It may have been deleted or moved, or the app no longer has access to it."
    }

    fun showText(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "टेक्स्ट दिखाएँ"
        else -> "Show text"
    }

    fun hideText(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "टेक्स्ट छिपाएँ"
        else -> "Hide text"
    }

    fun readFromPicture(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "इस तस्वीर से पढ़ा गया टेक्स्ट"
        else -> "Text read from this picture"
    }

    fun pdfViewerTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "PDF"
        else -> "PDF"
    }

    fun viewerLockedNote(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "यह PDF पासवर्ड से बंद है। खोलने के लिए पासवर्ड डालें। पासवर्ड नहीं रखा जाता।"
        else -> "This PDF is locked. Type its password to open it. The password is not kept."
    }

    fun viewerWrongPassword(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "यह पासवर्ड इसे खोल नहीं सका।"
        else -> "That password did not open it."
    }

    fun findTheFile(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "फ़ाइल ढूँढें"
        else -> "Find the file"
    }

    fun checkingFile(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "फ़ाइल की जाँच हो रही है…"
        else -> "Checking the file…"
    }

    fun viewerFileGone(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "यह फ़ाइल अब उस जगह नहीं है जहाँ वह जोड़ते समय थी: वह हटाई गई, बदली गई या कहीं और चली गई। उसका टेक्स्ट अब भी खोजा जा सकता है। ऐप को बताएँ कि फ़ाइल अब कहाँ है, और वह फिर खुलेगी।"
        else -> "This file is no longer where it was when you added it: it was moved, renamed or deleted. Its text can still be searched. Show the app where the file is now and it opens again."
    }

    fun viewerFileGoneAddAgain(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "यह फ़ाइल अब उस जगह नहीं है जहाँ वह जोड़ते समय थी: वह हटाई गई, बदली गई या कहीं और चली गई। इसे दस्तावेज़ जोड़ें से दोबारा जोड़ें।"
        else -> "This file is no longer where it was when you added it: it was moved, renamed or deleted. Add it again from Add documents."
    }

    fun viewerCannotRead(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "यह फ़ाइल पढ़ी नहीं जा सकी। वही फ़ाइल चुनें जो आपने जोड़ी थी।"
        else -> "That file could not be read. Choose the file you added."
    }

    fun viewerAnotherFile(lang: AppLanguage, name: String) = when (lang) {
        AppLanguage.HINDI -> "यह एक अलग फ़ाइल है: उसका भीतरी भाग वही नहीं है जो" +
            (if (name.isBlank()) "।" else " \"$name\" के लिए रखा गया है।") + " वही फ़ाइल चुनें जो आपने जोड़ी थी।"
        else -> "That is a different file: its contents are not the ones stored" +
            (if (name.isBlank()) "." else " for \"$name\".") + " Choose the file you added."
    }

    fun viewerDamaged(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "यह फ़ाइल टूटी-फूटी है या PDF नहीं है, इसलिए नहीं दिखाई जा सकती। उसका टेक्स्ट अब भी खोजा जा सकता है।"
        else -> "This file is damaged or is not a PDF, so it cannot be shown. Its text can still be searched."
    }

    fun viewerTooBig(lang: AppLanguage) = when (lang) {
        AppLanguage.HINDI -> "इस फ़ोन की खाली मेमोरी के लिए यह फ़ाइल बहुत बड़ी है, इसलिए वह नहीं दिखाई जा सकती। उसका टेक्स्ट अब भी खोजा जा सकता है।"
        else -> "This file is too big for the memory this phone has free, so it cannot be shown. Its text can still be searched."
    }

    fun viewerCouldNotOpen(lang: AppLanguage, detail: String) = when (lang) {
        AppLanguage.HINDI -> "फ़ाइल नहीं खुली: $detail"
        else -> "The file could not be opened: $detail"
    }
}
