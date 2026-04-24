package com.example.mob_dev_portfolio.data.medication

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure next-fire calculator shared by the scheduler (when arming an
 * alarm) and the list screen (when labelling each reminder). Kept
 * fully deterministic for a given `now` + zone so it's straightforward
 * to unit-test without a live clock.
 */
object NextFireCalculator {

    /**
     * Returns the next fire instant strictly *after* [now], or `null`
     * when the reminder has no future occurrence (one-off in the past,
     * or a weekly mask of 0). The scheduler treats null as "cancel any
     * pending alarm for this id and don't rearm".
     */
    fun nextFire(
        reminder: MedicationReminder,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant? {
        if (!reminder.enabled) return null
        return when (val freq = reminder.frequency) {
            ReminderFrequency.Daily -> nextDaily(reminder.timeOfDayMinutes, now, zone)
            is ReminderFrequency.WeeklyDays -> nextWeekly(
                timeOfDayMinutes = reminder.timeOfDayMinutes,
                daysMask = freq.daysMask,
                now = now,
                zone = zone,
            )
            is ReminderFrequency.OneOff -> {
                val instant = Instant.ofEpochMilli(freq.atEpochMillis)
                if (instant.isAfter(now)) instant else null
            }
        }
    }

    private fun nextDaily(timeOfDayMinutes: Int, now: Instant, zone: ZoneId): Instant {
        val zoned = now.atZone(zone)
        val time = LocalTime.of(timeOfDayMinutes / 60, timeOfDayMinutes % 60)
        val todayFire = zoned.toLocalDate().atTime(time).atZone(zone).toInstant()
        // `isAfter` (not `isBefore`) — if fire time == now we still want
        // to push to tomorrow, because the alarm wouldn't be delivered
        // meaningfully and we'd burn a wake-up on nothing.
        return if (todayFire.isAfter(now)) todayFire
        else zoned.toLocalDate().plusDays(1).atTime(time).atZone(zone).toInstant()
    }

    private fun nextWeekly(
        timeOfDayMinutes: Int,
        daysMask: Int,
        now: Instant,
        zone: ZoneId,
    ): Instant? {
        if (daysMask == 0) return null
        val zoned = now.atZone(zone)
        val time = LocalTime.of(timeOfDayMinutes / 60, timeOfDayMinutes % 60)
        // Walk the next 7 days + today, returning the first one that's
        // both (a) enabled in the mask, and (b) strictly in the future.
        for (offset in 0..7) {
            val candidate = zoned.toLocalDate().plusDays(offset.toLong())
            val maskBit = 1 shl (candidate.dayOfWeek.value - 1) // Mon=1 → bit 0
            if ((daysMask and maskBit) == 0) continue
            val fire = candidate.atTime(time).atZone(zone).toInstant()
            if (fire.isAfter(now)) return fire
        }
        return null
    }

    /** Formatter-friendly label for a days-of-week mask. */
    fun maskToLabel(mask: Int): String {
        if (mask == 0) return "Never"
        if (mask == ReminderFrequency.MASK_ALL_DAYS) return "Every day"
        val labels = DayOfWeek.values().mapNotNull { dow ->
            val bit = 1 shl (dow.value - 1)
            if ((mask and bit) != 0) dow.short() else null
        }
        return labels.joinToString(", ")
    }

    private fun DayOfWeek.short(): String = when (this) {
        DayOfWeek.MONDAY -> "Mon"
        DayOfWeek.TUESDAY -> "Tue"
        DayOfWeek.WEDNESDAY -> "Wed"
        DayOfWeek.THURSDAY -> "Thu"
        DayOfWeek.FRIDAY -> "Fri"
        DayOfWeek.SATURDAY -> "Sat"
        DayOfWeek.SUNDAY -> "Sun"
    }

    fun LocalDate.toStartOfDayInstant(zone: ZoneId): Instant =
        atStartOfDay(zone).toInstant()
}
