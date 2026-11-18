/**
 * Crewsync MCP connector — a Model Context Protocol server exposed as a Firebase Cloud Function.
 *
 * Claude (and any other MCP client) talks to Crewsync's Firestore through this file. It runs
 * with the Admin SDK, so Firestore rules do NOT apply here; every tool scopes its reads and
 * writes to the caller's active Environment and acts in that caller's name.
 *
 * Deployed as the `crewsyncMcp` function → https://crewsyncmcp-z6f43rxwha-uc.a.run.app (the URL
 * Claude's custom connector points at). Auth is OAuth 2.0 (see oauth.js): the MCP client registers
 * itself, the human types a Crewsync access key once on the consent page, and a long-lived bearer
 * token tied to that person's email is issued. Every tool call then runs as that person, inside
 * their active workspace (Environment). Access keys are per person — see create_access_key —
 * so each account's AI is attributed separately.
 *
 * Data model mirrors the app (composeApp/.../data/model + util/FirestoreUtils.kt):
 *   projects/{id}                       Project  (environmentId, name, buckets, members, ...)
 *   projects/{id}/appointments/{id}     Appointment (startDate/endDate epoch ms, recurrence None|Daily|Weekly|Monthly)
 *   projects/{id}/schedulePhases/{id}   SchedulePhase (start/end epoch ms at local midnight, baseline, predecessors,
 *                                             taskIds/appointmentIds = its linked tasks and appointments; see schedule.js)
 *   projects/{id}/files/{id}            ProjectFile (name, url, folderId, uploadedBy, uploadedAt)
 *   projects/{id}/folders/{id}          ProjectFolder
 *   tasks/{id}                          Task (environmentId, projectId, status == one of project.buckets,
 *                                             checklistGroups[{id,title,items[{id,text,isDone}]}], ...)
 *   contacts/{id}                       Contact (environmentId, ...)
 *   broadcasts/{id}                     Broadcast
 *   users/{uid}                         User (email, name, activeEnvironmentId, ...)
 *
 * Numbers are written as plain JS numbers; the Kotlin client reads them back as Long/Double.
 * Empty lists are omitted on write, matching Task.toFirestoreMap() so the web client never
 * trips over GitLive's empty-array encoder bug.
 */

const crypto = require("crypto");
const express = require("express");
const { z } = require("zod");
const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const { logger } = require("firebase-functions");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { McpServer } = require("@modelcontextprotocol/sdk/server/mcp.js");
const { StreamableHTTPServerTransport } = require("@modelcontextprotocol/sdk/server/streamableHttp.js");
const { mcpAuthRouter } = require("@modelcontextprotocol/sdk/server/auth/router.js");
const { requireBearerAuth } = require("@modelcontextprotocol/sdk/server/auth/middleware/bearerAuth.js");
const { buildProvider, hashKey, DEFAULT_TIMEZONE } = require("./oauth");
const dailyReport = require("./dailyReport");
const schedule = require("./schedule");

// Legacy shared secret from the first connector build. Still accepted at the consent page and
// mapped to the owner account, so existing sign-ins keep working after this deploy.
const MCP_AUTH_TOKEN = defineSecret("MCP_AUTH_TOKEN");
// Stable Cloud Run URL for this function - used as the OAuth issuer/resource identifier.
const BASE_URL = "https://crewsyncmcp-z6f43rxwha-uc.a.run.app";
const MAX_RECURRENCE_INSTANCES = 400;

// ---------------------------------------------------------------------------------------------
// Date helpers — Crewsync stores epoch milliseconds; people talk in local wall-clock time.
// ---------------------------------------------------------------------------------------------

/** Offset (ms) of `tz` from UTC at the instant `utcMs`. */
function tzOffsetMs(utcMs, tz) {
  const dtf = new Intl.DateTimeFormat("en-US", {
    timeZone: tz,
    hourCycle: "h23",
    year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit",
  });
  const parts = {};
  for (const p of dtf.formatToParts(new Date(utcMs))) parts[p.type] = p.value;
  const asUtc = Date.UTC(+parts.year, +parts.month - 1, +parts.day, +parts.hour, +parts.minute, +parts.second);
  return asUtc - Math.floor(utcMs / 1000) * 1000;
}

/** Wall-clock components in `tz` → epoch ms. */
function zonedToUtc(y, mo, d, h, mi, s, tz) {
  const guess = Date.UTC(y, mo - 1, d, h, mi, s);
  const off1 = tzOffsetMs(guess, tz);
  const utc1 = guess - off1;
  const off2 = tzOffsetMs(utc1, tz);
  return off2 === off1 ? utc1 : guess - off2;
}

/** Epoch ms → wall-clock components in `tz`. */
function utcToZoned(ms, tz) {
  const dtf = new Intl.DateTimeFormat("en-US", {
    timeZone: tz, hourCycle: "h23", weekday: "short",
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
  });
  const p = {};
  for (const x of dtf.formatToParts(new Date(ms))) p[x.type] = x.value;
  return { y: +p.year, mo: +p.month, d: +p.day, h: +p.hour, mi: +p.minute, weekday: p.weekday };
}

/**
 * Parse what a person or an LLM is likely to send:
 *   "2026-10-21"              → local midnight (or end of day when endOfDay)
 *   "2026-10-21T09:00"        → 9:00 local time in `tz`
 *   "2026-10-21T13:00:00Z"    → honored as given
 *   "2026-10-21T09:00-04:00"  → honored as given
 *   "today" / "tomorrow"      → that local date
 * Returns epoch ms, or throws a readable error.
 */
function parseDate(input, tz, { endOfDay = false } = {}) {
  if (input === undefined || input === null || input === "") return undefined;
  if (typeof input === "number") return input;
  let s = String(input).trim();
  const lower = s.toLowerCase();
  if (lower === "now") return Date.now();
  if (lower === "today" || lower === "tomorrow" || lower === "yesterday") {
    const z = utcToZoned(Date.now(), tz);
    const shift = lower === "tomorrow" ? 1 : lower === "yesterday" ? -1 : 0;
    const base = zonedToUtc(z.y, z.mo, z.d + shift, 0, 0, 0, tz);
    return endOfDay ? base + 86400000 - 1 : base;
  }
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?)?\s*(Z|[+-]\d{2}:?\d{2})?$/i);
  if (!m) {
    const t = Date.parse(s);
    if (Number.isNaN(t)) throw new Error(`Could not understand date "${input}". Use YYYY-MM-DD or YYYY-MM-DDTHH:MM (local time) or an ISO string with an offset.`);
    return t;
  }
  const [, y, mo, d, h, mi, sec, off] = m;
  if (off) {
    const iso = `${y}-${mo}-${d}T${h || "00"}:${mi || "00"}:${sec || "00"}${off.toUpperCase() === "Z" ? "Z" : off.replace(/(\d{2})(\d{2})$/, "$1:$2")}`;
    return Date.parse(iso);
  }
  if (h === undefined) {
    const base = zonedToUtc(+y, +mo, +d, 0, 0, 0, tz);
    return endOfDay ? base + 86400000 - 1 : base;
  }
  return zonedToUtc(+y, +mo, +d, +h, +mi, +(sec || 0), tz);
}

function fmtDateTime(ms, tz) {
  if (ms === undefined || ms === null) return "";
  const z = utcToZoned(ms, tz);
  const hh = z.h % 12 === 0 ? 12 : z.h % 12;
  const ampm = z.h < 12 ? "AM" : "PM";
  return `${z.weekday} ${z.y}-${pad(z.mo)}-${pad(z.d)} ${hh}:${pad(z.mi)} ${ampm}`;
}
function fmtDate(ms, tz) {
  if (ms === undefined || ms === null) return "";
  const z = utcToZoned(ms, tz);
  return `${z.weekday} ${z.y}-${pad(z.mo)}-${pad(z.d)}`;
}
function pad(n) { return String(n).padStart(2, "0"); }

// ---------------------------------------------------------------------------------------------
// Small utilities
// ---------------------------------------------------------------------------------------------

const norm = (s) => String(s ?? "").trim().toLowerCase().replace(/\s+/g, " ");
const newId = (prefix) => `${prefix}${Date.now()}_${crypto.randomBytes(3).toString("hex")}`;

class ToolError extends Error {}

/** Resolve "name or id" against a list of {id, name}. Exact (trimmed, case-insensitive) wins, then unique partial. */
function resolveByName(candidates, query, label) {
  const q = norm(query);
  if (!q) throw new ToolError(`A ${label} name or ID is required.`);
  const byId = candidates.find((c) => c.id === String(query).trim());
  if (byId) return byId;
  const exact = candidates.filter((c) => norm(c.name) === q);
  if (exact.length === 1) return exact[0];
  if (exact.length > 1) {
    throw new ToolError(`There are ${exact.length} ${label}s named "${query}": ${exact.map((c) => `${c.name} (id ${c.id})`).join(", ")}. Use the id.`);
  }
  const partial = candidates.filter((c) => norm(c.name).includes(q));
  if (partial.length === 1) return partial[0];
  if (partial.length > 1) {
    throw new ToolError(`"${query}" matches several ${label}s: ${partial.map((c) => `${c.name} (id ${c.id})`).join(", ")}. Be more specific or use the id.`);
  }
  throw new ToolError(`No ${label} found matching "${query}". Known: ${candidates.map((c) => c.name).join(", ") || "(none)"}.`);
}

function text(s) { return { content: [{ type: "text", text: s }] }; }
function json(obj) { return { content: [{ type: "text", text: JSON.stringify(obj, null, 2) }] }; }

/** Strip undefined/null and empty arrays so the Kotlin web client never sees an empty array. */
function cleanForWrite(obj) {
  const out = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v === undefined || v === null) continue;
    if (Array.isArray(v) && v.length === 0) continue;
    out[k] = v;
  }
  return out;
}

// ---------------------------------------------------------------------------------------------
// Data access — everything below takes `ctx` = { db, email, uid, name, envId, tz }
// ---------------------------------------------------------------------------------------------

async function loadProjects(ctx) {
  const snap = await ctx.db.collection("projects").where("environmentId", "==", ctx.envId).get();
  return snap.docs.map((d) => ({ ...d.data(), id: d.id, name: String(d.data().name ?? "").trim() }));
}

async function findProject(ctx, query) {
  const projects = await loadProjects(ctx);
  return resolveByName(projects, query, "project");
}

/**
 * Tasks are looked up by project (projects are already workspace-scoped) rather than by
 * environmentId, because the first connector build wrote tasks with no environmentId at all —
 * the app filters on that field, so those tasks were invisible in the planner. Any such task
 * found here is repaired in place by stamping the workspace id its project belongs to.
 */
async function loadTasks(ctx, projectId) {
  const projectIds = projectId ? [projectId] : (await loadProjects(ctx)).map((p) => p.id);
  if (!projectIds.length) return [];
  const docs = [];
  for (let i = 0; i < projectIds.length; i += 30) {
    const snap = await ctx.db.collection("tasks").where("projectId", "in", projectIds.slice(i, i + 30)).get();
    docs.push(...snap.docs);
  }
  const repairs = [];
  const tasks = docs.map((d) => {
    const t = normalizeTask(d.id, d.data());
    if (!t.environmentId) {
      t.environmentId = ctx.envId;
      repairs.push(d.ref.update({ environmentId: ctx.envId }));
    }
    return t;
  });
  if (repairs.length) {
    await Promise.all(repairs);
    console.log(`[mcp] stamped environmentId on ${repairs.length} task(s) that were missing it`);
  }
  return tasks;
}

/** Mirror toTaskSafe(): recover legacy flat `checklist` into a group when there are no groups. */
function normalizeTask(id, data) {
  const t = { ...data, id }; // doc id wins over any stale "id" field stored in the document
  t.title = String(t.title ?? "").trim();
  t.status = t.status ?? "Not Started";
  t.assignedMembers = Array.isArray(t.assignedMembers) ? t.assignedMembers : [];
  if (t.assignedTo && !t.assignedMembers.includes(t.assignedTo)) t.assignedMembers = [...t.assignedMembers, t.assignedTo];
  t.checklistGroups = Array.isArray(t.checklistGroups) ? t.checklistGroups : [];
  if (t.checklistGroups.length === 0 && Array.isArray(t.checklist) && t.checklist.length > 0) {
    t.checklistGroups = [{
      id: "chk_grp_legacy",
      title: "Checklist",
      items: t.checklist.filter((i) => i && typeof i.text === "string").map((i, idx) => ({ id: i.id || `chk_legacy_${idx}`, text: i.text, isDone: !!i.isDone })),
    }];
  }
  delete t.checklist; // never write the legacy field back (see Task.toFirestoreMap)
  return t;
}

async function findTask(ctx, query, projectQuery) {
  let projectId;
  if (projectQuery) projectId = (await findProject(ctx, projectQuery)).id;
  const tasks = await loadTasks(ctx, projectId);
  const found = resolveByName(tasks.map((t) => ({ ...t, name: t.title })), query, "task");
  return tasks.find((t) => t.id === found.id);
}

