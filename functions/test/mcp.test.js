// Local smoke test: in-memory Firestore fake + in-memory MCP client against buildServer().
const assert = require("assert");
const { FieldValue } = require("firebase-admin/firestore");
const { Client } = require("@modelcontextprotocol/sdk/client/index.js");
const { InMemoryTransport } = require("@modelcontextprotocol/sdk/inMemory.js");
// Offline stubs: no Firebase Storage, no network. The PDF is still really rendered by pdfkit.
const saved = {};
require("../dailyReport.js").storageBucket = () => ({ name: "test-bucket", file: (p) => ({ save: async (bytes) => { saved[p] = bytes; } }) });
// Fake NWS + geocoder so the weather recorder can be exercised offline.
let obsTempC = 1.1, obsText = "Light Rain";
global.fetch = async (url) => {
  const j = (o) => ({ ok: true, json: async () => o, arrayBuffer: async () => new ArrayBuffer(0) });
  url = String(url);
  if (url.includes("/geocode?")) return j({ lat: 44.4, lon: -86.1 });
  if (url.includes("api.weather.gov/points/")) return j({ properties: { observationStations: "https://api.weather.gov/gridpoints/X/1,1/stations", forecastHourly: "https://api.weather.gov/gridpoints/X/1,1/forecast/hourly" } });
  if (url.endsWith("/stations")) return j({ features: [{ id: "https://api.weather.gov/stations/KMBL", properties: { stationIdentifier: "KMBL" } }] });
  if (url.endsWith("/observations/latest")) return j({ properties: { timestamp: new Date().toISOString(), temperature: { value: obsTempC }, windSpeed: { value: 24.1 }, textDescription: obsText, precipitationLastHour: { value: 0 } } });
  return { ok: false };
};
const { buildServer, resolveContext, expandAppointment, parseDate, fmtDateTime } = require("../mcp.js");

// ---- tiny Firestore fake -------------------------------------------------------------------
function makeDb() {
  const store = new Map(); // "path" -> data
  let n = 0;
  const docRef = (path) => ({
    id: path.split("/").pop(),
    path,
    async get() { const d = store.get(path); return { id: path.split("/").pop(), exists: !!d, data: () => (d ? JSON.parse(JSON.stringify(d)) : undefined) }; },
    async set(data, opts) { const d = JSON.parse(JSON.stringify(data)); store.set(path, opts?.merge ? { ...(store.get(path) || {}), ...d } : d); },
    // FieldValue.delete() removes the field, as it does in real Firestore
    async update(patch) { const d = store.get(path); if (!d) throw new Error("no doc " + path); const next = { ...d }; for (const [k, v] of Object.entries(patch)) { if (v instanceof FieldValue) { assert(v.isEqual(FieldValue.delete()), "only FieldValue.delete() is faked"); delete next[k]; } else if (v !== undefined) next[k] = JSON.parse(JSON.stringify(v)); } store.set(path, next); },
    async delete() { store.delete(path); },
    collection(name) { return colRef(path + "/" + name); },
  });
  const colRef = (path, filters = [], lim = Infinity) => ({
    doc(id) { return docRef(path + "/" + (id || "auto" + ++n)); },
    where(f, op, v) { assert(op === "==" || op === "in"); return colRef(path, [...filters, [f, op, v]], lim); },
    limit(k) { return colRef(path, filters, k); },
    async add(data) { const r = docRef(path + "/auto" + ++n); await r.set(data); return r; },
    async get() {
      const docs = [];
      for (const [p, d] of store) {
        if (!p.startsWith(path + "/") || p.slice(path.length + 1).includes("/")) continue;
        if (filters.every(([f, op, v]) => (op === "in" ? v.includes(d[f]) : d[f] === v))) docs.push({ id: p.split("/").pop(), ref: docRef(p), exists: true, data: () => JSON.parse(JSON.stringify(d)) });
        if (docs.length >= lim) break;
      }
      return { docs, empty: docs.length === 0, size: docs.length, forEach: (cb) => docs.forEach(cb) };
    },
  });
  return {
    collection: (name) => colRef(name),
    async runTransaction(fn) { const tx = { get: (r) => r.get(), set: (r, d, o) => { ops.push(() => r.set(d, o)); }, update: (r, p) => { ops.push(() => r.update(p)); } }; const ops = []; const out = await fn(tx); for (const o of ops) await o(); return out; },
    batch() { const ops = []; return { set: (r, d, o) => ops.push(() => r.set(d, o)), update: (r, p) => ops.push(() => r.update(p)), delete: (r) => ops.push(() => r.delete()), async commit() { for (const o of ops) await o(); } }; },
    _store: store,
  };
}

