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
const { getFirestore } = require("firebase-admin/firestore");
const { McpServer } = require("@modelcontextprotocol/sdk/server/mcp.js");
const { StreamableHTTPServerTransport } = require("@modelcontextprotocol/sdk/server/streamableHttp.js");
const { mcpAuthRouter } = require("@modelcontextprotocol/sdk/server/auth/router.js");
const { requireBearerAuth } = require("@modelcontextprotocol/sdk/server/auth/middleware/bearerAuth.js");
const { buildProvider, hashKey, DEFAULT_TIMEZONE } = require("./oauth");

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

async function loadTasks(ctx, projectId) {
  let q = ctx.db.collection("tasks").where("environmentId", "==", ctx.envId);
  if (projectId) q = q.where("projectId", "==", projectId);
  const snap = await q.get();
  return snap.docs.map((d) => normalizeTask(d.id, d.data()));
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
    "List every project in the active workspace with task counts, location, and members.",
    {},
    async () => {
      const [projects, tasks] = await Promise.all([loadProjects(ctx), loadTasks(ctx)]);
      const rows = projects.map((p) => {
        const mine = tasks.filter((t) => t.projectId === p.id);
        const doneBucket = (p.buckets || []).slice(-1)[0];
        const open = mine.filter((t) => !/^(done|completed?)$/i.test(t.status) && t.status !== doneBucket).length;
        return { id: p.id, name: p.name, location: p.location || undefined, openTasks: open, totalTasks: mine.length, members: p.members || [], buckets: p.buckets || [] };
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
    "Update a project's name, description, location, owner/client info, members, or status buckets. Only the fields you pass change.",
    {
      project: z.string().describe("Project name or ID"),
      name: z.string().optional(), description: z.string().optional(), location: z.string().optional(),
      ownerName: z.string().optional(), ownerPhone: z.string().optional(), ownerEmail: z.string().optional(),
      addMembers: z.array(z.string()).optional(), removeMembers: z.array(z.string()).optional(),
      buckets: z.array(z.string()).optional().describe("Replace the full ordered list of status columns. Tasks keep their status string, so keep existing names unless you also move the tasks."),
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
      if (!Object.keys(patch).length) throw new ToolError("Nothing to update — pass at least one field.");
      await ctx.db.collection("projects").doc(p.id).update(patch);
      const back = (await ctx.db.collection("projects").doc(p.id).get()).data();
      return json({ updated: Object.keys(patch), id: p.id, name: back.name, members: back.members || [], buckets: back.buckets || [] });
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
    "Full detail for one task including every checklist and item (with item IDs) and attachments.",
    { task: z.string().describe("Task title or ID"), project: z.string().optional().describe("Project name or ID to narrow the lookup") },
    async (a) => {
      const t = await findTask(ctx, a.task, a.project);
      const p = (await loadProjects(ctx)).find((x) => x.id === t.projectId);
      return json(taskDetail(t, ctx, p?.name));
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
    "Appointments/site-calendar events in a date window, with recurring events expanded into each occurrence. Defaults to now → 30 days. Times are shown in the connector's timezone.",
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
        const appts = await loadAppointments(ctx, p.id);
        for (const appt of appts) for (const inst of expandAppointment(appt, from, to, ctx.tz)) out.push({ inst, name: p.name });
      }
      out.sort((x, y) => x.inst.instanceStart - y.inst.instanceStart);
      return json({ window: { from: fmtDateTime(from, ctx.tz), to: fmtDateTime(to, ctx.tz) }, count: out.length, appointments: out.map((o) => apptView(o.inst, ctx, o.name)) });
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

module.exports = { crewsyncMcp, buildServer, resolveContext, expandAppointment, parseDate, fmtDateTime, fmtDate, zonedToUtc, utcToZoned };
