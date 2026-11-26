package com.dessalines.thumbkey.autocorrect

/**
 * Packed 16-bit frequency coding compatible with `.wmdict` v3 tries:
 * a 5-bit exponent and an 11-bit mantissa with an implicit leading bit.
 */
object FrequencyCodec {
    private const val MANTISSA_BITS = 11
    private const val MANTISSA_MASK = (1 shl MANTISSA_BITS) - 1
    private const val IMPLICIT_BIT = 1 shl MANTISSA_BITS
    const val MAX_CODE = 0xA7FF

    fun encode(value: Int): Int {
        if (value <= MANTISSA_MASK) return if (value <= 0) 0 else value
        val exponent = 32 - Integer.numberOfLeadingZeros(value) - MANTISSA_BITS
        val code = (exponent shl MANTISSA_BITS) or ((value ushr (exponent - 1)) and MANTISSA_MASK)
        return code.coerceAtMost(MAX_CODE)
    }

    fun decode(code: Int): Int {
        val raw = code and 0xFFFF
        val exponent = raw ushr MANTISSA_BITS
        if (exponent == 0) return raw
        val value = (IMPLICIT_BIT or (raw and MANTISSA_MASK)) shl (exponent - 1)
        return if (value < 0) Int.MAX_VALUE else value
    }

    fun round(value: Int): Int = decode(encode(value))
}
