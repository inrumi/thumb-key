package com.dessalines.thumbkey.autocorrect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp

class WmlmModelTest {
    @Test
    fun syntheticModelOpensScoresPiecesAndReusesKvCache() {
        val modelBytes = buildTinyModel(extraVocab = listOf("\u2581qu", "##ick", "\u2581a", "##a"))
        val model = WmlmModel.open(modelBytes)
        assertNotNull(model)
        model!!

        assertEquals(22, model.vocabSize)
        assertEquals(16, model.idOf("hello"))
        assertEquals(17, model.idOf("world"))
        assertTrue(model.isPiece(18))
        assertTrue(model.isPiece(19))
        assertFalse(model.isPiece(16))

        assertEquals(listOf(18, 19), model.piecesOf("quick")?.toList())
        assertEquals(listOf(16), model.tokensOf("hello").toList())
        assertEquals(listOf(WmlmModel.UNK), model.tokensOf("zebra").toList())

        val session = WmlmSession(model)
        session.setContext(listOf("hello"))
        val dist1 = session.distribution().copyOf()
        val probSum = dist1.filter { it.isFinite() }.sumOf { exp(it.toDouble()) }
        assertTrue("Probability sum should be ~1.0, was $probSum", abs(probSum - 1.0) < 1e-3)

        val tokensShort = session.currentTokensCopy()
        session.setContext(listOf("hello", "world"))
        assertEquals(tokensShort.size + 1, session.currentTokensCopy().size)

        // Going back to shorter context reuses prefix cache cleanly
        session.setContext(listOf("hello"))
        assertEquals(tokensShort.toList(), session.currentTokensCopy().toList())
        val distAgain = session.distribution()
        for (i in dist1.indices) {
            if (dist1[i].isFinite() || distAgain[i].isFinite()) {
                assertEquals(dist1[i], distAgain[i], 1e-5f)
            }
        }

        val top = session.topWords(5, Float.NEGATIVE_INFINITY)
        // Pieces are excluded from top next-word offers; only "hello" and "world" remain
        assertEquals(2, top.size)
        assertTrue(top.all { it.first == "hello" || it.first == "world" })
    }

    @Test
    fun malformedWmlmBytesAreRejected() {
        assertNull(WmlmModel.open(byteArrayOf()))
        assertNull(WmlmModel.open("WMLX\u0001\u0000\u0000\u0000".toByteArray()))
    }

    @Test
    fun bundledEnglishWmlmOpensAndScoresContextAccurately() {
        val file = PackedTrieAndCodecTest.locateAssetFile("dictionaries/en_context.wmlm")
        val bytes = file.readBytes()
        val model = WmlmModel.open(bytes)
        assertNotNull("Bundled en_context.wmlm must open cleanly", model)
        model!!

        assertEquals(24576, model.vocabSize)
        assertTrue("en" in model.langs)

        val session = WmlmSession(model)
        val startNs = System.nanoTime()
        session.setContext(listOf("i", "want", "to"))
        val snap = session.buildSnapshot(listOf("i", "want", "to"), "en")
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        assertTrue("First context evaluation took ${elapsedMs}ms (> 1500ms)", elapsedMs < 1500.0)

        assertTrue(snap.matchesContext(listOf("i", "want", "to")))
        assertFalse(snap.matchesContext(listOf("i", "want")))

        val lpGo = snap.logProbListed("go")
        val lpBe = snap.logProbListed("be")
        val lpBanana = snap.logProbListed("banana")
        assertTrue("go ($lpGo) should outrank banana ($lpBanana)", lpGo > lpBanana)
        assertTrue("be ($lpBe) should outrank banana ($lpBanana)", lpBe > lpBanana)
        assertTrue(snap.topWords.isNotEmpty())
        assertTrue(snap.topWords.none { it.first.startsWith("<") || it.first.startsWith("\u2581") || it.first.startsWith("##") })

        // Verify next-word prediction after "i want" strongly favors "to"
        session.setContext(listOf("i", "want"))
        val afterWant = session.buildSnapshot(listOf("i", "want"), "en")
        assertEquals("to", afterWant.topWords.firstOrNull()?.first)

        // Verify logProb handles subword pieces while preserving session tokens
        val beforePieceTokens = session.currentTokensCopy().toList()
        val oovScore = session.logProb("tryng")
        assertEquals(beforePieceTokens, session.currentTokensCopy().toList())
        assertTrue("Subword-spelled token should score finite or handle gracefully", oovScore.isFinite() || model.piecesOf("tryng") == null)

        // Verify retroactive revise(): "I want to buy a car" beats "by" decisively
        val realWordScores =
            session.revise(
                context = listOf("i", "want", "to"),
                candidates = listOf("by", "buy"),
                followers = listOf("a", "car"),
            )
        assertTrue(
            "by (${realWordScores[0]}) and buy (${realWordScores[1]}) must be finite",
            realWordScores[0].isFinite() && realWordScores[1].isFinite(),
        )
        assertTrue(
            "Expected buy (${realWordScores[1]}) > by (${realWordScores[0]}) + 3.0",
            realWordScores[1] - realWordScores[0] > 3.0f,
        )

        // Verify retroactive revise(): "I'm trying to learn" beats both "tying" and "tryng"
        val typoScores =
            session.revise(
                context = listOf("i'm"),
                candidates = listOf("tryng", "trying", "tying"),
                followers = listOf("to", "learn"),
            )
        val scoreTryng = typoScores[0]
        val scoreTrying = typoScores[1]
        val scoreTying = typoScores[2]
        assertTrue("trying ($scoreTrying) must beat tying ($scoreTying)", scoreTrying > scoreTying)
        if (scoreTryng.isFinite()) {
            assertTrue("trying ($scoreTrying) must beat tryng ($scoreTryng)", scoreTrying > scoreTryng)
        }
    }

