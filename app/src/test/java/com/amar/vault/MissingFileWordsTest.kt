package com.amar.vault

import com.amar.vault.ui.AppLanguage
import com.amar.vault.ui.VaultStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app says about a document whose file is gone, and after the user looked for it. */
class MissingFileWordsTest {

    @Test
    fun theCardCountsTheDocuments() {
        assertEquals("1 document cannot be opened", MissingWords.headline(1))
        assertEquals("4 documents cannot be opened", MissingWords.headline(4))
        assertTrue("it says the text is not lost", "can still be searched" in MissingWords.EXPLANATION)
    }

    @Test
    fun lookingSaysWhatWasFound() {
        assertEquals("Found it. It opens again.", MissingWords.afterLooking(found = 1, lookedFor = 1, picked = 1))
        assertEquals("Found all 3. They open again.", MissingWords.afterLooking(found = 3, lookedFor = 3, picked = 5))
        assertEquals("Found 2 of 4.", MissingWords.afterLooking(found = 2, lookedFor = 4, picked = 2))
        assertEquals(
            "That is not one of the missing files: its contents are different.",
            MissingWords.afterLooking(found = 0, lookedFor = 4, picked = 1),
        )
        assertEquals(
            "None of those is one of the missing files: their contents are different.",
            MissingWords.afterLooking(found = 0, lookedFor = 4, picked = 3),
        )
    }

    @Test
    fun theViewerSaysTheFileIsGoneAndThatItsTextIsNot() {
        val english = AppLanguage.ENGLISH
        val gone = VaultStrings.viewerFileGone(english)
        assertTrue("moved, renamed or deleted" in gone)
        assertTrue("can still be searched" in gone)
        assertEquals(
            "That is a different file: its contents are not the ones stored for \"Act.pdf\". Choose the file you added.",
            VaultStrings.viewerAnotherFile(english, "Act.pdf"),
        )
        assertTrue("Add it again" in VaultStrings.viewerFileGoneAddAgain(english) && "Add it again" !in gone)
        assertTrue(VaultStrings.viewerFileGone(AppLanguage.HINDI).any { it in 'ऀ'..'ॿ' })
    }
}
