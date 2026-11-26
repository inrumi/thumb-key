package com.dessalines.thumbkey.autocorrect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSuggesterTest {
    private val suggester: EmojiSuggester by lazy {
        val triggers = PackedTrieAndCodecTest.locateAssetFile("emoji/triggers.tsv")
        val shortcodes = PackedTrieAndCodecTest.locateAssetFile("emoji/shortcodes.tsv")
        triggers.inputStream().use { t ->
            shortcodes.inputStream().use { s ->
                EmojiSuggester.load(t, s)
            }
        }
    }

    @Test
    fun englishTriggersShortcodesPhrasesAndPluralsMatchCleanly() {
        assertTrue("☕" in suggester.suggest("coffee"))
        assertEquals("🎂", suggester.suggest("birthday").firstOrNull())
        assertEquals("⏰", suggester.suggest("clock", previousWord = "alarm").firstOrNull())
        assertTrue(suggester.suggest("cats").any { it == "🐱" || it == "🐈" || it == "😺" })
        assertTrue(suggester.suggest("a").isEmpty())
    }

    @Test
    fun spanishTriggersMatchAccentedAndUnaccentedForms() {
        assertTrue("☕" in suggester.suggest("café"))
        assertTrue("☕" in suggester.suggest("cafe"))
        assertTrue("🔥" in suggester.suggest("fuego"))
        assertTrue("🙏" in suggester.suggest("gracias"))
        assertTrue("🌅" in suggester.suggest("mañana"))
        assertTrue("🌅" in suggester.suggest("manana"))
        assertTrue("⚽" in suggester.suggest("fútbol"))
        assertTrue("⚽" in suggester.suggest("futbol"))
    }
}
