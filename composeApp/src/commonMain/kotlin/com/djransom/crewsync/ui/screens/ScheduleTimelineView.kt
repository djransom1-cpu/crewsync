package com.djransom.crewsync.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.djransom.crewsync.data.model.SchedulePhase
import com.djransom.crewsync.data.model.Task
import com.djransom.crewsync.i18n.I18n
import com.djransom.crewsync.i18n.monthDay
import com.djransom.crewsync.i18n.monthName
import com.djransom.crewsync.i18n.monthShort
import com.djransom.crewsync.i18n.t
import com.djransom.crewsync.util.ScheduleRow
import com.djransom.crewsync.util.TIMELINE_WEEKS_DAY_WIDTH
import com.djransom.crewsync.util.TimelineBar
import com.djransom.crewsync.util.TimelineFit
import com.djransom.crewsync.util.TimelineSpan
import com.djransom.crewsync.util.WorkCalendar
import com.djransom.crewsync.util.around
import com.djransom.crewsync.util.endDate
import com.djransom.crewsync.util.fit
import com.djransom.crewsync.util.lastPhaseEnd
import com.djransom.crewsync.util.linkedTasks
import com.djransom.crewsync.util.millisToLocalDate
import com.djransom.crewsync.util.percentShown
import com.djransom.crewsync.util.scheduleRows
import com.djransom.crewsync.util.scheduleToday
import com.djransom.crewsync.util.startDate
import com.djransom.crewsync.util.timelineSpan
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val TODAY_RED = Color(0xFFD32F2F)

/**
 * The timeline's sizes. [Standard] is what the Schedule tab uses; [presentation] is the
 * meeting-room version with taller rows, thicker bars, bigger lettering and stronger lines. The
 * name column on the left and the bars on the right are two separate pieces that scroll
 * together, so both read their heights from here and can't drift apart.
 */
@Immutable
class TimelineStyle(
    val rowHeight: Dp,
    val groupHeight: Dp,
    val monthBand: Dp,      // the header is three strips: month names...
    val weekBand: Dp,       // ...the week columns' dates...
    val markerStrip: Dp,    // ...and a thin one the today pointer sits in
    val barHeight: Dp,
    val ghostHeight: Dp,
    val nameWidth: Dp?,     // null: 240dp on a wide window, 132dp on a phone
    val nameSize: TextUnit,
    val groupSize: TextUnit,
    val monthSize: TextUnit,
    val weekSize: TextUnit,
    val trackAlpha: Float,  // how strongly the not-yet-done part of a bar is tinted
    val todayWidth: Dp,
    val strong: Boolean     // high contrast: darker grid lines, lettering and ghost bars
) {
    val headerHeight: Dp get() = monthBand + weekBand + markerStrip

    companion object {
        val Standard = TimelineStyle(
            rowHeight = 40.dp, groupHeight = 30.dp, monthBand = 24.dp, weekBand = 22.dp, markerStrip = 8.dp,
            barHeight = 18.dp, ghostHeight = 28.dp, nameWidth = null,
            nameSize = 14.sp, groupSize = 14.sp, monthSize = 14.sp, weekSize = 12.sp,
            trackAlpha = 0.30f, todayWidth = 2.dp, strong = false
        )

        /** Everything scaled from the row height, which presentation mode picks to fill the screen. */
        fun presentation(rowHeight: Dp, nameWidth: Dp): TimelineStyle {
            val lettering = (rowHeight.value * 0.42f).coerceIn(16f, 26f).sp
            return TimelineStyle(
                rowHeight = rowHeight, groupHeight = rowHeight * 0.72f, monthBand = 38.dp, weekBand = 32.dp, markerStrip = 12.dp,
                barHeight = rowHeight * 0.56f, ghostHeight = rowHeight * 0.84f, nameWidth = nameWidth,
                nameSize = lettering, groupSize = lettering, monthSize = 22.sp, weekSize = 17.sp,
                trackAlpha = 0.55f, todayWidth = 4.dp, strong = true
            )
        }
    }
}

/**
 * Where the timeline is looking: how wide a day is drawn and how far it's scrolled. Kept outside
 * [ScheduleTimeline] so the toolbar's zoom and Fit buttons can drive it, and so the position
 * survives a hop over to the list and back.
 */