async function main() {
  const db = makeDb();
  const tz = "America/Detroit";
  await db.collection("users").doc("u1").set({ email: "dan@example.com", name: "Dan", role: "Admin", activeEnvironmentId: "env1", environmentIds: ["env1"] });
  await db.collection("users").doc("u2").set({ email: "mike@example.com", name: "Mike", role: "Member", activeEnvironmentId: "env1", environmentIds: ["env1"] });
  await db.collection("environments").doc("env1").set({ name: "Ransom Carpentry" });
  // note the stale blank "id" field some real project docs carry - the doc id must win
  await db.collection("projects").doc("pM").set({ id: "", environmentId: "env1", name: "Martin ", location: "2676 Schaef Rd, Bear Lake, MI 49614", buckets: ["Not Started", "In Progress", "TOP Priority", "Orders", "Completed"], members: ["dan@example.com"] });
  await db.collection("projects").doc("pT").set({ environmentId: "env1", name: "test", buckets: ["Not Started", "Done"] });
  await db.collection("projects").doc("pT2").set({ environmentId: "env1", name: "Test", buckets: ["Not Started", "Done"] });
  // Weekly meeting Tue Oct 13 2026 9:00 AM Detroit
  const { zonedToUtc } = require("../mcp.js");
  const mtgStart = zonedToUtc(2026, 10, 13, 9, 0, 0, tz);
  await db.collection("projects").doc("pM").collection("appointments").doc("a1").set({ title: "Weekly Meeting", startDate: mtgStart, endDate: mtgStart + 3600000, recurrence: "Weekly", isAllDay: false });
  // written by the old connector: no environmentId -> invisible in the app until repaired
  await db.collection("tasks").doc("t0").set({ projectId: "pM", title: "Oct 20th Meeting Agenda", status: "TOP Priority" });
  await db.collection("tasks").doc("t1").set({ environmentId: "env1", projectId: "pM", title: "Roofing", status: "TOP Priority", assignedTo: "dan@example.com", dueDate: zonedToUtc(2026, 10, 5, 0, 0, 0, tz), checklist: [{ id: "x1", text: "Order shingles", isDone: true }, { id: "x2", text: "Tear off", isDone: false }] });

  // recurrence expansion: Oct 20 and Oct 27 should appear in a window Oct 19 - Oct 31
  const from = zonedToUtc(2026, 10, 19, 0, 0, 0, tz), to = zonedToUtc(2026, 10, 31, 23, 59, 0, tz);
  const inst = expandAppointment({ id: "a1", title: "Weekly Meeting", startDate: mtgStart, endDate: mtgStart + 3600000, recurrence: "Weekly" }, from, to, tz);
  assert.deepStrictEqual(inst.map((i) => fmtDateTime(i.instanceStart, tz)), ["Tue 2026-10-20 9:00 AM", "Tue 2026-10-27 9:00 AM"]);
  // DST: Nov 3 is after the Nov 1 change; still 9 AM local
  const inst2 = expandAppointment({ startDate: mtgStart, endDate: mtgStart, recurrence: "Weekly" }, zonedToUtc(2026, 11, 2, 0, 0, 0, tz), zonedToUtc(2026, 11, 4, 0, 0, 0, tz), tz);
  assert.strictEqual(fmtDateTime(inst2[0].instanceStart, tz), "Tue 2026-11-03 9:00 AM");
  // date parsing
  assert.strictEqual(fmtDateTime(parseDate("2026-10-21T09:00", tz), tz), "Wed 2026-10-21 9:00 AM");
  assert.strictEqual(fmtDateTime(parseDate("2026-10-21T13:00:00Z", tz), tz), "Wed 2026-10-21 9:00 AM");
  assert.strictEqual(fmtDateTime(parseDate("2026-10-21", tz), tz), "Wed 2026-10-21 12:00 AM");
  console.log("date/recurrence helpers ok");

  // ---- schedule: working-day math (the same cases as the app's ScheduleDatesTest.kt) ----
  const sch = require("../schedule.js");
  const D = sch.isoToDay;
  const cal = sch.workCalendar({});
  assert.strictEqual(sch.fmtDay(D("2026-12-04")), "Fri 2026-12-04");
  assert.strictEqual(sch.isoToDay("2026-02-31"), null, "not a real date");
  assert.strictEqual(sch.isoToDay("12/04/2026"), null);
  assert.strictEqual(cal.addWorkingDays(D("2026-12-04"), 1), D("2026-12-07"), "Friday + 1 is Monday");
  assert.strictEqual(cal.addWorkingDays(D("2026-12-07"), -1), D("2026-12-04"), "Monday - 1 is Friday");
  assert.strictEqual(cal.addWorkingDays(D("2026-12-05"), 0), D("2026-12-07"), "a Saturday snaps forward to Monday");
  assert.strictEqual(cal.durationDays(D("2026-09-21"), D("2026-10-15")), 19);
  assert.strictEqual(cal.endForDuration(D("2026-12-04"), 3), D("2026-12-08"), "Fri, Mon, Tue");
  assert.strictEqual(cal.startForDuration(D("2026-12-08"), 3), D("2026-12-04"));
  assert.strictEqual(cal.endForDuration(D("2026-09-25"), 1), D("2026-09-25"), "a one-day milestone ends the day it starts");
  assert.strictEqual(cal.workingDaysBetween(D("2026-10-15"), D("2026-12-11")), 41, "the Roof complete slip");
  const thanksgiving = sch.workCalendar({ scheduleHolidays: ["2026-11-26", "2026-11-27", "not a date"] });
  assert.deepStrictEqual(thanksgiving.holidays, ["2026-11-26", "2026-11-27"]);
  assert.strictEqual(thanksgiving.workingDaysBetween(D("2026-10-15"), D("2026-12-11")), 39);
  assert.strictEqual(thanksgiving.workingDaysBetween(D("2026-12-11"), D("2026-10-15")), -39, "counting back undoes counting forward");
  assert.strictEqual(thanksgiving.addWorkingDays(D("2026-11-25"), 1), D("2026-11-30"), "the Wednesday before Thanksgiving + 1 is the Monday after");
  assert.deepStrictEqual(sch.workCalendar({ workWeek: [] }).workWeek, [1, 2, 3, 4, 5], "an empty work week falls back to Monday-Friday");
  assert.strictEqual(sch.workCalendar({ workWeek: [1, 2, 3, 4, 5, 6] }).addWorkingDays(D("2026-12-04"), 1), D("2026-12-05"), "a six-day week works Saturday");
  // a phase with missing or wrong-typed fields still reads (the same cases as SchedulePhaseParsingTest.kt)
  let ph = sch.normalizePhase("p1", {});
  assert.strictEqual(ph.status, "Not started"); assert.strictEqual(ph.percentMode, "auto"); assert.strictEqual(ph.start, null); assert.strictEqual(ph.end, null); assert.deepStrictEqual(ph.taskIds, []); assert.deepStrictEqual(ph.predecessors, []);
  ph = sch.normalizePhase("p6", {
    id: "stale", name: 42, start: "next Tuesday", end: "1796446800000", baselineEnd: 1796446800000.0, status: "  in   PROGRESS ", percentMode: "Manual", percentComplete: 140, durationDays: -3, isMilestone: "yes", notes: null,
    predecessors: [{ phaseId: "roof", type: "SS", lagDays: -2.0 }, { phaseId: "siding" }, { phaseId: "septic", type: "finish" }, { type: "FS", lagDays: 3 }, { phaseId: "p6" }, { phaseId: "roof", lagDays: 9 }, "not a predecessor", null],
    taskIds: ["t1", 7, null, "", "t2", "t1"], appointmentIds: { a: 1 },
  });
  assert.strictEqual(ph.id, "p6", "the document id wins"); assert.strictEqual(ph.name, ""); assert.strictEqual(ph.start, null); assert.strictEqual(ph.end, 1796446800000); assert.strictEqual(ph.baselineEnd, 1796446800000);
  assert.strictEqual(ph.status, "In progress"); assert.strictEqual(ph.percentMode, "manual"); assert.strictEqual(ph.percentComplete, 100); assert.strictEqual(ph.durationDays, 0); assert.strictEqual(ph.isMilestone, false); assert.strictEqual(ph.notes, "");
  assert.deepStrictEqual(ph.predecessors, [{ phaseId: "roof", type: "SS", lagDays: -2 }, { phaseId: "siding", type: "FS", lagDays: 0 }, { phaseId: "septic", type: "FS", lagDays: 0 }]);
  assert.deepStrictEqual(ph.taskIds, ["t1", "t2"]); assert.deepStrictEqual(ph.appointmentIds, []);
  const emptyPhase = sch.phaseWriteMap({ id: "p9", name: "Painting", predecessors: [], taskIds: [], appointmentIds: [], start: null, color: "" });
  for (const k of ["predecessors", "taskIds", "appointmentIds", "start", "end", "baselineStart", "baselineEnd", "color"]) assert(!(k in emptyPhase), k + " is left out when empty");
  assert.strictEqual(emptyPhase.status, "Not started"); assert.strictEqual(emptyPhase.percentMode, "auto");
  // percent from linked tasks: ticked items over all items; a task with no checklist counts as one item
  assert.strictEqual(sch.autoPercent([]), null);
  assert.strictEqual(sch.autoPercent([{ status: "Completed" }, { status: "In Progress", checklistGroups: [{ items: [{ isDone: true }, { isDone: false }, { isDone: false }] }] }]), 50);
  assert.strictEqual(sch.percentOf({ status: "Done", percentMode: "auto", percentComplete: 10 }, []), 100);
  assert.strictEqual(sch.percentOf({ status: "In progress", percentMode: "manual", percentComplete: 65 }, [{ status: "Completed" }]), 65);
  // late: past the start and not started, or past the end and not done
  assert.strictEqual(sch.isLate({ status: "Not started", startDay: D("2026-11-02"), endDay: D("2026-11-20") }, D("2026-11-03")), true);
  assert.strictEqual(sch.isLate({ status: "In progress", startDay: D("2026-11-02"), endDay: D("2026-11-20") }, D("2026-11-20")), false);
  assert.strictEqual(sch.isLate({ status: "In progress", startDay: D("2026-11-02"), endDay: D("2026-11-20") }, D("2026-11-21")), true);
  assert.strictEqual(sch.isLate({ status: "Done", startDay: D("2026-11-02"), endDay: D("2026-11-20") }, D("2027-01-01")), false);
  // a loop in the predecessors is found
  const chain = [{ id: "a", predecessors: [] }, { id: "b", predecessors: [{ phaseId: "a" }] }, { id: "c", predecessors: [{ phaseId: "b" }] }];
  assert.deepStrictEqual(sch.findLoop(chain, "a", ["c"]), ["a", "c", "b", "a"]);
  assert.strictEqual(sch.findLoop(chain, "c", ["a"]), null);
  // stored dates and calendar days convert both ways, across the November clock change
  const { msToDay, dayToMs } = require("../mcp.js");
  assert.strictEqual(dayToMs(D("2026-11-02"), tz), zonedToUtc(2026, 11, 2, 0, 0, 0, tz)); assert.strictEqual(msToDay(dayToMs(D("2026-11-01"), tz), tz), D("2026-11-01")); assert.strictEqual(msToDay(dayToMs(D("2027-03-14"), tz), tz), D("2027-03-14"));
  console.log("schedule math ok");

  const ctx = await resolveContext(db, { extra: { email: "dan@example.com", timezone: tz } });
  assert.strictEqual(ctx.envId, "env1");
  assert.strictEqual(ctx.role, "Admin");
  const server = buildServer(ctx);
  const [ct, st] = InMemoryTransport.createLinkedPair();
  await server.connect(st);
  const client = new Client({ name: "test", version: "0" });
  await client.connect(ct);
  const call = async (name, args) => { const r = await client.callTool({ name, arguments: args || {} }); const t = r.content[0].text; if (r.isError) return { error: t }; try { return JSON.parse(t); } catch { return t; } };

  const tools = (await client.listTools()).tools.map((t) => t.name);
  console.log("tools:", tools.length, tools.join(", "));

  let r = await call("whoami"); assert.strictEqual(r.user.email, "dan@example.com");
  r = await call("list_projects"); assert.strictEqual(r.length, 3); assert.strictEqual(r[0].name, "Martin");
  r = await call("list_appointments", { afterDate: "2026-10-19", beforeDate: "2026-10-31" }); assert.strictEqual(r.count, 2); assert.strictEqual(r.appointments[0].start, "Tue 2026-10-20 9:00 AM");
  r = await call("list_appointments", { project: "martin", afterDate: "2026-10-19", beforeDate: "2026-10-31" }); assert.strictEqual(r.count, 2);
  r = await call("get_project", { project: "tes" }); assert(r.error && /several/.test(r.error), "ambiguous should error");
  r = await call("get_project", { project: "Test" }); assert(r.error && /2 projects named/.test(r.error), r.error);
  r = await call("get_project", { project: "pT2" }); assert.strictEqual(r.id, "pT2");

  // legacy checklist recovered
  r = await call("get_task", { task: "roofing" }); assert.strictEqual(r.checklists[0].items.length, 2); assert.strictEqual(r.checklist, "1/2 done");
  r = await call("set_checklist_item", { task: "Roofing", item: "tear off", done: true }); assert.deepStrictEqual(r.progress, { done: 2, total: 2 });
  assert(!("checklist" in db._store.get("tasks/t1")), "legacy field must not be written back");
  assert.strictEqual(db._store.get("tasks/t1").checklistGroups[0].items[1].isDone, true);

  // create_task rejects bad status
  r = await call("create_task", { project: "Martin", title: "Calls - Oct 21", status: "Top priority stuff" }); assert(r.error && /not a bucket/.test(r.error));
  r = await call("create_task", { project: "Martin", title: "Calls - Oct 21", status: "top priority", assignedTo: ["me"], dueDate: "2026-10-21", checklists: [{ title: "Calls", items: ["A Plus", "Electrician"] }] });
  assert.strictEqual(r.created, true); assert.strictEqual(r.status, "TOP Priority"); assert.deepStrictEqual(r.assignees, ["dan@example.com"]);
  const newId = r.id;
  r = await call("create_task", { project: "Martin", title: "calls - oct 21", status: "TOP Priority" }); assert(r.error && /already exists/.test(r.error));
  r = await call("add_checklist_items", { task: newId, checklistTitle: "calls", items: ["Electrician", "Spectrum"] }); assert.strictEqual(r.added.length, 1); assert.strictEqual(r.nowHas, 3);
  r = await call("list_tasks", { project: "Martin", assignee: "me", dueBefore: "2026-10-21" }); assert.strictEqual(r.count, 2);
  assert.strictEqual(db._store.get("tasks/t0").environmentId, "env1", "orphaned task repaired");
  r = await call("update_task", { task: newId, status: "Completed", addAssignees: ["mike@example.com"] }); assert.strictEqual(r.status, "Completed"); assert.strictEqual(r.assignees.length, 2);
  r = await call("list_tasks", { project: "Martin" }); assert.strictEqual(r.count, 2, "completed hidden");
  r = await call("summarize_project", { project: "Martin" }); assert.strictEqual(r.overdue.length, 1);

  // appointments CRUD
  r = await call("create_appointment", { project: "Martin", title: "Roofer on site", start: "2026-10-22T11:00", location: "Martin Cottage" }); assert.strictEqual(r.start, "Thu 2026-10-22 11:00 AM"); assert.strictEqual(r.end, "Thu 2026-10-22 12:00 PM");
  r = await call("update_appointment", { project: "Martin", appointment: "Roofer on site", start: "2026-10-22T13:30" }); assert.strictEqual(r.start, "Thu 2026-10-22 1:30 PM"); assert.strictEqual(r.end, "Thu 2026-10-22 2:30 PM");
  r = await call("delete_appointment", { project: "Martin", appointment: "Roofer on site" }); assert(/Deleted/.test(r));

  // contacts
  r = await call("create_contact", { name: "A Plus Concrete", type: "Subcontractor", jobTitle: "Concrete", phone: "231-555-0100" }); assert.strictEqual(r.created, true);
  r = await call("list_contacts", { search: "concrete" }); assert.strictEqual(r.count, 1);
  r = await call("update_contact", { contact: "a plus", email: "aplus@example.com" }); assert.strictEqual(r.email, "aplus@example.com");

  // project create/update
  r = await call("create_project", { name: "Hansen Garage", location: "Manistee, MI" }); assert.strictEqual(r.created, true); assert.deepStrictEqual(r.members, ["dan@example.com"]);
  r = await call("update_project", { project: "Hansen", addMembers: ["Mike@Example.com"], buckets: ["Not Started", "Working", "Done"] }); assert.deepStrictEqual(r.members, ["dan@example.com", "mike@example.com"]);
  r = await call("list_team"); assert.strictEqual(r.length, 2);

  // access keys
  r = await call("create_access_key", { email: "mike@example.com", label: "Mike - ChatGPT" }); assert(r.accessKey.startsWith("cs_"));
  r = await call("list_access_keys"); assert.strictEqual(r.length, 1);
  r = await call("revoke_access_key", { email: "mike@example.com" }); assert(/Revoked 1/.test(r));
  // member can't mint
  const ctx2 = await resolveContext(db, { extra: { email: "mike@example.com" } });
  const s2 = buildServer(ctx2); const [c2, t2] = InMemoryTransport.createLinkedPair(); await s2.connect(t2); const cl2 = new Client({ name: "t", version: "0" }); await cl2.connect(c2);
  const rr = await cl2.callTool({ name: "create_access_key", arguments: { email: "mike@example.com" } }); assert(rr.isError);

  // ---- daily log ----
  r = await call("get_daily_log", { project: "Martin" }); assert.strictEqual(r.empty, true);
  r = await call("add_to_daily_log", { project: "Martin", crewOnSite: ["JR + 2 helpers - roofing", "Jeff, Ian, Owen - lanai"], workCompleted: ["East side roof dried in"], rawText: "JR and two guys roofed, east side dried in, my guys were on the lanai", source: "sierra" });
  assert.deepStrictEqual(r.added.sort(), ["crewOnSite", "workCompleted"]); assert.strictEqual(r.crewOnSite.length, 2); assert.strictEqual(r.contributors[0], "dan@example.com");
  const today = r.date; assert.match(today, /^\d{4}-\d{2}-\d{2}$/);
  r = await call("add_to_daily_log", { project: "Martin", workCompleted: ["east side roof dried in", "Lanai pocket door set"], issuesDelays: ["Waiting on meter can from Consumers"], notes: "Mike stopped by at 2." });
  assert.strictEqual(r.workCompleted.length, 2, "dupe skipped case-insensitively"); assert.strictEqual(r.recentAdditions.length, 1);
  r = await call("set_daily_log_section", { project: "Martin", section: "issuesDelays", lines: [] }); assert.deepStrictEqual(r.issuesDelays, []);
  r = await call("get_daily_log", { project: "martin", date: today }); assert.strictEqual(r.notes, "Mike stopped by at 2."); assert.strictEqual(r.pdfUrl, undefined);
  r = await call("list_daily_logs", { project: "Martin" }); assert.strictEqual(r.count, 1); assert.strictEqual(r.days[0].pdf, "none");
  r = await call("daily_report_pdf", { project: "Martin" }); assert.strictEqual(r.rebuilt, true); assert(r.url.includes("daily_reports")); assert(r.fileName.startsWith("Daily Report - Martin - "));
  const pdfBytes = saved[`projects/pM/daily_reports/${today}.pdf`]; assert(pdfBytes && pdfBytes.length > 1500, "pdf rendered"); assert.strictEqual(pdfBytes.slice(0, 4).toString(), "%PDF");
  require("fs").writeFileSync(require("path").join(__dirname, "last-daily-report.pdf"), pdfBytes);
  r = await call("daily_report_pdf", { project: "Martin" }); assert.strictEqual(r.rebuilt, false, "current pdf reused");
  r = await call("list_files", { project: "Martin", folder: "Daily Reports" }); assert.strictEqual(r.count, 1);
  r = await call("add_to_daily_log", { project: "Martin", tomorrowPlan: ["Pour patio - A Plus 8 AM"] });
  r = await call("list_daily_logs", { project: "Martin" }); assert.strictEqual(r.days[0].pdf, "stale");
  // ---- weather readings ----
  const dr = require("../dailyReport.js");
  const projDoc = { ...(await db.collection("projects").doc("pM").get()).data(), id: "pM" };
  let w = await dr.recordWeatherForProject(db, projDoc, "morning"); assert.strictEqual(w.tempF, 34); assert.strictEqual(w.windMph, 15); assert.strictEqual(w.precip, true);
  assert.strictEqual(db._store.get("projects/pM").siteGeo.lat, 44.4, "geocode cached on project");
  obsTempC = 5; obsText = "Overcast";
  w = await dr.recordWeatherForProject(db, projDoc, "midday"); assert.strictEqual(w.tempF, 41);
  w = await dr.recordWeatherForProject(db, projDoc, "midday"); // same slot again replaces, not duplicates
  // (the very first add_to_daily_log above already took a first-touch reading for the current slot, so 2 or 3 here)
  r = await call("get_daily_log", { project: "Martin" }); assert(r.weatherReadings.length >= 2 && r.weatherReadings.length <= 3, "readings: " + r.weatherReadings.length);
  assert(r.weather.includes("7 AM 34° light rain, 15 mph wind") && r.weather.includes("Noon 41° overcast"), r.weather);
  assert.strictEqual(r.noWorkLogged, undefined);
  r = await call("list_daily_logs", { project: "Martin" }); assert.strictEqual(r.days[0].pdf, "stale", "new reading makes the pdf stale");
  // a weekend: weather only, nobody wrote anything
  const sat = "2026-11-21"; await db.collection("projects").doc("pM").collection("dailyLogs").doc(sat).set({ weatherReadings: [{ slot: "morning", at: 1, tempF: 30, conditions: "Snow", windMph: 5, precip: true }], createdBy: "weather", createdAt: 1 });
  r = await call("get_daily_log", { project: "Martin", date: sat }); assert.strictEqual(r.noWorkLogged, true); assert.strictEqual(r.weather, "7 AM 30° snow");
  r = await call("add_to_daily_log", { project: "Martin", date: "2026-11-10", workCompleted: ["Backdated entry"] }); assert.strictEqual(r.weatherReadings, undefined, "no made-up weather for past days");
  r = await call("daily_report_pdf", { project: "Martin", force: true }); require("fs").writeFileSync(require("path").join(__dirname, "last-daily-report.pdf"), saved[`projects/pM/daily_reports/${today}.pdf`]);

  r = await call("add_to_daily_log", { project: "Martin", date: "11/17/2026", workCompleted: ["x"] }); assert(r.error && /YYYY-MM-DD/.test(r.error));
  r = await call("add_to_daily_log", { project: "Martin" }); assert(r.error && /Nothing to add/.test(r.error));

  // ---- schedule tools ----
  const phaseDocs = () => [...db._store].filter(([k]) => k.startsWith("projects/pM/schedulePhases/")).map(([, v]) => v);
  const phaseDoc = (name) => phaseDocs().find((d) => d.name === name);
  r = await call("list_schedule", { project: "Martin" }); assert.strictEqual(r.count, 0); assert(/No schedule yet/.test(r.note)); assert.deepStrictEqual(r.workWeek, ["Mon", "Tue", "Wed", "Thu", "Fri"]);
  // The Martin schedule as planned on Sep 21, 2026
  const martin = [
    { group: "Exterior / Site", name: "Exterior walls at chimney", start: "2026-09-25", milestone: true },
    { group: "Exterior / Site", name: "Roof complete", start: "2026-09-21", end: "2026-10-15" },
    { group: "Exterior / Site", name: "Siding", start: "2026-09-21", end: "2026-11-15" },
    { group: "Exterior / Site", name: "Septic & grading", start: "2026-10-15", end: "2026-11-15" },
    { group: "Exterior / Site", name: "Remaining concrete", start: "2026-11-01", end: "2026-11-15" },
    { group: "Rough trades", name: "Electrical service", start: "2026-10-05", end: "2026-10-09" },
    { group: "Rough trades", name: "Mechanical & plumbing", start: "2026-10-15", end: "2026-11-19" },
    { group: "Rough trades", name: "Electrical rough-in", start: "2026-10-29", end: "2026-11-19" },
    { group: "Interior", name: "Insulation", start: "2026-11-19", end: "2026-12-04" },
    { group: "Interior", name: "Drywall", start: "2026-12-04", end: "2027-01-04" },
    { group: "Interior", name: "Tile & flooring", start: "2027-01-04", end: "2027-01-23" },
    { group: "Interior", name: "Painting", start: "2027-01-09", end: "2027-03-20" },
    { group: "Interior", name: "Trim & cabinets", start: "2027-01-23", end: "2027-03-05" },
    { group: "Closeout", name: "Finish MEP", start: "2027-03-15", end: "2027-04-05" },
  ];

  // import: a dry run shows what would be created and saves nothing
  r = await call("import_schedule", { project: "Martin", rows: martin, asBaseline: true, dryRun: true });
  assert.strictEqual(r.dryRun, true); assert.strictEqual(r.saved, false); assert.strictEqual(r.wouldCreate, 14); assert.strictEqual(r.asBaseline, true); assert.strictEqual(r.problems, undefined);
  assert.deepStrictEqual(r.phases[1], { name: "Roof complete", group: "Exterior / Site", start: "Mon 2026-09-21", end: "Thu 2026-10-15", workingDays: 19 });
  assert.strictEqual(r.phases[0].milestone, true); assert.strictEqual(r.phases[0].end, "Fri 2026-09-25"); assert.strictEqual(r.phases[0].workingDays, 1);
  assert.strictEqual(phaseDocs().length, 0, "a dry run saves nothing");
  // ... and lists every row that has a problem
  r = await call("import_schedule", { project: "Martin", dryRun: true, rows: [
    { name: "Good", start: "2026-12-01", durationDays: 3 }, { name: "No end", start: "2026-12-01" }, { name: "Backwards", start: "2026-12-10", end: "2026-12-01" },
    { name: "good", start: "2026-12-01", end: "2026-12-02" }, { name: "Bad date", start: "12/01/2026", end: "2026-12-02" }, { name: "Undated" }, { name: " ", start: "2026-12-01", end: "2026-12-02" }, { name: "Bad status", start: "2026-12-01", end: "2026-12-02", status: "Cancelled" },
  ] });
  assert.strictEqual(r.wouldCreate, 1); assert.strictEqual(r.phases[0].end, "Thu 2026-12-03"); assert.strictEqual(r.problems.length, 7, JSON.stringify(r.problems));
  assert(/^Row 2 \(No end\): .*also needs an end/.test(r.problems[0])); assert(/^Row 3 \(Backwards\): .*before the start/.test(r.problems[1])); assert(/^Row 4 \(good\): Same name/.test(r.problems[2]));
  assert(/^Row 5 \(Bad date\): .*YYYY-MM-DD/.test(r.problems[3])); assert(/^Row 6 \(Undated\): No dates/.test(r.problems[4])); assert(/^Row 7: No name/.test(r.problems[5])); assert(/^Row 8 \(Bad status\): .*not a phase status/.test(r.problems[6]));
  // a real import with one bad row saves nothing at all
  r = await call("import_schedule", { project: "Martin", rows: [...martin, { name: "No end", start: "2026-12-01" }] });
  assert(r.error && /Nothing was saved/.test(r.error) && /Row 15 \(No end\)/.test(r.error), r.error); assert.strictEqual(phaseDocs().length, 0);
  // the real import, with the plan recorded as the baseline
  r = await call("import_schedule", { project: "martin", rows: martin, asBaseline: true });
  assert.strictEqual(r.created, 14); assert.strictEqual(r.baselineSet, true); assert.strictEqual(r.phases.length, 14); assert.strictEqual(phaseDocs().length, 14);
  assert.deepStrictEqual(r.totals.finish, { planned: "Mon 2027-04-05", current: "Mon 2027-04-05", slipDays: 0 });
  // stored the way the app stores it: local-midnight epoch ms, the same keys as SchedulePhase.toFirestoreMap()
  const roofDoc = phaseDoc("Roof complete");
  assert.strictEqual(roofDoc.start, zonedToUtc(2026, 9, 21, 0, 0, 0, tz)); assert.strictEqual(roofDoc.end, zonedToUtc(2026, 10, 15, 0, 0, 0, tz));
  assert.strictEqual(roofDoc.baselineStart, roofDoc.start); assert.strictEqual(roofDoc.baselineEnd, roofDoc.end); assert.strictEqual(roofDoc.durationDays, 19);
  assert.strictEqual(roofDoc.projectId, "pM"); assert.strictEqual(roofDoc.environmentId, "env1"); assert.strictEqual(roofDoc.group, "Exterior / Site"); assert.strictEqual(roofDoc.order, 2);
  assert.strictEqual(roofDoc.status, "Not started"); assert.strictEqual(roofDoc.percentMode, "auto"); assert.strictEqual(roofDoc.isMilestone, false); assert.strictEqual(roofDoc.updatedBy, "dan@example.com"); assert(roofDoc.id && roofDoc.createdAt > 0);
  assert(!("taskIds" in roofDoc) && !("appointmentIds" in roofDoc) && !("predecessors" in roofDoc) && !("color" in roofDoc), "empty lists and a blank colour are not written");
  assert.strictEqual(phaseDoc("Exterior walls at chimney").isMilestone, true);
  // running the same import again adds nothing
  r = await call("import_schedule", { project: "Martin", rows: martin, asBaseline: true }); assert.strictEqual(r.created, 0); assert.strictEqual(r.skippedAlreadyOnSchedule.length, 14); assert.strictEqual(phaseDocs().length, 14);

  // list_schedule
  r = await call("list_schedule", { project: "Martin" });
  assert.strictEqual(r.count, 14); assert.deepStrictEqual(r.phases.map((x) => x.name), martin.map((x) => x.name), "kept in the order given");
  let roof = r.phases.find((x) => x.name === "Roof complete");
  assert.strictEqual(roof.start, "Mon 2026-09-21"); assert.strictEqual(roof.end, "Thu 2026-10-15"); assert.strictEqual(roof.workingDays, 19); assert.strictEqual(roof.baselineEnd, "Thu 2026-10-15");
  assert.strictEqual(roof.slipDays, 0); assert.strictEqual(roof.status, "Not started"); assert.strictEqual(roof.percent, 0); assert.strictEqual(roof.linkedTasks, 0); assert.strictEqual(roof.linkedAppointments, 0);
  assert.strictEqual(r.phases.find((x) => x.name === "Siding").end, "Sun 2026-11-15", "a planned date that falls on a weekend is kept as given");
  assert.strictEqual(r.totals.notStarted, 14); assert.strictEqual(r.totals.done, 0);
  r = await call("list_schedule", { project: "Martin", from: "2026-12-01", to: "2026-12-31" }); assert.deepStrictEqual(r.phases.map((x) => x.name), ["Insulation", "Drywall"], "a date window");
  assert.deepStrictEqual(r.window, { from: "Tue 2026-12-01", to: "Thu 2026-12-31" }); assert.strictEqual(r.totals.notStarted, 14, "totals are for the whole schedule");

  // the slip test from the plan: Roof complete's end moves to Dec 11 and the baseline stays at Oct 15
  r = await call("update_phase", { project: "Martin", phase: "roof complete", end: "2026-12-11", status: "in progress" });
  assert.deepStrictEqual([...r.updated].sort(), ["end", "status"]); assert.strictEqual(r.start, "Mon 2026-09-21"); assert.strictEqual(r.end, "Fri 2026-12-11"); assert.strictEqual(r.workingDays, 60);
  assert.strictEqual(r.baselineStart, "Mon 2026-09-21"); assert.strictEqual(r.baselineEnd, "Thu 2026-10-15"); assert.strictEqual(r.slipDays, 41); assert.strictEqual(r.status, "In progress"); assert.strictEqual(r.updatedBy, "dan@example.com");
  assert.strictEqual(phaseDoc("Roof complete").baselineEnd, roofDoc.baselineEnd, "the baseline never moves on an update"); assert.strictEqual(phaseDoc("Roof complete").createdAt, roofDoc.createdAt);
  // with Thanksgiving off the same slip is two working days shorter, and the stored lengths follow
  r = await call("update_project", { project: "Martin", scheduleHolidays: ["2026-11-27", "2026-11-26", "2026-11-26"] });
  assert.deepStrictEqual(r.scheduleHolidays, ["2026-11-26", "2026-11-27"]); assert.deepStrictEqual(r.workWeek, ["Mon", "Tue", "Wed", "Thu", "Fri"]);
  r = await call("update_project", { project: "Martin", scheduleHolidays: ["Thanksgiving"] }); assert(r.error && /YYYY-MM-DD/.test(r.error));
  r = await call("list_schedule", { project: "Martin" }); roof = r.phases.find((x) => x.name === "Roof complete");
  assert.strictEqual(roof.slipDays, 39); assert.strictEqual(roof.workingDays, 58); assert.deepStrictEqual(r.holidays, ["2026-11-26", "2026-11-27"]); assert.strictEqual(r.totals.inProgress, 1);
  assert.strictEqual(phaseDoc("Roof complete").durationDays, 58, "stored length kept in step with the calendar"); assert.strictEqual(phaseDoc("Insulation").durationDays, 10);

  // create: start + working days, a predecessor with an overlap, a contact found by name
  r = await call("create_phase", { project: "Martin", name: "Final inspection", group: "Closeout", start: "2027-04-02", durationDays: 3, predecessors: [{ phase: "finish mep", lagDays: -1 }], trade: "Inspection", contact: "a plus", notes: "Call the county a week ahead" });
  assert.strictEqual(r.created, true); assert.strictEqual(r.start, "Fri 2027-04-02"); assert.strictEqual(r.end, "Tue 2027-04-06"); assert.strictEqual(r.workingDays, 3); assert.strictEqual(r.group, "Closeout"); assert.strictEqual(r.project, "Martin");
  assert.strictEqual(r.contact, "A Plus Concrete"); assert.strictEqual(r.contactLinked, true); assert.strictEqual(r.trade, "Inspection"); assert.strictEqual(r.notes, "Call the county a week ahead");
  assert.deepStrictEqual(r.predecessors.map((x) => [x.name, x.type, x.lagDays, x.end]), [["Finish MEP", "FS", -1, "Mon 2027-04-05"]]);
  assert.strictEqual(r.baselineEnd, undefined); assert.strictEqual(r.slipDays, undefined, "no baseline until set_baseline");
  const inspectionId = r.id; assert.strictEqual(phaseDoc("Final inspection").order, 15, "a new phase goes to the bottom");
  r = await call("create_phase", { project: "Martin", name: "final inspection", start: "2027-04-02", durationDays: 1 }); assert(r.error && /already exists/.test(r.error));
  r = await call("create_phase", { project: "Martin", name: "Punch list", start: "2027-04-07" }); assert(r.error && /also needs an end/.test(r.error));
  r = await call("create_phase", { project: "Martin", name: "Punch list", start: "2027-04-07", end: "2027-04-01" }); assert(r.error && /before the start/.test(r.error));
  r = await call("create_phase", { project: "Martin", name: "Punch list", start: "April 7", durationDays: 2 }); assert(r.error && /YYYY-MM-DD/.test(r.error));
  r = await call("create_phase", { project: "Martin", name: "Punch list", start: "2027-04-07", durationDays: 2, predecessors: [{ phase: "No such phase" }] }); assert(r.error && /No phase found/.test(r.error));
  assert.strictEqual(phaseDocs().length, 15, "a refused create saves nothing");
  // end + working days works the start out backwards; a name that is not a contact is kept as text; a typed percent is manual
  r = await call("create_phase", { project: "Martin", name: "Punch list", end: "2027-04-16", durationDays: 5, contact: "Somebody New", status: "on hold", percentComplete: 20 });
  assert.strictEqual(r.start, "Mon 2027-04-12"); assert.strictEqual(r.contact, "Somebody New"); assert.strictEqual(r.contactLinked, false); assert.strictEqual(r.status, "On hold"); assert.strictEqual(r.percent, 20); assert.strictEqual(r.percentMode, "manual");
  r = await call("create_phase", { project: "Martin", name: "Owner walk-through", end: "2027-04-19", milestone: true }); assert.strictEqual(r.milestone, true); assert.strictEqual(r.start, "Mon 2027-04-19"); assert.strictEqual(r.end, "Mon 2027-04-19"); assert.strictEqual(r.workingDays, 1);
  r = await call("create_phase", { project: "Martin", name: "Landscaping" }); assert.strictEqual(r.created, true); assert.strictEqual(r.start, undefined, "a phase with no dates yet is allowed");
  r = await call("list_schedule", { project: "Martin" }); assert.strictEqual(r.count, 18); assert.strictEqual(r.phases[17].name, "Landscaping");
  assert.deepStrictEqual(r.totals.finish, { planned: "Mon 2027-04-05", current: "Mon 2027-04-19", slipDays: 10 }, "slip at the finish");

  // update: a new start alone moves the phase and keeps its length; a new length moves the end
  r = await call("update_phase", { project: "Martin", phase: inspectionId, start: "2027-04-08" }); assert.strictEqual(r.start, "Thu 2027-04-08"); assert.strictEqual(r.end, "Mon 2027-04-12"); assert.strictEqual(r.workingDays, 3);
  r = await call("update_phase", { project: "Martin", phase: inspectionId, durationDays: 1 }); assert.strictEqual(r.start, "Thu 2027-04-08"); assert.strictEqual(r.end, "Thu 2027-04-08");
  r = await call("update_phase", { project: "Martin", phase: "Owner walk-through", start: "2027-04-20" }); assert.strictEqual(r.end, "Tue 2027-04-20", "a milestone stays one day");
  r = await call("update_phase", { project: "Martin", phase: "Punch list", percentMode: "auto", contact: "", color: "#FF9800", order: 0.5, group: "Closeout", trade: "NACE" });
  assert.strictEqual(r.percentMode, "auto"); assert.strictEqual(r.contact, undefined); assert.strictEqual(r.color, "#FF9800"); assert.strictEqual(r.group, "Closeout"); assert.strictEqual(r.trade, "NACE");
  r = await call("list_schedule", { project: "Martin" }); assert.strictEqual(r.phases[0].name, "Punch list", "order moves a phase");
  r = await call("update_phase", { project: "Martin", phase: "Punch list", color: "", clearDates: true }); assert.strictEqual(r.start, undefined); assert.strictEqual(r.end, undefined); assert.strictEqual(r.color, undefined);
  assert(!("start" in phaseDoc("Punch list")) && !("end" in phaseDoc("Punch list")) && !("color" in phaseDoc("Punch list")), "cleared fields are removed, not blanked");
  r = await call("update_phase", { project: "Martin", phase: "Punch list", status: "Cancelled" }); assert(r.error && /not a phase status/.test(r.error));
  r = await call("update_phase", { project: "Martin", phase: "Punch list" }); assert(r.error && /Nothing to update/.test(r.error));
  r = await call("update_phase", { project: "Martin", phase: "Punch list", name: "Siding" }); assert(r.error && /already named/.test(r.error));
  r = await call("update_phase", { project: "Martin", phase: "ing", notes: "x" }); assert(r.error && /several phases/.test(r.error), "an ambiguous name asks for more");
  r = await call("update_phase", { project: "Martin", phase: "Punch list", name: "Punch-out" }); assert.strictEqual(r.name, "Punch-out");

  // predecessors: Drywall waits for Insulation, so Insulation can't be made to wait for Drywall
  r = await call("update_phase", { project: "Martin", phase: "Drywall", predecessors: [{ phase: "Insulation", lagDays: -1 }] }); assert.deepStrictEqual(r.predecessors.map((x) => [x.name, x.end]), [["Insulation", "Fri 2026-12-04"]]);
  r = await call("update_phase", { project: "Martin", phase: "Tile & flooring", predecessors: [{ phase: "Drywall", type: "SS", lagDays: 5 }] });
  r = await call("update_phase", { project: "Martin", phase: "Insulation", predecessors: [{ phase: "Tile & flooring" }] });
  assert(r.error && /Insulation waits for Tile & flooring waits for Drywall waits for Insulation/.test(r.error), r.error); assert(!("predecessors" in phaseDoc("Insulation")));
  r = await call("update_phase", { project: "Martin", phase: "Drywall", predecessors: [{ phase: "Drywall" }] }); assert(r.error && /own predecessor/.test(r.error));
  r = await call("get_phase", { project: "Martin", phase: "Drywall" }); assert.deepStrictEqual(r.successors.map((x) => [x.name, x.type, x.lagDays]), [["Tile & flooring", "SS", 5]]);
  r = await call("list_schedule", { project: "Martin" }); assert.deepStrictEqual(r.phases.find((x) => x.name === "Drywall").predecessors, ["Insulation (FS, lag -1)"]);

  // ---- linking: stored on the phase; the task and appointment documents are never written ----
  r = await call("create_task", { project: "Martin", title: "Roof tear-off", status: "In Progress", checklists: [{ title: "Steps", items: ["Strip", "Deck repair", "Dry in", "Shingle"] }] }); const tearOff = r.id;
  r = await call("set_checklist_item", { task: tearOff, item: "Strip", done: true });
  r = await call("create_task", { project: "Martin", title: "Order ridge vent", status: "Completed" }); const ridge = r.id;
  const taskBefore = JSON.stringify(db._store.get("tasks/" + tearOff)), apptBefore = JSON.stringify(db._store.get("projects/pM/appointments/a1"));
  r = await call("link_to_phase", { project: "Martin", phase: "Roof complete", tasks: ["roof tear-off", ridge], appointments: ["Weekly Meeting"] });
  assert.deepStrictEqual(r.linked, { tasks: ["Roof tear-off", "Order ridge vent"], appointments: ["Weekly Meeting"] }); assert.strictEqual(r.linkedTasks, 2); assert.strictEqual(r.linkedAppointments, 1);
  assert.strictEqual(r.percent, 40, "1 of 4 items ticked plus a completed task with no checklist = 2 of 5");
  assert.strictEqual(r.tasks[0].checklist, "1/4 done"); assert.strictEqual(r.appointments[0].title, "Weekly Meeting");
  assert.strictEqual(JSON.stringify(db._store.get("tasks/" + tearOff)), taskBefore, "linking does not write the task"); assert.strictEqual(JSON.stringify(db._store.get("projects/pM/appointments/a1")), apptBefore, "or the appointment");
  assert.deepStrictEqual(phaseDoc("Roof complete").taskIds, [tearOff, ridge]); assert.deepStrictEqual(phaseDoc("Roof complete").appointmentIds, ["a1"]);
  // get_task and list_appointments now say which phase
  r = await call("get_task", { task: tearOff }); assert.strictEqual(r.phaseId, roofDoc.id); assert.strictEqual(r.phase, "Roof complete"); assert.strictEqual(r.checklist, "1/4 done");
  r = await call("get_task", { task: "Roofing" }); assert.strictEqual(r.phase, undefined, "a task on no phase reads as before"); assert.strictEqual(r.checklists[0].items.length, 2);
  r = await call("list_appointments", { project: "Martin", afterDate: "2026-10-19", beforeDate: "2026-10-31" }); assert.strictEqual(r.count, 2); assert.strictEqual(r.appointments[0].phase, "Roof complete"); assert.strictEqual(r.appointments[0].phaseId, roofDoc.id); assert.strictEqual(r.appointments[0].start, "Tue 2026-10-20 9:00 AM");
  // the link survives a whole-task save, which is why it is not kept on the task
  r = await call("update_task", { task: tearOff, description: "Start on the east side" }); r = await call("get_task", { task: tearOff }); assert.strictEqual(r.phase, "Roof complete");
  // linking again changes nothing; a task is on at most one phase, so linking it elsewhere moves it
  r = await call("link_to_phase", { project: "Martin", phase: "Roof complete", tasks: [tearOff] }); assert.strictEqual(r.linkedTasks, 2);
  r = await call("link_to_phase", { project: "Martin", phase: "Siding", tasks: [ridge] }); assert.deepStrictEqual(r.movedFrom, ["Roof complete"]); assert.strictEqual(r.linkedTasks, 1); assert.strictEqual(r.percent, 100);
  r = await call("get_phase", { project: "Martin", phase: "Roof complete" }); assert.strictEqual(r.linkedTasks, 1); assert.strictEqual(r.percent, 25);
  r = await call("link_to_phase", { project: "Martin", phase: "Siding", tasks: ["No such task"] }); assert(r.error && /No task found/.test(r.error));
  r = await call("link_to_phase", { project: "Martin", phase: "Siding" }); assert(r.error && /Pass tasks/.test(r.error));
  // a typed percent overrides the checklists; auto goes back to them; Done is always 100
  r = await call("update_phase", { project: "Martin", phase: "Roof complete", percentComplete: 80 }); assert.strictEqual(r.percent, 80); assert.strictEqual(r.percentMode, "manual");
  r = await call("update_phase", { project: "Martin", phase: "Roof complete", percentMode: "auto" }); assert.strictEqual(r.percent, 25);
  r = await call("update_phase", { project: "Martin", phase: "Roof complete", status: "Done" }); assert.strictEqual(r.percent, 100); assert.strictEqual(r.late, undefined);
  // unlink: from whichever phase it is on; a deleted task's id can still be cleaned off
  r = await call("unlink_from_phase", { project: "Martin", tasks: [ridge] }); assert.deepStrictEqual(r, { unlinked: 1, from: ["Siding"] });
  assert(!("taskIds" in phaseDoc("Siding")), "an emptied list is removed, never stored empty");
  r = await call("unlink_from_phase", { project: "Martin", tasks: [ridge] }); assert(/Nothing to unlink/.test(r), String(r));
  r = await call("unlink_from_phase", { project: "Martin", phase: "Siding", tasks: [tearOff] }); assert(/none of those are on "Siding"/.test(r), String(r)); assert.deepStrictEqual(phaseDoc("Roof complete").taskIds, [tearOff]);
  r = await call("link_to_phase", { project: "Martin", phase: "Siding", tasks: [ridge] });
  r = await call("delete_task", { task: ridge });
  r = await call("get_phase", { project: "Martin", phase: "Siding" }); assert.strictEqual(r.linkedTasks, 0); assert.deepStrictEqual(r.missingTaskIds, [ridge]);
  r = await call("unlink_from_phase", { project: "Martin", tasks: [ridge] }); assert.strictEqual(r.unlinked, 1);

  // ---- baseline: the original plan is not overwritten unless asked ----
  r = await call("set_baseline", { project: "Martin" });
  assert.deepStrictEqual([...r.baselineSet].sort(), ["Final inspection", "Owner walk-through"]); assert.strictEqual(r.keptExistingBaseline.length, 14); assert.deepStrictEqual([...r.skippedNoDates].sort(), ["Landscaping", "Punch-out"]);
  r = await call("get_phase", { project: "Martin", phase: "Roof complete" }); assert.strictEqual(r.baselineEnd, "Thu 2026-10-15"); assert.strictEqual(r.slipDays, 39);
  r = await call("get_phase", { project: "Martin", phase: "Final inspection" }); assert.strictEqual(r.baselineEnd, "Thu 2027-04-08"); assert.strictEqual(r.slipDays, 0);
  r = await call("set_baseline", { project: "Martin", phases: ["Roof complete"], overwrite: true }); assert.deepStrictEqual(r.baselineSet, ["Roof complete"]); assert.strictEqual(r.keptExistingBaseline, undefined);
  r = await call("get_phase", { project: "Martin", phase: "Roof complete" }); assert.strictEqual(r.baselineEnd, "Fri 2026-12-11"); assert.strictEqual(r.slipDays, 0);
  r = await call("set_baseline", { project: "pT" }); assert(r.error && /no schedule phases/.test(r.error));

  // ---- delete: the phase goes, its tasks and appointments stay, and nothing is left waiting on it ----
  r = await call("delete_phase", { project: "Martin", phase: "Insulation" }); assert(/Deleted phase "Insulation"/.test(r) && /predecessor of: Drywall/.test(r), String(r));
  r = await call("get_phase", { project: "Martin", phase: "Drywall" }); assert.deepStrictEqual(r.predecessors, []); assert(!("predecessors" in phaseDoc("Drywall")));
  r = await call("delete_phase", { project: "Martin", phase: "Roof complete" }); assert(/Its 1 linked task\(s\) and 1 linked appointment\(s\) are kept/.test(r), String(r));
  assert(db._store.has("tasks/" + tearOff) && db._store.has("projects/pM/appointments/a1"), "the linked records survive");
  r = await call("get_task", { task: tearOff }); assert.strictEqual(r.phase, undefined); assert.strictEqual(r.phaseId, undefined);
  r = await call("list_appointments", { project: "Martin", afterDate: "2026-10-19", beforeDate: "2026-10-31" }); assert.strictEqual(r.appointments[0].phase, undefined);
  r = await call("get_phase", { project: "Martin", phase: "Roof complete" }); assert(r.error && /No phase found/.test(r.error));
  // nothing these tools wrote holds an empty list (the web app's encoder can't take one)
  for (const d of phaseDocs()) for (const [k, v] of Object.entries(d)) assert(!(Array.isArray(v) && v.length === 0), `${d.name}.${k} is an empty list`);
  r = await call("list_schedule", { project: "Martin" }); assert.strictEqual(r.count, 16);
  // a hand-made phase with almost nothing in it still lists
  await db.collection("projects").doc("pT").collection("schedulePhases").doc("bare").set({ name: "Bare", end: "soon", status: 7 });
  r = await call("list_schedule", { project: "pT" }); assert.strictEqual(r.count, 1); assert.deepStrictEqual(r.phases[0], { id: "bare", name: "Bare", status: "Not started", percent: 0, linkedTasks: 0, linkedAppointments: 0 });
  r = await call("delete_task", { task: tearOff });
  console.log("schedule tools ok");

  // ---- shifting: the same five-phase chain as the app's ScheduleShiftTest.kt, and the same answers ----
  //   Framing            Mon Nov 2  - Fri Nov 6
  //   Rough plumbing     Mon Nov 9  - Fri Nov 13    after Framing finishes
  //   Rough electrical   Wed Nov 11 - Tue Nov 17    two working days after plumbing STARTS
  //   Insulation         Tue Nov 17 - Mon Nov 23    starts on electrical's last day (finish-to-start, lag -1)
  //   Drywall            Tue Nov 24 - ...           after Insulation AND after plumbing
  assert.strictEqual(cal.successorStart(D("2026-11-09"), D("2026-11-13"), "FS", 0), D("2026-11-16"), "the working day after it finishes");
  assert.strictEqual(cal.successorStart(D("2026-11-09"), D("2026-11-13"), "FS", -1), D("2026-11-13"), "lag -1 starts on its last day");
  assert.strictEqual(cal.successorStart(D("2026-11-09"), D("2026-11-13"), "SS", 2), D("2026-11-11"), "two working days after it starts");
  const hansen = "Hansen Garage";
  const mk = (name, start, extra) => call("create_phase", { project: hansen, name, start, ...extra });
  r = await mk("Framing", "2026-11-02", { end: "2026-11-06" });
  r = await mk("Rough plumbing", "2026-11-09", { end: "2026-11-13", predecessors: [{ phase: "Framing" }] });
  r = await mk("Rough electrical", "2026-11-11", { end: "2026-11-17", predecessors: [{ phase: "Rough plumbing", type: "SS", lagDays: 2 }] });
  r = await mk("Insulation", "2026-11-17", { end: "2026-11-23", predecessors: [{ phase: "Rough electrical", lagDays: -1 }] });
  r = await mk("Drywall", "2026-11-24", { durationDays: 5, predecessors: [{ phase: "Insulation" }, { phase: "Rough plumbing" }] });
  assert.strictEqual(r.end, "Mon 2026-11-30");
  r = await call("set_baseline", { project: hansen }); assert.strictEqual(r.baselineSet.length, 5);
  const chainDates = async () => Object.fromEntries((await call("list_schedule", { project: hansen })).phases.map((x) => [x.name, `${x.start} to ${x.end}`]));
  const unshifted = await chainDates();

  // a dry run says what would move and saves nothing
  r = await call("shift_phase", { project: hansen, phase: "Framing", days: 3, dryRun: true });
  assert.strictEqual(r.dryRun, true); assert.strictEqual(r.saved, false); assert.strictEqual(r.phasesMoved, 5);
  assert.deepStrictEqual(r.phases.map((x) => [x.name, x.to, x.shiftDays]), [
    ["Framing", "Thu 2026-11-05 to Wed 2026-11-11", 3],
    ["Rough plumbing", "Thu 2026-11-12 to Wed 2026-11-18", 3],      // finish-to-start: the working day after Framing ends
    ["Rough electrical", "Mon 2026-11-16 to Fri 2026-11-20", 3],    // start-to-start + 2
    ["Insulation", "Fri 2026-11-20 to Thu 2026-11-26", 3],          // lag -1: still starts on electrical's last day
    ["Drywall", "Fri 2026-11-27 to Thu 2026-12-03", 3],             // after the later of its two predecessors
  ]);
  assert.strictEqual(r.phases[0].from, "Mon 2026-11-02 to Fri 2026-11-06"); assert.strictEqual(r.phases[0].follower, undefined); assert.strictEqual(r.phases[1].follower, true);
  assert.deepStrictEqual(await chainDates(), unshifted, "a dry run saves nothing");

  // across a holiday: with Thanksgiving and the Friday after it off, Drywall's five days are Nov 24, 25, 30, Dec 1, 2
  r = await call("update_project", { project: hansen, scheduleHolidays: ["2026-11-26", "2026-11-27"] });
  r = await call("update_phase", { project: hansen, phase: "Drywall", end: "2026-12-02" }); assert.strictEqual(r.workingDays, 5); assert.strictEqual(r.alsoMoved, undefined);
  r = await call("set_baseline", { project: hansen, phases: ["Drywall"], overwrite: true });
  const planned = await chainDates();
  r = await call("shift_phase", { project: hansen, phase: "Framing", days: 3 });
  assert.strictEqual(r.shifted, true); assert.strictEqual(r.phasesMoved, 5);
  assert.deepStrictEqual(r.phases.slice(3).map((x) => [x.name, x.to, x.shiftDays]), [
    ["Insulation", "Fri 2026-11-20 to Mon 2026-11-30", 3],          // Fri 20, Mon 23, Tue 24, Wed 25, then Mon 30
    ["Drywall", "Tue 2026-12-01 to Mon 2026-12-07", 3],             // three working days on from Nov 24 is Dec 1
  ]);
  assert.deepStrictEqual(r.totals.finish, { planned: "Wed 2026-12-02", current: "Mon 2026-12-07", slipDays: 3 }, "the baseline did not move, so the slip shows");
  r = await call("get_phase", { project: hansen, phase: "Drywall" });
  assert.strictEqual(r.start, "Tue 2026-12-01"); assert.strictEqual(r.end, "Mon 2026-12-07"); assert.strictEqual(r.workingDays, 5, "its length in working days is kept");
  assert.strictEqual(r.baselineStart, "Tue 2026-11-24"); assert.strictEqual(r.baselineEnd, "Wed 2026-12-02"); assert.strictEqual(r.slipDays, 3);
  // shifting back by the same amount puts everything back
  r = await call("shift_phase", { project: hansen, phase: "Framing", days: -3 }); assert.deepStrictEqual(await chainDates(), planned);

  // update_phase: nothing follows unless cascade is asked for; with cascade + dryRun it shows what would
  r = await call("update_phase", { project: hansen, phase: "Rough plumbing", end: "2026-11-25", dryRun: true });
  assert.strictEqual(r.dryRun, true); assert.deepStrictEqual(r.wouldUpdate, ["end"]); assert.deepStrictEqual(r.phases.map((x) => [x.name, x.shiftDays, x.endShiftDays]), [["Rough plumbing", 0, 8]]);
  r = await call("update_phase", { project: hansen, phase: "Rough plumbing", end: "2026-11-25", cascade: true, dryRun: true });
  assert.deepStrictEqual(r.phases.map((x) => [x.name, x.to, x.shiftDays]), [
    ["Rough plumbing", "Mon 2026-11-09 to Wed 2026-11-25", 0],
    ["Drywall", "Mon 2026-11-30 to Fri 2026-12-04", 2],             // electrical waits on plumbing's START, so only Drywall follows
  ]);
  assert.deepStrictEqual(await chainDates(), planned, "still nothing saved");
  r = await call("update_phase", { project: hansen, phase: "Rough plumbing", end: "2026-11-25", cascade: true });
  assert.deepStrictEqual(r.updated, ["end"]); assert.strictEqual(r.end, "Wed 2026-11-25"); assert.deepStrictEqual(r.alsoMoved.phases.map((x) => [x.name, x.shiftDays]), [["Drywall", 2]]);
  r = await call("get_phase", { project: hansen, phase: "Drywall" }); assert.strictEqual(r.start, "Mon 2026-11-30"); assert.strictEqual(r.baselineStart, "Tue 2026-11-24");
  r = await call("update_phase", { project: hansen, phase: "Rough plumbing", end: "2026-11-13", cascade: true }); assert.deepStrictEqual(await chainDates(), planned, "and back again");

  // linked appointments move with their phase - not repeating ones, not ones already past - and task dates only when asked.
  // "Today" is pinned to Mon Nov 9, 2026 for this part so the result does not depend on the day the test runs.
  const realNow = Date.now;
  Date.now = () => parseDate("2026-11-09T08:00", tz);
  try {
    r = await call("create_appointment", { project: hansen, title: "Rough-in inspection", start: "2026-11-13T13:30" }); const inspectionId = r.id;
    r = await call("create_appointment", { project: hansen, title: "Plumber weekly", start: "2026-11-10T09:00", recurrence: "Weekly" });
    r = await call("create_appointment", { project: hansen, title: "Pipe delivery", start: "2026-11-03T07:00" });
    r = await call("create_task", { project: hansen, title: "Order fixtures", dueDate: "2026-11-13" }); const fixtures = r.id;
    r = await call("create_task", { project: hansen, title: "Permit", status: "Done", dueDate: "2026-11-06" });
    r = await call("link_to_phase", { project: hansen, phase: "Rough plumbing", tasks: ["Order fixtures", "Permit"], appointments: ["Rough-in inspection", "Plumber weekly", "Pipe delivery"] });
    assert.strictEqual(r.linkedTasks, 2); assert.strictEqual(r.linkedAppointments, 3);
    r = await call("shift_phase", { project: hansen, phase: "Rough plumbing", days: 3, cascade: false, dryRun: true });
    assert.deepStrictEqual(r.appointmentsMoved.map((x) => [x.title, x.from, x.to]), [["Rough-in inspection", "Fri 2026-11-13 1:30 PM", "Wed 2026-11-18 1:30 PM"]]);
    assert.strictEqual(r.tasksMoved, undefined, "task dates stay unless asked");
    r = await call("shift_phase", { project: hansen, phase: "Rough plumbing", days: 3, cascade: false, moveAppointments: false, dryRun: true }); assert.strictEqual(r.appointmentsMoved, undefined);
    r = await call("shift_phase", { project: hansen, phase: "Rough plumbing", days: 3, cascade: false, moveTasks: true });
    assert.deepStrictEqual(r.phases.map((x) => x.name), ["Rough plumbing"], "cascade: false moves only the phase itself");
    assert.deepStrictEqual(r.tasksMoved.map((x) => [x.title, x.dueDate]), [["Order fixtures", "Wed 2026-11-18"]], "not the card that is Done");
    r = await call("list_appointments", { project: hansen, afterDate: "2026-11-01", beforeDate: "2026-11-30" });
    assert.strictEqual(r.appointments.find((x) => x.id === inspectionId).start, "Wed 2026-11-18 1:30 PM"); assert.strictEqual(r.appointments.find((x) => x.id === inspectionId).end, "Wed 2026-11-18 2:30 PM");
    assert.strictEqual(r.appointments.find((x) => x.title === "Pipe delivery").start, "Tue 2026-11-03 7:00 AM", "the one already past is left alone");
    assert.strictEqual(r.appointments.find((x) => x.title === "Plumber weekly").start, "Tue 2026-11-10 9:00 AM", "so is the repeating one");
    r = await call("get_task", { task: fixtures }); assert.strictEqual(r.dueDate, "Wed 2026-11-18"); assert.strictEqual(r.phase, "Rough plumbing");
    r = await call("shift_phase", { project: hansen, phase: "Rough plumbing", days: -3, cascade: false, moveAppointments: false });
    assert.deepStrictEqual(await chainDates(), planned);
  } finally {
    Date.now = realNow;
  }

  // a loop in the predecessors (which the tools refuse to create, so it is written straight into the store here) is refused by name
  const hansenId = (await call("get_project", { project: hansen })).id;
  const hansenPhase = (name) => [...db._store].find(([k, v]) => k.startsWith(`projects/${hansenId}/schedulePhases/`) && v.name === name);
  db._store.set(hansenPhase("Framing")[0], { ...hansenPhase("Framing")[1], predecessors: [{ phaseId: hansenPhase("Drywall")[1].id, type: "FS", lagDays: 0 }] });
  r = await call("shift_phase", { project: hansen, phase: "Framing", days: 3, dryRun: true });
  assert(r.error && /go round in a loop: Framing -> Rough plumbing -> Rough electrical -> Insulation -> Drywall -> Framing/.test(r.error), r.error);
  r = await call("shift_phase", { project: hansen, phase: "Framing", days: 3 }); assert(r.error && /Nothing was saved/.test(r.error)); assert.deepStrictEqual(await chainDates(), planned);
  r = await call("update_phase", { project: hansen, phase: "Framing", start: "2026-11-05", cascade: true }); assert(r.error && /loop/.test(r.error), r.error);
  r = await call("shift_phase", { project: hansen, phase: "Framing", days: 3, cascade: false, dryRun: true }); assert.strictEqual(r.phasesMoved, 1, "with cascade off the phase itself can still be moved");
  // other refusals
  r = await call("shift_phase", { project: hansen, phase: "Framing", days: 0 }); assert(r.error && /other than 0/.test(r.error));
  r = await call("create_phase", { project: hansen, name: "Punch list" });
  r = await call("shift_phase", { project: hansen, phase: "Punch list", days: 2 }); assert(r.error && /no dates to shift/.test(r.error));
  console.log("schedule shifting ok");

  // ---- look-ahead: the same scenario as the app's ScheduleLookAheadTest.kt, with "today" pinned to Wed Nov 18, 2026 ----
  {
    const realNow = Date.now;
    Date.now = () => parseDate("2026-11-18T08:00", tz);
    try {
      const la = "Lookahead Test";
      r = await call("create_project", { name: la }); assert.strictEqual(r.created, true);
      const ph = (name, start, end, extra) => call("create_phase", { project: la, name, start, end, ...extra });
      await ph("Roof complete", "2026-09-21", "2026-12-11", { status: "In progress", trade: "Roofing" });
      await ph("Siding", "2026-09-21", "2026-11-15", { status: "In progress" });
      await ph("Septic & grading", "2026-10-15", "2026-11-15", { status: "On hold" });
      await ph("Remaining concrete", "2026-11-01", "2026-11-15");
      await ph("Electrical service", "2026-10-05", "2026-10-09", { status: "Done" });
      await ph("Mechanical & plumbing", "2026-10-15", "2026-11-19", { status: "In progress", trade: "Mechanical" });
      await ph("Electrical rough-in", "2026-10-29", "2026-11-19", { trade: " electrical " });
      await ph("Insulation", "2026-11-19", "2026-12-04");
      await ph("Drywall", "2026-12-04", "2027-01-04");
      await call("create_task", { project: la, title: "Septic permit", status: "In Progress", dueDate: "2026-11-13" });
      await call("create_task", { project: la, title: "Flashing at chimney", dueDate: "2026-11-20" });
      await call("create_task", { project: la, title: "Order insulation", status: "Done", dueDate: "2026-11-19" });
      await call("create_appointment", { project: la, title: "Weekly Meeting", start: "2026-10-13T09:00", recurrence: "Weekly" });
      await call("create_appointment", { project: la, title: "Roofer on site - ridge and flashing", start: "2026-11-20T07:30", end: "2026-11-20T15:00" });
      await call("create_appointment", { project: la, title: "Insulation delivery", start: "2026-11-19", isAllDay: true });
      await call("create_appointment", { project: la, title: "Walk-through", start: "2026-11-10T09:00" });
      await call("link_to_phase", { project: la, phase: "Roof complete", tasks: ["Flashing at chimney"], appointments: ["Weekly Meeting", "Roofer on site - ridge and flashing"] });

      const names = (groups) => groups.map((g) => [g.day || g.trade || g.heading, g.items.map((i) => (i.type === "phase" ? `${i.name} ${i.what || "running"}` : `${i.type === "task" ? "due" : "visit"} ${i.name || i.title}`))]);
      r = await call("look_ahead", { project: la, weeks: 1 });
      assert.deepStrictEqual(r.window, { from: "Wed 2026-11-18", to: "Sun 2026-11-22", weeks: 1 });
      assert.deepStrictEqual(r.behind.phases.map((b) => [b.name, b.reason, b.since]), [
        ["Electrical rough-in", "should have started", "Thu 2026-10-29"],
        ["Remaining concrete", "should have started", "Sun 2026-11-01"],
        ["Septic & grading", "should have finished", "Sun 2026-11-15"],   // on hold, past its end
        ["Siding", "should have finished", "Sun 2026-11-15"],             // in progress, past its end
      ]);
      assert.deepStrictEqual(r.behind.overdueTasks.map((t) => [t.title, t.due]), [["Septic permit", "Fri 2026-11-13"]]);
      assert.deepStrictEqual(r.counts, { phases: 4, appointments: 2, tasks: 1 }, "the Done card and the past walk-through are left out");
      assert.strictEqual(r.groupBy, "day");
      assert.deepStrictEqual(names(r.groups), [
        ["Under way", ["Roof complete under way"]],
        ["Thu 2026-11-19", ["visit Insulation delivery", "Insulation starts", "Electrical rough-in finishes", "Mechanical & plumbing finishes"]],
        ["Fri 2026-11-20", ["visit Roofer on site - ridge and flashing", "due Flashing at chimney"]],
      ]);
      const roofer = r.groups[2].items[0];
      assert.deepStrictEqual([roofer.day, roofer.time, roofer.phase], ["Fri 2026-11-20", "7:30 AM", "Roof complete"]);
      assert.strictEqual(r.groups[1].items[0].time, "all day");
      assert.strictEqual(r.groups[1].items[2].late, true);

      r = await call("look_ahead", { project: la, groupBy: "trade" });
      assert.deepStrictEqual(r.window, { from: "Wed 2026-11-18", to: "Sun 2026-11-29", weeks: 2 }, "two weeks by default");
      assert.deepStrictEqual(names(r.groups), [
        ["electrical", ["Electrical rough-in running"]],
        ["Mechanical", ["Mechanical & plumbing running"]],
        ["Roofing", ["Roof complete running", "visit Roofer on site - ridge and flashing", "due Flashing at chimney", "visit Weekly Meeting"]],
        ["No trade", ["visit Insulation delivery", "Insulation running"]],
      ]);
      assert.strictEqual(r.groups[2].items[3].day, "Tue 2026-11-24", "the Tuesday meeting comes round");

      r = await call("look_ahead", { project: la, weeks: 3 });
      assert.strictEqual(r.window.to, "Sun 2026-12-06");
      const byDay = Object.fromEntries(names(r.groups));
      assert.deepStrictEqual(byDay["Fri 2026-12-04"], ["Drywall starts", "Insulation finishes"], "a phase starting and finishing inside the window is listed on both days");
      assert.deepStrictEqual(byDay["Tue 2026-12-01"], ["visit Weekly Meeting"]);

      r = await call("look_ahead", { project: "pT" });
      assert.deepStrictEqual([r.counts, r.groups, r.behind.phases], [{ phases: 0, appointments: 0, tasks: 0 }, [], []], "a project with nothing coming up");
    } finally {
      Date.now = realNow;
    }
  }
  console.log("look-ahead ok");

  // ---- weekly report: the week of Mon Nov 9 - Sun Nov 15, 2026 ----
  {
    const wr = require("../weeklyReport.js");
    // who did it: real lines from the Martin log, against the workspace's subcontractor contacts
    const contacts = [
      { type: "Subcontractor", name: "Andy", company: "D & W Mechanical" },
      { type: "Subcontractor", name: "Rich / Karla Arntson", company: "A+ Concrete" },
      { type: "Subcontractor", name: "Jared Pratt", company: "NACE" },          // the crew's own entry: never a sub
      { type: "Subcontractor", name: "Adam Aldridge", company: "" },
    ];
    const who = wr.makeWho(contacts, "NACE");
    const whoOf = (line) => { const w = who(wr.splitLabel(line).label); return w.crew ? (w.explicit ? "crew (named)" : "crew") : w.name; };
    assert.deepStrictEqual([
      "NACE crew (14): Dan (Site Supervisor), Michael, Jeff, Alex",
      "D&W - Plumbing & HVAC",
      "A+ Concrete",
      "Alex, Miguel, Miguel - roof plywood and weathering in",
      "Ian, Owen, Adam and Adam's father - screened-in porch deck boards",
      "D&W Plumbing, Heating & Cooling - mechanical crew",
      "A Plus - pouring the patio",
      "A+ Concrete (critical path) - mobilized and set perimeter forms",
      "MI Metal Roofers - measuring",
    ].map(whoOf), ["crew (named)", "D & W Mechanical", "A+ Concrete", "crew", "crew", "D & W Mechanical", "A+ Concrete", "A+ Concrete", "MI Metal Roofers"]);
    assert.deepStrictEqual([
      "NACE crew (14): Dan, Michael", "Alex, Miguel, Miguel - roof", "Ian, Owen, Adam and Adam's father - porch", "JR + 2 helpers - roofing", "NACE (5)", "NACE crew",
    ].map((l) => wr.headcount(l, "NACE")), [14, 3, 4, 3, 5, null]);
    assert.strictEqual(wr.weekStartOf("2026-11-15"), "2026-11-09", "Sunday belongs to the week that started Monday");
    assert.strictEqual(wr.weekStartOf("2026-11-16"), "2026-11-16");
    assert.strictEqual(wr.weekRange("2026-11-30"), "Nov 30 - Dec 6, 2026");

    const W = "Weekly Test";
    r = await call("create_project", { name: W }); const wId = r.id;
    await call("create_contact", { name: "Andy", company: "D & W Mechanical", type: "Subcontractor" });
    await call("create_contact", { name: "Jared Pratt", company: "NACE", type: "Subcontractor" });
    const putLog = (date, data) => db.collection("projects").doc(wId).collection("dailyLogs").doc(date).set({ createdBy: "dan@example.com", updatedAt: 1, ...data });
    const photo = (n) => ({ id: "ph" + n, name: `p${n}.jpg`, url: `https://example.com/p${n}.jpg` });
    await putLog("2026-11-09", {
      crewOnSite: ["NACE crew (6): Dan, Jeff, Alex, Ian, Owen, Jake", "D&W - plumbing rough-in"],
      workCompleted: ["NACE - framed the lanai walls", "D&W - started plumbing rough-in"],
      issuesDelays: ["Waiting on meter can from Consumers"],
      weatherReadings: [{ slot: "morning", at: 1, tempF: 34, conditions: "Light Rain", windMph: 5, precip: true }, { slot: "midday", at: 2, tempF: 41, conditions: "Overcast", windMph: 8, precip: false }],
      photos: [photo(1), photo(2)],
    });
    // Tuesday Nov 10: no log at all
    await putLog("2026-11-11", {
      crewOnSite: ["Jeff, Ian and Owen - lanai", "A+ Concrete - forms"],
      workCompleted: ["NACE - Framed the lanai walls.", "A Plus - set the patio forms"],
      issuesDelays: ["Waiting on meter can from Consumers"], deliveriesInspections: ["Drywall delivered"],
      notes: "Weekly meeting with the owner.", photos: [photo(3)],
    });
    await putLog("2026-11-12", {
      crewOnSite: ["D&W Plumbing, Heating & Cooling - mechanical crew", "MI Metal Roofers - measuring"],
      workCompleted: ["Sealed window perimeters"], issuesDelays: ["waiting on meter can from consumers"],
    });
    await putLog("2026-11-13", {
      crewOnSite: ["NACE (5)"], workCompleted: ["NACE - roof sheathing"], issuesDelays: ["Roof leak at the chimney"],
      tomorrowPlan: ["NACE - continue roof"], photos: [photo(4), photo(5), photo(6), photo(7)],
    });
    await putLog("2026-11-14", { createdBy: "weather", weatherReadings: [{ slot: "morning", at: 3, tempF: 30, conditions: "Snow", windMph: 4, precip: true }] });
    await call("create_appointment", { project: W, title: "Insulation inspection", start: "2026-11-17T10:00" });
    await call("create_phase", { project: W, name: "Insulation", start: "2026-11-19", end: "2026-12-04" });

    r = await call("get_weekly_report", { project: W, date: "2026-11-12" }); assert.strictEqual(r.empty, true);
    r = await call("build_weekly_report", { project: W, date: "2026-11-12" });
    assert.strictEqual(r.built, true); assert.strictEqual(r.status, "Draft"); assert.strictEqual(r.weekStart, "2026-11-09");
    assert.deepStrictEqual(r.daysLogged, ["Mon Nov 9", "Wed Nov 11", "Thu Nov 12", "Fri Nov 13"], "Tuesday had no log; Saturday has weather only");
    assert.strictEqual(r.summary, "4 days logged, 6 crew at peak, D & W Mechanical, A Plus Concrete and MI Metal Roofers on site, 1 issue still open.");
    assert.deepStrictEqual(r.weather, ["Mon Nov 9: 34°-41°F, light rain, then overcast", "Sat Nov 14: 30°F, snow"]);
    assert.strictEqual(r.precipDays, 2);
    assert.deepStrictEqual(r.crew, [
      "Mon Nov 9: NACE 6; subs: D & W Mechanical",
      "Wed Nov 11: NACE 3; subs: A Plus Concrete",
      "Thu Nov 12: subs: D & W Mechanical, MI Metal Roofers",
      "Fri Nov 13: NACE 5",
    ]);
    assert.deepStrictEqual(r.subsOnSite, ["D & W Mechanical", "A Plus Concrete", "MI Metal Roofers"], "D&W on two days, listed once");
    assert.deepStrictEqual(r.workCompleted, [
      { who: "NACE", lines: ["framed the lanai walls (Mon, Wed)", "Sealed window perimeters (Thu)", "roof sheathing (Fri)"] },
      { who: "D & W Mechanical", lines: ["started plumbing rough-in (Mon)"] },
      { who: "A Plus Concrete", lines: ["set the patio forms (Wed)"] },
    ]);
    assert.deepStrictEqual(r.deliveriesInspections, ["Wed Nov 11 - Drywall delivered"]);
    assert.deepStrictEqual(r.issues, [
      { text: "Roof leak at the chimney", status: "open", since: "Fri Nov 13" },
      { text: "Waiting on meter can from Consumers", status: "cleared", since: "Mon Nov 9" },   // on three days, gone by Friday
    ]);
    assert.deepStrictEqual(r.nextWeek, ["NACE - continue roof", "Tue Nov 17, 10:00 AM - Insulation inspection", "Phase: Insulation, Thu Nov 19 to Fri Dec 4"]);
    assert.deepStrictEqual(r.photos.map((x) => [x.name, x.date]), [["p1.jpg", "2026-11-09"], ["p3.jpg", "2026-11-11"], ["p4.jpg", "2026-11-13"], ["p2.jpg", "2026-11-09"], ["p5.jpg", "2026-11-13"], ["p6.jpg", "2026-11-13"]], "spread across the days, six at most");
    assert.deepStrictEqual(r.notes, ["Wed Nov 11: Weekly meeting with the owner."]);
    assert(!("edited" in db._store.get(`projects/${wId}/weeklyReports/2026-11-09`)) || db._store.get(`projects/${wId}/weeklyReports/2026-11-09`).edited === false);

    // the PDF: built, filed under Weekly Reports, reused while current, rebuilt once the report changes
    r = await call("weekly_report_pdf", { project: W, date: "2026-11-09" });
    assert.strictEqual(r.rebuilt, true); assert(r.url.includes("weekly_reports")); assert(r.fileName.startsWith("Weekly Report - Weekly Test - 2026-11-09"));
    assert.strictEqual(r.emailSubject, "Weekly Report - Weekly Test - week of Nov 9 - 15, 2026"); assert(r.emailBody.includes("WORK COMPLETED") && r.emailBody.includes("PDF: "));
    const weeklyPdf = saved[`projects/${wId}/weekly_reports/2026-11-09.pdf`]; assert(weeklyPdf && weeklyPdf.slice(0, 4).toString() === "%PDF");
    require("fs").writeFileSync(require("path").join(__dirname, "last-weekly-report.pdf"), weeklyPdf);
    r = await call("list_files", { project: W, folder: "Weekly Reports" }); assert.strictEqual(r.count, 1);
    r = await call("weekly_report_pdf", { project: W, date: "2026-11-09" }); assert.strictEqual(r.rebuilt, false, "current PDF reused");

    // a person's wording sticks: editing marks the report, and a plain rebuild leaves it alone
    r = await call("set_weekly_report_section", { project: W, date: "2026-11-09", section: "summary", text: "Short week, lanai framed, roof under way." });
    assert.strictEqual(r.summary, "Short week, lanai framed, roof under way."); assert.strictEqual(r.edited, true); assert.strictEqual(r.pdfIsCurrent, false, "the PDF is now stale");
    r = await call("set_weekly_report_section", { project: W, date: "2026-11-09", section: "workCompleted", groups: [{ who: "NACE", lines: ["Lanai walls framed"] }, { who: "MI Metal Roofers", lines: ["Measured the roof"] }] });
    assert.deepStrictEqual(r.workCompleted.map((g) => g.who), ["NACE", "MI Metal Roofers"]);
    r = await call("set_weekly_report_section", { project: W, date: "2026-11-09", section: "issues", issues: [{ text: "Roof leak at the chimney", status: "cleared" }] });
    assert.deepStrictEqual(r.issues, [{ text: "Roof leak at the chimney", status: "cleared" }]);
    r = await call("set_weekly_report_section", { project: W, date: "2026-11-09", section: "notes", lines: [] }); assert.strictEqual(r.notes, undefined, "an empty list clears the section");
    r = await call("set_weekly_report_section", { project: W, date: "2026-11-09", section: "crew" }); assert(r.error && /Pass lines/.test(r.error), r.error);
    r = await call("set_weekly_report_section", { project: W, date: "2026-11-09", section: "status", text: "Done" }); assert(r.error && /Draft or Final/.test(r.error));
    r = await call("set_weekly_report_section", { project: W, date: "2026-11-02", section: "summary", text: "x" }); assert(r.error && /Build it first/.test(r.error));
    r = await call("build_weekly_report", { project: W, date: "2026-11-09" });
    assert.strictEqual(r.built, false); assert.strictEqual(r.reason, "edited"); assert.strictEqual(r.summary, "Short week, lanai framed, roof under way.");

    // another project's report, marked Final, that the Saturday run must not touch
    r = await call("create_project", { name: "Final Test" }); const fId = r.id;
    await db.collection("projects").doc(fId).collection("dailyLogs").doc("2026-11-10").set({ crewOnSite: ["NACE (3)"], workCompleted: ["NACE - trim"], updatedAt: 1 });
    r = await call("build_weekly_report", { project: "Final Test", date: "2026-11-10" }); assert.strictEqual(r.built, true);
    r = await call("set_weekly_report_section", { project: "Final Test", date: "2026-11-10", section: "status", text: "Final" });
    assert.strictEqual(r.status, "Final"); assert.strictEqual(r.edited, undefined, "marking Final isn't an edit");
    const finalBefore = JSON.stringify(db._store.get(`projects/${fId}/weeklyReports/2026-11-09`));
    await db.collection("projects").doc(fId).collection("dailyLogs").doc("2026-11-12").set({ workCompleted: ["NACE - more trim"], updatedAt: 2 });   // logged after it was finalised

    // Saturday Nov 14, 7 AM: the weekly run
    const run = await wr.runWeeklyReports(db, parseDate("2026-11-14T07:00", tz));
    assert.strictEqual(run.weekStart, "2026-11-09");
    assert(run.keptFinal.includes("Final Test"), JSON.stringify(run));
    assert(run.keptEdited.includes(W), JSON.stringify(run));
    assert(run.built.includes("Martin"), "Martin has a log on Nov 10, so it gets a Draft: " + JSON.stringify(run));
    assert(run.nothingLogged.includes("Hansen Garage"));
    assert.strictEqual(JSON.stringify(db._store.get(`projects/${fId}/weeklyReports/2026-11-09`)), finalBefore, "the Final report is exactly as it was");
    assert.strictEqual(db._store.get(`projects/${wId}/weeklyReports/2026-11-09`).summary, "Short week, lanai framed, roof under way.", "the edited report keeps its wording");
    r = await call("get_weekly_report", { project: "Martin", date: "2026-11-10" }); assert.strictEqual(r.status, "Draft"); assert.deepStrictEqual(r.daysLogged, ["Tue Nov 10"]);

    // asked to, a rebuild replaces the edits and goes back to Draft
    r = await call("build_weekly_report", { project: "Final Test", date: "2026-11-10", force: true });
    assert.strictEqual(r.built, true); assert.strictEqual(r.status, "Draft"); assert.deepStrictEqual(r.workCompleted, [{ who: "NACE", lines: ["trim (Tue)", "more trim (Thu)"] }]);
    r = await call("build_weekly_report", { project: W, date: "2026-11-09", force: true }); assert.strictEqual(r.edited, undefined); assert.strictEqual(r.summary.startsWith("4 days logged"), true);

    r = await call("list_weekly_reports", { project: W });
    assert.deepStrictEqual(r.weeks.map((x) => [x.weekStart, x.status, x.daysLogged, x.pdf]), [["2026-11-09", "Draft", 4, "stale"]]);
  }
  console.log("weekly report ok");

  r = await call("delete_task", { task: newId }); assert(/Deleted/.test(r));
  console.log("ALL TESTS PASSED");
}
main().catch((e) => { console.error(e); process.exit(1); });
