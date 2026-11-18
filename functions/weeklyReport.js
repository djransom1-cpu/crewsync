/**
 * Weekly report for a project, built from that week's daily logs.
 *
 * Data lives at projects/{projectId}/weeklyReports/{weekStart}, weekStart being the Monday
 * (YYYY-MM-DD); the week runs Monday to Sunday. Every section is plain data the app and the
 * connector can show and edit (see composeApp/.../data/model/WeeklyReport.kt, which mirrors it):
 *
 *   weekStart, weekEnd, daysLogged      the dates in the week whose log has something written in it
 *   summary                             one short paragraph, generated, then editable
 *   weather [lines], precipDays         one line per day with readings: high, low, conditions
 *   crew [lines], subsOnSite [names]    per day: the crew's headcount and the subs on site
 *   workCompleted [{who, lines}]        the week's work by who did it: the crew first, then each sub
 *   deliveriesInspections [lines]       each with its day
 *   issues [{text, firstSeen, status}]  status "open" while still on the last logged day, else "cleared"
 *   nextWeek [lines]                    the last day's plan, next week's site calendar and phases
 *   photos [{date, name, url}]          up to 6, spread across the week
 *   notes [lines]                       each day's notes, with its day
 *   status Draft|Final, edited, pdfUrl, pdfBuiltAt, builtAt, createdAt, updatedAt, updatedBy
 *
 * Who did what is plain code, no AI: the logs are written as "NACE - roof sheathing",
 * "A+ Concrete - set forms", "Jeff and Alex - insulation". The part before the dash is who. It is
 * the crew when it names the crew (NACE unless the project's crewName says otherwise) or is a
 * list of people's names; it is a sub when it matches a Subcontractor contact's company or name,
 * or reads like a firm ("... Roofing", "... Electric"). Work lines with no recognisable who go
 * under the crew. Anything it gets wrong can be edited in the app or with
 * set_weekly_report_section.
 *
 * A report someone has edited, or marked Final, is never rebuilt over unless asked to (force).
 * Three consumers share this file: the `weeklyReport` HTTPS endpoint the app calls, the Saturday
 * 7 AM `buildWeeklyReports` run, and the connector tools in mcp.js.
 */

const PDFDocument = require("pdfkit");
const { onRequest } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { logger } = require("firebase-functions");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const dailyReport = require("./dailyReport");
const schedule = require("./schedule");

const SITE_TZ = "America/Detroit";
const DEFAULT_CREW = "NACE";
const WEEKLY_REPORTS_FOLDER = "Weekly Reports";
const MAX_PHOTOS = 6;
const IMAGE_EXT = /\.(jpe?g|png|webp|gif)$/i;
/** The sections a person can replace (set_weekly_report_section), and what each holds. */
const LINE_SECTIONS = ["weather", "crew", "subsOnSite", "deliveriesInspections", "nextWeek", "notes"];
const SECTION_KEYS = ["summary", ...LINE_SECTIONS, "workCompleted", "issues", "photos"];

// ---------------------------------------------------------------------------------------------
// Days. Dates are "YYYY-MM-DD" strings throughout; day numbers come from schedule.js.
// ---------------------------------------------------------------------------------------------

/** The Monday of the week [date] falls in. */
function weekStartOf(date) {
  const day = schedule.isoToDay(date);
  if (day === null) throw new Error(`Not a date: ${date}`);
  return schedule.dayToIso(day - (schedule.isoWeekday(day) - 1));
}
const addDays = (date, n) => schedule.dayToIso(schedule.isoToDay(date) + n);
const weekDates = (weekStart) => [0, 1, 2, 3, 4, 5, 6].map((i) => addDays(weekStart, i));
/** "Mon Nov 16" */
function shortDay(date) {
  return new Date(date + "T12:00:00Z").toLocaleDateString("en-US", { weekday: "short", month: "short", day: "numeric", timeZone: "UTC" }).replace(",", "");
}
const weekdayOf = (date) => shortDay(date).split(" ")[0];
/** "Nov 16 - 22, 2026" or "Nov 30 - Dec 6, 2026" */
function weekRange(weekStart) {
  const end = addDays(weekStart, 6);
  const a = new Date(weekStart + "T12:00:00Z"), b = new Date(end + "T12:00:00Z");
  const md = (d) => d.toLocaleDateString("en-US", { month: "short", day: "numeric", timeZone: "UTC" });
  return a.getUTCMonth() === b.getUTCMonth() ? `${md(a)} - ${b.getUTCDate()}, ${b.getUTCFullYear()}` : `${md(a)} - ${md(b)}, ${b.getUTCFullYear()}`;
}

// ---------------------------------------------------------------------------------------------
// Who did it
// ---------------------------------------------------------------------------------------------