@Stable
class ScheduleTimelineState {
    /** Width of one day, in dp. See TIMELINE_WEEKS_DAY_WIDTH / TIMELINE_MONTHS_DAY_WIDTH for the two presets. */
    var dayWidth by mutableFloatStateOf(TIMELINE_WEEKS_DAY_WIDTH)
        private set
    val horizontal = ScrollState(0)
    val vertical = ScrollState(0)

    // A scroll can't be applied the moment the zoom changes - the new width hasn't been laid out
    // yet, so it would be clamped to the old one. It waits here for the next frame instead.
    internal var pendingLeftDay by mutableStateOf<Int?>(null)
    internal var fitRequested by mutableStateOf(false)
    internal var aroundRequested by mutableStateOf<Pair<Int, Int>?>(null)
    internal var positioned = false
    internal var leftDayNow: () -> Int = { 0 }

    /** Change the zoom, keeping whatever day is at the left edge where it is. */
    fun zoom(dayWidthDp: Float) {
        pendingLeftDay = pendingLeftDay ?: leftDayNow()
        dayWidth = dayWidthDp
    }

    /** Frame today through the last phase. Carried out by the timeline, which knows how wide its window is. */
    fun fit() {
        positioned = true
        fitRequested = true
    }

    /** Frame [weeksBack] whole weeks before this week through [weeksAhead] after it - what presentation mode opens on. */
    fun showAroundToday(weeksBack: Int, weeksAhead: Int) {
        positioned = true
        aroundRequested = weeksBack to weeksAhead
    }

    internal fun apply(fit: TimelineFit) {
        dayWidth = fit.dayWidth
        pendingLeftDay = fit.firstDayOffset
    }
}

@Composable
fun rememberScheduleTimelineState(): ScheduleTimelineState = remember { ScheduleTimelineState() }

/**
 * The schedule as a Gantt chart: phase names down the left, the calendar across the top in week
 * columns, one bar per phase coloured by status with the done part filled in, a diamond for a
 * milestone, a faint outline behind a bar that has moved showing where the original plan (the
 * baseline) put it, and a red line for today.
 *
 * Everything to the right of the names is one Canvas. Scrolling just slides it - nothing is
 * redrawn or re-laid-out while it moves - which is what keeps it smooth with a few dozen phases
 * over most of a year.
 */
