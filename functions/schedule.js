/**
 * Schedule phases - the server-side twin of the app's SchedulePhase.kt, ScheduleDates.kt and the
 * phase section of FirestoreUtils.kt. Keep the three in step: the app and the connector read and
 * write the same documents at projects/{projectId}/schedulePhases/{id}.
 *
 * Everything here is plain data in, plain data out - no Firestore, no clock, no time zone - so it
 * can be tested directly (see test/mcp.test.js) and reused by later scheduled functions. mcp.js
 * owns the Firestore access and the conversion between stored dates and calendar days.
 *
 * A calendar day is handled as a "day number": whole days since 1970-01-01. Phase dates are
 * STORED as epoch milliseconds at local midnight, the way task dates are; mcp.js converts at the
 * edges (msToDay / dayToMs) and all the math in between is done in day numbers.
 *
 * Links to tasks and appointments live on the phase (taskIds, appointmentIds), not as a phaseId on
 * the task or appointment - both the app and the connector save a task by rewriting the whole
 * document, which would silently erase a field an older build does not know about.
 */

const DAY_MS = 86400000;
// Longest run of non-working days to walk across before giving up, so bad data can never hang.
const MAX_SCAN_DAYS = 400;

const STATUSES = ["Not started", "In progress", "Done", "On hold"];
const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const DEFAULT_WORK_WEEK = [1, 2, 3, 4, 5];

const squash = (s) => String(s ?? "").trim().toLowerCase().replace(/\s+/g, " ");

/** The stored status that matches `raw` ignoring case and spacing, or undefined if it is not a status. */
function matchStatus(raw) {
  const key = squash(raw);
  return STATUSES.find((s) => s.toLowerCase() === key);
}
/** Like PhaseStatus.normalize: anything unknown reads as "Not started". */
const normalizeStatus = (raw) => matchStatus(raw) || STATUSES[0];
const normalizeType = (raw) => (String(raw ?? "").trim().toUpperCase() === "SS" ? "SS" : "FS");
const normalizeMode = (raw) => (squash(raw) === "manual" ? "manual" : "auto");

// ---------------------------------------------------------------------------------------------
// Calendar days
// ---------------------------------------------------------------------------------------------

const dayNum = (y, mo, d) => Math.round(Date.UTC(y, mo - 1, d) / DAY_MS);
const dayToIso = (n) => new Date(n * DAY_MS).toISOString().slice(0, 10);

/** "YYYY-MM-DD" to a day number, or null when it is not a real date (2026-02-31, "next week"). */
function isoToDay(s) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(s ?? "").trim());
  if (!m) return null;
  const n = dayNum(+m[1], +m[2], +m[3]);
  return dayToIso(n) === m[0] ? n : null;
}

/** 1 = Monday ... 7 = Sunday (day 0, 1970-01-01, was a Thursday). */
const isoWeekday = (n) => ((((n + 3) % 7) + 7) % 7) + 1;

/** "Fri 2026-12-04" - the same shape fmtDate() gives for task and appointment dates. */
const fmtDay = (n) => (n === null || n === undefined ? undefined : `${WEEKDAYS[isoWeekday(n) - 1]} ${dayToIso(n)}`);

/**
 * Which days count as working days on a project: its working week minus its holidays
 * (Project.workWeek as ISO day numbers, Project.scheduleHolidays as "YYYY-MM-DD"). Monday to
 * Friday with no holidays when neither is set. Mirrors WorkCalendar in ScheduleDates.kt.
 */