/** "NACE - roof sheathing" -> { label: "NACE", rest: "roof sheathing" }; no dash or colon -> the whole line is the label. */
function splitLabel(line) {
  const m = String(line).match(/^(.{1,60}?)(?:\s+[-–—]\s+|:\s+)/);
  return m ? { label: m[1].trim(), rest: String(line).slice(m[0].length).trim() } : { label: String(line).trim(), rest: "" };
}
/** For matching: lower case, "+" read as "plus", brackets and everything but letters and digits dropped. "A+ Concrete (critical)" -> "aplusconcrete". */
const whoKey = (s) => String(s || "").toLowerCase().replace(/\(.*?\)/g, "").replace(/\+/g, "plus").replace(/[^a-z0-9]/g, "");
const firstWordKey = (s) => whoKey(String(s || "").replace(/\(.*?\)/g, "").trim().split(/\s+/)[0]);
const FIRM_WORDS = /\b(roof(ing|ers)?|electric(al)?|plumbing|heating|cooling|hvac|mechanical|concrete|masonry|excavating|excavation|drywall|insulation|painting|flooring|carpet|supply|construction|contracting|builders?|inc|llc|co|company|corp|services?|windows?|glass|steel|metal)\b/i;

/**
 * Builds the "who is this" test for one project. Returns, for a label, { crew: true, explicit }
 * for the crew (explicit when the label names the crew itself) or { crew: false, name } for a sub.
 */
function makeWho(contacts, crewName) {
  const crewKey = whoKey(crewName);
  const subs = (contacts || [])
    .filter((c) => c && c.type === "Subcontractor")
    .map((c) => {
      // A name that reads like a firm ("A Plus Concrete", "Onekama Building Supply") is matched the way a company is;
      // a person's name only when the log writes it out in full, so "Adam" on the crew isn't taken for Adam Aldridge.
      const firmName = FIRM_WORDS.test(String(c.name || ""));
      return {
        display: String(c.company || "").trim() || String(c.name || "").trim(),
        companies: [whoKey(c.company), firmName ? whoKey(c.name) : ""].filter(Boolean),
        name: firmName ? "" : whoKey(c.name),
      };
    })
    .filter((s) => s.display && !s.companies.includes(crewKey) && s.name !== crewKey); // the crew's own contact entry isn't a sub
  const crewWord = new RegExp(`\\b${String(crewName).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\b`, "i");
  return (label) => {
    const k = whoKey(label);
    if (!k) return { crew: true, explicit: false };
    if (crewKey && (k.startsWith(crewKey) || crewWord.test(label))) return { crew: true, explicit: true };
    const fw = firstWordKey(label);
    const match = subs.find((s) =>
      // A company may be written shorter ("D&W" for D & W Mechanical) or longer ("A+ Concrete crew"); very short keys only match exactly.
      s.companies.some((co) => co === k || (k.length >= 2 && co.startsWith(k)) || (co.length >= 3 && k.startsWith(co)) || (fw.length >= 2 && co.startsWith(fw))) ||
      (s.name && s.name.length >= 3 && (k === s.name || k.startsWith(s.name))));
    if (match) return { crew: false, name: match.display };
    if (FIRM_WORDS.test(label)) return { crew: false, name: label.replace(/\(.*?\)/g, "").trim() };
    return { crew: true, explicit: false };
  };
}

/** How many people a crew line counts: "(14)" or "14 guys" when given, otherwise one per name ("JR + 2 helpers" is 3). Null when it only names the crew. */
function headcount(line, crewName) {
  const { label } = splitLabel(line);
  const explicit = label.match(/\((\d{1,3})\)/) || label.match(/\b(\d{1,3})\s*(?:people|persons|guys|men|workers|crew|hands|helpers)\b/i);
  if (explicit && !/\+/.test(label)) return +explicit[1];
  const bare = label.replace(/\(.*?\)/g, "").trim();
  if (whoKey(bare) === whoKey(crewName) || whoKey(bare) === whoKey(`${crewName} crew`)) return null;
  let n = 0;
  for (const part of bare.replace(new RegExp(`^${crewName}\\s*(crew)?\\s*:?`, "i"), "").split(/,|\band\b|&|\+/i).map((s) => s.trim()).filter(Boolean)) {
    const num = part.match(/^(\d{1,3})\b/);
    n += num ? +num[1] : 1;
  }
  return n || null;
}

// ---------------------------------------------------------------------------------------------
// Building the report (pure: logs and the rest in, report sections out)
// ---------------------------------------------------------------------------------------------

