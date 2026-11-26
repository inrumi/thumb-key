package com.dessalines.thumbkey.autocorrect

import java.io.InputStream
import java.text.Normalizer

/**
 * Offline word-to-emoji predictor modeled on `wmkeyboard`'s `EmojiSuggester`,
 * combining high-precision curated EN/ES triggers with `gemoji` trigger and shortcode TSVs.
 */
class EmojiSuggester(
    private val triggers: Map<String, List<String>> = emptyMap(),
    private val shortcodes: Map<String, String> = emptyMap(),
) {
    fun suggest(
        word: String,
        previousWord: String = "",
        limit: Int = DEFAULT_LIMIT,
    ): List<String> {
        if (limit <= 0) return emptyList()
        val cleaned = word.trim().lowercase()
        if (cleaned.length < MIN_WORD_LENGTH) return emptyList()

        val results = LinkedHashSet<String>(limit * 2)
        val prevClean = previousWord.trim().lowercase()
        if (prevClean.length >= MIN_WORD_LENGTH) {
            shortcodes["${prevClean}_$cleaned"]?.let { results.add(it) }
        }

        addWordLayers(cleaned, results)
        if (results.size < limit && cleaned.length >= 4 && cleaned.endsWith('s')) {
            addWordLayers(cleaned.dropLast(1), results)
        }
        return results.take(limit)
    }

    private fun addWordLayers(
        wordLower: String,
        into: MutableSet<String>,
    ) {
        CURATED_TRIGGERS[wordLower]?.let { into.addAll(it) }
        val folded = stripAccents(wordLower)
        if (folded != wordLower) {
            CURATED_TRIGGERS[folded]?.let { into.addAll(it) }
        }
        triggers[wordLower]?.let { into.addAll(it) }
        if (wordLower.length >= MIN_SHORTCODE_LENGTH) {
            shortcodes[wordLower]?.let { into.add(it) }
        }
    }

    companion object {
        val EMPTY = EmojiSuggester()
        private const val MIN_WORD_LENGTH = 2
        private const val MIN_SHORTCODE_LENGTH = 3
        private const val DEFAULT_LIMIT = 3

        fun load(
            triggersStream: InputStream?,
            shortcodesStream: InputStream?,
        ): EmojiSuggester {
            val triggerMap = HashMap<String, List<String>>(2048)
            triggersStream?.bufferedReader(Charsets.UTF_8)?.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEachLine
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val key = line.substring(0, tab).trim().lowercase()
                val emojis =
                    line
                        .substring(tab + 1)
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                if (key.isNotEmpty() && emojis.isNotEmpty()) {
                    triggerMap[key] = emojis
                }
            }

            val shortcodeMap = HashMap<String, String>(2048)
            shortcodesStream?.bufferedReader(Charsets.UTF_8)?.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEachLine
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val key = line.substring(0, tab).trim().lowercase()
                val emoji = line.substring(tab + 1).trim()
                if (key.isNotEmpty() && emoji.isNotEmpty()) {
                    shortcodeMap[key] = emoji
                }
            }
            return EmojiSuggester(triggerMap, shortcodeMap)
        }

        private fun stripAccents(s: String): String {
            val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
            val sb = StringBuilder(s.length)
            for (c in nfd) {
                if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private val CURATED_TRIGGERS: Map<String, List<String>> =
            mapOf(
                // English triggers (matching wmkeyboard ordering)
                "birthday" to listOf("🎂", "🎉", "🥳", "🎁"),
                "coffee" to listOf("☕", "😊"),
                "tea" to listOf("🍵", "☕"),
                "party" to listOf("🥳", "🎉", "🪩"),
                "congrats" to listOf("🎉", "👏", "🥳"),
                "congratulations" to listOf("🎉", "👏", "🥳"),
                "thanks" to listOf("🙏", "😊", "❤️"),
                "thank" to listOf("🙏", "😊"),
                "love" to listOf("❤️", "😍", "🥰"),
                "haha" to listOf("😂", "🤣"),
                "lol" to listOf("😂", "🤣"),
                "wow" to listOf("😮", "🤩"),
                "sad" to listOf("😢", "😞", "💔"),
                "cry" to listOf("😭", "😢"),
                "angry" to listOf("😠", "😡"),
                "sorry" to listOf("😔", "🙏"),
                "please" to listOf("🙏", "🥺"),
                "good" to listOf("👍", "😊"),
                "great" to listOf("👏", "🔥", "💯"),
                "night" to listOf("🌙", "😴", "✨"),
                "morning" to listOf("🌅", "☀️", "☕"),
                "sleep" to listOf("😴", "💤", "🛌"),
                "fire" to listOf("🔥"),
                "hot" to listOf("🥵", "🔥", "☀️"),
                "cold" to listOf("🥶", "❄️"),
                "rain" to listOf("🌧️", "☔", "⛈️"),
                "sun" to listOf("☀️", "😎"),
                "food" to listOf("🍽️", "😋", "🍲"),
                "hungry" to listOf("😋", "🍽️"),
                "pizza" to listOf("🍕"),
                "cake" to listOf("🍰", "🎂"),
                "music" to listOf("🎵", "🎶", "🎧"),
                "football" to listOf("⚽", "🏈"),
                "win" to listOf("🏆", "🥇", "🎉"),
                "money" to listOf("💰", "💸"),
                "work" to listOf("💼", "💻"),
                "home" to listOf("🏠", "🏡"),
                "car" to listOf("🚗"),
                "flight" to listOf("✈️", "🛫"),
                "beach" to listOf("🏖️", "🌊"),
                "book" to listOf("📚", "📖"),
                "idea" to listOf("💡", "🤔"),
                "ok" to listOf("👌", "👍"),
                "yes" to listOf("✅", "👍"),
                "no" to listOf("❌", "🙅"),
                "wedding" to listOf("💍", "👰", "🤵"),
                "baby" to listOf("👶", "🍼"),
                // Spanish triggers
                "cumpleaños" to listOf("🎂", "🎉", "🥳", "🎁"),
                "cumpleanos" to listOf("🎂", "🎉", "🥳", "🎁"),
                "café" to listOf("☕", "😊"),
                "cafe" to listOf("☕", "😊"),
                "té" to listOf("🍵", "☕"),
                "fiesta" to listOf("🥳", "🎉", "🪩"),
                "felicidades" to listOf("🎉", "👏", "🥳"),
                "gracias" to listOf("🙏", "😊", "❤️"),
                "amor" to listOf("❤️", "😍", "🥰"),
                "corazón" to listOf("❤️", "💖"),
                "corazon" to listOf("❤️", "💖"),
                "jaja" to listOf("😂", "🤣"),
                "jajaja" to listOf("😂", "🤣"),
                "triste" to listOf("😢", "😞", "💔"),
                "llorar" to listOf("😭", "😢"),
                "enojado" to listOf("😠", "😡"),
                "enfadado" to listOf("😠", "😡"),
                "perdón" to listOf("😔", "🙏"),
                "perdon" to listOf("😔", "🙏"),
                "bueno" to listOf("👍", "😊"),
                "genial" to listOf("👏", "🔥", "💯"),
                "noche" to listOf("🌙", "😴", "✨"),
                "mañana" to listOf("🌅", "☀️", "☕"),
                "manana" to listOf("🌅", "☀️", "☕"),
                "dormir" to listOf("😴", "💤", "🛌"),
                "fuego" to listOf("🔥"),
                "calor" to listOf("🥵", "🔥", "☀️"),
                "frío" to listOf("🥶", "❄️"),
                "frio" to listOf("🥶", "❄️"),
                "lluvia" to listOf("🌧️", "☔", "⛈️"),
                "sol" to listOf("☀️", "😎"),
                "comida" to listOf("🍽️", "😋", "🍲"),
                "hambre" to listOf("😋", "🍽️"),
                "pastel" to listOf("🍰", "🎂"),
                "tarta" to listOf("🍰", "🎂"),
                "música" to listOf("🎵", "🎶", "🎧"),
                "musica" to listOf("🎵", "🎶", "🎧"),
                "fútbol" to listOf("⚽"),
                "futbol" to listOf("⚽"),
                "dinero" to listOf("💰", "💸"),
                "trabajo" to listOf("💼", "💻"),
                "casa" to listOf("🏠", "🏡"),
                "coche" to listOf("🚗"),
                "auto" to listOf("🚗"),
                "avión" to listOf("✈️", "🛫"),
                "avion" to listOf("✈️", "🛫"),
                "playa" to listOf("🏖️", "🌊"),
                "libro" to listOf("📚", "📖"),
                "boda" to listOf("💍", "👰", "🤵"),
                "bebé" to listOf("👶", "🍼"),
                "bebe" to listOf("👶", "🍼"),
                "feliz" to listOf("😊", "🥳", "❤️"),
                "beso" to listOf("😘", "💋"),
                "hola" to listOf("👋", "😊"),
                "adiós" to listOf("👋", "😢"),
                "adios" to listOf("👋", "😢"),
                "perro" to listOf("🐶", "🐕"),
                "gato" to listOf("🐱", "🐈"),
                "cerveza" to listOf("🍺", "🍻"),
                "vino" to listOf("🍷"),
            )
    }
}