function workCalendar(project = {}) {
  let week = (Array.isArray(project.workWeek) ? project.workWeek : []).map(Number).filter((n) => Number.isInteger(n) && n >= 1 && n <= 7);
  if (!week.length) week = DEFAULT_WORK_WEEK;
  const weekSet = new Set(week);
  const holidays = new Set((Array.isArray(project.scheduleHolidays) ? project.scheduleHolidays : []).map(isoToDay).filter((n) => n !== null));

  const isWorkingDay = (n) => weekSet.has(isoWeekday(n)) && !holidays.has(n);
  /** The day itself if it is a working day, otherwise the next one. */
  const onOrAfter = (n) => { let d = n, guard = 0; while (!isWorkingDay(d) && guard++ < MAX_SCAN_DAYS) d++; return d; };
  /** The day itself if it is a working day, otherwise the last one before it. */
  const onOrBefore = (n) => { let d = n, guard = 0; while (!isWorkingDay(d) && guard++ < MAX_SCAN_DAYS) d--; return d; };
  /** Friday + 1 is Monday; Monday - 1 is Friday; + 0 is the day itself (or the next working day). */
  const addWorkingDays = (n, k) => {
    if (k === 0) return onOrAfter(n);
    let d = n;
    for (let i = 0; i < Math.abs(k); i++) d = k > 0 ? onOrAfter(d + 1) : onOrBefore(d - 1);
    return d;
  };
  const count = (first, last) => { let c = 0; for (let d = first; d <= last; d++) if (isWorkingDay(d)) c++; return c; };
  /** Working days to step from one day to another; negative going back. This is the slip measure. */
  const workingDaysBetween = (from, to) => (to === from ? 0 : to > from ? count(from + 1, to) : -count(to, from - 1));
  /** A phase's length: working days from start through end, both included. */
  const durationDays = (start, end) => count(start, end);
  /** Last working day of a phase that starts on `start` and runs `days` working days. */
  const endForDuration = (start, days) => addWorkingDays(onOrAfter(start), Math.max(1, days) - 1);
  /** First working day of a phase that ends on `end` and runs `days` working days. */
  const startForDuration = (end, days) => addWorkingDays(onOrBefore(end), 1 - Math.max(1, days));
  /**
   * The earliest day a phase may start given one predecessor: FS, the working day after the
   * predecessor's last day plus the lag (lag -1 starts on that last day); SS, the predecessor's
   * first day plus the lag. Same as successorStart in ScheduleDates.kt.
   */
  const successorStart = (predStart, predEnd, type, lagDays) =>
    (normalizeType(type) === "SS" ? addWorkingDays(onOrAfter(predStart), lagDays || 0) : addWorkingDays(onOrBefore(predEnd), 1 + (lagDays || 0)));

  return {
    workWeek: [...weekSet].sort((a, b) => a - b),
    holidays: [...holidays].sort((a, b) => a - b).map(dayToIso),
    isWorkingDay, onOrAfter, onOrBefore, addWorkingDays, workingDaysBetween, durationDays, endForDuration, startForDuration, successorStart,
  };
}

// ---------------------------------------------------------------------------------------------
// Reading and writing a phase document
// ---------------------------------------------------------------------------------------------

function numberOrNull(v) {
  if (typeof v === "number") return Number.isFinite(v) ? v : null;
  if (typeof v === "string" && v.trim() !== "") { const n = Number(v.trim()); return Number.isFinite(n) ? n : null; }
  return null;
}

/**
 * A phase from whatever is stored, tolerating anything missing or of the wrong type - the same
 * rules as schedulePhaseFromFields() + normalized() in FirestoreUtils.kt. The document id wins
 * over any "id" field inside the document.
 */
function normalizePhase(id, data) {
  const d = data && typeof data === "object" ? data : {};
  const str = (k) => (typeof d[k] === "string" ? d[k] : "");
  const whole = (k) => { const n = numberOrNull(d[k]); return n === null ? null : Math.trunc(n); };
  const ids = (k) => [...new Set((Array.isArray(d[k]) ? d[k] : []).filter((x) => typeof x === "string" && x.trim() !== ""))];
  const seen = new Set();
  const predecessors = (Array.isArray(d.predecessors) ? d.predecessors : [])
    .filter((x) => x && typeof x === "object" && !Array.isArray(x))
    .map((x) => ({ phaseId: typeof x.phaseId === "string" ? x.phaseId : "", type: normalizeType(x.type), lagDays: Math.trunc(numberOrNull(x.lagDays) ?? 0) }))
    .filter((x) => {
      if (x.phaseId.trim() === "" || x.phaseId === id || seen.has(x.phaseId)) return false;
      seen.add(x.phaseId);
      return true;
    });
  return {
    id,
    projectId: str("projectId"),
    environmentId: str("environmentId"),
    name: str("name").trim(),
    group: str("group").trim(),
    order: numberOrNull(d.order) ?? 0,
    start: whole("start"),
    end: whole("end"),
    durationDays: Math.max(0, whole("durationDays") ?? 0),
    isMilestone: d.isMilestone === true,
    predecessors,
    trade: str("trade"),
    contactId: str("contactId"),
    contactName: str("contactName"),
    status: normalizeStatus(d.status),
    percentComplete: Math.min(100, Math.max(0, whole("percentComplete") ?? 0)),
    percentMode: normalizeMode(d.percentMode),
    baselineStart: whole("baselineStart"),
    baselineEnd: whole("baselineEnd"),
    notes: str("notes"),
    color: str("color"),
    taskIds: ids("taskIds"),
    appointmentIds: ids("appointmentIds"),
    createdAt: whole("createdAt") ?? 0,
    updatedAt: whole("updatedAt") ?? 0,
    updatedBy: str("updatedBy"),
  };
}

/**
 * What a new phase is saved as - the same keys SchedulePhase.toFirestoreMap() writes. A missing
 * date, an empty list and a blank colour are left out (the web client cannot take an empty array,
 * and a missing key reads back as the default anyway).
 */