const squash = (s) => String(s || "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
/** A log with something a person wrote in it, as opposed to the automatic weather alone. */
const hasContent = (log) => dailyReport.SECTIONS.some(([k]) => log[k].length) || !!String(log.notes || "").trim() || log.photos.length > 0;

/**
 * Works out every section of the report for the week starting [weekStart].
 *  logs              that week's daily logs (normalised, any order; days without one are simply absent)
 *  contacts          the workspace's contacts, for recognising subs
 *  crewName          what the crew is called in the logs (NACE)
 *  nextWeekVisits    [{ title, when }] - the site calendar for the following week, already worded
 *  nextWeekPhases    [{ name, start, end }] - schedule phases running the following week (dates as YYYY-MM-DD)
 */
function buildWeeklySections({ weekStart, logs, contacts = [], crewName = DEFAULT_CREW, nextWeekVisits = [], nextWeekPhases = [] }) {
  const days = weekDates(weekStart);
  const inWeek = logs.filter((l) => days.includes(l.date)).sort((a, b) => a.date.localeCompare(b.date));
  const logged = inWeek.filter(hasContent);
  const who = makeWho(contacts, crewName);

  // Weather: every day with readings or a weather note - a rain day with nobody on site still counts.
  let precipDays = 0;
  const weather = inWeek.filter((l) => l.weatherReadings.length || String(l.weather || "").trim()).map((l) => {
    const temps = l.weatherReadings.map((r) => r.tempF).filter((t) => typeof t === "number");
    const conditions = [...new Set(l.weatherReadings.map((r) => String(r.conditions || "").trim().toLowerCase()).filter(Boolean))].join(", then ");
    const wet = l.weatherReadings.some((r) => r.precip) || /\b(rain|snow|sleet|drizzle|shower|storm|thunder)/i.test(l.weather || "");
    if (wet) precipDays++;
    const range = temps.length ? (Math.min(...temps) === Math.max(...temps) ? `${temps[0]}°F` : `${Math.min(...temps)}°-${Math.max(...temps)}°F`) : "";
    return `${shortDay(l.date)}: ${[range, conditions, String(l.weather || "").trim()].filter(Boolean).join(", ")}`;
  });

  // Crew and subs, day by day.
  const subsOnSite = [];
  let peak = 0;
  const crew = [];
  for (const l of logged) {
    let count = 0, crewSeen = false;
    const subs = [];
    for (const line of l.crewOnSite) {
      const w = who(splitLabel(line).label);
      if (w.crew) { crewSeen = true; count += headcount(line, crewName) || 0; } else if (!subs.includes(w.name)) subs.push(w.name);
    }
    for (const s of subs) if (!subsOnSite.includes(s)) subsOnSite.push(s);
    peak = Math.max(peak, count);
    if (!crewSeen && !subs.length) continue;
    const parts = [];
    if (crewSeen) parts.push(count ? `${crewName} ${count}` : `${crewName} on site`);
    if (subs.length) parts.push(`subs: ${subs.join(", ")}`);
    crew.push(`${shortDay(l.date)}: ${parts.join("; ")}`);
  }

  // Work completed, by who did it; the same line on several days is listed once with its days.
  const groups = new Map(); // who -> Map(textKey -> { text, days })
  const addWork = (name, text, date) => {
    if (!groups.has(name)) groups.set(name, new Map());
    const g = groups.get(name), k = squash(text);
    if (!g.has(k)) g.set(k, { text, days: [] });
    if (!g.get(k).days.includes(date)) g.get(k).days.push(date);
  };
  for (const l of logged) for (const line of l.workCompleted) {
    const { label, rest } = splitLabel(line);
    const w = who(label);
    if (w.crew && w.explicit && rest) addWork(crewName, rest, l.date);
    else if (!w.crew && rest) addWork(w.name, rest, l.date);
    else addWork(crewName, line, l.date);
  }
  const groupOrder = [crewName, ...[...groups.keys()].filter((k) => k !== crewName)].filter((k) => groups.has(k));
  const workCompleted = groupOrder.map((name) => ({
    who: name,
    lines: [...groups.get(name).values()].map((x) => `${x.text} (${x.days.map(weekdayOf).join(", ")})`),
  }));

  const deliveriesInspections = logged.flatMap((l) => l.deliveriesInspections.map((x) => `${shortDay(l.date)} - ${x}`));

  // Issues: first day each was raised; open while it's still listed on the last logged day.
  const lastLogged = logged[logged.length - 1];
  const stillListed = new Set((lastLogged?.issuesDelays || []).map(squash));
  const issueMap = new Map();
  for (const l of logged) for (const x of l.issuesDelays) if (!issueMap.has(squash(x))) issueMap.set(squash(x), { text: x, firstSeen: l.date });
  const issues = [...issueMap.entries()].map(([k, v]) => ({ ...v, status: stillListed.has(k) ? "open" : "cleared" }))
    .sort((a, b) => (a.status === b.status ? a.firstSeen.localeCompare(b.firstSeen) : a.status === "open" ? -1 : 1));

  const nextWeek = [
    ...(lastLogged?.tomorrowPlan || []),
    ...nextWeekVisits.map((v) => `${v.when} - ${v.title}`),
    ...nextWeekPhases.map((p) => `Phase: ${p.name}, ${shortDay(p.start)} to ${shortDay(p.end)}`),
  ];

  // Photos: the first from each day, then the second from each day, and so on, up to six.
  const perDay = logged.map((l) => l.photos.filter((p) => p && p.url && IMAGE_EXT.test(p.name || p.url)).map((p) => ({ date: l.date, name: p.name || "", url: p.url })));
  const photos = [];
  for (let i = 0; photos.length < MAX_PHOTOS && perDay.some((list) => list[i]); i++) for (const list of perDay) if (list[i] && photos.length < MAX_PHOTOS) photos.push(list[i]);

  const notes = logged.filter((l) => String(l.notes || "").trim()).map((l) => `${shortDay(l.date)}: ${String(l.notes).trim()}`);

  const open = issues.filter((x) => x.status === "open").length;
  const andList = (xs) => (xs.length <= 1 ? xs.join("") : `${xs.slice(0, -1).join(", ")} and ${xs[xs.length - 1]}`);
  const summary = [
    `${logged.length} day${logged.length === 1 ? "" : "s"} logged`,
    peak ? `${peak} crew at peak` : "",
    subsOnSite.length ? `${andList(subsOnSite)} on site` : "",
    issues.length ? `${open} issue${open === 1 ? "" : "s"} still open` : "",
  ].filter(Boolean).join(", ") + ".";

  return {
    weekStart, weekEnd: addDays(weekStart, 6), daysLogged: logged.map((l) => l.date),
    summary, weather, precipDays, crew, subsOnSite, workCompleted, deliveriesInspections, issues, nextWeek, photos, notes,
  };
}

// ---------------------------------------------------------------------------------------------
// Firestore
// ---------------------------------------------------------------------------------------------

const weeklyRef = (db, projectId, weekStart) => db.collection("projects").doc(projectId).collection("weeklyReports").doc(weekStart);

function normalizeWeekly(projectId, weekStart, data) {
  const d = data || {};
  const lines = (k) => (Array.isArray(d[k]) ? d[k].filter((x) => typeof x === "string" && x.trim()) : []);
  return {
    id: weekStart, projectId, weekStart, weekEnd: d.weekEnd || addDays(weekStart, 6),
    daysLogged: lines("daysLogged"), summary: typeof d.summary === "string" ? d.summary : "",
    weather: lines("weather"), precipDays: Number(d.precipDays) || 0,
    crew: lines("crew"), subsOnSite: lines("subsOnSite"),
    workCompleted: (Array.isArray(d.workCompleted) ? d.workCompleted : []).filter((g) => g && typeof g.who === "string")
      .map((g) => ({ who: g.who, lines: (Array.isArray(g.lines) ? g.lines : []).filter((x) => typeof x === "string") })),
    deliveriesInspections: lines("deliveriesInspections"),
    issues: (Array.isArray(d.issues) ? d.issues : []).filter((x) => x && typeof x.text === "string")
      .map((x) => ({ text: x.text, firstSeen: x.firstSeen || "", status: x.status === "open" ? "open" : "cleared" })),
    nextWeek: lines("nextWeek"),
    photos: (Array.isArray(d.photos) ? d.photos : []).filter((p) => p && p.url).map((p) => ({ date: p.date || "", name: p.name || "", url: p.url })),
    notes: lines("notes"),
    status: d.status === "Final" ? "Final" : "Draft", edited: d.edited === true,
    pdfUrl: d.pdfUrl || "", pdfBuiltAt: Number(d.pdfBuiltAt) || 0, builtAt: Number(d.builtAt) || 0,
    createdAt: Number(d.createdAt) || 0, updatedAt: Number(d.updatedAt) || 0, updatedBy: d.updatedBy || "",
  };
}

/** Never an empty array, never undefined - the same rule as the daily log (the web app can't write empty arrays). */
function writeMap(obj) {
  const out = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v === undefined || v === null || k === "id" || k === "projectId") continue;
    if (Array.isArray(v) && v.length === 0) continue;
    out[k] = v;
  }
  return out;
}

