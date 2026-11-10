package global.ptg.ui

import java.text.Normalizer

/**
 * One matcher for every search in the app. Case and accents don't matter, `_ - .` count as
 * spaces (so "img 2025" finds IMG_20250101.jpg), and every word typed must appear somewhere
 * in the fields, in any order ("trip panch" finds "Panchpokhari Trip Views Only").
 */
class Query(text: String) {
    private val words = normalize(text).split(' ').filter { it.isNotEmpty() }
    val isEmpty get() = words.isEmpty()

    fun matches(vararg fields: String): Boolean {
        if (words.isEmpty()) return true
        val hay = fields.joinToString(" ") { normalize(it) }
        return words.all { it in hay }
    }

    companion object {
        private val marks = Regex("\\p{Mn}+")
        private val separators = Regex("[\\s_.\\-]+")
        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase().replace(separators, " ").trim()
    }
}
