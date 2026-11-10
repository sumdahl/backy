package global.ptg.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}

fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
}

fun formatCount(n: Int): String = NumberFormat.getIntegerInstance().format(n)

fun plural(n: Int, one: String, many: String = one + "s") = "${formatCount(n)} ${if (n == 1) one else many}"

private val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun dayLabel(epochMillis: Long): String {
    val d = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> if (d.year == today.year)
            d.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
        else d.format(dayFormat)
    }
}

fun dayKey(epochMillis: Long): Long = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