function taskWriteMap(t) {
  return cleanForWrite({
    id: t.id,
    environmentId: t.environmentId,
    projectId: t.projectId,
    title: t.title,
    description: t.description ?? "",
    status: t.status,
    assignedTo: t.assignedTo ?? undefined,
    assignedMembers: t.assignedMembers,
    color: t.color ?? "#FFFFFF",
    startDate: t.startDate ?? undefined,
    dueDate: t.dueDate ?? undefined,
    order: t.order ?? 0,
    checklistGroups: t.checklistGroups,
    attachments: t.attachments ?? [],
  });
}

async function saveTask(ctx, t) {
  const ref = ctx.db.collection("tasks").doc(t.id);
  await ref.set(taskWriteMap(t));
  const back = await ref.get();
  if (!back.exists) throw new ToolError("Write did not persist (task not found after save).");
  return normalizeTask(back.id, back.data());
}

function checklistProgress(t) {
  let done = 0, total = 0;
  for (const g of t.checklistGroups) for (const i of g.items || []) { total++; if (i.isDone) done++; }
  return { done, total };
}

function taskSummary(t, ctx, projectName) {
  const p = checklistProgress(t);
  return {
    id: t.id,
    title: t.title,
    project: projectName,
    status: t.status,
    assignees: t.assignedMembers,
    startDate: t.startDate ? fmtDate(t.startDate, ctx.tz) : undefined,
    dueDate: t.dueDate ? fmtDate(t.dueDate, ctx.tz) : undefined,
    checklist: p.total ? `${p.done}/${p.total} done` : undefined,
  };
}

function taskDetail(t, ctx, projectName) {
  return {
    ...taskSummary(t, ctx, projectName),
    description: t.description || undefined,
    color: t.color,
    checklists: t.checklistGroups.map((g) => ({
      id: g.id,
      title: g.title,
      items: (g.items || []).map((i) => ({ id: i.id, text: i.text, done: !!i.isDone })),
    })),
    attachments: (t.attachments || []).map((a) => ({ name: a.name, url: a.url })),
  };
}

// --- appointments -----------------------------------------------------------------------------

async function loadAppointments(ctx, projectId) {
  const snap = await ctx.db.collection("projects").doc(projectId).collection("appointments").get();
  return snap.docs.map((d) => ({ ...d.data(), id: d.id }));
}

/** Expand one stored appointment (with optional recurrence) into concrete instances within [from, to]. */
function expandAppointment(appt, from, to, tz) {
  const start = Number(appt.startDate);
  const end = Number(appt.endDate || appt.startDate);
  const duration = Math.max(0, end - start);
  const rec = appt.recurrence || "None";
  const out = [];
  const push = (s) => out.push({ ...appt, instanceStart: s, instanceEnd: s + duration });

  if (!rec || rec === "None") {
    if (end >= from && start <= to) push(start);
    return out;
  }
  // Step in local wall-clock so a 9 AM meeting stays 9 AM across DST changes.
  const z = utcToZoned(start, tz);
  for (let n = 0; n < MAX_RECURRENCE_INSTANCES * 4; n++) {
    let s;
    if (rec === "Daily") s = zonedToUtc(z.y, z.mo, z.d + n, z.h, z.mi, 0, tz);
    else if (rec === "Weekly") s = zonedToUtc(z.y, z.mo, z.d + 7 * n, z.h, z.mi, 0, tz);
    else if (rec === "Monthly") s = zonedToUtc(z.y, z.mo + n, z.d, z.h, z.mi, 0, tz);
    else { if (end >= from && start <= to) push(start); return out; }
    if (s > to) break;
    if (s + duration >= from) push(s);
    if (out.length >= MAX_RECURRENCE_INSTANCES) break;
  }
  return out;
}

function apptView(a, ctx, projectName) {
  return {
    id: a.id,
    title: a.title,
    project: projectName,
    start: a.isAllDay ? fmtDate(a.instanceStart ?? a.startDate, ctx.tz) : fmtDateTime(a.instanceStart ?? a.startDate, ctx.tz),
    end: a.isAllDay ? undefined : fmtDateTime(a.instanceEnd ?? a.endDate, ctx.tz),
    allDay: !!a.isAllDay || undefined,
    recurrence: a.recurrence && a.recurrence !== "None" ? a.recurrence : undefined,
    location: a.location || undefined,
    description: a.description || undefined,
    notes: a.notes || undefined,
  };
}

// --- schedule phases --------------------------------------------------------------------------
// Phase dates are stored the way task dates are (epoch ms at local midnight), but the schedule
// math is done in whole calendar days ("day numbers") - see schedule.js, the twin of the app's
// ScheduleDates.kt. These helpers convert at the edges and do the Firestore access.

function msToDay(ms, tz) {
  if (ms === null || ms === undefined) return null;
  const z = utcToZoned(ms, tz);
  return schedule.dayNum(z.y, z.mo, z.d);
}
function dayToMs(day, tz) {
  if (day === null || day === undefined) return null;
  const d = new Date(day * 86400000);
  return zonedToUtc(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate(), 0, 0, 0, tz);
}
/**
 * A date as a person gives it ("2026-12-01", "today") as a calendar day; a time of day, if any,
 * is dropped. Stricter than parseDate(): that hands anything it doesn't recognise to Date.parse,
 * which reads "12/01/2026" in the SERVER's time zone and can land a phase a day early.
 */
function parseDay(input, tz) {
  if (input === undefined || input === null || String(input).trim() === "") return undefined;
  const s = String(input).trim();
  if (/^(today|tomorrow|yesterday|now)$/i.test(s)) return msToDay(parseDate(s, tz), tz);
  const m = /^(\d{4}-\d{2}-\d{2})(?:[T ].*)?$/.exec(s);
  const day = m ? schedule.isoToDay(m[1]) : null;
  if (day === null) throw new ToolError(`Could not understand the date "${input}". Use YYYY-MM-DD, for example 2026-12-01.`);
  return day;
}

const phasesCol = (ctx, projectId) => ctx.db.collection("projects").doc(projectId).collection("schedulePhases");
/** An empty list is never written (see cleanForWrite): the field is removed instead. */
const listOrDelete = (arr) => (arr.length ? arr : FieldValue.delete());

function withDays(ph, tz) {
  return {
    ...ph,
    startDay: msToDay(ph.start, tz), endDay: msToDay(ph.end, tz),
    baselineStartDay: msToDay(ph.baselineStart, tz), baselineEndDay: msToDay(ph.baselineEnd, tz),
  };
}

async function loadPhases(ctx, projectId) {
  const snap = await phasesCol(ctx, projectId).get();
  return schedule.sortPhases(snap.docs.map((d) => withDays(schedule.normalizePhase(d.id, d.data()), ctx.tz)));
}

/** Everything the schedule tools need about one project, loaded together. */
async function loadSchedule(ctx, p) {
  const [phases, tasks, appts] = await Promise.all([loadPhases(ctx, p.id), loadTasks(ctx, p.id), loadAppointments(ctx, p.id)]);
  return {
    project: p, cal: schedule.workCalendar(p), today: msToDay(Date.now(), ctx.tz),
    phases, tasks, appts,
    taskById: new Map(tasks.map((t) => [t.id, t])),
    apptById: new Map(appts.map((x) => [x.id, x])),
  };
}

const findPhase = (sc, query) => sc.phases.find((x) => x.id === resolveByName(sc.phases, query, "phase").id);

/**
 * Works out a phase's dates from what was passed, on top of what it has now (`cur`; empty for a
 * new phase). Dates are day numbers.
 *   start + end           both as given
 *   start + durationDays  the end is worked out
 *   end + durationDays    the start is worked out backwards
 *   start alone           the phase moves and keeps its length
 *   end alone             the phase stretches or shrinks from its start
 *   durationDays alone    the end is worked out from the start
 *   milestone             one day
 */
function resolvePhaseDates(cal, cur, a) {
  const has = (k) => a[k] !== undefined && a[k] !== null;
  const milestone = a.milestone !== undefined ? !!a.milestone : !!cur.isMilestone;
  let s = cur.startDay ?? null, e = cur.endDay ?? null;
  if (milestone) {
    if (has("start") && has("end") && a.start !== a.end) throw new ToolError("A milestone is a single day: pass one date, or the same start and end.");
    const d = has("start") ? a.start : has("end") ? a.end : (s ?? e);
    s = e = d ?? null;
  } else if (has("start") && has("end")) {
    s = a.start; e = a.end;
  } else if (has("start") && has("durationDays")) {
    s = a.start; e = cal.endForDuration(s, a.durationDays);
  } else if (has("end") && has("durationDays")) {
    e = a.end; s = cal.startForDuration(e, a.durationDays);
  } else if (has("start")) {
    const len = s !== null && e !== null ? cal.durationDays(s, e) : cur.durationDays || 0;
    if (len < 1 && e === null) throw new ToolError("A start date also needs an end, durationDays, or milestone: true.");
    s = a.start;
    if (len >= 1) e = cal.endForDuration(s, len);
  } else if (has("end")) {
    if (s === null) throw new ToolError("An end date also needs a start or durationDays.");
    e = a.end;
  } else if (has("durationDays") && s !== null) {
    e = cal.endForDuration(s, a.durationDays);
  }
  if (s !== null && e !== null && e < s) throw new ToolError(`The end (${schedule.dayToIso(e)}) is before the start (${schedule.dayToIso(s)}).`);
  const durationDays = s !== null && e !== null ? cal.durationDays(s, e) : has("durationDays") ? a.durationDays : cur.durationDays || 0;
  return { startDay: s, endDay: e, durationDays, isMilestone: milestone };
}

/** One row of the schedule: dates, status, percent, slip against the baseline, and link counts. */
function phaseSummary(ph, sc) {
  const tasks = ph.taskIds.map((id) => sc.taskById.get(id)).filter(Boolean);
  const appts = ph.appointmentIds.map((id) => sc.apptById.get(id)).filter(Boolean);
  const dated = ph.startDay !== null && ph.endDay !== null;
  const nameOf = (id) => sc.phases.find((x) => x.id === id)?.name ?? `(missing phase ${id})`;
  const slip = schedule.slipDays(ph, sc.cal);
  return {
    id: ph.id,
    name: ph.name,
    group: ph.group || undefined,
    start: schedule.fmtDay(ph.startDay),
    end: schedule.fmtDay(ph.endDay),
    workingDays: dated ? sc.cal.durationDays(ph.startDay, ph.endDay) : ph.durationDays || undefined,
    milestone: ph.isMilestone || undefined,
    status: ph.status,
    percent: schedule.percentOf(ph, tasks),
    late: schedule.isLate(ph, sc.today) || undefined,
    baselineStart: schedule.fmtDay(ph.baselineStartDay),
    baselineEnd: schedule.fmtDay(ph.baselineEndDay),
    slipDays: slip === null ? undefined : slip,
    trade: ph.trade || undefined,
    contact: ph.contactName || undefined,
    predecessors: ph.predecessors.length ? ph.predecessors.map((x) => `${nameOf(x.phaseId)} (${x.type}${x.lagDays ? `, lag ${x.lagDays}` : ""})`) : undefined,
    linkedTasks: tasks.length,
    linkedAppointments: appts.length,
  };
}

/** A phase in full: the summary plus notes, who it waits for and who waits for it, and its linked tasks and appointments. */
function phaseDetail(ph, sc, ctx) {
  const tasks = ph.taskIds.map((id) => sc.taskById.get(id)).filter(Boolean);
  const appts = ph.appointmentIds.map((id) => sc.apptById.get(id)).filter(Boolean).sort((x, y) => Number(x.startDate) - Number(y.startDate));
  const missingTasks = ph.taskIds.filter((id) => !sc.taskById.has(id));
  const missingAppts = ph.appointmentIds.filter((id) => !sc.apptById.has(id));
  const byId = (id) => sc.phases.find((x) => x.id === id);
  return {
    ...phaseSummary(ph, sc),
    project: sc.project.name,
    percentMode: ph.percentMode,
    notes: ph.notes || undefined,
    color: ph.color || undefined,
    predecessors: ph.predecessors.map((x) => {
      const other = byId(x.phaseId);
      return { id: x.phaseId, name: other?.name ?? "(missing phase)", type: x.type, lagDays: x.lagDays, start: schedule.fmtDay(other?.startDay), end: schedule.fmtDay(other?.endDay) };
    }),
    successors: sc.phases.filter((x) => x.predecessors.some((q) => q.phaseId === ph.id)).map((x) => {
      const link = x.predecessors.find((q) => q.phaseId === ph.id);
      return { id: x.id, name: x.name, type: link.type, lagDays: link.lagDays, start: schedule.fmtDay(x.startDay) };
    }),
    tasks: tasks.map((t) => taskSummary(t, ctx, sc.project.name)),
    appointments: appts.map((x) => apptView(x, ctx, sc.project.name)),
    missingTaskIds: missingTasks.length ? missingTasks : undefined,
    missingAppointmentIds: missingAppts.length ? missingAppts : undefined,
    lastUpdated: ph.updatedAt ? fmtDateTime(ph.updatedAt, ctx.tz) : undefined,
    updatedBy: ph.updatedBy || undefined,
  };
}

