package de.yurtemre.sonora.ui.format

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import de.yurtemre.sonora.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Total-duration parts per audit/data §10.2 (LIB-15). Null part = omitted. */
data class DurationParts(val days: Long?, val hours: Long?, val minutes: Long?, val seconds: Long)

object DurationFormat {
    /** Song `m:ss` (minutes unbounded, parity). */
    fun mss(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        return "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
    }

    /** Seek bar: `h:mm:ss` when ≥ 1 h else `m:ss` (FIX D-05). */
    fun clock(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(Locale.ROOT, s / 3600, (s / 60) % 60, s % 60) else mss(s)
    }

    fun clockMs(ms: Long): String = clock(ms / 1000)

    /** audit/data §10.2: d if >0, h if h%24>0, m if m%60>0 or (d==0&&h==0), always s. */
    fun totalParts(totalSeconds: Long): DurationParts {
        val s = totalSeconds.coerceAtLeast(0)
        val d = s / 86400; val h = (s / 3600) % 24; val m = (s / 60) % 60
        val totalH = s / 3600
        return DurationParts(
            days = d.takeIf { it > 0 },
            hours = h.takeIf { it > 0 },
            minutes = m.takeIf { it > 0 || (d == 0L && totalH == 0L) },
            seconds = s % 60,
        )
    }

    /** Localized total text, e.g. "1d 2h 3m 4s" / "1日 2時間 3分 4秒". */
    fun totalText(context: Context, totalSeconds: Long, locale: Locale): String {
        val p = totalParts(totalSeconds)
        val fmt = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.NARROW)
        fun f(v: Long, u: MeasureUnit) = fmt.format(Measure(v, u))
        return buildList {
            p.days?.let { add(f(it, MeasureUnit.DAY)) }
            p.hours?.let { add(f(it, MeasureUnit.HOUR)) }
            p.minutes?.let { add(f(it, MeasureUnit.MINUTE)) }
            add(context.getString(R.string.seconds_abbr, p.seconds.toInt()))
        }.joinToString(" ")
    }
}

object SizeFormat {
    /** Locale-aware file size (context locale). */
    fun fileSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
}

enum class RelativeDay { TODAY, YESTERDAY, THIS_WEEK, OLDER }

object DateFormats {
    private fun pattern(locale: Locale, skeleton: String) =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    fun date(instant: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(instant.atZone(zone))

    fun dateTime(instant: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(instant.atZone(zone))

    fun time(instant: Instant, locale: Locale, is24h: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
        pattern(locale, if (is24h) "Hm" else "hm").format(instant.atZone(zone))

    fun monthYear(date: LocalDate, locale: Locale): String = pattern(locale, "yMMMM").format(date)

    fun monthDay(date: LocalDate, locale: Locale): String = pattern(locale, "MMMd").format(date)

    fun weekdayName(day: DayOfWeek, locale: Locale, style: TextStyle = TextStyle.FULL): String =
        day.getDisplayName(style, locale)

    fun relativeDay(date: LocalDate, today: LocalDate): RelativeDay = when {
        date == today -> RelativeDay.TODAY
        date == today.minusDays(1) -> RelativeDay.YESTERDAY
        date.isAfter(today.minusDays(7)) && date.isBefore(today) -> RelativeDay.THIS_WEEK
        else -> RelativeDay.OLDER
    }

    /** Today / Yesterday (resources) / weekday name / localized date. */
    fun relativeDayLabel(context: Context, date: LocalDate, today: LocalDate, locale: Locale): String =
        when (relativeDay(date, today)) {
            RelativeDay.TODAY -> context.getString(R.string.today)
            RelativeDay.YESTERDAY -> context.getString(R.string.yesterday)
            RelativeDay.THIS_WEEK -> weekdayName(date.dayOfWeek, locale)
            RelativeDay.OLDER -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
        }
}

/** Current app locale (follows AppCompat per-app locale via configuration). */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
fun rememberTotalDurationText(totalSeconds: Long): String =
    DurationFormat.totalText(LocalContext.current, totalSeconds, currentLocale())

@Composable
fun fileSizeText(bytes: Long): String = SizeFormat.fileSize(LocalContext.current, bytes)

@Composable
fun relativeDayText(date: LocalDate, today: LocalDate = LocalDate.now()): String =
    DateFormats.relativeDayLabel(LocalContext.current, date, today, currentLocale())

@Composable
fun timeText(instant: Instant): String =
    DateFormats.time(instant, currentLocale(), DateFormat.is24HourFormat(LocalContext.current))

object RelativeTime {
    /** "just now" (< 1 min), "N minutes ago", "N hours ago", else localized date-time (app locale). */
    fun ago(context: Context, thenMs: Long, nowMs: Long = System.currentTimeMillis(), locale: Locale = context.appLocale()): String {
        val diff = (nowMs - thenMs).coerceAtLeast(0)
        val min = diff / 60_000
        val res = context.resources
        return when {
            min < 1 -> context.getString(R.string.a11y_just_now)
            min < 60 -> res.getQuantityString(R.plurals.a11y_minutes_ago, min.toInt(), min.toInt())
            min < 24 * 60 -> (min / 60).toInt().let { res.getQuantityString(R.plurals.a11y_hours_ago, it, it) }
            else -> DateFormats.dateTime(Instant.ofEpochMilli(thenMs), locale)
        }
    }
}

@Composable
fun relativeTimeText(thenMs: Long): String =
    RelativeTime.ago(LocalContext.current, thenMs, System.currentTimeMillis(), currentLocale())
