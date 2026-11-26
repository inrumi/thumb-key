package com.dessalines.thumbkey.autocorrect

/**
 * Minimal dependency-free JSON codec for persisted autocorrect state.
 *
 * Using pure Kotlin avoids Android SDK `org.json` stubs in plain JVM unit
 * tests and avoids adding external dependencies.
 */
internal object SimpleJson {
    fun write(value: Any?): String {
        val sb = StringBuilder(256)
        appendValue(sb, value)
        return sb.toString()
    }

    fun parseObject(text: String): Map<String, Any?>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val parser = Parser(trimmed)
        return try {
            val result = parser.readValue()
            parser.skipWhitespace()
            if (!parser.isEnd()) null else result as? Map<String, Any?>
        } catch (_: Exception) {
            null
        }
    }

    private fun appendValue(
        sb: StringBuilder,
        value: Any?,
    ) {
        when (value) {
            null -> {
                sb.append("null")
            }

            is Boolean -> {
                sb.append(if (value) "true" else "false")
            }

            is Int, is Long, is Float, is Double -> {
                sb.append(value.toString())
            }

            is Number -> {
                sb.append(value.toString())
            }

            is String -> {
                appendString(sb, value)
            }

            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (k !is String) continue
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, k)
                    sb.append(':')
                    appendValue(sb, v)
                }
                sb.append('}')
            }

            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendValue(sb, item)
                }
                sb.append(']')
            }

            else -> {
                appendString(sb, value.toString())
            }
        }
    }

    private fun appendString(
        sb: StringBuilder,
        s: String,
    ) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> {
                    sb.append("\\\"")
                }

                '\\' -> {
                    sb.append("\\\\")
                }

                '\b' -> {
                    sb.append("\\b")
                }

                '\u000C' -> {
                    sb.append("\\f")
                }

                '\n' -> {
                    sb.append("\\n")
                }

                '\r' -> {
                    sb.append("\\r")
                }

                '\t' -> {
                    sb.append("\\t")
                }

                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u")
                        sb.append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
    }

    private class Parser(
        private val input: String,
    ) {
        private var pos = 0

        fun isEnd(): Boolean = pos >= input.length

        fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (isEnd()) error("Unexpected EOF")
            return when (val ch = input[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> error("Unexpected char '$ch' at $pos")
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // '{'
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (pos < input.length && input[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (isEnd() || input[pos] != '"') error("Expected string key at $pos")
                val key = readString()
                skipWhitespace()
                if (isEnd() || input[pos] != ':') error("Expected ':' at $pos")
                pos++
                val value = readValue()
                map[key] = value
                skipWhitespace()
                if (isEnd()) error("Unterminated object")
                when (input[pos]) {
                    ',' -> {
                        pos++
                    }

                    '}' -> {
                        pos++
                        return map
                    }

                    else -> {
                        error("Expected ',' or '}' at $pos")
                    }
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // '['
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (pos < input.length && input[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(readValue())
                skipWhitespace()
                if (isEnd()) error("Unterminated array")
                when (input[pos]) {
                    ',' -> {
                        pos++
                    }

                    ']' -> {
                        pos++
                        return list
                    }

                    else -> {
                        error("Expected ',' or ']' at $pos")
                    }
                }
            }
        }

        private fun readString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < input.length) {
                val c = input[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    if (pos >= input.length) error("Unterminated escape")
                    when (val esc = input[pos++]) {
                        '"', '\\', '/' -> {
                            sb.append(esc)
                        }

                        'b' -> {
                            sb.append('\b')
                        }

                        'f' -> {
                            sb.append('\u000C')
                        }

                        'n' -> {
                            sb.append('\n')
                        }

                        'r' -> {
                            sb.append('\r')
                        }

                        't' -> {
                            sb.append('\t')
                        }

                        'u' -> {
                            if (pos + 4 > input.length) error("Short unicode escape")
                            val hex = input.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }

                        else -> {
                            error("Invalid escape '\\$esc'")
                        }
                    }
                } else {
                    sb.append(c)
                }
            }
            error("Unterminated string")
        }

        private fun <T> readLiteral(
            literal: String,
            value: T,
        ): T {
            if (!input.regionMatches(pos, literal, 0, literal.length)) {
                error("Expected $literal at $pos")
            }
            pos += literal.length
            return value
        }

        private fun readNumber(): Number {
            val start = pos
            if (input[pos] == '-') pos++
            var isFloat = false
            while (pos < input.length) {
                val c = input[pos]
                if (c in '0'..'9') {
                    pos++
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    if (c == '.' || c == 'e' || c == 'E') isFloat = true
                    pos++
                } else {
                    break
                }
            }
            val token = input.substring(start, pos)
            return if (isFloat) {
                token.toDouble()
            } else {
                val l = token.toLong()
                if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
            }
        }
    }
}

internal fun Any?.asInt(default: Int = 0): Int = (this as? Number)?.toInt() ?: default

internal fun Any?.asLong(default: Long = 0L): Long = (this as? Number)?.toLong() ?: default

internal fun Any?.asBoolean(default: Boolean = false): Boolean = this as? Boolean ?: default

@Suppress("UNCHECKED_CAST")
internal fun Any?.asStringAnyMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

@Suppress("UNCHECKED_CAST")
internal fun Any?.asStringList(): List<String> = (this as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