/** The counts and the finish line for the whole schedule (what the presentation header shows). */
function scheduleTotals(sc) {
  const n = (status) => sc.phases.filter((x) => x.status === status).length;
  const ends = sc.phases.map((x) => x.endDay).filter((d) => d !== null);
  const planned = sc.phases.map((x) => x.baselineEndDay).filter((d) => d !== null);
  const current = ends.length ? Math.max(...ends) : null;
  const baseline = planned.length ? Math.max(...planned) : null;
  return {
    done: n("Done"), inProgress: n("In progress"), notStarted: n("Not started"), onHold: n("On hold"),
    late: sc.phases.filter((x) => schedule.isLate(x, sc.today)).length,
    finish: current === null ? undefined : {
      planned: schedule.fmtDay(baseline),
      current: schedule.fmtDay(current),
      slipDays: baseline === null ? undefined : sc.cal.workingDaysBetween(baseline, current),
    },
  };
}

/** Looks a contact up for a phase. Text that matches no contact is kept as a plain name, unlinked. */
async function phaseContact(ctx, query) {
  const q = String(query ?? "").trim();
  if (!q) return { contactId: "", contactName: "", linked: false };
  const contacts = await loadContacts(ctx);
  const byId = contacts.find((c) => c.id === q);
  if (byId) return { contactId: byId.id, contactName: byId.name, linked: true };
  const exact = contacts.filter((c) => norm(c.name) === norm(q));
  const hits = exact.length ? exact : contacts.filter((c) => norm(c.name).includes(norm(q)) || norm(c.company).includes(norm(q)));
  if (hits.length > 1) throw new ToolError(`"${q}" matches several contacts: ${hits.map((c) => `${c.name} (id ${c.id})`).join(", ")}. Be more specific or use the id.`);
  if (hits.length === 1) return { contactId: hits[0].id, contactName: hits[0].name, linked: true };
  return { contactId: "", contactName: q, linked: false };
}

/** After the working week or the holidays change: keep every phase's stored working-day count in step with its dates. */
async function restampPhaseDurations(ctx, project) {
  const cal = schedule.workCalendar(project);
  const phases = await loadPhases(ctx, project.id);
  const stale = phases.filter((ph) => ph.startDay !== null && ph.endDay !== null && cal.durationDays(ph.startDay, ph.endDay) !== ph.durationDays);
  if (!stale.length) return 0;
  const batch = ctx.db.batch();
  for (const ph of stale) batch.update(phasesCol(ctx, project.id).doc(ph.id), { durationDays: cal.durationDays(ph.startDay, ph.endDay) });
  await batch.commit();
  return stale.length;
}

// --- contacts ---------------------------------------------------------------------------------

async function loadContacts(ctx) {
  const snap = await ctx.db.collection("contacts").where("environmentId", "==", ctx.envId).get();
  return snap.docs.map((d) => ({ ...d.data(), id: d.id, name: String(d.data().name ?? "").trim() }));
}

function contactView(c) {
  return cleanForWrite({
    id: c.id, name: c.name, type: c.type, jobTitle: c.jobTitle, company: c.company,
    phone: c.phone, secondaryPhone: c.secondaryPhone, email: c.email, secondaryEmail: c.secondaryEmail,
    address: c.address, notes: c.notes,
  });
}

// ---------------------------------------------------------------------------------------------
// Tool registration
// ---------------------------------------------------------------------------------------------

const dateDoc = (what) => `${what} as YYYY-MM-DD, YYYY-MM-DDTHH:MM (local time), 'today'/'tomorrow', or ISO with offset.`;