async function getWeekly(db, projectId, weekStart) {
  const snap = await weeklyRef(db, projectId, weekStart).get();
  return snap.exists ? normalizeWeekly(projectId, weekStart, snap.data()) : null;
}

async function listWeekly(db, projectId, { from, to } = {}) {
  const snap = await db.collection("projects").doc(projectId).collection("weeklyReports").get();
  let out = snap.docs.map((d) => normalizeWeekly(projectId, d.id, d.data()));
  if (from) out = out.filter((r) => r.weekEnd >= from);
  if (to) out = out.filter((r) => r.weekStart <= to);
  return out.sort((a, b) => b.weekStart.localeCompare(a.weekStart));
}

/** Gathers what the week's report is built from: its logs, the contacts, and next week's calendar and phases. */
async function gatherWeek(db, project, weekStart, tz = SITE_TZ) {
  const { expandAppointment, msToDay, dayToMs, fmtDateTime } = require("./mcp"); // required here: mcp.js requires this file
  const pRef = db.collection("projects").doc(project.id);
  const logs = await dailyReport.listLogs(db, project.id, { from: weekStart, to: addDays(weekStart, 6), limit: 7 });
  const contactsSnap = await db.collection("contacts").where("environmentId", "==", project.environmentId || "").get();
  const contacts = contactsSnap.docs.map((d) => d.data());
  const nextStart = schedule.isoToDay(addDays(weekStart, 7)), nextEnd = nextStart + 6;
  const from = dayToMs(nextStart, tz), to = dayToMs(nextEnd + 1, tz) - 1;
  const appts = (await pRef.collection("appointments").get()).docs.map((d) => ({ ...d.data(), id: d.id }));
  const nextWeekVisits = appts.flatMap((a) => expandAppointment(a, from, to, tz).map((inst) => ({ at: inst.instanceStart, title: a.title || "", allDay: !!a.isAllDay })))
    .sort((a, b) => a.at - b.at)
    // Worded like the rest of the report: "Tue Nov 17, 10:00 AM", or just the day for an all-day visit.
    .map((v) => {
      const day = shortDay(schedule.dayToIso(msToDay(v.at, tz)));
      return { title: v.title, when: v.allDay ? day : `${day}, ${fmtDateTime(v.at, tz).split(" ").slice(2).join(" ")}` };
    });
  const phases = (await pRef.collection("schedulePhases").get()).docs.map((d) => schedule.normalizePhase(d.id, d.data()))
    .map((p) => ({ ...p, s: msToDay(p.start, tz), e: msToDay(p.end, tz) }))
    .filter((p) => p.status !== "Done" && p.s !== null && p.e !== null && p.e >= nextStart && p.s <= nextEnd);
  const nextWeekPhases = schedule.sortPhases(phases.map((p) => ({ ...p, startDay: p.s }))).map((p) => ({ name: p.name, start: schedule.dayToIso(p.s), end: schedule.dayToIso(p.e) }));
  return { logs, contacts, crewName: String(project.crewName || "").trim() || DEFAULT_CREW, nextWeekVisits, nextWeekPhases };
}

