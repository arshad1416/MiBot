package app.mibot.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/* How times are written, the same as in the web app (web/src/lib/time.ts). */

object TimeText {
    private val clockFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
    private val dayYearFormat = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

    private fun at(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone)

    /** "3:56 PM" */
    fun clock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = clockFormat.format(at(ms, zone))

    /** "Today 9:02 AM", "Yesterday 11:24 PM", "Fri, Nov 6 3:56 PM": the line between stretches of a conversation. */
    fun divider(ms: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val day = at(ms, zone).toLocalDate()
        val today = at(now, zone).toLocalDate()
        val date = when {
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            day.year == today.year -> dayFormat.format(day)
            else -> dayYearFormat.format(day)
        }
        return "$date ${clock(ms, zone)}"
    }

    /** When a bot was last active, for the home screen: "5:34 PM" today, then "Yesterday", "Mon", "Nov 3". */
    fun listTime(ms: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        if (ms <= 0) return ""
        val day = at(ms, zone).toLocalDate()
        val today: LocalDate = at(now, zone).toLocalDate()
        return when {
            day == today -> clock(ms, zone)
            day == today.minusDays(1) -> "Yesterday"
            day.isAfter(today.minusDays(7)) -> DateTimeFormatter.ofPattern("EEE", Locale.US).format(day)
            day.year == today.year -> DateTimeFormatter.ofPattern("MMM d", Locale.US).format(day)
            else -> DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).format(day)
        }
    }

    /** "now", "12m", "3h", "Yesterday", "5d" */
    fun relative(ms: Long, now: Long = System.currentTimeMillis()): String {
        val s = (now - ms) / 1000
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            s < 172_800 -> "Yesterday"
            else -> "${s / 86_400}d"
        }
    }

    /** A length of time with seconds: "8s", "1m 23s", "1h 4m". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        if (s < 60) return "${s}s"
        val m = s / 60
        return if (m < 60) "${m}m ${s % 60}s" else "${m / 60}h ${m % 60}m"
    }

    /** A call's length: "00:07", "12:40". */
    fun callLength(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        // Western digits, like every other number the app shows, whatever language the phone is in.
        return "%02d:%02d".format(Locale.ROOT, s / 60, s % 60)
    }

    /** A file's size: "812 B", "4.2 MB". */
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(Locale.ROOT, bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
    }
}