function buildServer(ctx) {
  const server = new McpServer({ name: "crewsync", version: "2.0.0" });

  const reg = (name, description, schema, handler) => {
    server.registerTool(name, { description, inputSchema: schema }, async (args) => {
      try {
        return await handler(args || {});
      } catch (e) {
        if (e instanceof ToolError) return { isError: true, content: [{ type: "text", text: e.message }] };
        console.error(`[mcp] ${name} failed`, e);
        return { isError: true, content: [{ type: "text", text: `${name} failed: ${e.message}` }] };
      }
    });
  };

  // ---- whoami ------------------------------------------------------------------------------
  reg("whoami",
    "Who this connector acts as, the active workspace, the timezone used for dates, and the current local date/time. Call this first in a planning session.",
    {},
    async () => {
      const now = Date.now();
      return json({
        user: { email: ctx.email, name: ctx.name, uid: ctx.uid },
        environment: { id: ctx.envId, name: ctx.envName },
        timezone: ctx.tz,
        now: fmtDateTime(now, ctx.tz),
        today: fmtDate(now, ctx.tz),
      });
    });

  // ---- projects ----------------------------------------------------------------------------
  reg("list_projects",
    "List every project in the active workspace with task counts, location, and members. Archived projects are marked archived: true.",
    {},
    async () => {
      const [projects, tasks] = await Promise.all([loadProjects(ctx), loadTasks(ctx)]);
      const rows = projects.map((p) => {
        const mine = tasks.filter((t) => t.projectId === p.id);
        const doneBucket = (p.buckets || []).slice(-1)[0];
        const open = mine.filter((t) => !/^(done|completed?)$/i.test(t.status) && t.status !== doneBucket).length;
        return { id: p.id, name: p.name, archived: p.archived ? true : undefined, location: p.location || undefined, openTasks: open, totalTasks: mine.length, members: p.members || [], buckets: p.buckets || [] };
      });
      rows.sort((a, b) => b.totalTasks - a.totalTasks || a.name.localeCompare(b.name));
      return json(rows);
    });

  reg("get_project",
    "Full details for one project: description, owner/client, members, status buckets, task counts by bucket, and appointments in the next 7 days.",
    { project: z.string().describe("Project name or ID") },
    async ({ project }) => {
      const p = await findProject(ctx, project);
      const tasks = await loadTasks(ctx, p.id);
      const byBucket = {};
      for (const t of tasks) byBucket[t.status] = (byBucket[t.status] || 0) + 1;
      const now = Date.now();
      const appts = (await loadAppointments(ctx, p.id)).flatMap((a) => expandAppointment(a, now, now + 7 * 86400000, ctx.tz));
      appts.sort((a, b) => a.instanceStart - b.instanceStart);
      return json({
        id: p.id, name: p.name, description: p.description || undefined, location: p.location || undefined,
        owner: cleanForWrite({ name: p.ownerName, phone: p.ownerPhone, email: p.ownerEmail }),
        teamLeader: p.teamLeaderId || undefined, members: p.members || [],
        buckets: p.buckets || [], tasksByBucket: byBucket, totalTasks: tasks.length,
        next7Days: appts.map((a) => apptView(a, ctx, p.name)),
      });
    });

  reg("create_project",
    "Create a new project in the active workspace. You become the team leader and a member.",
    {
      name: z.string().min(1),
      description: z.string().optional(),
      location: z.string().optional().describe("Job-site address (also drives the weather card)"),
      ownerName: z.string().optional(), ownerPhone: z.string().optional(), ownerEmail: z.string().optional(),
      members: z.array(z.string()).optional().describe("Member emails"),
      buckets: z.array(z.string()).optional().describe("Status columns; default Not Started / In Progress / Paused / Done"),
    },
    async (a) => {
      const existing = await loadProjects(ctx);
      if (existing.some((p) => norm(p.name) === norm(a.name))) throw new ToolError(`A project named "${a.name.trim()}" already exists.`);
      const members = [...new Set([ctx.email, ...(a.members || []).map((m) => m.trim().toLowerCase())])];
      const ref = ctx.db.collection("projects").doc();
      const doc = cleanForWrite({
        id: ref.id,
        environmentId: ctx.envId,
        name: a.name.trim(),
        description: a.description ?? "",
        teamLeaderId: ctx.uid || ctx.email,
        members,
        location: a.location ?? "",
        ownerName: a.ownerName ?? "", ownerPhone: a.ownerPhone ?? "", ownerEmail: a.ownerEmail ?? "",
        buckets: a.buckets && a.buckets.length ? a.buckets : ["Not Started", "In Progress", "Paused", "Done"],
        cardOrder: ["Weather", "Calendar", "Tasks", "Chat", "Team", "Files", "Notes"],
        cardSizes: {},
        calendarShareEnabled: false,
        calendarShareToken: "",
        createdAt: Date.now(),
      });
      await ref.set(doc);
      const back = await ref.get();
      return json({ created: true, id: back.id, name: back.data().name, buckets: back.data().buckets, members: back.data().members });
    });

  reg("update_project",
    "Update a project's name, description, location, owner/client info, members, status buckets, or the Schedule's working week and holidays. Only the fields you pass change.",
    {
      project: z.string().describe("Project name or ID"),
      name: z.string().optional(), description: z.string().optional(), location: z.string().optional(),
      ownerName: z.string().optional(), ownerPhone: z.string().optional(), ownerEmail: z.string().optional(),
      addMembers: z.array(z.string()).optional(), removeMembers: z.array(z.string()).optional(),
      buckets: z.array(z.string()).optional().describe("Replace the full ordered list of status columns. Tasks keep their status string, so keep existing names unless you also move the tasks."),
      scheduleHolidays: z.array(z.string()).optional().describe("Replace the full list of days nobody works, as YYYY-MM-DD (e.g. Thanksgiving). The Schedule skips them when counting working days. An empty list clears them."),
      workWeek: z.array(z.number().int().min(1).max(7)).optional().describe("The Schedule's working days of the week: 1 = Monday ... 7 = Sunday. Default Monday to Friday."),
    },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const patch = {};
      for (const k of ["name", "description", "location", "ownerName", "ownerPhone", "ownerEmail"]) if (a[k] !== undefined) patch[k] = k === "name" ? a[k].trim() : a[k];
      if (a.addMembers || a.removeMembers) {
        let m = (p.members || []).map((x) => x.toLowerCase());
        for (const x of a.addMembers || []) if (!m.includes(x.trim().toLowerCase())) m.push(x.trim().toLowerCase());
        if (a.removeMembers) m = m.filter((x) => !a.removeMembers.map((r) => r.trim().toLowerCase()).includes(x));
        patch.members = m;
      }
      if (a.buckets) {
        if (!a.buckets.length) throw new ToolError("buckets cannot be empty.");
        const tasks = await loadTasks(ctx, p.id);
        const orphaned = [...new Set(tasks.map((t) => t.status).filter((s) => !a.buckets.includes(s)))];
        if (orphaned.length) throw new ToolError(`Tasks still use statuses not in the new buckets: ${orphaned.join(", ")}. Move those tasks first.`);
        patch.buckets = a.buckets;
      }
      if (a.scheduleHolidays) {
        const bad = a.scheduleHolidays.filter((d) => schedule.isoToDay(d) === null);
        if (bad.length) throw new ToolError(`scheduleHolidays must be real dates as YYYY-MM-DD (got ${bad.map((d) => `"${d}"`).join(", ")}).`);
        patch.scheduleHolidays = listOrDelete([...new Set(a.scheduleHolidays.map((d) => d.trim()))].sort());
      }
      if (a.workWeek) {
        if (!a.workWeek.length) throw new ToolError("workWeek cannot be empty.");
        patch.workWeek = [...new Set(a.workWeek)].sort((x, y) => x - y);
      }
      if (!Object.keys(patch).length) throw new ToolError("Nothing to update — pass at least one field.");
      await ctx.db.collection("projects").doc(p.id).update(patch);
      const back = (await ctx.db.collection("projects").doc(p.id).get()).data();
      // A change to the working calendar changes how many working days each phase spans.
      const calendarChanged = patch.scheduleHolidays !== undefined || patch.workWeek !== undefined;
      if (calendarChanged) await restampPhaseDurations(ctx, { ...back, id: p.id });
      const cal = schedule.workCalendar(back);
      return json({
        updated: Object.keys(patch), id: p.id, name: back.name, members: back.members || [], buckets: back.buckets || [],
        workWeek: calendarChanged ? cal.workWeek.map((n) => schedule.WEEKDAYS[n - 1]) : undefined,
        scheduleHolidays: calendarChanged ? cal.holidays : undefined,
      });
    });

  reg("summarize_project",
    "Status breakdown, overdue tasks, tasks due in the next 7 days, unassigned open tasks, and open-task load per assignee for one project.",
    { project: z.string().describe("Project name or ID") },
    async ({ project }) => {
      const p = await findProject(ctx, project);
      const tasks = await loadTasks(ctx, p.id);
      const doneBucket = (p.buckets || []).slice(-1)[0];
      const isDone = (t) => /^(done|completed?)$/i.test(t.status) || t.status === doneBucket;
      const open = tasks.filter((t) => !isDone(t));
      const now = Date.now();
      const week = now + 7 * 86400000;
      const byStatus = {};
      for (const t of tasks) byStatus[t.status] = (byStatus[t.status] || 0) + 1;
      let cDone = 0, cTotal = 0;
      for (const t of tasks) { const p2 = checklistProgress(t); cDone += p2.done; cTotal += p2.total; }
      const load = {};
      for (const t of open) for (const m of t.assignedMembers) load[m] = (load[m] || 0) + 1;
      const row = (t) => taskSummary(t, ctx, p.name);
      return json({
        project: p.name, id: p.id, totalTasks: tasks.length, byStatus, checklistItems: `${cDone}/${cTotal} done`,
        overdue: open.filter((t) => t.dueDate && t.dueDate < now).sort((a, b) => a.dueDate - b.dueDate).map(row),
        dueNext7Days: open.filter((t) => t.dueDate && t.dueDate >= now && t.dueDate <= week).sort((a, b) => a.dueDate - b.dueDate).map(row),
        unassignedOpen: open.filter((t) => t.assignedMembers.length === 0).map(row),
        openLoadByAssignee: load,
      });
    });

  // ---- tasks -------------------------------------------------------------------------------
  reg("list_tasks",
    "List tasks with IDs, status, assignees, dates and checklist progress. Filter by project, status, assignee, or due-date window. Done/Completed tasks are hidden unless includeDone is true.",
    {
      project: z.string().optional().describe("Project name or ID; omit for every project in the workspace"),
      status: z.string().optional().describe("Exact status/bucket name, e.g. 'TOP Priority'"),
      assignee: z.string().optional().describe("Email, or 'me'"),
      dueAfter: z.string().optional().describe(dateDoc("Only tasks due on/after")),
      dueBefore: z.string().optional().describe(dateDoc("Only tasks due on/before")),
      search: z.string().optional().describe("Case-insensitive text match on title/description"),
      includeDone: z.boolean().optional(),
    },
    async (a) => {
      const projects = await loadProjects(ctx);
      let projectId;
      if (a.project) projectId = resolveByName(projects, a.project, "project").id;
      let tasks = await loadTasks(ctx, projectId);
      const pname = (id) => projects.find((p) => p.id === id)?.name ?? "(unknown project)";
      const doneFor = (t) => { const p = projects.find((x) => x.id === t.projectId); const last = (p?.buckets || []).slice(-1)[0]; return /^(done|completed?)$/i.test(t.status) || t.status === last; };
      if (!a.includeDone) tasks = tasks.filter((t) => !doneFor(t));
      if (a.status) tasks = tasks.filter((t) => norm(t.status) === norm(a.status));
      if (a.assignee) { const who = a.assignee === "me" ? ctx.email : a.assignee.trim().toLowerCase(); tasks = tasks.filter((t) => t.assignedMembers.map((m) => m.toLowerCase()).includes(who)); }
      if (a.dueAfter) { const d = parseDate(a.dueAfter, ctx.tz); tasks = tasks.filter((t) => t.dueDate && t.dueDate >= d); }
      if (a.dueBefore) { const d = parseDate(a.dueBefore, ctx.tz, { endOfDay: true }); tasks = tasks.filter((t) => t.dueDate && t.dueDate <= d); }
      if (a.search) { const q = norm(a.search); tasks = tasks.filter((t) => norm(t.title).includes(q) || norm(t.description).includes(q)); }
      tasks.sort((x, y) => (x.dueDate ?? Infinity) - (y.dueDate ?? Infinity) || x.title.localeCompare(y.title));
      return json({ count: tasks.length, tasks: tasks.map((t) => taskSummary(t, ctx, pname(t.projectId))) });
    });

  reg("get_task",
    "Full detail for one task including every checklist and item (with item IDs), attachments, and the schedule phase it is linked to (phaseId and phase), if any.",
    { task: z.string().describe("Task title or ID"), project: z.string().optional().describe("Project name or ID to narrow the lookup") },
    async (a) => {
      const t = await findTask(ctx, a.task, a.project);
      const p = (await loadProjects(ctx)).find((x) => x.id === t.projectId);
      // The link is stored on the phase, not the task (see schedule.js), so look it up there.
      const phase = (await loadPhases(ctx, t.projectId)).find((ph) => ph.taskIds.includes(t.id));
      return json({ ...taskDetail(t, ctx, p?.name), phaseId: phase?.id, phase: phase?.name });
    });

  reg("create_task",
    "Create a task card in a project. Status must be one of the project's buckets (see get_project) or the task will not show in the planner. Optional checklists are created with it.",
    {
      project: z.string().describe("Project name or ID"),
      title: z.string().min(1),
      description: z.string().optional(),
      status: z.string().optional().describe("One of the project's bucket names; defaults to the first bucket"),
      assignedTo: z.array(z.string()).optional().describe("Emails, or 'me'"),
      startDate: z.string().optional().describe(dateDoc("Start")),
      dueDate: z.string().optional().describe(dateDoc("Due")),
      color: z.string().optional().describe("Hex like #FFC107"),
      checklists: z.array(z.object({ title: z.string(), items: z.array(z.string()) })).optional(),
    },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const buckets = p.buckets && p.buckets.length ? p.buckets : ["Not Started", "In Progress", "Paused", "Done"];
      let status = a.status ? buckets.find((b) => norm(b) === norm(a.status)) : buckets[0];
      if (!status) throw new ToolError(`Status "${a.status}" is not a bucket in ${p.name}. Use one of: ${buckets.join(", ")}.`);
      const assignees = [...new Set((a.assignedTo || []).map((m) => (m === "me" ? ctx.email : m.trim().toLowerCase())))];
      const siblings = await loadTasks(ctx, p.id);
      const dup = siblings.find((t) => norm(t.title) === norm(a.title) && t.status === status);
      if (dup) throw new ToolError(`A task titled "${dup.title}" already exists in ${p.name} (${status}), id ${dup.id}. Use update_task or add_checklist_items instead.`);
      const maxOrder = siblings.filter((t) => t.status === status).reduce((m, t) => Math.max(m, Number(t.order) || 0), 0);
      const ref = ctx.db.collection("tasks").doc();
      const task = {
        id: ref.id,
        environmentId: ctx.envId,
        projectId: p.id,
        title: a.title.trim(),
        description: a.description ?? "",
        status,
        assignedTo: assignees[0],
        assignedMembers: assignees,
        color: a.color ?? "#FFFFFF",
        startDate: parseDate(a.startDate, ctx.tz),
        dueDate: parseDate(a.dueDate, ctx.tz, { endOfDay: false }),
        order: maxOrder + 1,
        checklistGroups: (a.checklists || []).map((g) => ({
          id: newId("chk_grp_"),
          title: g.title.trim(),
          items: g.items.map((txt) => ({ id: newId("chk_"), text: txt.trim(), isDone: false })).filter((i) => i.text),
        })).filter((g) => g.items.length || g.title),
        attachments: [],
      };
      const saved = await saveTask(ctx, task);
      return json({ created: true, ...taskDetail(saved, ctx, p.name) });
    });

  reg("update_task",
    "Change a task's title, description, status, assignees, dates, or color. Only the fields you pass change. Status must be one of the project's buckets.",
    {
      task: z.string().describe("Task title or ID"),
      project: z.string().optional().describe("Project name or ID to narrow the lookup"),
      title: z.string().optional(), description: z.string().optional(),
      status: z.string().optional(),
      assignedTo: z.array(z.string()).optional().describe("Replace assignees. Emails or 'me'"),
      addAssignees: z.array(z.string()).optional(), removeAssignees: z.array(z.string()).optional(),
      startDate: z.string().optional().describe(dateDoc("Start")), dueDate: z.string().optional().describe(dateDoc("Due")),
      clearStartDate: z.boolean().optional(), clearDueDate: z.boolean().optional(),
      color: z.string().optional(),
    },
    async (a) => {
      const t = await findTask(ctx, a.task, a.project);
      const p = (await loadProjects(ctx)).find((x) => x.id === t.projectId);
      const changed = [];
      if (a.title !== undefined) { t.title = a.title.trim(); changed.push("title"); }
      if (a.description !== undefined) { t.description = a.description; changed.push("description"); }
      if (a.color !== undefined) { t.color = a.color; changed.push("color"); }
      if (a.status !== undefined) {
        const buckets = p?.buckets && p.buckets.length ? p.buckets : ["Not Started", "In Progress", "Paused", "Done"];
        const s = buckets.find((b) => norm(b) === norm(a.status));
        if (!s) throw new ToolError(`Status "${a.status}" is not a bucket in ${p?.name}. Use one of: ${buckets.join(", ")}.`);
        if (s !== t.status) {
          const siblings = (await loadTasks(ctx, t.projectId)).filter((x) => x.status === s);
          t.order = siblings.reduce((m, x) => Math.max(m, Number(x.order) || 0), 0) + 1;
          t.status = s; changed.push("status");
        }
      }
      const mapWho = (xs) => (xs || []).map((m) => (m === "me" ? ctx.email : m.trim().toLowerCase()));
      if (a.assignedTo) { t.assignedMembers = [...new Set(mapWho(a.assignedTo))]; changed.push("assignees"); }
      if (a.addAssignees) { for (const m of mapWho(a.addAssignees)) if (!t.assignedMembers.includes(m)) t.assignedMembers.push(m); changed.push("assignees"); }
      if (a.removeAssignees) { const rm = mapWho(a.removeAssignees); t.assignedMembers = t.assignedMembers.filter((m) => !rm.includes(m.toLowerCase())); changed.push("assignees"); }
      if (a.assignedTo || a.addAssignees || a.removeAssignees) t.assignedTo = t.assignedMembers[0];
      if (a.clearStartDate) { t.startDate = undefined; changed.push("startDate"); } else if (a.startDate) { t.startDate = parseDate(a.startDate, ctx.tz); changed.push("startDate"); }
      if (a.clearDueDate) { t.dueDate = undefined; changed.push("dueDate"); } else if (a.dueDate) { t.dueDate = parseDate(a.dueDate, ctx.tz); changed.push("dueDate"); }
      if (!changed.length) throw new ToolError("Nothing to update — pass at least one field.");
      // Firestore `set` without merge drops cleared fields, which is what we want for clear*Date.
      const saved = await saveTask(ctx, t);
      return json({ updated: [...new Set(changed)], ...taskSummary(saved, ctx, p?.name) });
    });

  reg("delete_task",
    "Permanently delete a task card and its checklists. Ask the person before calling this on anything that isn't obviously a duplicate or test.",
    { task: z.string().describe("Task title or ID"), project: z.string().optional() },
    async (a) => {
      const t = await findTask(ctx, a.task, a.project);
      await ctx.db.collection("tasks").doc(t.id).delete();
      return text(`Deleted task "${t.title}" (id ${t.id}).`);
    });

  // ---- checklists --------------------------------------------------------------------------
  reg("add_checklist_items",
    "Add items to a task's checklist. If a checklist with that title already exists on the task the items are APPENDED to it; otherwise a new checklist is created. Never creates duplicates.",
    {
      task: z.string().describe("Task title or ID"),
      project: z.string().optional(),
      checklistTitle: z.string().optional().describe("Defaults to 'Checklist'"),
      items: z.array(z.string()).min(1),
    },
    async (a) => {
      const t = await findTask(ctx, a.task, a.project);
      const title = (a.checklistTitle || "Checklist").trim();
      let g = t.checklistGroups.find((x) => norm(x.title) === norm(title));
      if (!g) { g = { id: newId("chk_grp_"), title, items: [] }; t.checklistGroups.push(g); }
      g.items = g.items || [];
      const added = [];
      for (const raw of a.items) {
        const txt = raw.trim();
        if (!txt) continue;
        if (g.items.some((i) => norm(i.text) === norm(txt))) continue; // skip exact dupes
        const item = { id: newId("chk_"), text: txt, isDone: false };
        g.items.push(item); added.push(item);
      }
      const saved = await saveTask(ctx, t);
      const sg = saved.checklistGroups.find((x) => x.id === g.id);
      return json({ task: saved.title, checklist: sg.title, added: added.map((i) => ({ id: i.id, text: i.text })), nowHas: sg.items.length });
    });

  reg("set_checklist_item",
    "Check or uncheck one checklist item on a task, by item ID or by its text.",
    {
      task: z.string().describe("Task title or ID"),
      project: z.string().optional(),
      item: z.string().describe("Item ID or item text (case-insensitive)"),
      done: z.boolean(),
      checklistTitle: z.string().optional().describe("Narrow to one checklist when the same text appears in several"),
    },
    async (a) => {
      const t = await findTask(ctx, a.task, a.project);
      const groups = a.checklistTitle ? t.checklistGroups.filter((g) => norm(g.title) === norm(a.checklistTitle)) : t.checklistGroups;
      const hits = [];
      for (const g of groups) for (const i of g.items || []) if (i.id === a.item || norm(i.text) === norm(a.item)) hits.push({ g, i });
      if (!hits.length) throw new ToolError(`No checklist item "${a.item}" on task "${t.title}".`);
      if (hits.length > 1) throw new ToolError(`"${a.item}" matches ${hits.length} items (${hits.map((h) => `${h.g.title}: ${h.i.text} [${h.i.id}]`).join("; ")}). Use the item id or checklistTitle.`);
      hits[0].i.isDone = a.done;
      const saved = await saveTask(ctx, t);
      return json({ task: saved.title, checklist: hits[0].g.title, item: hits[0].i.text, done: a.done, progress: checklistProgress(saved) });
    });

  reg("remove_checklist_item",
    "Remove one checklist item (or an entire empty checklist) from a task.",
    {
      task: z.string(), project: z.string().optional(),
      item: z.string().optional().describe("Item ID or text to remove"),
      checklistTitle: z.string().optional().describe("With no item: remove this whole checklist"),
    },
    async (a) => {
      const t = await findTask(ctx, a.task, a.project);
      if (a.item) {
        let removed;
        for (const g of t.checklistGroups) {
          const idx = (g.items || []).findIndex((i) => i.id === a.item || norm(i.text) === norm(a.item));
          if (idx >= 0) { removed = g.items.splice(idx, 1)[0]; break; }
        }
        if (!removed) throw new ToolError(`No checklist item "${a.item}" on task "${t.title}".`);
        await saveTask(ctx, t);
        return text(`Removed "${removed.text}" from "${t.title}".`);
      }
      if (a.checklistTitle) {
        const before = t.checklistGroups.length;
        t.checklistGroups = t.checklistGroups.filter((g) => norm(g.title) !== norm(a.checklistTitle));
        if (t.checklistGroups.length === before) throw new ToolError(`No checklist titled "${a.checklistTitle}" on "${t.title}".`);
        await saveTask(ctx, t);
        return text(`Removed checklist "${a.checklistTitle}" from "${t.title}".`);
      }
      throw new ToolError("Pass item or checklistTitle.");
    });

  // ---- appointments ------------------------------------------------------------------------
  reg("list_appointments",
    "Appointments/site-calendar events in a date window, with recurring events expanded into each occurrence. Defaults to now → 30 days. Times are shown in the connector's timezone. An appointment linked to a schedule phase also shows phaseId and phase.",
    {
      project: z.string().optional().describe("Project name or ID; omit for every project"),
      afterDate: z.string().optional().describe(dateDoc("Window start")),
      beforeDate: z.string().optional().describe(dateDoc("Window end")),
    },
    async (a) => {
      const projects = await loadProjects(ctx);
      const targets = a.project ? [resolveByName(projects, a.project, "project")] : projects;
      const from = a.afterDate ? parseDate(a.afterDate, ctx.tz) : Date.now();
      const to = a.beforeDate ? parseDate(a.beforeDate, ctx.tz, { endOfDay: true }) : from + 30 * 86400000;
      const out = [];
      for (const p of targets) {
        const [appts, phases] = await Promise.all([loadAppointments(ctx, p.id), loadPhases(ctx, p.id)]);
        for (const appt of appts) {
          const phase = phases.find((ph) => ph.appointmentIds.includes(appt.id));
          for (const inst of expandAppointment(appt, from, to, ctx.tz)) out.push({ inst, name: p.name, phase });
        }
      }
      out.sort((x, y) => x.inst.instanceStart - y.inst.instanceStart);
      return json({ window: { from: fmtDateTime(from, ctx.tz), to: fmtDateTime(to, ctx.tz) }, count: out.length, appointments: out.map((o) => ({ ...apptView(o.inst, ctx, o.name), phaseId: o.phase?.id, phase: o.phase?.name })) });
    });

  reg("create_appointment",
    "Add an appointment to a project's site calendar. Times are local to the connector's timezone unless you pass an offset.",
    {
      project: z.string().describe("Project name or ID"),
      title: z.string().min(1),
      start: z.string().describe(dateDoc("Start")),
      end: z.string().optional().describe("Defaults to one hour after start (or the same day for all-day)"),
      isAllDay: z.boolean().optional(),
      recurrence: z.enum(["None", "Daily", "Weekly", "Monthly"]).optional(),
      location: z.string().optional(), description: z.string().optional(), notes: z.string().optional(),
      color: z.string().optional().describe("Hex, default #2196F3"),
    },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const start = parseDate(a.start, ctx.tz);
      let end = a.end ? parseDate(a.end, ctx.tz, { endOfDay: !!a.isAllDay }) : (a.isAllDay ? start : start + 3600000);
      if (end < start) throw new ToolError("end is before start.");
      const existing = await loadAppointments(ctx, p.id);
      const dup = existing.find((x) => norm(x.title) === norm(a.title) && Number(x.startDate) === start);
      if (dup) throw new ToolError(`"${dup.title}" already exists at that time (id ${dup.id}).`);
      const ref = ctx.db.collection("projects").doc(p.id).collection("appointments").doc();
      const doc = {
        id: ref.id, projectId: p.id, title: a.title.trim(),
        description: a.description ?? "", notes: a.notes ?? "", location: a.location ?? "",
        color: a.color ?? "#2196F3", isAllDay: !!a.isAllDay,
        startDate: start, endDate: end, recurrence: a.recurrence ?? "None", createdBy: ctx.email,
      };
      await ref.set(doc);
      const back = (await ref.get()).data();
      return json({ created: true, ...apptView({ ...back, id: ref.id }, ctx, p.name) });
    });

  reg("update_appointment",
    "Change an appointment's title, time, recurrence, location or notes. Only the fields you pass change.",
    {
      appointment: z.string().describe("Appointment ID (from list_appointments) or exact title"),
      project: z.string().describe("Project name or ID"),
      title: z.string().optional(),
      start: z.string().optional().describe(dateDoc("New start")), end: z.string().optional(),
      isAllDay: z.boolean().optional(),
      recurrence: z.enum(["None", "Daily", "Weekly", "Monthly"]).optional(),
      location: z.string().optional(), description: z.string().optional(), notes: z.string().optional(), color: z.string().optional(),
    },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const appts = await loadAppointments(ctx, p.id);
      const target = resolveByName(appts.map((x) => ({ ...x, name: x.title })), a.appointment, "appointment");
      const patch = {};
      for (const k of ["title", "location", "description", "notes", "color", "recurrence"]) if (a[k] !== undefined) patch[k] = k === "title" ? a[k].trim() : a[k];
      if (a.isAllDay !== undefined) patch.isAllDay = a.isAllDay;
      const dur = Number(target.endDate || target.startDate) - Number(target.startDate);
      if (a.start) { patch.startDate = parseDate(a.start, ctx.tz); patch.endDate = a.end ? parseDate(a.end, ctx.tz) : patch.startDate + Math.max(0, dur); }
      else if (a.end) patch.endDate = parseDate(a.end, ctx.tz);
      if (patch.endDate !== undefined && patch.endDate < (patch.startDate ?? Number(target.startDate))) throw new ToolError("end is before start.");
      if (!Object.keys(patch).length) throw new ToolError("Nothing to update.");
      const ref = ctx.db.collection("projects").doc(p.id).collection("appointments").doc(target.id);
      await ref.update(patch);
      const back = (await ref.get()).data();
      return json({ updated: Object.keys(patch), ...apptView({ ...back, id: target.id }, ctx, p.name) });
    });

  reg("delete_appointment",
    "Delete an appointment (all occurrences if recurring).",
    { appointment: z.string().describe("Appointment ID or exact title"), project: z.string().describe("Project name or ID") },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const appts = await loadAppointments(ctx, p.id);
      const target = resolveByName(appts.map((x) => ({ ...x, name: x.title })), a.appointment, "appointment");
      await ctx.db.collection("projects").doc(p.id).collection("appointments").doc(target.id).delete();
      return text(`Deleted "${target.title}" (${fmtDateTime(target.startDate, ctx.tz)}${target.recurrence && target.recurrence !== "None" ? ", " + target.recurrence : ""}) from ${p.name}.`);
    });

  // ---- schedule ----------------------------------------------------------------------------
  // Phases sit above the tasks and appointments that do the work. The links are stored on the
  // phase (taskIds / appointmentIds), so none of these tools ever writes a task or appointment.
  const dayDoc = (what) => `${what} as YYYY-MM-DD (or 'today' / 'tomorrow').`;
  const statusDoc = `One of: ${schedule.STATUSES.join(", ")}`;
  const phaseStatus = (raw) => {
    const s = schedule.matchStatus(raw);
    if (!s) throw new ToolError(`Status "${raw}" is not a phase status. Use one of: ${schedule.STATUSES.join(", ")}.`);
    return s;
  };
  const predecessorSchema = z.array(z.object({
    phase: z.string().describe("Name or ID of the phase that must come first"),
    type: z.enum(["FS", "SS"]).optional().describe("FS = starts after that phase finishes (default); SS = starts when that phase starts"),
    lagDays: z.number().int().optional().describe("Working days to wait; negative for an overlap (-1 starts on the other phase's last day)"),
  }));
  /** Names or ids to stored predecessor links. Refuses a phase waiting on itself, directly or round a loop. */
  const resolvePredecessors = (sc, selfId, list) => {
    const out = [];
    for (const x of list) {
      const other = findPhase(sc, x.phase);
      if (other.id === selfId) throw new ToolError("A phase cannot be its own predecessor.");
      if (!out.some((o) => o.phaseId === other.id)) out.push({ phaseId: other.id, type: x.type || "FS", lagDays: x.lagDays || 0 });
    }
    const loop = selfId ? schedule.findLoop(sc.phases, selfId, out.map((o) => o.phaseId)) : null;
    if (loop) throw new ToolError(`That would make a loop in the predecessors: ${loop.map((id) => sc.phases.find((x) => x.id === id)?.name ?? id).join(" waits for ")}. Nothing was saved.`);
    return out;
  };
  /** Task and appointment names or ids to ids. With allowStale, an id still listed on a phase is accepted even if its record is gone. */
  const resolveLinks = (sc, a, { allowStale = false } = {}) => {
    const pick = (list, candidates, label, stored) => [...new Set((list || []).map((q) => {
      try {
        return resolveByName(candidates, q, label).id;
      } catch (e) {
        const raw = String(q).trim();
        if (allowStale && sc.phases.some((x) => x[stored].includes(raw))) return raw;
        throw e;
      }
    }))];
    return {
      taskIds: pick(a.tasks, sc.tasks.map((t) => ({ ...t, name: t.title })), "task", "taskIds"),
      apptIds: pick(a.appointments, sc.appts.map((x) => ({ ...x, name: x.title })), "appointment", "appointmentIds"),
    };
  };

  reg("list_schedule",
    "The project schedule: every phase in order with its dates, working days, status, percent complete, slip against the baseline in working days (positive = late), and how many tasks and appointments are linked to it. Also the totals for the whole schedule and the finish date against the plan. Pass from/to for only the phases that overlap a date window.",
    {
      project: z.string().describe("Project name or ID"),
      from: z.string().optional().describe(dayDoc("Only phases still running on or after this day")),
      to: z.string().optional().describe(dayDoc("Only phases that start on or before this day")),
    },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const sc = await loadSchedule(ctx, p);
      const from = parseDay(a.from, ctx.tz), to = parseDay(a.to, ctx.tz);
      const windowed = from !== undefined || to !== undefined;
      const rows = sc.phases.filter((x) => {
        if (!windowed) return true;
        if (x.startDay === null || x.endDay === null) return false;
        return (from === undefined || x.endDay >= from) && (to === undefined || x.startDay <= to);
      });
      return json({
        project: p.name,
        today: schedule.fmtDay(sc.today),
        workWeek: sc.cal.workWeek.map((n) => schedule.WEEKDAYS[n - 1]),
        holidays: sc.cal.holidays.length ? sc.cal.holidays : undefined,
        window: windowed ? { from: schedule.fmtDay(from ?? null), to: schedule.fmtDay(to ?? null) } : undefined,
        totals: sc.phases.length ? scheduleTotals(sc) : undefined,
        count: rows.length,
        note: sc.phases.length ? undefined : "No schedule yet. Use create_phase or import_schedule to start one.",
        phases: rows.map((x) => phaseSummary(x, sc)),
      });
    });

  reg("get_phase",
    "One schedule phase in full: dates, status, percent, slip, notes, the phases it waits for (predecessors) and the phases waiting on it, its linked tasks with checklist progress, and its linked appointments.",
    { project: z.string().describe("Project name or ID"), phase: z.string().describe("Phase name or ID") },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const sc = await loadSchedule(ctx, p);
      return json(phaseDetail(findPhase(sc, a.phase), sc, ctx));
    });

  reg("create_phase",
    "Add a phase to a project's schedule (e.g. 'Drywall'). Give start with end or durationDays (working days: weekends and the project's holidays are skipped), or one date with milestone: true. A phase with no dates yet is allowed. It goes at the bottom of the schedule unless you pass order. This does not set the baseline - use set_baseline for that.",
    {
      project: z.string().describe("Project name or ID"),
      name: z.string().min(1),
      group: z.string().optional().describe("The band it is drawn in, e.g. 'Exterior / Site', 'Rough trades', 'Interior', 'Closeout'"),
      start: z.string().optional().describe(dayDoc("First working day")),
      end: z.string().optional().describe(dayDoc("Last working day, inclusive")),
      durationDays: z.number().int().min(1).optional().describe("Working days, counting both the first and the last day"),
      milestone: z.boolean().optional().describe("A one-day marker such as 'Roof complete'"),
      predecessors: predecessorSchema.optional(),
      trade: z.string().optional(),
      contact: z.string().optional().describe("Contact name or ID (see list_contacts). A name that is not a contact is kept as plain text."),
      notes: z.string().optional(),
      status: z.string().optional().describe(`${statusDoc}. Default Not started`),
      percentComplete: z.number().int().min(0).max(100).optional().describe("Sets a manual percent. Leave out to have it worked out from the linked tasks' checklists."),
      color: z.string().optional().describe("Hex like #2196F3. Leave out to colour the bar by status."),
      order: z.number().optional().describe("Position in the schedule; lower comes first. Defaults to the bottom."),
    },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const sc = await loadSchedule(ctx, p);
      const name = a.name.trim();
      const dup = sc.phases.find((x) => norm(x.name) === norm(name));
      if (dup) throw new ToolError(`A phase named "${dup.name}" already exists in ${p.name} (id ${dup.id}). Use update_phase instead.`);
      const dates = resolvePhaseDates(sc.cal, {}, { start: parseDay(a.start, ctx.tz), end: parseDay(a.end, ctx.tz), durationDays: a.durationDays, milestone: a.milestone });
      const contact = await phaseContact(ctx, a.contact);
      const ref = phasesCol(ctx, p.id).doc();
      const now = Date.now();
      await ref.set(schedule.phaseWriteMap({
        id: ref.id, projectId: p.id, environmentId: ctx.envId,
        name, group: (a.group ?? "").trim(),
        order: a.order ?? sc.phases.reduce((m, x) => Math.max(m, x.order), 0) + 1,
        start: dayToMs(dates.startDay, ctx.tz), end: dayToMs(dates.endDay, ctx.tz),
        durationDays: dates.durationDays, isMilestone: dates.isMilestone,
        predecessors: a.predecessors ? resolvePredecessors(sc, null, a.predecessors) : [],
        trade: a.trade ?? "", contactId: contact.contactId, contactName: contact.contactName,
        status: a.status !== undefined ? phaseStatus(a.status) : "Not started",
        percentComplete: a.percentComplete ?? 0, percentMode: a.percentComplete !== undefined ? "manual" : "auto",
        notes: a.notes ?? "", color: a.color ?? "",
        createdAt: now, updatedAt: now, updatedBy: ctx.email,
      }));
      const after = await loadSchedule(ctx, p);
      const saved = after.phases.find((x) => x.id === ref.id);
      if (!saved) throw new ToolError("Write did not persist (phase not found after save).");
      return json({ created: true, ...phaseDetail(saved, after, ctx), contactLinked: a.contact ? contact.linked : undefined });
    });

  reg("update_phase",
    "Change a schedule phase. Only the fields you pass change. Dates: a new start alone moves the phase and keeps its length; a new end alone stretches or shrinks it; start + end, start + durationDays or end + durationDays set it exactly. The baseline never moves here. The phases that follow are NOT shifted - check list_schedule afterwards and tell the person if later phases now overlap or start too early. Setting percentComplete switches the phase to a manual percent; percentMode 'auto' goes back to working it out from the linked tasks' checklists.",
    {
      project: z.string().describe("Project name or ID"),
      phase: z.string().describe("Phase name or ID"),
      name: z.string().min(1).optional(),
      group: z.string().optional(),
      start: z.string().optional().describe(dayDoc("First working day")),
      end: z.string().optional().describe(dayDoc("Last working day, inclusive")),
      durationDays: z.number().int().min(1).optional().describe("Working days, counting both the first and the last day"),
      clearDates: z.boolean().optional().describe("Remove the dates; the phase stays on the schedule, undated"),
      milestone: z.boolean().optional(),
      predecessors: predecessorSchema.optional().describe("Replaces the whole list; pass [] to remove them all"),
      trade: z.string().optional(),
      contact: z.string().optional().describe("Contact name or ID; an empty string clears it"),
      notes: z.string().optional(),
      status: z.string().optional().describe(statusDoc),
      percentComplete: z.number().int().min(0).max(100).optional(),
      percentMode: z.enum(["auto", "manual"]).optional(),
      color: z.string().optional().describe("Hex like #2196F3; an empty string goes back to colouring by status"),
      order: z.number().optional().describe("Position in the schedule; lower comes first"),
    },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const sc = await loadSchedule(ctx, p);
      const ph = findPhase(sc, a.phase);
      const patch = {};
      if (a.name !== undefined) {
        const name = a.name.trim();
        const dup = sc.phases.find((x) => x.id !== ph.id && norm(x.name) === norm(name));
        if (dup) throw new ToolError(`Another phase is already named "${dup.name}" (id ${dup.id}).`);
        patch.name = name;
      }
      if (a.group !== undefined) patch.group = a.group.trim();
      for (const k of ["trade", "notes"]) if (a[k] !== undefined) patch[k] = a[k];
      if (a.color !== undefined) patch.color = a.color.trim() ? a.color.trim() : FieldValue.delete();
      if (a.order !== undefined) patch.order = a.order;
      if (a.status !== undefined) patch.status = phaseStatus(a.status);
      if (a.percentComplete !== undefined) { patch.percentComplete = a.percentComplete; patch.percentMode = "manual"; }
      if (a.percentMode !== undefined) patch.percentMode = a.percentMode;
      if (a.contact !== undefined) { const c = await phaseContact(ctx, a.contact); patch.contactId = c.contactId; patch.contactName = c.contactName; }
      if (a.predecessors !== undefined) patch.predecessors = listOrDelete(resolvePredecessors(sc, ph.id, a.predecessors));
      if (a.clearDates) {
        if (a.start !== undefined || a.end !== undefined) throw new ToolError("Pass either clearDates or new dates, not both.");
        patch.start = FieldValue.delete();
        patch.end = FieldValue.delete();
        if (a.durationDays !== undefined) patch.durationDays = a.durationDays;
        if (a.milestone !== undefined) patch.isMilestone = a.milestone;
      } else if (["start", "end", "durationDays", "milestone"].some((k) => a[k] !== undefined)) {
        const d = resolvePhaseDates(sc.cal, ph, { start: parseDay(a.start, ctx.tz), end: parseDay(a.end, ctx.tz), durationDays: a.durationDays, milestone: a.milestone });
        patch.start = d.startDay === null ? FieldValue.delete() : dayToMs(d.startDay, ctx.tz);
        patch.end = d.endDay === null ? FieldValue.delete() : dayToMs(d.endDay, ctx.tz);
        patch.durationDays = d.durationDays;
        patch.isMilestone = d.isMilestone;
      }
      if (!Object.keys(patch).length) throw new ToolError("Nothing to update — pass at least one field.");
      const updated = Object.keys(a).filter((k) => k !== "project" && k !== "phase" && a[k] !== undefined);
      // A targeted update, never a whole-document save, so fields this build doesn't know about survive.
      await phasesCol(ctx, p.id).doc(ph.id).update({ ...patch, updatedAt: Date.now(), updatedBy: ctx.email });
      const after = await loadSchedule(ctx, p);
      return json({ updated, ...phaseDetail(after.phases.find((x) => x.id === ph.id), after, ctx) });
    });

  reg("delete_phase",
    "Permanently remove a phase from the schedule. Its linked tasks and appointments are kept exactly as they are and simply come off the phase, and it is removed from the predecessors of any phase that was waiting on it. Ask the person before calling this on anything that isn't obviously a duplicate or test.",
    { project: z.string().describe("Project name or ID"), phase: z.string().describe("Phase name or ID") },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const sc = await loadSchedule(ctx, p);
      const ph = findPhase(sc, a.phase);
      const waiting = sc.phases.filter((x) => x.predecessors.some((q) => q.phaseId === ph.id));
      const now = Date.now();
      const batch = ctx.db.batch();
      batch.delete(phasesCol(ctx, p.id).doc(ph.id));
      for (const x of waiting) {
        batch.update(phasesCol(ctx, p.id).doc(x.id), { predecessors: listOrDelete(x.predecessors.filter((q) => q.phaseId !== ph.id)), updatedAt: now, updatedBy: ctx.email });
      }
      await batch.commit();
      const nTasks = ph.taskIds.filter((id) => sc.taskById.has(id)).length;
      const nAppts = ph.appointmentIds.filter((id) => sc.apptById.has(id)).length;
      return text(`Deleted phase "${ph.name}" (id ${ph.id}) from ${p.name}.`
        + (nTasks || nAppts ? ` Its ${nTasks} linked task(s) and ${nAppts} linked appointment(s) are kept and are no longer on a phase.` : "")
        + (waiting.length ? ` Removed it as a predecessor of: ${waiting.map((x) => x.name).join(", ")}.` : ""));
    });

  reg("link_to_phase",
    "Put existing tasks and/or site-calendar appointments on a schedule phase, so the phase shows their checklist progress and visits. A task or appointment is on at most one phase: linking one that is already on another phase moves it. The tasks and appointments themselves are not changed.",
    {
      project: z.string().describe("Project name or ID"),
      phase: z.string().describe("Phase name or ID"),
      tasks: z.array(z.string()).optional().describe("Task titles or IDs"),
      appointments: z.array(z.string()).optional().describe("Appointment IDs (from list_appointments) or exact titles"),
    },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const sc = await loadSchedule(ctx, p);
      const target = findPhase(sc, a.phase);
      const { taskIds, apptIds } = resolveLinks(sc, a);
      if (!taskIds.length && !apptIds.length) throw new ToolError("Pass tasks and/or appointments to link.");
      const now = Date.now();
      const batch = ctx.db.batch();
      const movedFrom = [];
      for (const x of sc.phases) {
        const isTarget = x.id === target.id;
        const nextTasks = isTarget ? [...new Set([...x.taskIds, ...taskIds])] : x.taskIds.filter((id) => !taskIds.includes(id));
        const nextAppts = isTarget ? [...new Set([...x.appointmentIds, ...apptIds])] : x.appointmentIds.filter((id) => !apptIds.includes(id));
        if (nextTasks.length === x.taskIds.length && nextAppts.length === x.appointmentIds.length) continue;
        if (!isTarget) movedFrom.push(x.name);
        batch.update(phasesCol(ctx, p.id).doc(x.id), { taskIds: listOrDelete(nextTasks), appointmentIds: listOrDelete(nextAppts), updatedAt: now, updatedBy: ctx.email });
      }
      await batch.commit();
      const after = await loadSchedule(ctx, p);
      return json({
        linked: { tasks: taskIds.map((id) => sc.taskById.get(id).title), appointments: apptIds.map((id) => sc.apptById.get(id).title) },
        movedFrom: movedFrom.length ? movedFrom : undefined,
        ...phaseDetail(after.phases.find((x) => x.id === target.id), after, ctx),
      });
    });

  reg("unlink_from_phase",
    "Take tasks and/or appointments off their schedule phase. The tasks and appointments themselves are kept unchanged. Pass phase to unlink only from that phase; leave it out to unlink each one from whichever phase it is on.",
    {
      project: z.string().describe("Project name or ID"),
      phase: z.string().optional().describe("Phase name or ID"),
      tasks: z.array(z.string()).optional().describe("Task titles or IDs"),
      appointments: z.array(z.string()).optional().describe("Appointment IDs or exact titles"),
    },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const sc = await loadSchedule(ctx, p);
      const only = a.phase ? findPhase(sc, a.phase) : null;
      const { taskIds, apptIds } = resolveLinks(sc, a, { allowStale: true });
      if (!taskIds.length && !apptIds.length) throw new ToolError("Pass tasks and/or appointments to unlink.");
      const now = Date.now();
      const batch = ctx.db.batch();
      const from = [];
      let unlinked = 0;
      for (const x of sc.phases) {
        if (only && x.id !== only.id) continue;
        const nextTasks = x.taskIds.filter((id) => !taskIds.includes(id));
        const nextAppts = x.appointmentIds.filter((id) => !apptIds.includes(id));
        const n = (x.taskIds.length - nextTasks.length) + (x.appointmentIds.length - nextAppts.length);
        if (!n) continue;
        unlinked += n;
        from.push(x.name);
        batch.update(phasesCol(ctx, p.id).doc(x.id), { taskIds: listOrDelete(nextTasks), appointmentIds: listOrDelete(nextAppts), updatedAt: now, updatedBy: ctx.email });
      }
      await batch.commit();
      if (!unlinked) return text(only ? `Nothing to unlink: none of those are on "${only.name}".` : "Nothing to unlink: none of those are on a phase.");
      return json({ unlinked, from });
    });

  reg("import_schedule",
    "Load a whole schedule in one go, one row per phase. Each row needs a name and either start + end, start + durationDays, or one date with milestone: true. Rows are added below the existing phases in the order given. A row whose name is already a phase is skipped, so the same import can safely be run again. asBaseline also records these dates as the baseline (the original plan that slip is measured against). dryRun returns what would be created and any problems WITHOUT saving - run it first and show the person. If any row has a problem nothing at all is saved.",
    {
      project: z.string().describe("Project name or ID"),
      rows: z.array(z.object({
        name: z.string(),
        start: z.string().optional().describe("YYYY-MM-DD"),
        end: z.string().optional().describe("YYYY-MM-DD, the last working day"),
        durationDays: z.number().int().min(1).optional(),
        group: z.string().optional(),
        milestone: z.boolean().optional(),
        trade: z.string().optional(),
        notes: z.string().optional(),
        status: z.string().optional().describe(statusDoc),
      })).min(1).max(300),
      asBaseline: z.boolean().optional().describe("Also set the baseline to these dates"),
      dryRun: z.boolean().optional().describe("Check and preview only; save nothing"),
    },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const sc = await loadSchedule(ctx, p);
      const existing = new Set(sc.phases.map((x) => norm(x.name)));
      const seen = new Set();
      const make = [], skipped = [], problems = [];
      a.rows.forEach((row, i) => {
        const name = String(row.name ?? "").trim();
        try {
          if (!name) throw new ToolError("No name.");
          if (seen.has(norm(name))) throw new ToolError("Same name as an earlier row.");
          seen.add(norm(name));
          if (existing.has(norm(name))) { skipped.push(name); return; }
          const start = parseDay(row.start, ctx.tz), end = parseDay(row.end, ctx.tz);
          if (start === undefined && end === undefined) throw new ToolError("No dates.");
          const dates = resolvePhaseDates(sc.cal, {}, { start, end, durationDays: row.durationDays, milestone: row.milestone });
          make.push({ name, group: (row.group ?? "").trim(), ...dates, trade: row.trade ?? "", notes: row.notes ?? "", status: row.status !== undefined ? phaseStatus(row.status) : "Not started" });
        } catch (e) {
          problems.push(`Row ${i + 1}${name ? ` (${name})` : ""}: ${e.message}`);
        }
      });
      const preview = (m) => ({
        name: m.name, group: m.group || undefined,
        start: schedule.fmtDay(m.startDay), end: schedule.fmtDay(m.endDay), workingDays: m.durationDays,
        milestone: m.isMilestone || undefined, status: m.status !== "Not started" ? m.status : undefined,
      });
      if (a.dryRun) {
        return json({
          dryRun: true, saved: false, project: p.name, wouldCreate: make.length, asBaseline: !!a.asBaseline,
          phases: make.map(preview),
          skippedAlreadyOnSchedule: skipped.length ? skipped : undefined,
          problems: problems.length ? problems : undefined,
          note: problems.length ? "Nothing will be saved while any row has a problem. Fix them, then run without dryRun." : undefined,
        });
      }
      if (problems.length) throw new ToolError(`Nothing was saved. Fix these rows and run it again:\n- ${problems.join("\n- ")}`);
      if (!make.length) return json({ created: 0, skippedAlreadyOnSchedule: skipped, note: "Every row is already on the schedule." });
      const now = Date.now();
      let order = sc.phases.reduce((m, x) => Math.max(m, x.order), 0);
      for (let i = 0; i < make.length; i += 400) { // a Firestore batch holds at most 500 writes
        const batch = ctx.db.batch();
        for (const m of make.slice(i, i + 400)) {
          const ref = phasesCol(ctx, p.id).doc();
          const start = dayToMs(m.startDay, ctx.tz), end = dayToMs(m.endDay, ctx.tz);
          batch.set(ref, schedule.phaseWriteMap({
            id: ref.id, projectId: p.id, environmentId: ctx.envId,
            name: m.name, group: m.group, order: ++order,
            start, end, durationDays: m.durationDays, isMilestone: m.isMilestone,
            trade: m.trade, notes: m.notes, status: m.status,
            baselineStart: a.asBaseline ? start : null, baselineEnd: a.asBaseline ? end : null,
            createdAt: now, updatedAt: now, updatedBy: ctx.email,
          }));
        }
        await batch.commit();
      }
      const after = await loadSchedule(ctx, p);
      const made = new Set(make.map((m) => norm(m.name)));
      return json({
        created: make.length, baselineSet: !!a.asBaseline,
        skippedAlreadyOnSchedule: skipped.length ? skipped : undefined,
        totals: scheduleTotals(after),
        phases: after.phases.filter((x) => made.has(norm(x.name))).map((x) => phaseSummary(x, after)),
      });
    });

  reg("set_baseline",
    "Record the current dates as the baseline: the original plan that slip is measured against and that shows as ghost bars behind the schedule. For every phase, or only the ones listed. A phase that already has a baseline is left alone unless overwrite is true, so the original plan can't be wiped by accident - ask the person before using overwrite.",
    {
      project: z.string().describe("Project name or ID"),
      phases: z.array(z.string()).optional().describe("Phase names or IDs; leave out for every phase"),
      overwrite: z.boolean().optional().describe("Replace a baseline that is already set"),
    },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const sc = await loadSchedule(ctx, p);
      const targets = a.phases && a.phases.length ? [...new Map(a.phases.map((q) => findPhase(sc, q)).map((x) => [x.id, x])).values()] : sc.phases;
      if (!targets.length) throw new ToolError(`${p.name} has no schedule phases yet.`);
      const undated = targets.filter((x) => x.startDay === null || x.endDay === null);
      const kept = targets.filter((x) => !undated.includes(x) && !a.overwrite && (x.baselineStartDay !== null || x.baselineEndDay !== null));
      const todo = targets.filter((x) => !undated.includes(x) && !kept.includes(x));
      if (todo.length) {
        const now = Date.now();
        const batch = ctx.db.batch();
        for (const x of todo) batch.update(phasesCol(ctx, p.id).doc(x.id), { baselineStart: x.start, baselineEnd: x.end, updatedAt: now, updatedBy: ctx.email });
        await batch.commit();
      }
      const after = await loadSchedule(ctx, p);
      const names = (xs) => (xs.length ? xs.map((x) => x.name) : undefined);
      return json({
        project: p.name,
        baselineSet: todo.map((x) => x.name),
        keptExistingBaseline: names(kept),
        skippedNoDates: names(undated),
        note: kept.length ? "Phases that already had a baseline were left alone. Pass overwrite: true to replace it." : undefined,
        totals: scheduleTotals(after),
      });
    });

  // ---- contacts ----------------------------------------------------------------------------
  reg("list_contacts",
    "Contacts (companies and subcontractors) in the workspace, optionally filtered by a search string across name, company, trade/title, phone and email.",
    { search: z.string().optional(), type: z.enum(["Company", "Subcontractor"]).optional() },
    async (a) => {
      let cs = await loadContacts(ctx);
      if (a.type) cs = cs.filter((c) => c.type === a.type);
      if (a.search) { const q = norm(a.search); cs = cs.filter((c) => [c.name, c.company, c.jobTitle, c.phone, c.secondaryPhone, c.email, c.secondaryEmail, c.notes].some((f) => norm(f).includes(q))); }
      cs.sort((x, y) => x.name.localeCompare(y.name));
      return json({ count: cs.length, contacts: cs.map(contactView) });
    });

  reg("create_contact",
    "Add a contact (a company or a subcontractor) to the workspace.",
    {
      name: z.string().min(1), type: z.enum(["Company", "Subcontractor"]).optional(),
      jobTitle: z.string().optional().describe("Trade or role, e.g. Electrician"), company: z.string().optional(),
      phone: z.string().optional(), secondaryPhone: z.string().optional(), email: z.string().optional(), secondaryEmail: z.string().optional(),
      address: z.string().optional(), notes: z.string().optional(),
    },
    async (a) => {
      const existing = await loadContacts(ctx);
      const dup = existing.find((c) => norm(c.name) === norm(a.name));
      if (dup) throw new ToolError(`Contact "${dup.name}" already exists (id ${dup.id}). Use update_contact.`);
      const ref = ctx.db.collection("contacts").doc();
      const doc = {
        id: ref.id, environmentId: ctx.envId, name: a.name.trim(), jobTitle: a.jobTitle ?? "", company: a.company ?? "",
        email: a.email ?? "", secondaryEmail: a.secondaryEmail ?? "", phone: a.phone ?? "", secondaryPhone: a.secondaryPhone ?? "",
        address: a.address ?? "", notes: a.notes ?? "", type: a.type ?? "Company",
      };
      await ref.set(doc);
      return json({ created: true, ...contactView({ ...(await ref.get()).data(), id: ref.id }) });
    });

  reg("update_contact",
    "Update a contact's details. Only the fields you pass change.",
    {
      contact: z.string().describe("Contact name or ID"),
      name: z.string().optional(), type: z.enum(["Company", "Subcontractor"]).optional(), jobTitle: z.string().optional(), company: z.string().optional(),
      phone: z.string().optional(), secondaryPhone: z.string().optional(), email: z.string().optional(), secondaryEmail: z.string().optional(),
      address: z.string().optional(), notes: z.string().optional(),
    },
    async (a) => {
      const c = resolveByName(await loadContacts(ctx), a.contact, "contact");
      const patch = {};
      for (const k of ["name", "type", "jobTitle", "company", "phone", "secondaryPhone", "email", "secondaryEmail", "address", "notes"]) if (a[k] !== undefined) patch[k] = k === "name" ? a[k].trim() : a[k];
      if (!Object.keys(patch).length) throw new ToolError("Nothing to update.");
      const ref = ctx.db.collection("contacts").doc(c.id);
      await ref.update(patch);
      return json({ updated: Object.keys(patch), ...contactView({ ...(await ref.get()).data(), id: c.id }) });
    });

  reg("delete_contact",
    "Delete a contact.",
    { contact: z.string().describe("Contact name or ID") },
    async (a) => {
      const c = resolveByName(await loadContacts(ctx), a.contact, "contact");
      await ctx.db.collection("contacts").doc(c.id).delete();
      return text(`Deleted contact "${c.name}".`);
    });

  // ---- files -------------------------------------------------------------------------------
  reg("list_files",
    "Files attached to a project (from the project's Files card), with folder names and download URLs.",
    { project: z.string().describe("Project name or ID"), folder: z.string().optional().describe("Folder name to filter by") },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const [fSnap, dSnap] = await Promise.all([
        ctx.db.collection("projects").doc(p.id).collection("files").get(),
        ctx.db.collection("projects").doc(p.id).collection("folders").get(),
      ]);
      const folders = Object.fromEntries(dSnap.docs.map((d) => [d.id, d.data().name]));
      let files = fSnap.docs.map((d) => ({ ...d.data(), id: d.id })).map((f) => ({
        id: f.id, name: f.name, folder: f.folderId ? folders[f.folderId] || f.folderId : undefined,
        uploadedBy: f.uploadedBy || undefined, uploadedAt: f.uploadedAt ? fmtDateTime(Number(f.uploadedAt), ctx.tz) : undefined, url: f.url,
      }));
      if (a.folder) files = files.filter((f) => norm(f.folder) === norm(a.folder));
      files.sort((x, y) => String(x.name).localeCompare(String(y.name)));
      return json({ project: p.name, folders: Object.values(folders), count: files.length, files });
    });

  // ---- broadcasts --------------------------------------------------------------------------
  reg("create_broadcast",
    "Send a SITE ALERT push notification to every Crewsync user. Use sparingly — this interrupts the whole crew. Confirm with the person first.",
    { title: z.string().min(1), message: z.string().min(1), project: z.string().optional().describe("Project name or ID the alert is about") },
    async (a) => {
      let projectId = "";
      if (a.project) projectId = (await findProject(ctx, a.project)).id;
      const ref = ctx.db.collection("broadcasts").doc();
      await ref.set({ id: ref.id, environmentId: ctx.envId, projectId, senderName: ctx.name || ctx.email, title: a.title.trim(), message: a.message, timestamp: Date.now() });
      return text(`Broadcast "${a.title.trim()}" sent (id ${ref.id}).`);
    });

  // ---- team --------------------------------------------------------------------------------
  reg("list_team",
    "Registered Crewsync users in the active workspace (name, email, trade, role) — the people tasks can be assigned to.",
    {},
    async () => {
      const snap = await ctx.db.collection("users").get();
      const users = snap.docs.map((d) => ({ ...d.data(), uid: d.id }))
        .filter((u) => (u.environmentIds || []).includes(ctx.envId) || u.activeEnvironmentId === ctx.envId)
        .map((u) => cleanForWrite({ name: u.name, email: u.email, trade: u.trade, role: u.role, phone: u.phone }));
      users.sort((x, y) => String(x.name || x.email).localeCompare(String(y.name || y.email)));
      return json(users);
    });

  // ---- access keys (admins only) -----------------------------------------------------------
  const requireAdmin = () => {
    if (!/^(super)?admin$/i.test(ctx.role || "")) throw new ToolError(`Only Crewsync Admins can manage access keys (you are ${ctx.role || "a Member"}).`);
  };

  reg("create_access_key",
    "ADMIN ONLY. Mint a Crewsync access key for a person so they can connect their own AI assistant. The key is shown ONCE in the result — pass it to the person privately; they type it on the connector's authorization page. Everything their AI does is then attributed to their account.",
    {
      email: z.string().email().describe("The Crewsync user's sign-in email"),
      label: z.string().optional().describe("e.g. 'Cole - Claude'"),
      timezone: z.string().optional().describe("IANA zone for date parsing, default America/Detroit"),
    },
    async (a) => {
      requireAdmin();
      const email = a.email.trim().toLowerCase();
      const u = await ctx.db.collection("users").where("email", "==", email).limit(1).get();
      if (u.empty) throw new ToolError(`${email} is not a registered Crewsync user. They need to sign in to the app once first.`);
      if (a.timezone) { try { new Intl.DateTimeFormat("en-US", { timeZone: a.timezone }); } catch (_) { throw new ToolError(`Unknown timezone "${a.timezone}".`); } }
      const key = "cs_" + crypto.randomBytes(24).toString("base64url");
      await ctx.db.collection("mcp_access_keys").doc(hashKey(key)).set({
        email, label: a.label || email, timezone: a.timezone || DEFAULT_TIMEZONE, enabled: true,
        createdBy: ctx.email, createdAt: Date.now(),
      });
      return json({ created: true, email, label: a.label || email, accessKey: key, note: "Shown once. Give it to the person privately; it is not stored in plain text." });
    });

  reg("list_access_keys",
    "ADMIN ONLY. List issued access keys (who they belong to, label, enabled, last used). Keys themselves are never shown.",
    {},
    async () => {
      requireAdmin();
      const snap = await ctx.db.collection("mcp_access_keys").get();
      return json(snap.docs.map((d) => ({ id: d.id.slice(0, 8), ...cleanForWrite({ email: d.data().email, label: d.data().label, enabled: d.data().enabled !== false, timezone: d.data().timezone, createdAt: d.data().createdAt ? fmtDateTime(d.data().createdAt, ctx.tz) : undefined, lastUsedAt: d.data().lastUsedAt ? fmtDateTime(d.data().lastUsedAt, ctx.tz) : undefined }) })));
    });

  reg("revoke_access_key",
    "ADMIN ONLY. Disable every access key and every issued login token for an email address, cutting that person's AI off immediately.",
    { email: z.string().email() },
    async (a) => {
      requireAdmin();
      const email = a.email.trim().toLowerCase();
      const keys = await ctx.db.collection("mcp_access_keys").where("email", "==", email).get();
      const toks = await ctx.db.collection("mcp_oauth_tokens").where("email", "==", email).get();
      const batch = ctx.db.batch();
      keys.docs.forEach((d) => batch.update(d.ref, { enabled: false, revokedAt: Date.now(), revokedBy: ctx.email }));
      toks.docs.forEach((d) => batch.delete(d.ref));
      await batch.commit();
      return text(`Revoked ${keys.size} access key(s) and ${toks.size} active login(s) for ${email}.`);
    });

  // ---- daily log ---------------------------------------------------------------------------
  const sectionKeys = dailyReport.SECTIONS.map(([k]) => k);
  const sectionDoc = "Sections: " + dailyReport.SECTIONS.map(([k, t]) => `${k} (${t})`).join(", ");
  const resolveLogDate = (d) => {
    if (!d || /^today$/i.test(d)) return dailyReport.todayIn(ctx.tz);
    if (/^yesterday$/i.test(d)) { const t = new Date(Date.parse(dailyReport.todayIn(ctx.tz) + "T12:00:00Z") - 86400000); return t.toISOString().slice(0, 10); }
    if (!dailyReport.isLogDate(d)) throw new ToolError(`Date must be YYYY-MM-DD, 'today' or 'yesterday' (got "${d}").`);
    return d;
  };
  const logView = (log, projectName) => ({
    project: projectName, date: log.date, dayOfWeek: new Date(log.date + "T12:00:00Z").toLocaleDateString("en-US", { weekday: "long", timeZone: "UTC" }),
    weather: dailyReport.weatherLine(log) || undefined,
    weatherReadings: log.weatherReadings.length ? log.weatherReadings.map((r) => ({ when: dailyReport.SLOT_LABEL[r.slot] || r.slot, at: fmtDateTime(r.at, ctx.tz), tempF: r.tempF, conditions: r.conditions, windMph: r.windMph, precip: r.precip })) : undefined,
    noWorkLogged: (!log.crewOnSite.length && !log.workCompleted.length && !log.deliveriesInspections.length && !log.issuesDelays.length && !log.tomorrowPlan.length && !log.notes && !log.photos.length) || undefined,
    ...Object.fromEntries(dailyReport.SECTIONS.map(([k]) => [k, log[k]])),
    notes: log.notes || undefined,
    photos: log.photos.length ? log.photos.map((p) => ({ name: p.name, url: p.url })) : undefined,
    contributors: log.contributors,
    lastUpdated: log.updatedAt ? fmtDateTime(log.updatedAt, ctx.tz) : undefined,
    pdfUrl: log.pdfUrl || undefined,
    pdfIsCurrent: log.pdfUrl ? log.pdfBuiltAt >= log.updatedAt : undefined,
    recentAdditions: log.entries.slice(-5).map((e) => ({ by: e.by, at: fmtDateTime(e.at, ctx.tz), source: e.source, text: e.text })),
  });

  reg("get_daily_log",
    "The daily log / daily report for a project on one day: weather, crew & subs on site, work completed, deliveries & inspections, issues & delays, plan for tomorrow, notes, photos. Defaults to today.",
    { project: z.string().describe("Project name or ID"), date: z.string().optional().describe("YYYY-MM-DD, 'today' (default) or 'yesterday'") },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const date = resolveLogDate(a.date);
      const log = await dailyReport.getLog(ctx.db, p.id, date);
      if (!log) return json({ project: p.name, date, empty: true, note: "No log for this day yet. Use add_to_daily_log to start one." });
      return json(logView(log, p.name));
    });

  reg("list_daily_logs",
    "Which days have a daily log for a project, newest first, with a one-line summary each. Use get_daily_log for the full day.",
    { project: z.string().describe("Project name or ID"), from: z.string().optional().describe("YYYY-MM-DD"), to: z.string().optional().describe("YYYY-MM-DD"), limit: z.number().int().min(1).max(200).optional() },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const logs = await dailyReport.listLogs(ctx.db, p.id, { from: a.from, to: a.to, limit: a.limit || 60 });
      return json({ project: p.name, count: logs.length, days: logs.map((l) => ({
        date: l.date, weather: dailyReport.weatherLine(l) || undefined, noWorkLogged: (!l.crewOnSite.length && !l.workCompleted.length && !l.notes) || undefined,
        summary: `${l.workCompleted.length} work item(s), ${l.crewOnSite.length} crew line(s), ${l.issuesDelays.length} issue(s), ${l.photos.length} photo(s)`,
        contributors: l.contributors, pdf: l.pdfUrl ? (l.pdfBuiltAt >= l.updatedAt ? "current" : "stale") : "none",
      })) });
    });

  reg("add_to_daily_log",
    `Add to a project's daily log for a day (today by default) WITHOUT overwriting what's already there - lines are appended to the sections you give, duplicates skipped, and the raw text you were given is kept with your name on it. This is what Sierra and the morning planning session should use. Sort what the person said into sections yourself. ${sectionDoc}.`,
    {
      project: z.string().describe("Project name or ID"),
      date: z.string().optional().describe("YYYY-MM-DD, 'today' (default) or 'yesterday'"),
      crewOnSite: z.array(z.string()).optional().describe("e.g. 'JR + 2 helpers - roofing', 'Jeff, Ian, Owen - lanai'"),
      workCompleted: z.array(z.string()).optional(),
      deliveriesInspections: z.array(z.string()).optional(),
      issuesDelays: z.array(z.string()).optional(),
      tomorrowPlan: z.array(z.string()).optional(),
      notes: z.string().optional().describe("Free text appended to the Notes section"),
      weather: z.string().optional().describe("Weather NOTES in the person's words ('rained out at 1:30'). Readings at 7 AM / noon / 4 PM are recorded automatically; don't restate them here."),
      rawText: z.string().optional().describe("What the person actually said, verbatim, so nothing is lost in the sorting"),
      source: z.enum(["claude", "sierra", "app"]).optional().describe("Default claude"),
    },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const date = resolveLogDate(a.date);
      const add = Object.fromEntries(sectionKeys.filter((k) => Array.isArray(a[k]) && a[k].length).map((k) => [k, a[k]]));
      if (!Object.keys(add).length && !a.notes && !a.weather) throw new ToolError("Nothing to add — pass at least one section, notes, or weather.");
      const { log, changed } = await dailyReport.upsertLog(ctx.db, { projectId: p.id, project: p, date, by: ctx.email, source: a.source || "claude", add, notes: a.notes, weather: a.weather, rawText: a.rawText });
      return json({ added: changed, ...logView(log, p.name) });
    });

  reg("set_daily_log_section",
    `Replace one whole section of a day's log with a new list of lines (use when the person asks to rewrite or remove items; add_to_daily_log is the normal way to add). ${sectionDoc}.`,
    {
      project: z.string(), date: z.string().optional(),
      section: z.enum(sectionKeys), lines: z.array(z.string()).describe("The full new list; empty clears the section"),
    },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const date = resolveLogDate(a.date);
      const { log } = await dailyReport.upsertLog(ctx.db, { projectId: p.id, project: p, date, by: ctx.email, source: "claude", replace: { [a.section]: a.lines } });
      return json({ replaced: a.section, ...logView(log, p.name) });
    });

  reg("daily_report_pdf",
    "Build (or rebuild) the formatted PDF of a day's daily log and return its link. The PDF is also filed under the project's Files tab in the 'Daily Reports' folder, so the crew can open it in the app. Use the link when the person wants to email or print the report.",
    { project: z.string(), date: z.string().optional().describe("YYYY-MM-DD, 'today' (default) or 'yesterday'"), force: z.boolean().optional().describe("Rebuild even if the stored PDF is current") },
    async (a) => {
      const p = await findProject(ctx, a.project);
      const date = resolveLogDate(a.date);
      const log = (await dailyReport.getLog(ctx.db, p.id, date)) || dailyReport.normalizeLog(p.id, date, {});
      if (!a.force && log.pdfUrl && log.pdfBuiltAt >= log.updatedAt) return json({ project: p.name, date, url: log.pdfUrl, rebuilt: false });
      const names = await dailyReport.namesForEmails(ctx.db, [...log.contributors, log.createdBy, ctx.email]);
      const { url, fileName } = await dailyReport.buildAndFilePdf(ctx.db, { projectId: p.id, project: p, log, names, builtBy: ctx.email });
      return json({ project: p.name, date, url, fileName, rebuilt: true, emailSubject: `Daily Report - ${p.name} - ${date}` });
    });

  return server;
}

