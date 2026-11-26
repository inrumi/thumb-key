package com.dessalines.thumbkey.autocorrect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LayoutLanguageResolverTest {
    @Test
    fun resolvesSingleMultiNonLatinAndExcludedLayouts() {
        assertEquals(listOf("en"), LayoutLanguageResolver.languagesForName("ENThumbKey"))
        assertEquals(listOf("fr"), LayoutLanguageResolver.languagesForName("FRThumbKeyV2"))
        assertEquals(listOf("fr", "en"), LayoutLanguageResolver.languagesForName("FRENFrappeFluideV1"))
        assertEquals(
            listOf("en", "fr", "de", "es", "it", "pt", "nl"),
            LayoutLanguageResolver.languagesForName("EuropeThumbKey"),
        )
        assertEquals(emptyList<String>(), LayoutLanguageResolver.languagesForName("JAHiraganaThumbKey"))
        assertEquals(emptyList<String>(), LayoutLanguageResolver.languagesForName("THThumbKey"))
        assertEquals(emptyList<String>(), LayoutLanguageResolver.languagesForName("MATHThumbKey"))
        assertEquals(emptyList<String>(), LayoutLanguageResolver.languagesForName("ENSymbolsTwoHands"))

        assertEquals(
            listOf("en", "es", "fr"),
            LayoutLanguageResolver.resolveLanguagesForName("ENThumbKey", "es, fr, es"),
        )
        assertEquals(
            emptyList<String>(),
            LayoutLanguageResolver.resolveLanguagesForName("ENSymbolsTwoHands", "es"),
        )
    }

    @Test
    fun allDeclaredKeyboardLayoutNamesResolveWithoutThrowing() {
        val candidates =
            listOf(
                File("src/main/java/com/dessalines/thumbkey/utils/KeyboardLayout.kt"),
                File("app/src/main/java/com/dessalines/thumbkey/utils/KeyboardLayout.kt"),
            )
        val layoutFile = candidates.firstOrNull { it.isFile } ?: candidates.first()
        assertTrue("Expected KeyboardLayout.kt at ${layoutFile.path}", layoutFile.isFile)

        val entryRegex = Regex("^\\s+([A-Za-z0-9_]+)\\(")
        val names =
            layoutFile
                .readLines()
                .mapNotNull { line -> entryRegex.find(line)?.groupValues?.get(1) }
        assertTrue("Expected > 50 layout enum entries", names.size > 50)
        for (name in names) {
            assertNotNull(LayoutLanguageResolver.languagesForName(name))
        }
    }

    @Test
    fun singleActiveLanguageCycleAndSpacebarLabelWorkCleanly() {
        assertEquals(
            listOf("en", "es"),
            LayoutLanguageResolver.enabledLanguageCycleForName("ENMessagEase", "es"),
        )
        assertEquals(
            "en",
            LayoutLanguageResolver.resolveActiveLanguageForName("ENMessagEase", "es", ""),
        )
        assertEquals(
            "es",
            LayoutLanguageResolver.resolveActiveLanguageForName("ENMessagEase", "es", "ES"),
        )
        // Switching to a stale language not in the cycle falls back cleanly to the layout default
        assertEquals(
            "en",
            LayoutLanguageResolver.resolveActiveLanguageForName("ENMessagEase", "es", "fr"),
        )
        assertNull(
            LayoutLanguageResolver.resolveActiveLanguageForName("ENSymbolsTwoHands", "es", "es"),
        )

        val cycle = listOf("en", "es", "fr")
        assertEquals("es", LayoutLanguageResolver.nextActiveLanguage(cycle, "en"))
        assertEquals("fr", LayoutLanguageResolver.nextActiveLanguage(cycle, "es"))
        assertEquals("en", LayoutLanguageResolver.nextActiveLanguage(cycle, "fr"))
        assertEquals("en", LayoutLanguageResolver.nextActiveLanguage(listOf("en"), "en"))
        assertNull(LayoutLanguageResolver.nextActiveLanguage(emptyList(), "en"))

        assertEquals("English", LayoutLanguageResolver.displayNameForSpacebar("en"))
        assertEquals("Español", LayoutLanguageResolver.displayNameForSpacebar("es"))
    }
}
