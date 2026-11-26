package com.dessalines.thumbkey.autocorrect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PackedTrieAndCodecTest {
    @Test
    fun frequencyCodecPreservesSmallValuesAndMonotonicOrder() {
        for (v in 0..2047) {
            val code = FrequencyCodec.encode(v)
            assertEquals(v, FrequencyCodec.decode(code))
        }
        assertEquals(204288, FrequencyCodec.round(204351))
        assertEquals(16816, FrequencyCodec.round(16821))

        var prev = 0
        for (v in listOf(10, 500, 2048, 4096, 10000, 50000, 500000, 5_000_000)) {
            val c = FrequencyCodec.encode(v)
            assertTrue("Expected monotone code for $v", c > prev)
            prev = c
        }
        assertEquals(Int.MAX_VALUE, FrequencyCodec.decode(0xFFFF))
    }

    @Test
    fun packedTrieCodecAndMappedTrieRoundTrip() {
        val entries =
            listOf(
                "the" to 9771,
                "there" to 5100,
                "their" to 4800,
                "definitely" to 3200,
                "café" to 400,
                "a" to 9331,
            )
        val trie = PackedTrie.of(entries)
        assertEquals(entries.size, trie.wordCount())
        assertEquals(FrequencyCodec.round(9771), FrequencyCodec.decode(FrequencyCodec.encode(9771)))
        assertEquals(FrequencyCodec.round(9771), trie.frequencyOf("the"))
        assertEquals(FrequencyCodec.round(400), trie.frequencyOf("café"))
        assertFalse(trie.contains("missing"))

        val bytes = PackedTrieCodec.toBytes(trie)
        val fromMem = MappedTrie.fromBytes(bytes)
        assertEquals(trie.wordCount(), fromMem.wordCount())
        assertEquals(trie.frequencyOf("the"), fromMem.frequencyOf("the"))
        assertEquals(trie.frequencyOf("definitely"), fromMem.frequencyOf("definitely"))
        assertTrue(fromMem.contains("café"))
        assertFalse(fromMem.contains("ther"))

        val tmpDir = Files.createTempDirectory("trie_test").toFile()
        try {
            val file = File(tmpDir, "test.wmdict")
            PackedTrieCodec.write(trie, file)
            assertTrue(PackedTrieCodec.isReadable(file))
            val header = PackedTrieCodec.readHeader(file)
            assertEquals(entries.size, header.wordCount)

            MappedTrie.open(file).use { mapped ->
                assertEquals(trie.wordCount(), mapped.wordCount())
                assertEquals(trie.frequencyOf("there"), mapped.frequencyOf("there"))
                val completions = mapped.complete("the", limit = 5)
                assertTrue(completions.any { it.word == "the" })
                assertTrue(completions.any { it.word == "there" })
                assertTrue(completions.any { it.word == "their" })
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun bundledEnglishDictionaryLoadsAndContainsCoreAndExpandedWords() {
        val assetFile = locateAssetFile("dictionaries/en.wmdict")
        assertTrue("Expected bundled en.wmdict to exist at ${assetFile.path}", assetFile.isFile)
        assertTrue(PackedTrieCodec.isReadable(assetFile))

        val header = PackedTrieCodec.readHeader(assetFile)
        assertTrue("Expected >= 70,000 words, got ${header.wordCount}", header.wordCount >= 70_000)

        MappedTrie.open(assetFile).use { mapped ->
            assertTrue(mapped.contains("the"))
            assertTrue(mapped.contains("definitely"))
            assertTrue(mapped.contains("median"))
            assertFalse(mapped.contains("definately"))
            assertTrue(mapped.frequencyOf("the") > mapped.frequencyOf("definitely"))
            val anchor = mapped.countAtRank(RankScale.ANCHOR_RANK)
            assertTrue("Expected 2000th-rank frequency > 2000, got $anchor", anchor > 2000)
        }
    }

    companion object {
        fun locateAssetFile(relative: String): File {
            val candidates =
                listOf(
                    File("src/main/assets/$relative"),
                    File("app/src/main/assets/$relative"),
                )
            return candidates.firstOrNull { it.isFile } ?: candidates.first()
        }
    }
}
