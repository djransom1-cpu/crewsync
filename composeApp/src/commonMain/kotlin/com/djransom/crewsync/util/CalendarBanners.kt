package com.djransom.crewsync.util

import com.djransom.crewsync.data.model.SchedulePhase
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

// ---- Schedule phases on the site calendar ------------------------------------------------------
// Phases are drawn on the Calendar tab as thin all-day banners straight from the phase records -
// they are never copied into appointments, so there is nothing to keep in step when a phase moves.
// This file works out where each banner sits; CalendarScreen draws them. Pure, so it can be
// unit-tested (see CalendarBannersTest).

/** The phases running on [day], in schedule order. Phases with no dates aren't on the calendar. */
fun phasesOnDay(phases: List<SchedulePhase>, day: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault()): List<SchedulePhase> =
    phases.inScheduleOrder().filter { phase ->
        val start = phase.startDate(zone)
        val end = phase.endDate(zone)
        start != null && end != null && day >= start && day <= maxOf(start, end)
    }

/**
 * One phase's piece of a calendar row: it covers columns [startCol] through [endCol] (both
 * included) and sits in [lane], counting down from 0. [continuesLeft] / [continuesRight] say the
 * phase carries on past the row's edge - drawn as a square end rather than a rounded one.
 */
data class BannerSegment(
    val phase: SchedulePhase,
    val startCol: Int,
    val endCol: Int,
    val lane: Int,
    val continuesLeft: Boolean,
    val continuesRight: Boolean
)

/**
 * The banners for one row of the calendar. [cellDates] is the row's days left to right; a null is
 * a blank cell (a day of the next or previous month in the month grid), which banners don't cross.
 * Each phase running on any day of the row gets one segment, and takes the first lane that's free
 * across all its columns, phases going in schedule order - so the top lane is, as far as it can
 * be, the first phase in the schedule.
 */
fun rowBanners(phases: List<SchedulePhase>, cellDates: List<LocalDate?>, zone: TimeZone = TimeZone.currentSystemDefault()): List<BannerSegment> {
    val cols = cellDates.indices.filter { cellDates[it] != null }
    if (cols.isEmpty()) return emptyList()
    val firstCol = cols.first()
    val lastCol = cols.last()
    val rowFirst = cellDates[firstCol]!!
    val rowLast = cellDates[lastCol]!!
    val taken = mutableListOf<BooleanArray>() // lane -> which columns are already used
    val out = mutableListOf<BannerSegment>()
    for (phase in phases.inScheduleOrder()) {
        val start = phase.startDate(zone) ?: continue
        val end = maxOf(start, phase.endDate(zone) ?: continue)
        if (end < rowFirst || start > rowLast) continue
        val from = (firstCol..lastCol).first { cellDates[it]!! >= start }
        val to = (firstCol..lastCol).last { cellDates[it]!! <= end }
        val lane = taken.indexOfFirst { used -> (from..to).none { used[it] } }.let { free ->
            if (free >= 0) free else { taken += BooleanArray(cellDates.size); taken.lastIndex }
        }
        for (c in from..to) taken[lane][c] = true
        out += BannerSegment(phase, from, to, lane, continuesLeft = start < cellDates[from]!!, continuesRight = end > cellDates[to]!!)
    }
    return out
}

/** How many lanes a row's banners need. */
fun List<BannerSegment>.laneCount(): Int = (maxOfOrNull { it.lane } ?: -1) + 1