@Composable
fun ScheduleTimeline(
    phases: List<SchedulePhase>,
    tasks: List<Task>,
    calendar: WorkCalendar,
    state: ScheduleTimelineState,
    onPhaseClick: (SchedulePhase) -> Unit,
    modifier: Modifier = Modifier,
    style: TimelineStyle = TimelineStyle.Standard,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    today: LocalDate = scheduleToday(zone)
) {
    val span = remember(phases, today, zone) { timelineSpan(phases, today, zone) }
    if (span == null) {
        ScheduleMessage(t("No dates yet"), t("The timeline needs at least one phase with dates."), modifier)
        return
    }
    val rows = remember(phases) { scheduleRows(phases) }
    val tasksById = remember(tasks) { tasks.associateBy { it.id } }
    val bars = remember(rows, span, tasksById, zone) { rows.map { row -> (row as? ScheduleRow.PhaseLine)?.let { phaseBar(it.phase, span, tasksById, zone) } } }
    val working = remember(span, calendar) { BooleanArray(span.days) { calendar.isWorkingDay(span.first.plus(it, DateTimeUnit.DAY)) } }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val colors = TimelineColors(
        surface = scheme.surface,
        band = scheme.surfaceVariant,
        grid = if (style.strong) scheme.outline.copy(alpha = 0.55f) else scheme.outlineVariant,
        offDay = scheme.onSurface.copy(alpha = if (style.strong) 0.06f else 0.045f),
        ghost = scheme.onSurface,
        text = if (style.strong) scheme.onSurface else scheme.onSurfaceVariant
    )
    val monthStyle = MaterialTheme.typography.labelLarge.copy(color = scheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = style.monthSize, lineHeight = style.monthSize * 1.3f)
    val weekStyle = MaterialTheme.typography.labelMedium.copy(color = colors.text, fontSize = style.weekSize, lineHeight = style.weekSize * 1.3f)
    val language = I18n.current
    val headerLabels = remember(span, measurer, monthStyle, weekStyle, density, language) { TimelineHeaderLabels(span, measurer, monthStyle, weekStyle) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val nameWidth = style.nameWidth ?: if (maxWidth >= 720.dp) 240.dp else 132.dp
        val viewportWidth = (maxWidth - nameWidth).coerceAtLeast(1.dp)
        val viewportHeight = (maxHeight - style.headerHeight).coerceAtLeast(1.dp)
        // One Canvas can only be so many pixels wide; stay well inside that however far someone zooms.
        val widestDay = with(density) { (30_000f / span.days).toDp().value }
        val dayWidth = min(state.dayWidth, widestDay).dp
        val chartWidth = max((dayWidth * span.days).value, viewportWidth.value).dp
        val rowsHeight = rows.fold(0.dp) { sum, row -> sum + rowHeight(row, style) }
        val chartHeight = max(rowsHeight.value, viewportHeight.value).dp
        val dayPx = with(density) { dayWidth.toPx() }

        SideEffect { state.leftDayNow = { (state.horizontal.value / dayPx).toInt() } }

        // First time in: start a week before today rather than at the far left of the job.
        LaunchedEffect(Unit) {
            if (!state.positioned) {
                state.positioned = true
                if (state.pendingLeftDay == null && today in span) state.pendingLeftDay = (span.offsetOf(today) - 7).coerceAtLeast(0)
            }
        }
        LaunchedEffect(state.fitRequested) {
            if (state.fitRequested) {
                state.apply(span.fit(today, lastPhaseEnd(phases, zone), viewportWidth.value))
                state.fitRequested = false
            }
        }
        val around = state.aroundRequested
        LaunchedEffect(around) {
            if (around != null) {
                state.apply(span.around(today, around.first, around.second, viewportWidth.value))
                state.aroundRequested = null
            }
        }
        val pending = state.pendingLeftDay
        LaunchedEffect(pending, dayPx) {
            if (pending != null) {
                withFrameNanos { }   // let the new width be measured first
                state.horizontal.scrollTo((pending * dayPx).roundToInt())
                state.pendingLeftDay = null
            }
        }

        Column(Modifier.fillMaxSize()) {
            // ---- calendar header: months over weeks ----------------------------------------------
            Row(Modifier.fillMaxWidth().height(style.headerHeight)) {
                Box(Modifier.width(nameWidth).fillMaxHeight().background(colors.surface).drawBehind { drawEdges(colors.grid, right = true, bottom = true) })
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().horizontalScroll(state.horizontal)) {
                    Canvas(Modifier.size(chartWidth, style.headerHeight)) {
                        // Reading the scroll position here redraws just this strip as it moves, which is
                        // what lets a month's name ride along the left edge while that month is on screen.
                        drawTimelineHeader(span, headerLabels, dayPx, today, colors, style, scrollX = state.horizontal.value.toFloat())
                    }
                }
            }
            // ---- names on the left, bars on the right ---------------------------------------------
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier.width(nameWidth).fillMaxHeight().background(colors.surface)
                        .drawBehind { drawEdges(colors.grid, right = true) }
                        .verticalScroll(state.vertical)
                ) {
                    rows.forEach { row -> TimelineNameCell(row, colors, style, onPhaseClick) }
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight().clipToBounds()
                        .mouseDragToScroll(state.horizontal, state.vertical)
                        .verticalScroll(state.vertical)
                        .horizontalScroll(state.horizontal)
                ) {
                    Canvas(
                        Modifier.size(chartWidth, chartHeight).pointerInput(rows, bars, dayPx, style) {
                            detectTapGestures { tap ->
                                phaseAt(tap, rows, bars, dayPx, slop = 10.dp.toPx(), rowHeightPx = { rowHeight(it, style).toPx() })?.let(onPhaseClick)
                            }
                        }
                    ) {
                        drawTimelineBody(span, rows, bars, working, dayPx, dayWidth, today, colors, style)
                    }
                }
            }
        }
    }
}

// ---- pieces ------------------------------------------------------------------------------------

private class TimelineColors(val surface: Color, val band: Color, val grid: Color, val offDay: Color, val ghost: Color, val text: Color)

/** Everything needed to draw one phase's row, worked out once rather than on every frame. */
private class PhaseBarDraw(val current: TimelineBar?, val baseline: TimelineBar?, val isMilestone: Boolean, val color: Color, val percent: Int)