    companion object {
        private fun buildTinyModel(extraVocab: List<String> = emptyList()): ByteArray {
            val d = 4
            val ff = 8
            val ctx = 8
            val specialsAndBase =
                listOf(
                    "<pad>",
                    "<s>",
                    "</s>",
                    "<unk>",
                    "<num>",
                    "<reg:neutral>",
                    "<reg:casual>",
                    "<reg:formal>",
                    "<field:other>",
                    "<field:chat>",
                    "<field:email>",
                    "<field:search>",
                    "<lang:0>",
                    "<lang:1>",
                    "<lang:2>",
                    "<lang:3>",
                    "hello",
                    "world",
                )
            val words = specialsAndBase + extraVocab
            val vocab = words.size

            val tensorData = ByteArrayOutputStream()
            val tensorJsonList = ArrayList<String>()

            fun addTensor(
                name: String,
                shape: IntArray,
                dtype: String,
            ) {
                val n = shape.fold(1) { a, b -> a * b }
                val offset = tensorData.size()
                val raw = ByteArrayOutputStream()
                if (dtype == "i8") {
                    val rows = shape[0]
                    for (i in 0 until n) {
                        raw.write(((i * 7 % 13) - 6) and 0xFF)
                    }
                    val scaleBuf = ByteBuffer.allocate(rows * 4).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(rows) { scaleBuf.putFloat(0.05f) }
                    raw.write(scaleBuf.array())
                } else {
                    val fBuf = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) {
                        val v = if (name.endsWith(".w")) 1.0f else (i % 3) * 0.01f
                        fBuf.putFloat(v)
                    }
                    raw.write(fBuf.array())
                }
                val bytesArr = raw.toByteArray()
                tensorData.write(bytesArr)
                while (tensorData.size() % 4 != 0) tensorData.write(0)
                val shapeStr = shape.joinToString(",")
                tensorJsonList.add(
                    "{\"name\":\"$name\",\"shape\":[$shapeStr],\"dtype\":\"$dtype\",\"offset\":$offset,\"bytes\":${bytesArr.size}}",
                )
            }

            addTensor("emb", intArrayOf(vocab, d), "i8")
            addTensor("pos", intArrayOf(ctx, d), "f32")
            addTensor("out_bias", intArrayOf(vocab), "f32")
            addTensor("l0.ln1.w", intArrayOf(d), "f32")
            addTensor("l0.ln1.b", intArrayOf(d), "f32")
            addTensor("l0.qkv.w", intArrayOf(3 * d, d), "i8")
            addTensor("l0.qkv.b", intArrayOf(3 * d), "f32")
            addTensor("l0.o.w", intArrayOf(d, d), "i8")
            addTensor("l0.o.b", intArrayOf(d), "f32")
            addTensor("l0.ln2.w", intArrayOf(d), "f32")
            addTensor("l0.ln2.b", intArrayOf(d), "f32")
            addTensor("l0.w1.w", intArrayOf(ff, d), "i8")
            addTensor("l0.w1.b", intArrayOf(ff), "f32")
            addTensor("l0.w2.w", intArrayOf(d, ff), "i8")
            addTensor("l0.w2.b", intArrayOf(d), "f32")
            addTensor("ln.w", intArrayOf(d), "f32")
            addTensor("ln.b", intArrayOf(d), "f32")

            val headerStr =
                "{\"d\":$d,\"layers\":1,\"heads\":2,\"ff\":$ff,\"ctx\":$ctx,\"vocab\":$vocab," +
                    "\"name\":\"tiny\",\"langs\":[\"en\"],\"tensors\":[${tensorJsonList.joinToString(",")}]}"
            val hdrRaw = headerStr.toByteArray(Charsets.UTF_8)
            val hdrPaddedLen = (hdrRaw.size + 3) and 3.inv()
            val hdrPadded = hdrRaw.copyOf(hdrPaddedLen)

            val out = ByteArrayOutputStream()
            out.write("WMLM".toByteArray(Charsets.US_ASCII))
            val metaBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            metaBuf.putInt(1)
            metaBuf.putInt(hdrPadded.size)
            out.write(metaBuf.array())
            out.write(hdrPadded)

            val countBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            countBuf.putInt(words.size)
            out.write(countBuf.array())
            for (w in words) {
                val wb = w.toByteArray(Charsets.UTF_8)
                val lb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                lb.putShort(wb.size.toShort())
                out.write(lb.array())
                out.write(wb)
            }
            while (out.size() % 4 != 0) out.write(0)
            out.write(tensorData.toByteArray())
            return out.toByteArray()
        }
    }
}
