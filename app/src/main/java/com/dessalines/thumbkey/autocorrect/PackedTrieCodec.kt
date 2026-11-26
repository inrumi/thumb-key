package com.dessalines.thumbkey.autocorrect

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary encoder/decoder for `.wmdict` v3 big-endian trie containers,
 * byte-for-byte compatible with `wmkeyboard`'s dictionary format.
 */
object PackedTrieCodec {
    const val MAGIC = 0x574D4443 // "WMDC"
    const val VERSION = 3
    const val FLAG_LABELS_U8 = 1
    const val FLAG_DEGREE_U8 = 2
    const val HEADER_BYTES = 52
    private const val SECTION_COUNT = 7

    data class Header(
        val wordCount: Int,
        val nodeCount: Int,
        val edgeCount: Int,
    )

    fun hasMagic(file: File): Boolean = file.isFile && runCatching { readHeader(file) }.isSuccess

    fun isReadable(file: File): Boolean = hasMagic(file)

    fun readHeader(file: File): Header =
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < HEADER_BYTES) throw IOException("Truncated dictionary header")
            if (raf.readInt() != MAGIC) throw IOException("Not a .wmdict file")
            val version = raf.readUnsignedShort()
            if (version != VERSION) throw IOException("Unsupported .wmdict version: $version")
            raf.readUnsignedShort() // flags
            Header(
                wordCount = raf.readInt(),
                nodeCount = raf.readInt(),
                edgeCount = raf.readInt(),
            )
        }

    fun write(
        trie: PackedTrie,
        file: File,
    ) {
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            write(trie, out)
        }
    }

    fun toBytes(trie: PackedTrie): ByteArray {
        val baos = ByteArrayOutputStream(HEADER_BYTES + trie.nodeCount * 6)
        write(trie, baos)
        return baos.toByteArray()
    }

    fun write(
        trie: PackedTrie,
        out: OutputStream,
    ) {
        val useU8Labels = trie.edgeLabelU8 != null
        val useU8Degrees = trie.degreeU8 != null
        val flags =
            (if (useU8Labels) FLAG_LABELS_U8 else 0) or
                (if (useU8Degrees) FLAG_DEGREE_U8 else 0)

        val symbolsBytes = pad4(trie.symbols.size * 2)
        val childStartBytes =
            pad4(
                if (useU8Degrees) trie.nodeCount else (trie.nodeCount + 1) * 4,
            )
        val checkpointBytes =
            pad4(
                if (useU8Degrees) (trie.checkpoint?.size ?: 0) * 4 else 0,
            )
        val edgeLabelBytes =
            pad4(
                if (useU8Labels) trie.edgeCount else trie.edgeCount * 2,
            )
        val freqBytes = pad4(trie.nodeCount * 2)
        val maxSubtreeBytes = pad4(trie.nodeCount * 2)
        val bitsetRawBytes = (trie.nodeCount + 7) ushr 3
        val isWordBytesPadded = pad4(bitsetRawBytes)

        val sectionSizes =
            intArrayOf(
                symbolsBytes,
                childStartBytes,
                checkpointBytes,
                edgeLabelBytes,
                freqBytes,
                maxSubtreeBytes,
                isWordBytesPadded,
            )
        val offsets = IntArray(SECTION_COUNT)
        var cursor = HEADER_BYTES
        for (i in 0 until SECTION_COUNT) {
            offsets[i] = cursor
            cursor += sectionSizes[i]
        }

        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        header.putInt(MAGIC)
        header.putShort(VERSION.toShort())
        header.putShort(flags.toShort())
        header.putInt(trie.wordCountValue)
        header.putInt(trie.nodeCount)
        header.putInt(trie.edgeCount)
        header.putInt(if (useU8Labels) trie.symbols.size else 0)
        for (off in offsets) {
            header.putInt(off)
        }
        out.write(header.array())

        writePadded(out, symbolsBytes) { bb ->
            for (c in trie.symbols) bb.putChar(c)
        }
        writePadded(out, childStartBytes) { bb ->
            val degs = trie.degreeU8
            if (degs != null) {
                bb.put(degs)
            } else {
                for (cs in trie.childStart!!) bb.putInt(cs)
            }
        }
        writePadded(out, checkpointBytes) { bb ->
            trie.checkpoint?.forEach { bb.putInt(it) }
        }
        writePadded(out, edgeLabelBytes) { bb ->
            val u8 = trie.edgeLabelU8
            if (u8 != null) {
                bb.put(u8)
            } else {
                for (c in trie.edgeLabelU16!!) bb.putChar(c)
            }
        }
        writePadded(out, freqBytes) { bb ->
            for (s in trie.freqCodes) bb.putShort(s)
        }
        writePadded(out, maxSubtreeBytes) { bb ->
            for (s in trie.maxSubtreeCodes) bb.putShort(s)
        }
        writePadded(out, isWordBytesPadded) { bb ->
            bb.put(trie.isWordBytes, 0, minOf(bitsetRawBytes, trie.isWordBytes.size))
        }
    }

    fun read(stream: InputStream): PackedTrie = readFromBytes(stream.readBytes())

    fun readFromBytes(bytes: ByteArray): PackedTrie = MappedTrie.fromBytes(bytes).toPackedTrie()

    private inline fun writePadded(
        out: OutputStream,
        paddedByteLength: Int,
        fill: (ByteBuffer) -> Unit,
    ) {
        if (paddedByteLength == 0) return
        val bb = ByteBuffer.allocate(paddedByteLength).order(ByteOrder.BIG_ENDIAN)
        fill(bb)
        out.write(bb.array())
    }

    internal fun pad4(byteLen: Int): Int = (byteLen + 3) and -4
}