function phaseWriteMap(p) {
  const out = {
    id: p.id,
    projectId: p.projectId,
    environmentId: p.environmentId,
    name: p.name,
    group: p.group ?? "",
    order: p.order ?? 0,
    durationDays: p.durationDays ?? 0,
    isMilestone: !!p.isMilestone,
    trade: p.trade ?? "",
    contactId: p.contactId ?? "",
    contactName: p.contactName ?? "",
    status: normalizeStatus(p.status),
    percentComplete: p.percentComplete ?? 0,
    percentMode: normalizeMode(p.percentMode),
    notes: p.notes ?? "",
    createdAt: p.createdAt ?? 0,
    updatedAt: p.updatedAt ?? 0,
    updatedBy: p.updatedBy ?? "",
  };
  for (const k of ["start", "end", "baselineStart", "baselineEnd"]) if (p[k] !== null && p[k] !== undefined) out[k] = p[k];
  for (const k of ["predecessors", "taskIds", "appointmentIds"]) if (Array.isArray(p[k]) && p[k].length) out[k] = p[k];
  if (p.color && String(p.color).trim()) out.color = p.color;
  return out;
}

// ---------------------------------------------------------------------------------------------
// Worked out from a phase, never stored. These take a phase that also carries its dates as day
// numbers (startDay, endDay, baselineStartDay, baselineEndDay - added by mcp.js when it loads).
// ---------------------------------------------------------------------------------------------

/** In schedule order: the order field, then start date (undated last), then name. */
function sortPhases(phases) {
  return [...phases].sort((a, b) =>
    a.order - b.order ||
    (a.startDay ?? Infinity) - (b.startDay ?? Infinity) ||
    a.name.localeCompare(b.name));
}

/** Working days the finish has moved against the baseline: positive is late. Null without both dates. */
function slipDays(phase, cal) {
  if (phase.baselineEndDay === null || phase.baselineEndDay === undefined) return null;
  if (phase.endDay === null || phase.endDay === undefined) return null;
  return cal.workingDaysBetween(phase.baselineEndDay, phase.endDay);
}

/** Past its start and still not started, or past its end and not done. */
function isLate(phase, today) {
  const s = phase.startDay, e = phase.endDay;
  const notStartedPastStart = phase.status === "Not started" && s !== null && s !== undefined && today > s;
  const notDonePastEnd = phase.status !== "Done" && e !== null && e !== undefined && today > e;
  return notStartedPastStart || notDonePastEnd;
}

// Same words as isDoneStatus() in the app's TaskUtils.kt, so the app and the connector agree.
const DONE_WORDS = new Set(["done", "completed", "complete", "finished", "closed"]);
const isDoneStatus = (status) => DONE_WORDS.has(String(status ?? "").trim().toLowerCase());

/**
 * Percent complete from a phase's linked tasks: ticked checklist items over all checklist items.
 * A task with no checklist counts as one item, ticked when the task sits in a Done-like bucket.
 * Null when there is nothing to count.
 */
function autoPercent(tasks) {
  let done = 0, total = 0;
  for (const t of tasks) {
    const items = (t.checklistGroups || []).flatMap((g) => g.items || []);
    if (!items.length) { total += 1; if (isDoneStatus(t.status)) done += 1; continue; }
    total += items.length;
    done += items.filter((i) => i && i.isDone).length;
  }
  return total ? Math.round((done / total) * 100) : null;
}

/** The percent to show: 100 once Done; the typed number in manual mode; otherwise from the linked tasks. */
function percentOf(phase, linkedTasks) {
  if (phase.status === "Done") return 100;
  if (phase.percentMode === "manual") return phase.percentComplete;
  return autoPercent(linkedTasks) ?? phase.percentComplete;
}

/**
 * Would giving phase `phaseId` these predecessors make a loop (A waits for B, B waits for A)?
 * Returns the loop as a list of phase ids starting and ending at `phaseId`, or null.
 */
function findLoop(phases, phaseId, predecessorIds) {
  const waitsFor = new Map(phases.map((p) => [p.id, p.predecessors.map((x) => x.phaseId)]));
  waitsFor.set(phaseId, predecessorIds);
  const dead = new Set(); // already walked and known not to lead back
  const walk = (id, path) => {
    for (const next of waitsFor.get(id) || []) {
      if (next === phaseId) return [...path, next];
      if (dead.has(next) || path.includes(next)) continue;
      const hit = walk(next, [...path, next]);
      if (hit) return hit;
      dead.add(next);
    }
    return null;
  };
  return walk(phaseId, [phaseId]);
}

