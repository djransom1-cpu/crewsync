package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.djransom.crewsync.data.model.PhaseStatus
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.i18n.t
import com.djransom.crewsync.util.BannerSegment
import com.djransom.crewsync.util.endDate
import com.djransom.crewsync.util.formatPhaseDates
import com.djransom.crewsync.util.isDarkColor
import com.djransom.crewsync.util.laneCount
import com.djransom.crewsync.util.startDate
import kotlinx.datetime.TimeZone

// ---- Schedule phases on the site calendar ------------------------------------------------------
// Thin all-day banners drawn straight from the phase records (see CalendarBanners.kt for where
// each one goes). They're never stored as appointments, and tapping one does what tapping that
// day does - so they can't get in the way of the calendar's own controls.

/** Height of one lane of banners, the gap under it included. */
val PHASE_BANNER_LANE: Dp = 13.dp

/** A banner's colour: the phase's own colour if it has one, otherwise its status colour. A finished phase is drawn paler. */
fun phaseBannerColor(phase: SchedulePhase): Color {
    val base = phase.color.takeIf { it.isNotBlank() }?.let { Color(parseColor(it)) } ?: phaseStatusColor(phase.status)
    return if (phase.status == PhaseStatus.DONE) base.copy(alpha = 0.55f) else base
}

/** How tall a row's banner strip is when at most [maxLanes] lanes are shown. */
fun bannerStripHeight(segments: List<BannerSegment>, maxLanes: Int): Dp = PHASE_BANNER_LANE * minOf(segments.laneCount(), maxLanes)

/**
 * One row's banners, laid over a row of [columns] equal-width cells. At most [maxLanes] lanes are
 * drawn; if there are more, the last lane says how many phases aren't shown (the day's list
 * underneath has them all).
 */
@Composable
fun PhaseBannerStrip(segments: List<BannerSegment>, columns: Int, maxLanes: Int, modifier: Modifier = Modifier, textSize: TextUnit = 9.sp) {
    if (segments.isEmpty()) return
    val lanes = segments.laneCount()
    val overflow = lanes > maxLanes
    val drawnLanes = if (overflow) maxLanes - 1 else lanes
    BoxWithConstraints(modifier.fillMaxWidth().height(PHASE_BANNER_LANE * minOf(lanes, maxLanes))) {
        val colWidth = maxWidth / columns
        segments.filter { it.lane < drawnLanes }.forEach { s ->
            val color = phaseBannerColor(s.phase)
            // Square ends where the phase carries on past the row; rounded where it really starts or stops.
            val shape = RoundedCornerShape(
                topStart = if (s.continuesLeft) 0.dp else 4.dp, bottomStart = if (s.continuesLeft) 0.dp else 4.dp,
                topEnd = if (s.continuesRight) 0.dp else 4.dp, bottomEnd = if (s.continuesRight) 0.dp else 4.dp
            )
            Box(
                Modifier
                    .offset(x = colWidth * s.startCol + (if (s.continuesLeft) 0.dp else 1.dp), y = PHASE_BANNER_LANE * s.lane)
                    .width(colWidth * (s.endCol - s.startCol + 1) - (if (s.continuesLeft) 0.dp else 1.dp) - (if (s.continuesRight) 0.dp else 1.dp))
                    .height(PHASE_BANNER_LANE - 1.dp)
                    .clip(shape)
                    .background(color),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    s.phase.name.ifBlank { t("(unnamed)") },
                    color = if (isDarkColor(color.copy(alpha = 1f))) Color.White else Color.Black,
                    fontSize = textSize, lineHeight = textSize, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(horizontal = 3.dp)
                )
            }
        }
        if (overflow) {
            // Under each day, how many of that day's phases aren't drawn.
            for (col in 0 until columns) {
                val hidden = segments.count { it.lane >= drawnLanes && col in it.startCol..it.endCol }
                if (hidden > 0) {
                    Text(
                        t("+{0} more", hidden),
                        fontSize = textSize, lineHeight = textSize, color = MaterialTheme.colorScheme.primary, maxLines = 1,
                        modifier = Modifier.offset(x = colWidth * col, y = PHASE_BANNER_LANE * drawnLanes).width(colWidth).padding(start = 3.dp)
                    )
                }
            }
        }
    }
}

/**
 * The phases running on one day, as full-width bars with their dates - for the Day view and the
 * list under the calendar. [onClick] opens the phase's card; null leaves the bars as they are.
 */
@Composable
fun PhaseDayBars(phases: List<SchedulePhase>, zone: TimeZone, onClick: ((SchedulePhase) -> Unit)?, modifier: Modifier = Modifier) {
    if (phases.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        phases.forEach { phase ->
            val color = phaseBannerColor(phase)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(color)
                    .then(if (onClick != null) Modifier.clickable { onClick(phase) } else Modifier)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val ink = if (isDarkColor(color.copy(alpha = 1f))) Color.White else Color.Black
                Text(phase.name.ifBlank { t("(unnamed)") }, color = ink, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(formatPhaseDates(phase.startDate(zone), phase.endDate(zone)), color = ink.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