private fun phaseBar(phase: SchedulePhase, span: TimelineSpan, tasksById: Map<String, Task>, zone: TimeZone): PhaseBarDraw {
    val current = span.bar(phase.startDate(zone), phase.endDate(zone))
    val baseline = span.bar(phase.baselineStart?.let { millisToLocalDate(it, zone) }, phase.baselineEnd?.let { millisToLocalDate(it, zone) })
    return PhaseBarDraw(
        current = current,
        // The ghost is only worth drawing where the phase has moved off its plan; on plan, it would sit exactly under the bar.
        baseline = baseline.takeIf { it != current },
        isMilestone = phase.isMilestone,
        color = phaseStatusColor(phase.status),
        percent = phase.percentShown(phase.linkedTasks(tasksById)).coerceIn(0, 100)
    )
}

/**
 * The header's wording, measured once. It doesn't change as the timeline scrolls or zooms - only
 * which form of each label fits - so nothing has to be measured while it's moving.
 */
private class TimelineHeaderLabels(span: TimelineSpan, measurer: TextMeasurer, monthStyle: TextStyle, weekStyle: TextStyle) {
    val months = span.months
    val weeks = span.weeks
    val monthLong: List<TextLayoutResult> = months.map { measurer.measure("${monthName(it.month)} ${it.year}", monthStyle, maxLines = 1) }
    val monthMedium: List<TextLayoutResult> = months.map { measurer.measure("${monthShort(it.month)} ${it.year}", monthStyle, maxLines = 1) }
    val monthBrief: List<TextLayoutResult> = months.map { measurer.measure(monthShort(it.month), monthStyle, maxLines = 1) }
    val weekLong: List<TextLayoutResult> = weeks.map { measurer.measure(monthDay(it.monthNumber, it.dayOfMonth), weekStyle, maxLines = 1) }
    val weekDayOnly: List<TextLayoutResult> = weeks.map { measurer.measure(it.dayOfMonth.toString(), weekStyle, maxLines = 1) }
    // Every week column gets the same form of label, so it's the widest one that has to fit.
    val weekLongWidth: Int = weekLong.maxOfOrNull { it.size.width } ?: 0
    val weekDayOnlyWidth: Int = weekDayOnly.maxOfOrNull { it.size.width } ?: 0
}

private fun rowHeight(row: ScheduleRow, style: TimelineStyle): Dp = if (row is ScheduleRow.GroupHead) style.groupHeight else style.rowHeight

