package com.dessalines.thumbkey.autocorrect

/**
 * Character-level alignment (Optimal String Alignment / Damerau-Levenshtein
 * with adjacent transposition) and quick swap/edit-distance queries.
 */
object EditOps {
    sealed class Op {
        abstract val at: Int

        data class Match(
            override val at: Int,
            val ch: Char,
        ) : Op()

        data class Sub(
            override val at: Int,
            val typed: Char,
            val intended: Char,
        ) : Op()

        data class Ins(
            override val at: Int,
            val intended: Char,
        ) : Op()

        data class Del(
            override val at: Int,
            val typed: Char,
        ) : Op()

        data class Swap(
            override val at: Int,
            val first: Char,
            val second: Char,
        ) : Op()
    }

    fun isOneSwap(
        typed: CharSequence,
        intended: CharSequence,
    ): Boolean {
        if (typed.length != intended.length || typed.length < 2) return false
        var i = 0
        while (i < typed.length && typed[i] == intended[i]) i++
        if (i >= typed.length - 1) return false
        if (typed[i] != intended[i + 1] || typed[i + 1] != intended[i]) return false
        for (k in i + 2 until typed.length) {
            if (typed[k] != intended[k]) return false
        }
        return true
    }

    fun distance(
        typed: CharSequence,
        intended: CharSequence,
    ): Int {
        val n = typed.length
        val m = intended.length
        if (n == 0) return m
        if (m == 0) return n
        return table(typed, intended)[n][m]
    }

    fun align(
        typed: CharSequence,
        intended: CharSequence,
    ): List<Op> {
        val n = typed.length
        val m = intended.length
        val d = table(typed, intended)
        val ops = ArrayList<Op>(maxOf(n, m))
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && typed[i - 1] == intended[j - 1] && d[i][j] == d[i - 1][j - 1] -> {
                    ops.add(Op.Match(i - 1, typed[i - 1]))
                    i--
                    j--
                }

                swappable(typed, intended, i, j) && d[i][j] == d[i - 2][j - 2] + 1 -> {
                    ops.add(Op.Swap(i - 2, intended[j - 2], intended[j - 1]))
                    i -= 2
                    j -= 2
                }

                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + 1 -> {
                    ops.add(Op.Sub(i - 1, typed[i - 1], intended[j - 1]))
                    i--
                    j--
                }

                i > 0 && d[i][j] == d[i - 1][j] + 1 -> {
                    ops.add(Op.Del(i - 1, typed[i - 1]))
                    i--
                }

                else -> {
                    ops.add(Op.Ins(i, intended[j - 1]))
                    j--
                }
            }
        }
        ops.reverse()
        return ops
    }

    private fun swappable(
        typed: CharSequence,
        intended: CharSequence,
        i: Int,
        j: Int,
    ): Boolean =
        i > 1 &&
            j > 1 &&
            typed[i - 1] == intended[j - 2] &&
            typed[i - 2] == intended[j - 1] &&
            typed[i - 1] != typed[i - 2]

    private fun table(
        typed: CharSequence,
        intended: CharSequence,
    ): Array<IntArray> {
        val n = typed.length
        val m = intended.length
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (typed[i - 1] == intended[j - 1]) 0 else 1
                var best = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (swappable(typed, intended, i, j)) {
                    best = minOf(best, d[i - 2][j - 2] + 1)
                }
                d[i][j] = best
            }
        }
        return d
    }
}
