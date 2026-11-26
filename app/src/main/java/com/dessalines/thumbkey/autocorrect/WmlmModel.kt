package com.dessalines.thumbkey.autocorrect

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Pure-Kotlin reader and int8 forward-pass runner for `.wmlm` v1 context language models
 * (`wmkeyboard`'s `lm.rs` format).
 */
class WmlmModel private constructor(
    private val bytes: ByteArray,
    val d: Int,
    val layers: Int,
    val heads: Int,
    val ff: Int,
    val ctx: Int,
    val name: String,
    val langs: List<String>,
    private val vocab: Array<String>,
    private val ids: Map<String, Int>,
    private val startPieces: Map<String, Int>,
    private val contPieces: Map<String, Int>,
    private val isPiece: BooleanArray,
    internal val emb: I8Matrix,
    internal val pos: FloatArray,
    internal val outBias: FloatArray,
    internal val blocks: Array<Layer>,
    internal val lnW: FloatArray,
    internal val lnB: FloatArray,
) {
    internal class I8Matrix(
        val dataOffset: Int,
        val rows: Int,
        val cols: Int,
        val scales: FloatArray,
    )

    internal class Layer(
        val ln1W: FloatArray,
        val ln1B: FloatArray,
        val qkv: I8Matrix,
        val qkvB: FloatArray,
        val o: I8Matrix,
        val oB: FloatArray,
        val ln2W: FloatArray,
        val ln2B: FloatArray,
        val w1: I8Matrix,
        val w1B: FloatArray,
        val w2: I8Matrix,
        val w2B: FloatArray,
    )

    val vocabSize: Int get() = vocab.size

    fun word(id: Int): String? = vocab.getOrNull(id)

    fun idOf(key: String): Int? = ids[key]

    fun isPiece(id: Int): Boolean = id in isPiece.indices && isPiece[id]

    fun maxWords(): Int = (ctx - 5).coerceAtLeast(0)

    fun slotOf(langId: String): Int {
        val norm = DictionaryManager.normalizeLang(langId)
        val idx = langs.indexOfFirst { DictionaryManager.normalizeLang(it) == norm }
        return if (idx >= 0) idx.coerceIn(0, 3) else 0
    }

    fun supportsLanguage(langId: String): Boolean {
        if (langs.isEmpty()) return true
        val norm = DictionaryManager.normalizeLang(langId)
        return langs.any { DictionaryManager.normalizeLang(it) == norm }
    }

    fun tokensOf(word: String): IntArray {
        val key = tokenKey(word) ?: return intArrayOf(UNK)
        ids[key]?.let { return intArrayOf(it) }
        return piecesOf(key) ?: intArrayOf(UNK)
    }

    fun piecesOf(key: String): IntArray? {
        if (startPieces.isEmpty() || key == "<num>") return null
        val len = key.length
        val out = IntArray(len)
        var outLen = 0
        var i = 0
        while (i < len) {
            val table = if (i == 0) startPieces else contPieces
            var taken = 0
            var matchedId = -1
            val maxJ = minOf(len, i + MAX_PIECE)
            for (j in maxJ downTo i + 1) {
                val piece = key.substring(i, j)
                val id = table[piece]
                if (id != null) {
                    taken = j
                    matchedId = id
                    break
                }
            }
            if (taken == 0) return null
            out[outLen++] = matchedId
            i = taken
        }
        return out.copyOf(outLen)
    }

    internal fun embed(
        id: Int,
        out: FloatArray,
    ) {
        val r = id.coerceIn(0, emb.rows - 1)
        val scale = emb.scales[r]
        val base = emb.dataOffset + r * d
        for (i in 0 until d) {
            out[i] = bytes[base + i].toFloat() * scale
        }
    }

    internal fun matvec(
        m: I8Matrix,
        bias: FloatArray,
        x: FloatArray,
        xq: ByteArray,
        out: FloatArray,
    ) {
        val cols = m.cols
        val sx = quantize(x, cols, xq)
        val weights = bytes
        val dataBase = m.dataOffset
        val scales = m.scales
        val unrolledLimit = cols and 3.inv()
        for (r in 0 until m.rows) {
            var acc = 0
            val rowOff = dataBase + r * cols
            var c = 0
            while (c < unrolledLimit) {
                acc +=
                    weights[rowOff + c] * xq[c] +
                    weights[rowOff + c + 1] * xq[c + 1] +
                    weights[rowOff + c + 2] * xq[c + 2] +
                    weights[rowOff + c + 3] * xq[c + 3]
                c += 4
            }
            while (c < cols) {
                acc += weights[rowOff + c] * xq[c]
                c++
            }
            val bVal = if (r < bias.size) bias[r] else 0f
            out[r] = acc.toFloat() * sx * scales[r] + bVal
        }
    }

    companion object {
        private val MAGIC = byteArrayOf('W'.code.toByte(), 'M'.code.toByte(), 'L'.code.toByte(), 'M'.code.toByte())
        const val VERSION = 1

        const val PAD = 0
        const val BOS = 1
        const val EOS = 2
        const val UNK = 3
        const val NUM = 4
        const val REG_NEUTRAL = 5
        const val REG_CASUAL = 6
        const val REG_FORMAL = 7
        const val FIELD_OTHER = 8
        const val FIELD_CHAT = 9
        const val FIELD_EMAIL = 10
        const val FIELD_SEARCH = 11
        const val LANG_0 = 12
        const val SPECIALS = 16

        const val MAX_PIECE = 5
        const val MAX_WORD_PIECES = 6
        const val NEXT_WORD_FLOOR = -8f

        fun open(bytes: ByteArray): WmlmModel? {
            if (bytes.size < 16) return null
            if (bytes[0] != MAGIC[0] ||
                bytes[1] != MAGIC[1] ||
                bytes[2] != MAGIC[2] ||
                bytes[3] != MAGIC[3]
            ) {
                return null
            }
            val version = u32Le(bytes, 4)
            if (version != VERSION) return null
            val headerLen = u32Le(bytes, 8)
            if (headerLen <= 0 || 12L + headerLen > bytes.size) return null

            var headerEnd = 12 + headerLen
            while (headerEnd > 12 && bytes[headerEnd - 1] == 0.toByte()) {
                headerEnd--
            }
            val headerJson = String(bytes, 12, headerEnd - 12, Charsets.UTF_8)
            val header = SimpleJson.parseObject(headerJson) ?: return null

            val d = header["d"].asInt(-1)
            val layers = header["layers"].asInt(-1)
            val heads = header["heads"].asInt(-1)
            val ff = header["ff"].asInt(-1)
            val ctx = header["ctx"].asInt(-1)
            val vocabLen = header["vocab"].asInt(-1)
            if (d <= 0 || heads <= 0 || d % heads != 0 || layers <= 0 || ctx <= 0 || vocabLen < SPECIALS) {
                return null
            }

            val name = header["name"] as? String ?: ""
            val langs = header["langs"].asStringList()

            var at = 12 + headerLen
            if (at + 4 > bytes.size) return null
            val count = u32Le(bytes, at)
            if (count != vocabLen) return null
            at += 4

            val vocab = Array(count) { "" }
            val ids = HashMap<String, Int>(count * 2)
            val startPieces = HashMap<String, Int>()
            val contPieces = HashMap<String, Int>()
            val isPiece = BooleanArray(count)

            for (i in 0 until count) {
                if (at + 2 > bytes.size) return null
                val len = u16Le(bytes, at)
                at += 2
                if (at + len > bytes.size) return null
                val word = String(bytes, at, len, Charsets.UTF_8)
                at += len
                vocab[i] = word
                when {
                    word.startsWith("\u2581") -> {
                        startPieces[word.substring(1)] = i
                        isPiece[i] = true
                    }

                    word.startsWith("##") -> {
                        contPieces[word.substring(2)] = i
                        isPiece[i] = true
                    }

                    else -> {
                        ids.putIfAbsent(word, i)
                    }
                }
            }
            val dataStart = (at + 3) and 3.inv()

            val rawTensors = header["tensors"] as? List<*> ?: return null
            val byName = HashMap<String, TensorMeta>(rawTensors.size * 2)
            for (item in rawTensors) {
                val map = item.asStringAnyMap()
                val tName = map["name"] as? String ?: return null
                val dtype = map["dtype"] as? String ?: return null
                val offset = map["offset"].asInt(-1)
                val len = map["bytes"].asInt(-1)
                val shapeList = (map["shape"] as? List<*>)?.map { it.asInt(-1) } ?: return null
                if (offset < 0 || len < 0) return null
                val absOffset = dataStart.toLong() + offset.toLong()
                if (absOffset + len > bytes.size) return null
                byName[tName] =
                    TensorMeta(
                        dtype = dtype,
                        shape = shapeList,
                        offset = absOffset.toInt(),
                        byteLen = len,
                    )
            }

            fun f32Tensor(
                tName: String,
                n: Int,
            ): FloatArray? {
                val meta = byName[tName] ?: return null
                if (meta.dtype != "f32" || meta.byteLen != n * 4) return null
                return readF32Array(bytes, meta.offset, n)
            }

            fun i8Matrix(
                tName: String,
                rows: Int,
                cols: Int,
            ): I8Matrix? {
                val meta = byName[tName] ?: return null
                if (meta.dtype != "i8" || meta.shape.size != 2) return null
                if (meta.shape[0] != rows || meta.shape[1] != cols) return null
                val expectedBytes = rows * cols + rows * 4
                if (meta.byteLen != expectedBytes || meta.offset + meta.byteLen > bytes.size) return null
                val scales = readF32Array(bytes, meta.offset + rows * cols, rows)
                return I8Matrix(meta.offset, rows, cols, scales)
            }

            val emb = i8Matrix("emb", vocabLen, d) ?: return null
            val pos = f32Tensor("pos", ctx * d) ?: return null
            val outBias = f32Tensor("out_bias", vocabLen) ?: return null
            val blocks =
                Array(layers) { i ->
                    Layer(
                        ln1W = f32Tensor("l$i.ln1.w", d) ?: return null,
                        ln1B = f32Tensor("l$i.ln1.b", d) ?: return null,
                        qkv = i8Matrix("l$i.qkv.w", 3 * d, d) ?: return null,
                        qkvB = f32Tensor("l$i.qkv.b", 3 * d) ?: return null,
                        o = i8Matrix("l$i.o.w", d, d) ?: return null,
                        oB = f32Tensor("l$i.o.b", d) ?: return null,
                        ln2W = f32Tensor("l$i.ln2.w", d) ?: return null,
                        ln2B = f32Tensor("l$i.ln2.b", d) ?: return null,
                        w1 = i8Matrix("l$i.w1.w", ff, d) ?: return null,
                        w1B = f32Tensor("l$i.w1.b", ff) ?: return null,
                        w2 = i8Matrix("l$i.w2.w", d, ff) ?: return null,
                        w2B = f32Tensor("l$i.w2.b", d) ?: return null,
                    )
                }
            val lnW = f32Tensor("ln.w", d) ?: return null
            val lnB = f32Tensor("ln.b", d) ?: return null

            return WmlmModel(
                bytes = bytes,
                d = d,
                layers = layers,
                heads = heads,
                ff = ff,
                ctx = ctx,
                name = name,
                langs = langs,
                vocab = vocab,
                ids = ids,
                startPieces = startPieces,
                contPieces = contPieces,
                isPiece = isPiece,
                emb = emb,
                pos = pos,
                outBias = outBias,
                blocks = blocks,
                lnW = lnW,
                lnB = lnB,
            )
        }

        private class TensorMeta(
            val dtype: String,
            val shape: List<Int>,
            val offset: Int,
            val byteLen: Int,
        )

        fun tokenKey(word: String): String? {
            if (word.isEmpty()) return null
            val folded =
                Normalizer
                    .normalize(word, Normalizer.Form.NFKC)
                    .lowercase(Locale.ROOT)
                    .replace('\u2019', '\'')

            fun isKeep(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '-' || c == '_'
            var start = 0
            while (start < folded.length && !isKeep(folded[start])) start++
            var end = folded.length
            while (end > start && !isKeep(folded[end - 1])) end--
            if (start >= end) return null
            val trimmed = folded.substring(start, end)
            var hasDigit = false
            var onlyPunct = true
            for (i in 0 until trimmed.length) {
                val c = trimmed[i]
                if (!isKeep(c)) return null
                if (c.isDigit()) hasDigit = true
                if (c != '\'' && c != '-') onlyPunct = false
            }
            if (onlyPunct) return null
            return if (hasDigit) "<num>" else trimmed
        }

        internal fun quantize(
            x: FloatArray,
            len: Int,
            out: ByteArray,
        ): Float {
            var maxAbs = 0f
            for (i in 0 until len) {
                val a = abs(x[i])
                if (a > maxAbs) maxAbs = a
            }
            if (maxAbs == 0f) {
                for (i in 0 until len) out[i] = 0
                return 1f
            }
            val scale = maxAbs / 127f
            val inv = 1f / scale
            for (i in 0 until len) {
                val q = (x[i] * inv).roundToInt().coerceIn(-127, 127)
                out[i] = q.toByte()
            }
            return scale
        }

        internal fun layerNorm(
            x: FloatArray,
            w: FloatArray,
            b: FloatArray,
            out: FloatArray,
        ) {
            val n = x.size
            var sum = 0f
            for (i in 0 until n) sum += x[i]
            val mean = sum / n.toFloat()
            var varSum = 0f
            for (i in 0 until n) {
                val diff = x[i] - mean
                varSum += diff * diff
            }
            val inv = 1f / sqrt(varSum / n.toFloat() + 1e-5f)
            for (i in 0 until n) {
                out[i] = (x[i] - mean) * inv * w[i] + b[i]
            }
        }

        internal fun gelu(x: Float): Float {
            val c = 0.79788456f
            val inner = c * (x + 0.044715f * x * x * x)
            return 0.5f * x * (1f + tanh(inner.toDouble()).toFloat())
        }

        private fun u16Le(
            b: ByteArray,
            at: Int,
        ): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun u32Le(
            b: ByteArray,
            at: Int,
        ): Int =
            (b[at].toInt() and 0xFF) or
                ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or
                ((b[at + 3].toInt() and 0xFF) shl 24)

        private fun readF32Array(
            bytes: ByteArray,
            offset: Int,
            count: Int,
        ): FloatArray {
            val out = FloatArray(count)
            ByteBuffer
                .wrap(bytes, offset, count * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
                .get(out)
            return out
        }
    }
}

/**
 * Single-thread KV-cached inference session for [WmlmModel].
 * Must only be accessed from the single background worker thread.
 */
class WmlmSession(
    val model: WmlmModel,
) {
    private val d = model.d
    private val layers = model.layers
    private val heads = model.heads
    private val dh = d / heads
    private val attnScale = 1f / sqrt(dh.toFloat())
    private val ctx = model.ctx

    private val currentTokens = IntArray(ctx)
    private var tokenLen = 0
    private val keys = Array(layers) { FloatArray(ctx * d) }
    private val values = Array(layers) { FloatArray(ctx * d) }
    private val last = FloatArray(d)
    private var dist: FloatArray? = null

    private val x = FloatArray(d)
    private val h = FloatArray(d)
    private val qkv = FloatArray(3 * d)
    private val attn = FloatArray(d)
    private val ff = FloatArray(model.ff)
    private val tmp = FloatArray(d)
    private val xq = ByteArray(maxOf(d, model.ff))
    private val attnScores = FloatArray(ctx)
    private val logitsScratch = FloatArray(model.vocabSize)
    private val spelled = HashMap<String, Float>()

    fun currentTokensCopy(): IntArray = currentTokens.copyOf(tokenLen)

    fun tokensFor(
        words: List<String>,
        register: Int = 0,
        field: Int = 0,
        langSlot: Int = 0,
    ): IntArray {
        val body = ArrayList<Int>(words.size + 4)
        for (w in words) {
            if (w.isEmpty() || w[0] == '\u0001') continue
            for (t in model.tokensOf(w)) {
                body.add(t)
            }
        }
        val keep = model.maxWords()
        val start = (body.size - keep).coerceAtLeast(0)
        val total = (4 + body.size - start).coerceAtMost(ctx)
        val out = IntArray(total)
        out[0] = WmlmModel.REG_NEUTRAL + register.coerceIn(0, 2)
        out[1] = WmlmModel.FIELD_OTHER + field.coerceIn(0, 3)
        out[2] = WmlmModel.LANG_0 + langSlot.coerceIn(0, 3)
        out[3] = WmlmModel.BOS
        var idx = 4
        var bIdx = start
        while (idx < total && bIdx < body.size) {
            out[idx++] = body[bIdx++]
        }
        return out
    }

    fun setContext(
        words: List<String>,
        register: Int = 0,
        field: Int = 0,
        langSlot: Int = 0,
    ) {
        val tokens = tokensFor(words, register, field, langSlot)
        setTokens(tokens)
    }

    fun setTokens(tokens: IntArray) {
        setTokensInner(tokens, keepSpelled = false)
    }

    private fun setTokensInner(
        tokens: IntArray,
        keepSpelled: Boolean,
    ) {
        val newLen = minOf(tokens.size, ctx)
        var common = 0
        val limit = minOf(tokenLen, newLen)
        while (common < limit && currentTokens[common] == tokens[common]) {
            common++
        }
        if (common == newLen && common == tokenLen) {
            return
        }
        if (!keepSpelled) {
            spelled.clear()
        }
        val keep = minOf(common, (newLen - 1).coerceAtLeast(0))
        tokenLen = keep
        dist = null
        for (i in keep until newLen) {
            step(tokens[i])
        }
    }

    private fun step(token: Int) {
        val pos = tokenLen
        if (pos >= ctx) return
        currentTokens[pos] = token
        tokenLen = pos + 1

        model.embed(token, x)
        val posOff = pos * d
        for (i in 0 until d) {
            x[i] += model.pos[posOff + i]
        }

        val t = pos + 1
        for (l in 0 until layers) {
            val layer = model.blocks[l]
            WmlmModel.layerNorm(x, layer.ln1W, layer.ln1B, h)
            model.matvec(layer.qkv, layer.qkvB, h, xq, qkv)

            val layerKeys = keys[l]
            val layerValues = values[l]
            val kvWriteBase = pos * d
            System.arraycopy(qkv, d, layerKeys, kvWriteBase, d)
            System.arraycopy(qkv, 2 * d, layerValues, kvWriteBase, d)

            for (hd in 0 until heads) {
                val hBase = hd * dh
                var maxScore = Float.NEGATIVE_INFINITY
                for (j in 0 until t) {
                    val kjBase = j * d + hBase
                    var dot = 0f
                    for (k in 0 until dh) {
                        dot += qkv[hBase + k] * layerKeys[kjBase + k]
                    }
                    val s = dot * attnScale
                    attnScores[j] = s
                    if (s > maxScore) maxScore = s
                }
                var sumExp = 0f
                for (j in 0 until t) {
                    val e = exp((attnScores[j] - maxScore).toDouble()).toFloat()
                    attnScores[j] = e
                    sumExp += e
                }
                val invSum = if (sumExp > 0f) 1f / sumExp else 0f
                for (k in 0 until dh) attn[hBase + k] = 0f
                for (j in 0 until t) {
                    val w = attnScores[j] * invSum
                    val vjBase = j * d + hBase
                    for (k in 0 until dh) {
                        attn[hBase + k] += w * layerValues[vjBase + k]
                    }
                }
            }

            model.matvec(layer.o, layer.oB, attn, xq, tmp)
            for (i in 0 until d) x[i] += tmp[i]

            WmlmModel.layerNorm(x, layer.ln2W, layer.ln2B, h)
            model.matvec(layer.w1, layer.w1B, h, xq, ff)
            for (i in 0 until model.ff) ff[i] = WmlmModel.gelu(ff[i])
            model.matvec(layer.w2, layer.w2B, ff, xq, tmp)
            for (i in 0 until d) x[i] += tmp[i]
        }

        WmlmModel.layerNorm(x, model.lnW, model.lnB, last)
    }

    fun distribution(): FloatArray {
        dist?.let { return it }
        if (tokenLen == 0) {
            setContext(emptyList())
        }
        val vocabSize = model.vocabSize
        model.matvec(model.emb, model.outBias, last, xq, logitsScratch)
        logitsScratch[WmlmModel.PAD] = Float.NEGATIVE_INFINITY

        var maxLogit = Float.NEGATIVE_INFINITY
        for (i in 0 until vocabSize) {
            val v = logitsScratch[i]
            if (v > maxLogit) maxLogit = v
        }
        var sumExp = 0.0
        for (i in 0 until vocabSize) {
            val v = logitsScratch[i]
            if (v.isFinite()) {
                sumExp += exp((v - maxLogit).toDouble())
            }
        }
        val logZ = (maxLogit + ln(sumExp)).toFloat()
        val out = FloatArray(vocabSize)
        for (i in 0 until vocabSize) {
            val v = logitsScratch[i]
            out[i] = if (v.isFinite()) v - logZ else Float.NEGATIVE_INFINITY
        }
        dist = out
        return out
    }

    fun logProbListed(word: String): Float {
        val key = WmlmModel.tokenKey(word) ?: return Float.NaN
        if (key == "<num>") return Float.NaN
        val id = model.idOf(key) ?: return Float.NaN
        return distribution().getOrElse(id) { Float.NEGATIVE_INFINITY }
    }

    fun logProb(word: String): Float {
        val key = WmlmModel.tokenKey(word) ?: return Float.NaN
        if (key == "<num>") return Float.NaN
        model.idOf(key)?.let { id ->
            return distribution().getOrElse(id) { Float.NEGATIVE_INFINITY }
        }
        if (spelled.containsKey(key)) {
            return spelled[key] ?: Float.NaN
        }
        val score = logProbPieces(key)
        if (spelled.size >= SPELLED_CACHE_MAX) {
            spelled.clear()
        }
        spelled[key] = score
        return score
    }

    private fun logProbPieces(key: String): Float {
        val pieces = model.piecesOf(key) ?: return Float.NaN
        if (tokenLen == 0) {
            setContext(emptyList())
        }
        if (pieces.isEmpty() || pieces.size > WmlmModel.MAX_WORD_PIECES || tokenLen + pieces.size > ctx) {
            return Float.NaN
        }
        var total = distribution().getOrElse(pieces[0]) { Float.NEGATIVE_INFINITY }
        if (pieces.size > 1) {
            val before = currentTokensCopy()
            val distBefore = dist
            for (i in 0 until pieces.size - 1) {
                step(pieces[i])
                dist = null
                total += distribution().getOrElse(pieces[i + 1]) { Float.NEGATIVE_INFINITY }
            }
            setTokensInner(before, keepSpelled = true)
            dist = distBefore
        }
        return total
    }

    /**
     * Scores how well each candidate fits between `context` and `followers`:
     * `ln P(candidate | context) + sum_i ln P(follower_i | context, candidate, ...)`.
     * Returns `Float.NaN` for any candidate or follower the model cannot spell.
     */
    fun revise(
        context: List<String>,
        candidates: List<String>,
        followers: List<String>,
        register: Int = 0,
        field: Int = 0,
        langSlot: Int = 0,
    ): FloatArray {
        val before = currentTokensCopy()
        val distBefore = dist
        val out = FloatArray(candidates.size) { Float.NaN }
        val seq = ArrayList<String>(context.size + 1 + followers.size)
        for (idx in candidates.indices) {
            val candidate = candidates[idx]
            setContext(context, register, field, langSlot)
            var score = logProb(candidate)
            if (!score.isNaN()) {
                seq.clear()
                seq.addAll(context)
                seq.add(candidate)
                for (follower in followers) {
                    setContext(seq, register, field, langSlot)
                    val flp = logProb(follower)
                    if (flp.isNaN()) {
                        score = Float.NaN
                        break
                    }
                    score += flp
                    seq.add(follower)
                }
            }
            out[idx] = score
        }
        if (before.isEmpty()) {
            tokenLen = 0
            dist = null
            spelled.clear()
        } else {
            setTokensInner(before, keepSpelled = false)
            dist = distBefore
        }
        return out
    }

    private companion object {
        private const val SPELLED_CACHE_MAX = 256
    }

    fun rankOf(word: String): Int {
        val key = WmlmModel.tokenKey(word) ?: return -1
        val id = model.idOf(key) ?: return -1
        val dArr = distribution()
        val own = dArr[id]
        var rank = 0
        for (i in WmlmModel.SPECIALS until dArr.size) {
            if (dArr[i] > own && !model.isPiece(i)) rank++
        }
        return rank
    }

    fun entropy(): Float {
        val dArr = distribution()
        var ent = 0.0
        for (i in dArr.indices) {
            val lp = dArr[i]
            if (lp.isFinite()) {
                ent -= exp(lp.toDouble()) * lp
            }
        }
        return ent.toFloat()
    }

    fun topWords(
        k: Int = 8,
        minLogProb: Float = WmlmModel.NEXT_WORD_FLOOR,
    ): List<Pair<String, Float>> {
        if (k <= 0) return emptyList()
        val dArr = distribution()
        val bestIds = IntArray(k)
        val bestScores = FloatArray(k) { Float.NEGATIVE_INFINITY }
        var count = 0
        for (id in WmlmModel.SPECIALS until dArr.size) {
            val lp = dArr[id]
            if (lp < minLogProb || model.isPiece(id)) continue
            if (count < k || lp > bestScores[count - 1]) {
                var pos = count
                while (pos > 0 && bestScores[pos - 1] < lp) {
                    if (pos < k) {
                        bestIds[pos] = bestIds[pos - 1]
                        bestScores[pos] = bestScores[pos - 1]
                    }
                    pos--
                }
                if (pos < k) {
                    bestIds[pos] = id
                    bestScores[pos] = lp
                    if (count < k) count++
                }
            }
        }
        val result = ArrayList<Pair<String, Float>>(count)
        for (i in 0 until count) {
            val w = model.word(bestIds[i]) ?: continue
            result.add(w to bestScores[i])
        }
        return result
    }

    fun buildSnapshot(
        contextWords: List<String>,
        languageId: String,
        topK: Int = 8,
        minLogProb: Float = WmlmModel.NEXT_WORD_FLOOR,
    ): WmlmSnapshot {
        val dArr = distribution()
        val normalizedCtx = contextWords.filter { it.isNotEmpty() }.takeLast(4).map { it.lowercase(Locale.ROOT) }
        return WmlmSnapshot(
            contextWords = normalizedCtx,
            languageId = DictionaryManager.normalizeLang(languageId),
            model = model,
            dist = dArr,
            topWords = topWords(topK, minLogProb),
            entropy = entropy(),
        )
    }
}

/**
 * Immutable snapshot of [WmlmSession]'s next-word distribution for a sentence prefix.
 * Safe for lock-free reads from UI / commit threads.
 */
class WmlmSnapshot(
    val contextWords: List<String>,
    val languageId: String,
    val model: WmlmModel,
    private val dist: FloatArray,
    val topWords: List<Pair<String, Float>>,
    val entropy: Float,
) {
    fun logProbListed(word: String): Float {
        val key = WmlmModel.tokenKey(word) ?: return Float.NaN
        if (key == "<num>") return Float.NaN
        val id = model.idOf(key) ?: return Float.NaN
        return if (id in dist.indices) dist[id] else Float.NaN
    }

    fun scores(words: List<String>): FloatArray = FloatArray(words.size) { idx -> logProbListed(words[idx]) }

    fun rankOf(word: String): Int {
        val key = WmlmModel.tokenKey(word) ?: return -1
        val id = model.idOf(key) ?: return -1
        if (id !in dist.indices) return -1
        val own = dist[id]
        var rank = 0
        for (i in WmlmModel.SPECIALS until dist.size) {
            if (dist[i] > own && !model.isPiece(i)) rank++
        }
        return rank
    }

    fun matchesContext(recentWords: List<String>): Boolean {
        val recentTail = recentWords.filter { it.isNotEmpty() }.takeLast(4).map { it.lowercase(Locale.ROOT) }
        return contextWords == recentTail
    }
}