/**
 * Builds the week's report from its daily logs and saves it as a Draft. A report someone has
 * edited, or marked Final, is left as it is unless [force]; a forced rebuild goes back to Draft.
 * Returns { report, built, reason } - reason "final" or "edited" when it was left alone.
 */
async function buildAndSaveWeekly(db, { project, weekStart, by, force = false, tz = SITE_TZ }) {
  const existing = await getWeekly(db, project.id, weekStart);
  if (existing && !force && (existing.status === "Final" || existing.edited)) {
    return { report: existing, built: false, reason: existing.status === "Final" ? "final" : "edited" };
  }
  const sections = buildWeeklySections({ weekStart, ...(await gatherWeek(db, project, weekStart, tz)) });
  const now = Date.now();
  const report = {
    ...sections, status: "Draft", edited: false,
    pdfUrl: existing?.pdfUrl || "", pdfBuiltAt: existing?.pdfBuiltAt || 0, // kept, but now older than the report, so stale
    builtAt: now, createdAt: existing?.createdAt || now, updatedAt: now, updatedBy: by || "",
  };
  // A whole-document write on purpose: this is the one place a report is (re)built from the logs.
  await weeklyRef(db, project.id, weekStart).set(writeMap(report));
  return { report: normalizeWeekly(project.id, weekStart, report), built: true };
}

/**
 * Replaces one section with what a person wrote, and marks the report edited so the Saturday
 * run leaves it alone. Throws with a readable message when the value doesn't fit the section.
 */
async function setWeeklySection(db, { projectId, weekStart, section, value, by }) {
  if (!SECTION_KEYS.includes(section) && section !== "status") throw new Error(`Unknown section "${section}". Use one of: ${SECTION_KEYS.join(", ")}, status.`);
  const existing = await getWeekly(db, projectId, weekStart);
  if (!existing) throw new Error("There's no weekly report for that week yet. Build it first.");
  let stored;
  if (section === "summary") {
    if (typeof value !== "string") throw new Error("The summary is a piece of text.");
    stored = value.trim();
  } else if (section === "status") {
    if (value !== "Draft" && value !== "Final") throw new Error("Status is Draft or Final.");
    stored = value;
  } else if (LINE_SECTIONS.includes(section)) {
    if (!Array.isArray(value)) throw new Error(`${section} is a list of lines.`);
    stored = value.map((x) => String(x).trim()).filter(Boolean);
  } else if (section === "workCompleted") {
    if (!Array.isArray(value)) throw new Error("workCompleted is a list of { who, lines }.");
    stored = value.map((g) => ({ who: String(g?.who || "").trim(), lines: (Array.isArray(g?.lines) ? g.lines : []).map((x) => String(x).trim()).filter(Boolean) }))
      .filter((g) => g.who && g.lines.length);
  } else if (section === "issues") {
    if (!Array.isArray(value)) throw new Error("issues is a list of { text, status }.");
    stored = value.map((x) => ({ text: String(x?.text || "").trim(), firstSeen: String(x?.firstSeen || ""), status: x?.status === "open" ? "open" : "cleared" })).filter((x) => x.text);
  } else if (section === "photos") {
    if (!Array.isArray(value)) throw new Error("photos is a list of { date, name, url }.");
    stored = value.filter((p) => p && p.url).slice(0, MAX_PHOTOS).map((p) => ({ date: String(p.date || ""), name: String(p.name || ""), url: String(p.url) }));
  }
  const { FieldValue } = require("firebase-admin/firestore");
  const patch = { [section]: Array.isArray(stored) && stored.length === 0 ? FieldValue.delete() : stored, updatedAt: Date.now(), updatedBy: by || "" };
  if (section !== "status") patch.edited = true;
  await weeklyRef(db, projectId, weekStart).update(patch);
  return getWeekly(db, projectId, weekStart);
}

/** Marking Final or back to Draft. */
const setWeeklyStatus = (db, { projectId, weekStart, status, by }) => setWeeklySection(db, { projectId, weekStart, section: "status", value: status, by });

// ---------------------------------------------------------------------------------------------
// The report as text (email body, connector) and as a PDF
// ---------------------------------------------------------------------------------------------

