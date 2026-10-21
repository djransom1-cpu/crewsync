// Local smoke test: in-memory Firestore fake + in-memory MCP client against buildServer().
const assert = require("assert");
const { Client } = require("@modelcontextprotocol/sdk/client/index.js");
const { InMemoryTransport } = require("@modelcontextprotocol/sdk/inMemory.js");
const { buildServer, resolveContext, expandAppointment, parseDate, fmtDateTime } = require("../mcp.js");

// ---- tiny Firestore fake -------------------------------------------------------------------
function makeDb() {
  const store = new Map(); // "path" -> data
  let n = 0;
  const docRef = (path) => ({
    id: path.split("/").pop(),
    path,
    async get() { const d = store.get(path); return { id: path.split("/").pop(), exists: !!d, data: () => (d ? JSON.parse(JSON.stringify(d)) : undefined) }; },
    async set(data) { store.set(path, JSON.parse(JSON.stringify(data))); },
    async update(patch) { const d = store.get(path); if (!d) throw new Error("no doc " + path); store.set(path, { ...d, ...JSON.parse(JSON.stringify(patch)) }); },
    async delete() { store.delete(path); },
    collection(name) { return colRef(path + "/" + name); },
  });
  const colRef = (path, filters = [], lim = Infinity) => ({
    doc(id) { return docRef(path + "/" + (id || "auto" + ++n)); },
    where(f, op, v) { assert.strictEqual(op, "=="); return colRef(path, [...filters, [f, v]], lim); },
    limit(k) { return colRef(path, filters, k); },
    async add(data) { const r = docRef(path + "/auto" + ++n); await r.set(data); return r; },
    async get() {
      const docs = [];
      for (const [p, d] of store) {
        if (!p.startsWith(path + "/") || p.slice(path.length + 1).includes("/")) continue;
        if (filters.every(([f, v]) => d[f] === v)) docs.push({ id: p.split("/").pop(), ref: docRef(p), exists: true, data: () => JSON.parse(JSON.stringify(d)) });
        if (docs.length >= lim) break;
      }
      return { docs, empty: docs.length === 0, size: docs.length, forEach: (cb) => docs.forEach(cb) };
    },
  });
  return {
    collection: (name) => colRef(name),
    batch() { const ops = []; return { update: (r, p) => ops.push(() => r.update(p)), delete: (r) => ops.push(() => r.delete()), async commit() { for (const o of ops) await o(); } }; },
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
  await db.collection("projects").doc("pM").set({ id: "", environmentId: "env1", name: "Martin ", buckets: ["Not Started", "In Progress", "TOP Priority", "Orders", "Completed"], members: ["dan@example.com"] });
  await db.collection("projects").doc("pT").set({ environmentId: "env1", name: "test", buckets: ["Not Started", "Done"] });
  await db.collection("projects").doc("pT2").set({ environmentId: "env1", name: "Test", buckets: ["Not Started", "Done"] });
  // Weekly meeting Tue Oct 13 2026 9:00 AM Detroit
  const { zonedToUtc } = require("../mcp.js");
  const mtgStart = zonedToUtc(2026, 10, 13, 9, 0, 0, tz);
  await db.collection("projects").doc("pM").collection("appointments").doc("a1").set({ title: "Weekly Meeting", startDate: mtgStart, endDate: mtgStart + 3600000, recurrence: "Weekly", isAllDay: false });
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
  r = await call("update_task", { task: newId, status: "Completed", addAssignees: ["mike@example.com"] }); assert.strictEqual(r.status, "Completed"); assert.strictEqual(r.assignees.length, 2);
  r = await call("list_tasks", { project: "Martin" }); assert.strictEqual(r.count, 1, "completed hidden");
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

  r = await call("delete_task", { task: newId }); assert(/Deleted/.test(r));
  console.log("ALL TESTS PASSED");
}
main().catch((e) => { console.error(e); process.exit(1); });