// ---------------------------------------------------------------------------------------------
// Identity: bearer token → { email, uid, name, role, envId, tz }
// ---------------------------------------------------------------------------------------------

async function resolveContext(db, authInfo) {
  const email = String(authInfo?.extra?.email || "").toLowerCase();
  if (!email) return { error: "This login has no account attached. Disconnect and reconnect the Crewsync connector." };
  const uSnap = await db.collection("users").where("email", "==", email).limit(1).get();
  let user = {};
  let uid = "";
  if (!uSnap.empty) { user = uSnap.docs[0].data(); uid = uSnap.docs[0].id; }
  const envId = user.activeEnvironmentId || (user.environmentIds || [])[0] || "";
  if (!envId) return { error: `No active workspace for ${email}. Sign in to the Crewsync app once so a workspace is selected.` };
  let envName = "";
  try { const e = await db.collection("environments").doc(envId).get(); envName = e.exists ? e.data().name : ""; } catch (_) { /* ignore */ }
  return { db, email, uid, name: user.name || email, role: user.role || "Member", envId, envName, tz: authInfo?.extra?.timezone || DEFAULT_TIMEZONE };
}

// ---------------------------------------------------------------------------------------------
// HTTP app: OAuth endpoints + the MCP endpoint
// ---------------------------------------------------------------------------------------------