function weeklyPlainText(report, projectName) {
  const out = [`Weekly Report - ${projectName}`, `Week of ${weekRange(report.weekStart)}${report.status === "Draft" ? " (draft)" : ""}`, ""];
  if (report.summary) out.push(report.summary, "");
  const block = (title, lines) => { out.push(title.toUpperCase()); if (!lines.length) out.push("  None"); else lines.forEach((l) => out.push(`  - ${l}`)); out.push(""); };
  block("Weather", [...report.weather, ...(report.precipDays ? [`Rain or snow on ${report.precipDays} day(s)`] : [])]);
  block("Crew & subs on site", [...report.crew, ...(report.subsOnSite.length ? [`Subs this week: ${report.subsOnSite.join(", ")}`] : [])]);
  out.push("WORK COMPLETED");
  if (!report.workCompleted.length) out.push("  None");
  report.workCompleted.forEach((g) => { out.push(`  ${g.who}`); g.lines.forEach((l) => out.push(`    - ${l}`)); });
  out.push("");
  block("Deliveries & inspections", report.deliveriesInspections);
  block("Issues", report.issues.map((x) => `${x.status === "open" ? "OPEN" : "Cleared"}: ${x.text}${x.firstSeen ? ` (since ${shortDay(x.firstSeen)})` : ""}`));
  block("Next week", report.nextWeek);
  if (report.notes.length) block("Notes", report.notes);
  if (report.pdfUrl) out.push(`PDF: ${report.pdfUrl}`);
  return out.join("\n").trim();
}

async function renderWeeklyPdf({ project, report, builtBy, names = {} }) {
  const images = await Promise.all(report.photos.slice(0, MAX_PHOTOS).map(async (p) => ({ ...p, bytes: await fetchBytes(p.url) })));
  return new Promise((resolve, reject) => {
    const doc = new PDFDocument({ size: "LETTER", bufferPages: true, margins: { top: 54, bottom: 54, left: 54, right: 54 }, info: { Title: `Weekly Report - ${project.name} - ${report.weekStart}`, Author: "Crewsync" } });
    const chunks = [];
    doc.on("data", (c) => chunks.push(c));
    doc.on("end", () => resolve(Buffer.concat(chunks)));
    doc.on("error", reject);
    const W = doc.page.width - doc.page.margins.left - doc.page.margins.right;
    const L = doc.page.margins.left;
    const ink = "#111111", muted = "#666666", rule = "#BBBBBB", band = "#1F3A5F", red = "#B3261E", green = "#2E7D32";

    doc.rect(0, 0, doc.page.width, 78).fill(band);
    doc.fillColor("#FFFFFF").font("Helvetica-Bold").fontSize(20).text(report.status === "Draft" ? "WEEKLY REPORT (DRAFT)" : "WEEKLY REPORT", L, 22, { width: W * 0.65 });
    doc.font("Helvetica").fontSize(11).text(project.name || "", L, 48, { width: W * 0.6 });
    doc.font("Helvetica-Bold").fontSize(12).text(`Week of ${weekRange(report.weekStart)}`, L + W * 0.5, 26, { width: W * 0.5, align: "right" });
    if (project.location) doc.font("Helvetica").fontSize(9).text(project.location, L + W * 0.45, 46, { width: W * 0.55, align: "right" });
    doc.fillColor(ink);
    doc.y = 96;
    if (report.summary) { doc.font("Helvetica").fontSize(11).text(report.summary, L, doc.y, { width: W }); }

    const ensureRoom = (h) => { if (doc.y + h > doc.page.height - doc.page.margins.bottom) doc.addPage(); };
    const heading = (title) => {
      ensureRoom(56);
      doc.moveDown(1.1);
      doc.font("Helvetica-Bold").fontSize(12).fillColor(band).text(title.toUpperCase(), L, doc.y, { width: W });
      const y = doc.y + 3;
      doc.moveTo(L, y).lineTo(L + W, y).lineWidth(0.8).strokeColor(rule).stroke();
      doc.fillColor(ink);
      doc.y = y + 8;
    };
    const bullets = (lines, indent = 0) => {
      doc.font("Helvetica").fontSize(10.5);
      if (!lines.length) { doc.fillColor(muted).text("None", L + 14 + indent, doc.y, { width: W - 14 - indent }); doc.fillColor(ink); return; }
      for (const line of lines) {
        ensureRoom(16);
        const y = doc.y;
        doc.text("•", L + 4 + indent, y, { width: 10, lineBreak: false });
        doc.text(line, L + 16 + indent, y, { width: W - 16 - indent });
        doc.moveDown(0.3);
      }
    };

    heading("Weather");
    bullets([...report.weather, ...(report.precipDays ? [`Rain or snow on ${report.precipDays} day${report.precipDays === 1 ? "" : "s"}`] : [])]);
    heading("Crew & Subs on Site");
    bullets([...report.crew, ...(report.subsOnSite.length ? [`Subs on site this week: ${report.subsOnSite.join(", ")}`] : [])]);
    heading("Work Completed");
    if (!report.workCompleted.length) bullets([]);
    for (const g of report.workCompleted) {
      ensureRoom(30);
      doc.font("Helvetica-Bold").fontSize(10.5).text(g.who, L + 4, doc.y, { width: W - 4 });
      doc.moveDown(0.2);
      bullets(g.lines, 8);
    }
    heading("Deliveries & Inspections");
    bullets(report.deliveriesInspections);
    heading("Issues");
    if (!report.issues.length) bullets([]);
    for (const x of report.issues) {
      ensureRoom(16);
      const y = doc.y;
      doc.font("Helvetica-Bold").fontSize(9).fillColor(x.status === "open" ? red : green).text(x.status === "open" ? "OPEN" : "CLEARED", L + 4, y + 1, { width: 52, lineBreak: false });
      doc.font("Helvetica").fontSize(10.5).fillColor(ink).text(`${x.text}${x.firstSeen ? `  (since ${shortDay(x.firstSeen)})` : ""}`, L + 60, y, { width: W - 60 });
      doc.moveDown(0.3);
    }
    heading("Next Week");
    bullets(report.nextWeek);
    if (report.notes.length) { heading("Notes"); bullets(report.notes); }

    const good = images.filter((p) => p.bytes);
    if (good.length) {
      doc.addPage();
      doc.font("Helvetica-Bold").fontSize(12).fillColor(band).text("PHOTOS", L, doc.y, { width: W });
      const y0 = doc.y + 2; doc.moveTo(L, y0).lineTo(L + W, y0).strokeColor(rule).stroke(); doc.fillColor(ink).moveDown(0.6);
      const cellW = (W - 12) / 2, cellH = 230;
      let col = 0, rowY = doc.y;
      for (const p of good) {
        if (col === 0 && rowY + cellH + 20 > doc.page.height - doc.page.margins.bottom) { doc.addPage(); rowY = doc.y; }
        const x = L + col * (cellW + 12);
        try { doc.image(p.bytes, x, rowY, { fit: [cellW, cellH - 18], align: "center", valign: "center" }); } catch (_) { /* unsupported image */ }
        doc.font("Helvetica").fontSize(8).fillColor(muted).text(p.date ? shortDay(p.date) : p.name, x, rowY + cellH - 14, { width: cellW, align: "center", lineBreak: false });
        doc.fillColor(ink);
        col = (col + 1) % 2;
        if (col === 0) rowY += cellH + 10;
      }
    }

    const range = doc.bufferedPageRange();
    const stamp = new Date().toLocaleString("en-US", { timeZone: SITE_TZ });
    for (let i = range.start; i < range.start + range.count; i++) {
      doc.switchToPage(i);
      const savedBottom = doc.page.margins.bottom;
      doc.page.margins.bottom = 0;
      doc.font("Helvetica").fontSize(8).fillColor(muted)
        .text(`${project.name}  ·  Weekly Report  ·  week of ${report.weekStart}  ·  generated ${stamp}${builtBy ? " by " + (names[builtBy] || builtBy) : ""}  ·  page ${i - range.start + 1} of ${range.count}`,
          L, doc.page.height - 36, { width: W, align: "center", lineBreak: false });
      doc.page.margins.bottom = savedBottom;
    }
    doc.end();
  });
}

