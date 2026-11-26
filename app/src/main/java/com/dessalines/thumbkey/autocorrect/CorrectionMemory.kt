package com.dessalines.thumbkey.autocorrect

import java.io.File

/**
 * Learns deliberate manual repairs when the user deletes a misspelled word and
 * retypes the intended spelling (ported from `wmkeyboard`'s `CorrectionMemory`).
 */
class CorrectionMemory(
    private val storageFile: File?,
) {
    private data class FixEntry(
        val intended: String,
        var count: Int,
        var gen: Long,
    )

    private val pairs = HashMap<String, MutableList<FixEntry>>()
    private val habitCounts = HashMap<String, Int>()
    private var generation = 0L
    private var totalHabitSightings = 0
    private var dirty = false

    init {
        load()
    }

    @Synchronized
    fun recordManualRepair(
        typed: String,
        intended: String,
    ) {
        val tLower = typed.lowercase()
        val iLower = intended.lowercase()
        if (tLower == iLower) return
        if (tLower.length < MIN_TYPED_LENGTH || tLower.length > MAX_WORD_LENGTH) return
        if (iLower.length < MIN_HALF_LENGTH || iLower.length > MAX_WORD_LENGTH) return

        val maxEdits = if (tLower.length >= LONG_WORD_LENGTH) MAX_EDITS_LONG else MAX_EDITS
        if (EditOps.distance(tLower, iLower) > maxEdits) return

        val list = pairs.getOrPut(tLower) { ArrayList(2) }
        val existing = list.firstOrNull { it.intended == iLower }
        if (existing != null) {
            existing.count = (existing.count + 1).coerceAtMost(MAX_COUNT)
            existing.gen = generation
        } else {
            if (list.size >= MAX_FIXES_PER_TYPED) {
                val weakest = list.minByOrNull { it.count }
                if (weakest != null) list.remove(weakest)
            }
            list.add(FixEntry(iLower, 1, generation))
        }
        if (pairs.size > MAX_PAIRS) {
            val victim =
                pairs.entries
                    .minByOrNull { (_, fixes) -> fixes.maxOfOrNull { it.gen } ?: 0L }
                    ?.key
            if (victim != null && victim != tLower) pairs.remove(victim)
        }

        // Track repeated character-slip habits via optimal string alignment
        for (op in EditOps.align(tLower, iLower)) {
            if (op is EditOps.Op.Sub) {
                val hKey = "sub:${op.typed}:${op.intended}"
                habitCounts[hKey] = ((habitCounts[hKey] ?: 0) + 1).coerceAtMost(MAX_COUNT)
                totalHabitSightings++
            }
        }
        if (totalHabitSightings > HABIT_WINDOW) {
            val iter = habitCounts.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                val halved = e.value / 2
                if (halved <= 0) iter.remove() else e.setValue(halved)
            }
            totalHabitSightings /= 2
        }
        dirty = true
    }

    @Synchronized
    fun lookupTaughtFix(
        typedLower: String,
        typedIsKnownWord: Boolean,
    ): String? {
        val list = pairs[typedLower] ?: return null
        if (list.isEmpty()) return null
        val sorted = list.sortedByDescending { it.count }
        val top = sorted[0]
        val required = if (typedIsKnownWord) APPLY_KNOWN_AT else APPLY_AT
        if (top.count < required) return null
        val secondCount = sorted.getOrNull(1)?.count ?: 0
        if (secondCount > 0 && top.count < secondCount * DOMINANCE) return null
        return top.intended
    }

    @Synchronized
    fun clear() {
        pairs.clear()
        habitCounts.clear()
        generation = 0L
        totalHabitSightings = 0
        dirty = false
        storageFile?.let { if (it.exists()) it.delete() }
    }

    @Synchronized
    fun saveIfDirty() {
        if (!dirty || storageFile == null) return
        generation++
        val pairsIter = pairs.entries.iterator()
        while (pairsIter.hasNext()) {
            val entry = pairsIter.next()
            entry.value.removeAll { generation - it.gen > EXPIRE_GENERATIONS && it.count <= 1 }
            if (entry.value.isEmpty()) pairsIter.remove()
        }
        val serializedPairs =
            pairs.mapValues { (_, list) ->
                list.map { mapOf("intended" to it.intended, "count" to it.count, "gen" to it.gen) }
            }
        val root =
            mapOf(
                "generation" to generation,
                "habits" to habitCounts.toMap(),
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
        habitCounts.clear()
        generation = parsed["generation"].asLong(0L)
        for ((k, v) in parsed["habits"].asStringAnyMap()) {
            val c = v.asInt(0)
            if (c > 0) habitCounts[k] = c
        }
        for ((typed, rawList) in parsed["pairs"].asStringAnyMap()) {
            val items = rawList as? List<*> ?: continue
            val entries = ArrayList<FixEntry>(items.size)
            for (item in items) {
                val m = item.asStringAnyMap()
                val intended = m["intended"] as? String ?: continue
                val c = m["count"].asInt(0)
                if (c > 0) {
                    entries.add(FixEntry(intended, c, m["gen"].asLong(generation)))
                }
            }
            if (entries.isNotEmpty()) pairs[typed] = entries
        }
        dirty = false
    }

    companion object {
        const val APPLY_AT = 2
        const val APPLY_KNOWN_AT = 3
        const val DOMINANCE = 2
        const val MAX_FIXES_PER_TYPED = 3
        const val MAX_PAIRS = 1000
        const val MAX_COUNT = 100
        const val EXPIRE_GENERATIONS = 180L
        const val HABIT_WINDOW = 400
        const val MIN_TYPED_LENGTH = 3
        const val MAX_WORD_LENGTH = 32
        const val MIN_HALF_LENGTH = 2
        const val MAX_EDITS = 2
        const val LONG_WORD_LENGTH = 8
        const val MAX_EDITS_LONG = 3
    }
}
