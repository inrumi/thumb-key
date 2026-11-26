package com.dessalines.thumbkey.autocorrect

import java.io.File

/**
 * Undo history and adaptive aggressiveness feedback ported from
 * `wmkeyboard`'s `correction_stats.rs`.
 */
class CorrectionStats(
    private val storageFile: File?,
) {
    enum class UndoMemory(
        val settingValue: Int,
    ) {
        OFF(0),
        LIGHT(1),
        NORMAL(2),
        STRICT(3),
        ;

        companion object {
            fun fromInt(value: Int): UndoMemory = entries.firstOrNull { it.settingValue == value } ?: NORMAL
        }
    }

    enum class Penalty {
        NONE,
        PENALIZED,
        PROBATION,
        BLOCKED,
    }

    private data class PairStat(
        val count: Int,
        val gen: Long,
        val kept: Int = 0,
        val held: Boolean = false,
    )

    private val pairs = HashMap<String, PairStat>()
    private val sessionRejected = HashMap<String, Long>()
    private var fired = 0
    private var reverted = 0
    private var generation = 0L
    private var tick = 0L
    private var dirty = false

    var memory: UndoMemory = UndoMemory.NORMAL

    init {
        load()
    }

    private fun pairKey(
        typed: String,
        fixed: String,
    ): String = "${typed.lowercase()}\u0000${fixed.lowercase()}"

    private fun bumpFiredLocked() {
        fired++
        tick++
        if (fired >= WINDOW) {
            fired /= 2
            reverted /= 2
        }
        dirty = true
    }

    @Synchronized
    fun recordRevert(
        typed: String,
        fixed: String,
        deliberate: Boolean,
    ) {
        val k = pairKey(typed, fixed)
        reverted++
        bumpFiredLocked()
        if (memory == UndoMemory.OFF) return

        if (deliberate || memory == UndoMemory.STRICT) {
            sessionRejected[k] = tick
        }
        val current = pairs[k]
        if (current != null && current.held) {
            dirty = true
            return
        }
        val bumped = (current?.count ?: 0) + 1
        val nextCount =
            when (memory) {
                UndoMemory.LIGHT -> 1
                UndoMemory.STRICT -> maxOf(bumped, BLOCK_AT)
                UndoMemory.NORMAL, UndoMemory.OFF -> bumped
            }
        pairs[k] = PairStat(count = nextCount, gen = generation, kept = 0, held = false)
        if (pairs.size > MAX_PAIRS) {
            val victim =
                pairs.entries
                    .filter { !it.value.held }
                    .minWithOrNull(compareBy<Map.Entry<String, PairStat>> { it.value.gen }.thenBy { it.key })
                    ?.key
            if (victim != null) pairs.remove(victim)
        }
        dirty = true
    }

    @Synchronized
    fun recordKept(
        typed: String,
        fixed: String,
    ) {
        val k = pairKey(typed, fixed)
        bumpFiredLocked()
        if (memory == UndoMemory.OFF) return
        val sessionStamp = sessionRejected[k]
        if (sessionStamp != null) {
            if (tick - sessionStamp < SESSION_BLOCK_SPAN) return
            sessionRejected.remove(k)
        }
        val existing = pairs[k] ?: return
        if (existing.held) return

        val nextKept = existing.kept + 1
        if (nextKept < KEEPS_TO_FORGIVE) {
            pairs[k] = existing.copy(kept = nextKept, gen = generation)
        } else if (existing.count <= 1) {
            pairs.remove(k)
        } else {
            pairs[k] = existing.copy(count = existing.count - 1, kept = 0, gen = generation)
        }
        dirty = true
    }

    @Synchronized
    fun penalty(
        typed: String,
        fixed: String,
    ): Penalty {
        if (memory == UndoMemory.OFF) return Penalty.NONE
        val k = pairKey(typed, fixed)
        val stamp = sessionRejected[k]
        if (stamp != null && tick - stamp < SESSION_BLOCK_SPAN) {
            return Penalty.BLOCKED
        }
        val stat = pairs[k] ?: return Penalty.NONE
        return when {
            stat.count < BLOCK_AT -> Penalty.PENALIZED

            !stat.held &&
                memory != UndoMemory.STRICT &&
                generation - stat.gen > REOFFER_GENERATIONS -> Penalty.PROBATION

            else -> Penalty.BLOCKED
        }
    }

    @Synchronized
    fun multiplier(): Double {
        if (fired < MIN_SAMPLE) return 1.0
        val rate = reverted.toDouble() / fired.toDouble()
        return (1.0 + RATE_GAIN * (rate - TARGET_RATE)).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
    }

    @Synchronized
    fun endFieldSession() {
        sessionRejected.clear()
    }

    @Synchronized
    fun clear() {
        pairs.clear()
        sessionRejected.clear()
        fired = 0
        reverted = 0
        generation = 0L
        dirty = false
        storageFile?.let { if (it.exists()) it.delete() }
    }

    @Synchronized
    fun saveIfDirty() {
        if (!dirty || storageFile == null) return
        generation++
        val it = pairs.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val p = entry.value
            if (!p.held && generation - p.gen > EXPIRE_GENERATIONS) {
                if (p.count <= 1) {
                    it.remove()
                } else {
                    entry.setValue(PairStat(count = p.count - 1, gen = generation, kept = 0, held = false))
                }
            }
        }
        val serializedPairs =
            pairs.mapValues { (_, p) ->
                mapOf("count" to p.count, "gen" to p.gen, "kept" to p.kept, "held" to p.held)
            }
        val root =
            mapOf(
                "generation" to generation,
                "fired" to fired,
                "reverted" to reverted,
                "pairs" to serializedPairs,
            )
        writeAtomic(storageFile, SimpleJson.write(root))
        dirty = false
    }

    @Synchronized
    fun load() {
        val file = storageFile ?: return
        if (!file.isFile) return
        val parsed =
            try {
                SimpleJson.parseObject(file.readText(Charsets.UTF_8))
            } catch (_: Exception) {
                null
            } ?: return
        pairs.clear()
        generation = parsed["generation"].asLong(0L)
        fired = parsed["fired"].asInt(0)
        reverted = parsed["reverted"].asInt(0)
        for ((k, v) in parsed["pairs"].asStringAnyMap()) {
            val m = v.asStringAnyMap()
            val c = m["count"].asInt(0)
            if (c > 0) {
                pairs[k] =
                    PairStat(
                        count = c,
                        gen = m["gen"].asLong(generation),
                        kept = m["kept"].asInt(0),
                        held = m["held"].asBoolean(false),
                    )
            }
        }
        dirty = false
    }

    companion object {
        const val BLOCK_AT = 2
        const val KEEPS_TO_FORGIVE = 3
        const val MAX_PAIRS = 500
        const val EXPIRE_GENERATIONS = 180L
        const val SESSION_BLOCK_SPAN = 20L
        const val REOFFER_GENERATIONS = 40L
        const val WINDOW = 200
        const val MIN_SAMPLE = 20
        const val TARGET_RATE = 0.03
        const val RATE_GAIN = 20.0
        const val MIN_MULTIPLIER = 0.85
        const val MAX_MULTIPLIER = 2.5
    }
}