async function fetchBytes(url, maxBytes = 8 * 1024 * 1024) {
  try {
    const r = await fetch(url);
    if (!r.ok) return null;
    const buf = Buffer.from(await r.arrayBuffer());
    return buf.length <= maxBytes ? buf : null;
  } catch (_) { return null; }
}

/** Builds the week's PDF, stores it, files it under Files -> "Weekly Reports", and records the link on the report. */
async function buildAndFileWeeklyPdf(db, { project, report, builtBy }) {
  const names = await dailyReport.namesForEmails(db, [builtBy]);
  const bytes = await renderWeeklyPdf({ project, report, builtBy, names });
  const fileName = `${dailyReport.safeName(`Weekly Report - ${project.name} - ${report.weekStart}`)}.pdf`;
  const { url } = await dailyReport.fileProjectPdf(db, {
    projectId: project.id, bytes, fileName, builtBy,
    storagePath: `projects/${project.id}/weekly_reports/${report.weekStart}.pdf`, folderName: WEEKLY_REPORTS_FOLDER,
  });
  await weeklyRef(db, project.id, report.weekStart).set({ pdfUrl: url, pdfBuiltAt: Date.now() }, { merge: true });
  return { url, fileName };
}

const pdfIsCurrent = (report) => !!report.pdfUrl && report.pdfBuiltAt >= report.updatedAt;

// ---------------------------------------------------------------------------------------------
// Saturday 7 AM: a Draft for each active project
// ---------------------------------------------------------------------------------------------

/**
 * Builds this week's Draft for every project that isn't archived and has something logged this
 * week. Reports someone has edited or marked Final are left alone. Returns { built, skipped }.
 */