// ---------------------------------------------------------------------------------------------
// Shifting a phase and everything that follows it - the twin of ScheduleShift.kt, and tested
// against the same five-phase chain. Phases here carry startDay / endDay (day numbers).
//
// The rule for a follower: it keeps its place relative to the earliest day its predecessors
// allow. If a change moves that earliest day by N working days, the follower moves N working
// days and keeps its length. So a gap left on purpose stays a gap, shifting back puts everything
// back, a follower held up by some other predecessor doesn't move, a phase that is Done doesn't
// move, and a phase with no dates has nothing to move. The baseline is never touched.
// ---------------------------------------------------------------------------------------------

/** Where a phase would sit if moved `days` working days (negative is earlier), keeping its length: [startDay, endDay]. Null if undated. */
function shiftedDays(phase, days, cal) {
  if (phase.startDay === null || phase.startDay === undefined || phase.endDay === null || phase.endDay === undefined) return null;
  if (!days) return [phase.startDay, phase.endDay];
  const start = cal.addWorkingDays(phase.startDay, days);
  return [start, phase.isMilestone ? start : cal.endForDuration(start, Math.max(1, cal.durationDays(phase.startDay, phase.endDay)))];
}

/**
 * Everything that moves when phase `originId` is given the dates newStart..newEnd (day numbers).
 * Returns { moves: [{ phase, oldStart, oldEnd, newStart, newEnd, startShift, endShift, isOrigin }] }
 * with the changed phase first, or { loop: [names...] } when the predecessors go round in a circle.
 * With cascade false only the phase itself is listed.
 */
function planCascade(phases, originId, newStart, newEnd, cal, cascade = true) {
  const byId = new Map(phases.map((p) => [p.id, p]));
  const origin = byId.get(originId);
  if (!origin) return { moves: [] };
  const followersOf = new Map();
  for (const p of phases) for (const link of p.predecessors) {
    if (!followersOf.has(link.phaseId)) followersOf.set(link.phaseId, []);
    followersOf.get(link.phaseId).push(p);
  }
  // Followers in an order where each comes after everything it waits on, stopping dead at a loop.
  const order = [], state = new Map(), trail = [];
  let loop = null;
  const walk = (p) => {
    if (loop) return;
    state.set(p.id, 1);
    trail.push(p);
    for (const next of followersOf.get(p.id) || []) {
      if (state.get(next.id) === 1) { loop = [...trail.slice(trail.findIndex((x) => x.id === next.id)), next].map((x) => x.name || x.id); return; }
      if (state.get(next.id) !== 2) walk(next);
      if (loop) return;
    }
    trail.pop();
    state.set(p.id, 2);
    order.push(p);
  };
  if (cascade) walk(origin);
  if (loop) return { loop };
  const followers = order.reverse().filter((p) => p.id !== originId);

  const dated = (p) => p.startDay !== null && p.startDay !== undefined && p.endDay !== null && p.endDay !== undefined;
  const oldDates = new Map(phases.filter(dated).map((p) => [p.id, [p.startDay, p.endDay]]));
  const newDates = new Map(oldDates);
  newDates.set(originId, [newStart, newEnd]);
  const earliestStart = (phase, dates) => {
    const days = phase.predecessors.filter((link) => dates.has(link.phaseId))
      .map((link) => cal.successorStart(dates.get(link.phaseId)[0], dates.get(link.phaseId)[1], link.type, link.lagDays));
    return days.length ? Math.max(...days) : null;
  };
  for (const follower of followers) {
    const old = oldDates.get(follower.id);
    if (!old || follower.status === "Done") continue;
    const before = earliestStart(follower, oldDates), after = earliestStart(follower, newDates);
    if (before === null || after === null) continue;
    const shift = cal.workingDaysBetween(before, after);
    if (!shift) continue;
    const start = cal.addWorkingDays(old[0], shift);
    newDates.set(follower.id, [start, follower.isMilestone ? start : cal.endForDuration(start, Math.max(1, cal.durationDays(old[0], old[1])))]);
  }
  const moves = [];
  for (const p of [origin, ...followers]) {
    const now = newDates.get(p.id);
    if (!now) continue;
    const old = oldDates.get(p.id);
    if (old && old[0] === now[0] && old[1] === now[1]) continue;
    const [os, oe] = old || now; // a phase being given its first dates: nothing to measure a shift from
    moves.push({
      phase: p, oldStart: os, oldEnd: oe, newStart: now[0], newEnd: now[1],
      startShift: cal.workingDaysBetween(os, now[0]), endShift: cal.workingDaysBetween(oe, now[1]), isOrigin: p.id === originId,
    });
  }
  return { moves };
}

module.exports = {
  shiftedDays, planCascade,
  STATUSES, WEEKDAYS, DEFAULT_WORK_WEEK,
  matchStatus, normalizeStatus, normalizeType, normalizeMode,
  dayNum, dayToIso, isoToDay, isoWeekday, fmtDay, workCalendar,
  normalizePhase, phaseWriteMap, sortPhases,
  slipDays, isLate, isDoneStatus, autoPercent, percentOf, findLoop,
};