@Composable
private fun TimelineNameCell(row: ScheduleRow, colors: TimelineColors, style: TimelineStyle, onPhaseClick: (SchedulePhase) -> Unit) {
    val side = if (style.strong) 20.dp else 12.dp
    when (row) {
        is ScheduleRow.GroupHead -> Box(
            Modifier.fillMaxWidth().height(style.groupHeight).background(colors.band).padding(horizontal = side),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                row.name.ifEmpty { t("Other") },
                style = MaterialTheme.typography.titleSmall.copy(fontSize = style.groupSize, lineHeight = style.groupSize * 1.2f),
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        is ScheduleRow.PhaseLine -> Row(
            Modifier.fillMaxWidth().height(style.rowHeight)
                .drawBehind { drawEdges(colors.grid.copy(alpha = 0.5f), bottom = true) }
                .clickable { onPhaseClick(row.phase) }
                .padding(horizontal = side),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (row.phase.isMilestone) {
                Box(Modifier.padding(end = 8.dp).size((style.nameSize.value * 0.65f).dp).rotate(45f).background(phaseStatusColor(row.phase.status)))
            }
            Text(
                row.phase.name.ifBlank { t("(unnamed)") },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = style.nameSize, lineHeight = style.nameSize * 1.15f),
                fontWeight = if (style.strong) FontWeight.Medium else null,
                // Two lines while the row is tall enough to hold them; otherwise one, cut off with an ellipsis.
                maxLines = if (style.rowHeight.value >= style.nameSize.value * 2.6f) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun DrawScope.drawEdges(color: Color, right: Boolean = false, bottom: Boolean = false) {
    val w = 1.dp.toPx()
    if (right) drawRect(color, Offset(size.width - w, 0f), Size(w, size.height))
    if (bottom) drawRect(color, Offset(0f, size.height - w), Size(size.width, w))
}

private fun DrawScope.drawTimelineHeader(
    span: TimelineSpan, labels: TimelineHeaderLabels, dayPx: Float, today: LocalDate, colors: TimelineColors, style: TimelineStyle, scrollX: Float
) {
    val monthBand = style.monthBand.toPx()
    val weekBand = style.weekBand.toPx()
    val line = 1.dp.toPx()
    val pad = (if (style.strong) 10.dp else 6.dp).toPx()
    drawRect(colors.surface)
    // Months along the top: the full name while there's room, shorter as the timeline is squeezed.
    labels.months.forEachIndexed { i, m ->
        val x = m.offsetDays * dayPx
        val width = m.lengthDays * dayPx
        drawRect(colors.grid, Offset(x, 0f), Size(line, size.height))
        val room = width - 2 * pad
        val label = when {
            labels.monthLong[i].size.width <= room -> labels.monthLong[i]
            labels.monthMedium[i].size.width <= room -> labels.monthMedium[i]
            labels.monthBrief[i].size.width <= room -> labels.monthBrief[i]
            else -> return@forEachIndexed
        }
        // The name stays at the left edge of the window for as long as any of its month is showing.
        val left = max(x, scrollX).coerceAtMost(x + width - label.size.width - 2 * pad)
        drawText(label, topLeft = Offset(left + pad, (monthBand - label.size.height) / 2))
    }
    drawRect(colors.grid.copy(alpha = 0.6f), Offset(0f, monthBand), Size(size.width, line))
    // Weeks underneath: a tick at each Monday, labelled with its date while the columns are wide enough.
    val weekPx = dayPx * 7
    if (weekPx >= 12.dp.toPx()) {
        val form = when {
            labels.weekLongWidth + 2 * pad <= weekPx -> labels.weekLong
            labels.weekDayOnlyWidth + 1.5f * pad <= weekPx -> labels.weekDayOnly
            else -> null
        }
        labels.weeks.forEachIndexed { i, monday ->
            val x = span.offsetOf(monday) * dayPx
            drawRect(colors.grid.copy(alpha = 0.6f), Offset(x, monthBand), Size(line, size.height - monthBand))
            if (form != null) drawText(form[i], topLeft = Offset(x + pad, monthBand + (weekBand - form[i].size.height) / 2))
        }
    }
    drawRect(colors.grid, Offset(0f, size.height - line), Size(size.width, line))
    // Today: a red pointer in the strip under the dates, sitting on top of the line that runs down the chart.
    if (today in span) {
        val x = (span.offsetOf(today) + 0.5f) * dayPx
        val top = monthBand + weekBand
        val half = style.markerStrip.toPx() * 0.7f
        val pointer = Path().apply { moveTo(x - half, top); lineTo(x + half, top); lineTo(x, size.height); close() }
        drawPath(pointer, TODAY_RED)
    }
}

private fun DrawScope.drawTimelineBody(
    span: TimelineSpan, rows: List<ScheduleRow>, bars: List<PhaseBarDraw?>, working: BooleanArray,
    dayPx: Float, dayWidth: Dp, today: LocalDate, colors: TimelineColors, style: TimelineStyle
) {
    val line = 1.dp.toPx()
    // Weekends and holidays, shaded while a day is wide enough to tell apart.
    if (dayWidth >= 5.dp) {
        var i = 0
        while (i < working.size) {
            if (working[i]) { i++; continue }
            var j = i
            while (j < working.size && !working[j]) j++
            drawRect(colors.offDay, Offset(i * dayPx, 0f), Size((j - i) * dayPx, size.height))
            i = j
        }
    }
    // Week lines (when weeks are wide enough to matter), with a stronger line at each month.
    if (dayPx * 7 >= 22.dp.toPx()) {
        span.weeks.forEach { monday -> drawRect(colors.grid.copy(alpha = 0.45f), Offset(span.offsetOf(monday) * dayPx, 0f), Size(line, size.height)) }
    }
    span.months.forEach { m -> drawRect(colors.grid, Offset(m.offsetDays * dayPx, 0f), Size(line, size.height)) }

    val rowH = style.rowHeight.toPx()
    val groupH = style.groupHeight.toPx()
    var y = 0f
    rows.forEachIndexed { index, row ->
        if (row is ScheduleRow.GroupHead) {
            drawRect(colors.band, Offset(0f, y), Size(size.width, groupH))
            y += groupH
        } else {
            bars[index]?.let { drawPhaseBar(it, dayPx, centerY = y + rowH / 2, colors, style) }
            drawRect(colors.grid.copy(alpha = 0.5f), Offset(0f, y + rowH - line), Size(size.width, line))
            y += rowH
        }
    }
    if (today in span) {
        val x = (span.offsetOf(today) + 0.5f) * dayPx
        val w = style.todayWidth.toPx()
        drawRect(TODAY_RED, Offset(x - w / 2, 0f), Size(w, size.height))
    }
}

/**
 * One phase: the baseline as a faint outline that stands a little taller than the bar (so it
 * shows above and below even when the bar covers it end to end), then the bar itself in its
 * status colour - pale for the whole length, solid for the part that's done.
 */
private fun DrawScope.drawPhaseBar(bar: PhaseBarDraw, dayPx: Float, centerY: Float, colors: TimelineColors, style: TimelineStyle) {
    val ghostFill = colors.ghost.copy(alpha = if (style.strong) 0.14f else 0.10f)
    val ghostEdge = colors.ghost.copy(alpha = if (style.strong) 0.70f else 0.46f)
    val ghostStroke = Stroke(width = (if (style.strong) 2.dp else 1.dp).toPx())
    if (bar.isMilestone) {
        bar.baseline?.let { drawDiamond(Offset((it.offsetDays + 0.5f) * dayPx, centerY), style.ghostHeight.toPx() * 0.43f, ghostFill, ghostEdge, ghostStroke) }
        bar.current?.let { drawDiamond(Offset((it.offsetDays + 0.5f) * dayPx, centerY), style.barHeight.toPx() * 0.47f, bar.color, null, ghostStroke) }
        return
    }
    val minWidth = 3.dp.toPx()
    bar.baseline?.let { b ->
        val h = style.ghostHeight.toPx()
        val topLeft = Offset(b.offsetDays * dayPx, centerY - h / 2)
        val ghostSize = Size(max(b.lengthDays * dayPx, minWidth), h)
        val corner = CornerRadius(h * 0.25f)
        drawRoundRect(ghostFill, topLeft, ghostSize, corner)
        drawRoundRect(ghostEdge, topLeft, ghostSize, corner, style = ghostStroke)
    }
    bar.current?.let { c ->
        val h = style.barHeight.toPx()
        val left = c.offsetDays * dayPx
        val width = max(c.lengthDays * dayPx, minWidth)
        val rect = Rect(left, centerY - h / 2, left + width, centerY + h / 2)
        val shape = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(h * 0.28f))) }
        clipPath(shape) {
            drawRect(colors.surface, rect.topLeft, rect.size)                       // so the baseline doesn't show through the pale part
            drawRect(bar.color.copy(alpha = style.trackAlpha), rect.topLeft, rect.size)
            if (bar.percent > 0) drawRect(bar.color, rect.topLeft, Size(width * bar.percent / 100f, h))
        }
    }
}

private fun DrawScope.drawDiamond(center: Offset, half: Float, fill: Color, outline: Color?, stroke: Stroke) {
    val path = Path().apply {
        moveTo(center.x, center.y - half); lineTo(center.x + half, center.y)
        lineTo(center.x, center.y + half); lineTo(center.x - half, center.y); close()
    }
    drawPath(path, fill)
    if (outline != null) drawPath(path, outline, style = stroke)
}

/** The phase whose bar (or milestone) is under a tap, allowing a fingertip of slop either side. Null for a tap on empty calendar. */
private fun phaseAt(
    tap: Offset, rows: List<ScheduleRow>, bars: List<PhaseBarDraw?>, dayPx: Float, slop: Float, rowHeightPx: (ScheduleRow) -> Float
): SchedulePhase? {
    var y = 0f
    rows.forEachIndexed { index, row ->
        val h = rowHeightPx(row)
        if (tap.y >= y && tap.y < y + h) {
            val phase = (row as? ScheduleRow.PhaseLine)?.phase ?: return null
            val bar = bars[index] ?: return null
            val spans = listOfNotNull(bar.current, bar.baseline)
            val hit = spans.any { tap.x >= it.offsetDays * dayPx - slop && tap.x <= (it.offsetDays + it.lengthDays) * dayPx + slop }
            return if (hit) phase else null
        }
        y += h
    }
    return null
}

/**
 * Lets a mouse drag the chart around, the way a finger does. A mouse wheel only scrolls up and
 * down (Shift+wheel for sideways), which is awkward on a laptop driving the meeting-room screen.
 * Touch is left alone - the scroll modifiers already handle it, with a proper fling.
 */
private fun Modifier.mouseDragToScroll(horizontal: ScrollState, vertical: ScrollState): Modifier = pointerInput(horizontal, vertical) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type != PointerType.Mouse) return@awaitEachGesture
        val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
        drag(start.id) { change ->
            val moved = change.positionChange()
            horizontal.dispatchRawDelta(-moved.x)
            vertical.dispatchRawDelta(-moved.y)
            change.consume()
        }
    }
}