async function runWeeklyReports(db, now = Date.now()) {
  const weekStart = weekStartOf(dailyReport.todayIn(SITE_TZ, now));
  const snap = await db.collection("projects").get();
  const out = { weekStart, built: [], keptFinal: [], keptEdited: [], nothingLogged: [] };
  for (const d of snap.docs) {
    const project = { ...d.data(), id: d.id };
    if (project.archived) continue;
    const name = String(project.name || "").trim();
    const logs = await dailyReport.listLogs(db, project.id, { from: weekStart, to: addDays(weekStart, 6), limit: 7 });
    if (!logs.some(hasContent)) { out.nothingLogged.push(name); continue; }
    try {
      const r = await buildAndSaveWeekly(db, { project, weekStart, by: "weekly-run" });
      if (r.built) out.built.push(name); else (r.reason === "final" ? out.keptFinal : out.keptEdited).push(name);
    } catch (e) { logger.warn(`buildWeeklyReports ${name}: ${e.message}`); }
  }
  return out;
}

const buildWeeklyReports = onSchedule({ schedule: "0 7 * * 6", timeZone: SITE_TZ, timeoutSeconds: 300, memory: "512MiB" }, async () => {
  const r = await runWeeklyReports(getFirestore());
  logger.info(`buildWeeklyReports ${r.weekStart}: built ${r.built.length}, kept final ${r.keptFinal.length}, kept edited ${r.keptEdited.length}, nothing logged ${r.nothingLogged.length}`);
});

// ---------------------------------------------------------------------------------------------
// HTTPS endpoint for the app: POST { projectId, weekStart, action: "build" | "pdf", force } with
// a Firebase ID token. Same sign-in and project check as dailyReportPdf.
// ---------------------------------------------------------------------------------------------

const weeklyReport = onRequest({ timeoutSeconds: 120, memory: "512MiB" }, async (req, res) => {
  res.set("Access-Control-Allow-Origin", "*");
  res.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
  res.set("Access-Control-Allow-Methods", "POST, OPTIONS");
  if (req.method === "OPTIONS") { res.status(204).send(""); return; }
  if (req.method !== "POST") { res.status(405).json({ error: "Use POST." }); return; }
  const bearer = (req.get("Authorization") || "").match(/^Bearer (.+)$/);
  if (!bearer) { res.status(401).json({ error: "You're not signed in." }); return; }
  let decoded;
  try { decoded = await getAuth().verifyIdToken(bearer[1]); } catch (_) { res.status(401).json({ error: "Your sign-in has expired. Sign in again and retry." }); return; }
  let body = req.body;
  if (typeof body === "string") { try { body = JSON.parse(body); } catch (_) { body = {}; } }
  const projectId = typeof body?.projectId === "string" ? body.projectId.trim() : "";
  const date = typeof body?.weekStart === "string" ? body.weekStart.trim() : "";
  const action = body?.action === "pdf" ? "pdf" : "build";
  if (!projectId || projectId.includes("/") || !dailyReport.isLogDate(date)) { res.status(400).json({ error: "Missing project or week." }); return; }
  const weekStart = weekStartOf(date);
  const db = getFirestore();
  try {
    const pSnap = await db.collection("projects").doc(projectId).get();
    if (!pSnap.exists) { res.status(404).json({ error: "That project no longer exists." }); return; }
    const project = { ...pSnap.data(), id: pSnap.id };
    const me = (await db.collection("users").doc(decoded.uid).get()).data() || {};
    const email = (decoded.email || me.email || "").toLowerCase();
    const members = (project.members || []).map((m) => String(m).toLowerCase());
    const myEnvs = [...(me.environmentIds || []), me.activeEnvironmentId].filter(Boolean);
    const allowed = members.includes(email) || project.teamLeaderId === decoded.uid || myEnvs.includes(project.environmentId) || /^(super)?admin$/i.test(me.role || "");
    if (!allowed) { res.status(403).json({ error: "You're not on this project." }); return; }

    if (action === "build") {
      const r = await buildAndSaveWeekly(db, { project, weekStart, by: email, force: body?.force === true });
      res.status(200).json({ ok: true, built: r.built, reason: r.reason || null });
      return;
    }
    let report = await getWeekly(db, projectId, weekStart);
    if (!report) report = (await buildAndSaveWeekly(db, { project, weekStart, by: email })).report;
    if (pdfIsCurrent(report) && body?.force !== true) { res.status(200).json({ ok: true, url: report.pdfUrl, rebuilt: false }); return; }
    const { url, fileName } = await buildAndFileWeeklyPdf(db, { project, report, builtBy: email });
    logger.info(`weeklyReport pdf ${projectId} ${weekStart} by ${email}`);
    res.status(200).json({ ok: true, url, fileName, rebuilt: true });
  } catch (error) {
    logger.error(`weeklyReport ${action} ${projectId} ${weekStart} failed`, error);
    res.status(500).json({ error: "Something went wrong with the weekly report. Try again." });
  }
});

module.exports = {
  weeklyReport, buildWeeklyReports, runWeeklyReports,
  buildWeeklySections, buildAndSaveWeekly, setWeeklySection, setWeeklyStatus, getWeekly, listWeekly, normalizeWeekly,
  buildAndFileWeeklyPdf, renderWeeklyPdf, weeklyPlainText, pdfIsCurrent,
  weekStartOf, weekRange, shortDay, splitLabel, makeWho, headcount,
  SECTION_KEYS, LINE_SECTIONS, DEFAULT_CREW, WEEKLY_REPORTS_FOLDER,
};