function buildApp(db) {
  const app = express();
  const { provider, confirmAuthorization } = buildProvider({ legacySecret: MCP_AUTH_TOKEN.value() });

  app.use(
    mcpAuthRouter({
      provider,
      issuerUrl: new URL(BASE_URL),
      resourceServerUrl: new URL(BASE_URL),
      resourceName: "Crewsync",
      // express-rate-limit can't derive req.ip in the Functions/Cloud Run request wrapper
      // (ERR_ERL_UNDEFINED_IP_ADDRESS).
      authorizationOptions: { rateLimit: false },
      tokenOptions: { rateLimit: false },
      clientRegistrationOptions: { rateLimit: false },
    })
  );

  // Consent page submits here (plain HTML form) after the human enters their access key.
  app.get("/authorize/confirm", async (req, res) => {
    try {
      const redirectUrl = await confirmAuthorization({ authId: req.query.authId, keyAttempt: req.query.secret });
      res.redirect(302, redirectUrl);
    } catch (error) {
      logger.warn("crewsyncMcp: authorization confirm failed", error);
      res.status(400).send("Authorization failed: " + error.message);
    }
  });

  app.get("/", (_req, res) => res.status(200).send("Crewsync MCP server is up. POST JSON-RPC here with a bearer token."));

  app.post("/", requireBearerAuth({ verifier: provider }), async (req, res) => {
    try {
      const ctx = await resolveContext(db, req.auth);
      if (ctx.error) { res.status(403).json({ error: ctx.error }); return; }
      const server = buildServer(ctx);
      // Stateless Streamable HTTP: a fresh server+transport per request suits serverless hosts.
      const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
      res.on("close", () => { transport.close(); server.close(); });
      await server.connect(transport);
      await transport.handleRequest(req, res, req.body);
    } catch (error) {
      logger.error("crewsyncMcp request failed", error);
      if (!res.headersSent) res.status(500).json({ error: "Internal server error" });
    }
  });

  return app;
}

// Built lazily on first request, not at module load - defineSecret's .value() isn't populated
// during Firebase's deploy-time source analysis, only inside a live request.
let app;
const crewsyncMcp = onRequest({ secrets: [MCP_AUTH_TOKEN], invoker: "public", timeoutSeconds: 120 }, (req, res) => {
  if (!app) app = buildApp(getFirestore());
  app(req, res);
});

module.exports = { crewsyncMcp, buildServer, resolveContext, expandAppointment, parseDate, fmtDateTime, fmtDate, zonedToUtc, utcToZoned, msToDay, dayToMs };
