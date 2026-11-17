/**
 * Daily log / daily report for a project.
 *
 * Data lives at projects/{projectId}/dailyLogs/{YYYY-MM-DD} (one document per day - see
 * composeApp/.../data/model/DailyLog.kt for the field list, which this file mirrors).
 *
 * Two consumers share the logic here:
 *   - the `dailyReportPdf` HTTPS endpoint the app calls (bearer Firebase ID token) to lay the day
 *     out as a PDF, file it under the project's Files tab ("Daily Reports" folder) and record the
 *     link on the log;
 *   - the MCP connector tools in mcp.js (get/upsert/list a day, build its PDF), so Sierra and
 *     Claude write into exactly the same record the app shows.
 *
 * PDF layout is done with pdfkit, server-side, so Android, desktop, iOS and web all produce the
 * identical page. US Letter, one column, section headings with bulleted lines, photos appended
 * as a contact sheet on the following page(s).
 */

const PDFDocument = require("pdfkit");
const { onRequest } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { logger } = require("firebase-functions");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const crypto = require("crypto");

const SECTIONS = [
  ["crewOnSite", "Crew & Subs on Site"],
  ["workCompleted", "Work Completed"],
  ["deliveriesInspections", "Deliveries & Inspections"],
  ["issuesDelays", "Issues & Delays"],
  ["tomorrowPlan", "Plan for Tomorrow"],
];
const DAILY_REPORTS_FOLDER = "Daily Reports";
const IMAGE_EXT = /\.(jpe?g|png|webp|gif)$/i;
// NWS asks every caller to identify itself in the User-Agent (it has no API keys).
const NWS_USER_AGENT = "(Crewsync daily log, github.com/djransom1-cpu/crewsync)";

// ---------------------------------------------------------------------------------------------
// Dates / weather
// ---------------------------------------------------------------------------------------------

function isLogDate(s) { return typeof s === "string" && /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(Date.parse(s + "T00:00:00Z")); }

/** "YYYY-MM-DD" for right now in `tz`. */
function todayIn(tz) {
  const p = {};
  for (const x of new Intl.DateTimeFormat("en-US", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date())) p[x.type] = x.value;
  return `${p.year}-${p.month}-${p.day}`;
}

function longDate(date) {
  const d = new Date(date + "T12:00:00Z");
  return d.toLocaleDateString("en-US", { weekday: "long", year: "numeric", month: "long", day: "numeric", timeZone: "UTC" });
}

const SITE_TZ = "America/Detroit";
const NWS_HEADERS = { "User-Agent": NWS_USER_AGENT, Accept: "application/geo+json" };
const SLOT_LABEL = { morning: "7 AM", midday: "Noon", afternoon: "4 PM" };

async function getJson(url) { try { const r = await fetch(url, { headers: NWS_HEADERS }); return r.ok ? await r.json() : null; } catch (_) { return null; } }

/** lat/lon for a project, cached on the project doc as `siteGeo` so we geocode each address once. */
async function siteCoordinates(db, project) {
  if (!project.location) return null;
  if (project.siteGeo && project.siteGeo.address === project.location && typeof project.siteGeo.lat === "number") return project.siteGeo;
  const geo = await getJson(`https://us-central1-gen-lang-client-0438127279.cloudfunctions.net/geocode?address=${encodeURIComponent(project.location)}`);
  if (!geo?.lat) return null;
  const siteGeo = { lat: geo.lat, lon: geo.lon, address: project.location };
  try { await db.collection("projects").doc(project.id).update({ siteGeo }); } catch (_) { /* best effort */ }
  return siteGeo;
}

/**
 * What it's doing at the site right now, from the nearest NWS station's latest observation
 * (actual readings, not a forecast). Falls back to the hourly forecast's current period when the
 * station hasn't reported recently. Returns { tempF, conditions, windMph, precip } or null.
 */
async function currentObservation(lat, lon) {
  const points = await getJson(`https://api.weather.gov/points/${lat.toFixed(4)},${lon.toFixed(4)}`);
  const stationsUrl = points?.properties?.observationStations;
  if (stationsUrl) {
    const stations = await getJson(stationsUrl);
    for (const st of (stations?.features || []).slice(0, 3)) {
      const obs = await getJson(`${st.id}/observations/latest`);
      const o = obs?.properties;
      if (!o || o.temperature?.value == null) continue;
      const ageMin = (Date.now() - Date.parse(o.timestamp)) / 60000;
      if (ageMin > 150) continue; // stale station
      const tempF = Math.round(o.temperature.value * 9 / 5 + 32);
      const windMph = o.windSpeed?.value == null ? null : Math.round(o.windSpeed.value * 0.621371);
      const conditions = o.textDescription || "";
      const precip = /rain|snow|drizzle|shower|sleet|thunder|storm|hail|freezing/i.test(conditions) || (o.precipitationLastHour?.value || 0) > 0;
      return { tempF, conditions, windMph, precip, station: st.properties?.stationIdentifier || "" };
    }
  }
  const hourlyUrl = points?.properties?.forecastHourly;
  const hourly = hourlyUrl ? await getJson(hourlyUrl) : null;
  const h = hourly?.properties?.periods?.[0];
  if (!h) return null;
  return { tempF: h.temperature, conditions: h.shortForecast || "", windMph: parseInt(String(h.windSpeed || "").match(/\d+/)?.[0] || "", 10) || null, precip: /rain|snow|drizzle|shower|sleet|thunder|storm/i.test(h.shortForecast || ""), station: "forecast" };
}

/** One-line summary used by the PDF, the plain-text report and the connector. */
function readingSummary(r) {
  const parts = [];
  if (r.tempF != null) parts.push(`${r.tempF}°`);
  if (r.conditions) parts.push(String(r.conditions).toLowerCase());
  let out = parts.join(" ");
  if (r.windMph != null && r.windMph >= 10) out += `, ${r.windMph} mph wind`;
  return out;
}
function weatherLine(log) {
  const order = { morning: 0, midday: 1, afternoon: 2 };
  const auto = [...(log.weatherReadings || [])].sort((a, b) => (order[a.slot] ?? 9) - (order[b.slot] ?? 9)).map((r) => `${SLOT_LABEL[r.slot] || r.slot} ${readingSummary(r)}`).join("  ·  ");
  return [auto, (log.weather || "").trim()].filter(Boolean).join("  —  ");
}

/** Which of the three daily slots a site-local hour belongs to. */
function slotForHour(h) { return h < 10 ? "morning" : h < 14 ? "midday" : "afternoon"; }

/**
 * Records the current observation on today's log for one project, in the given slot (replacing
 * an earlier reading for that slot). Creates the day's log if needed - a weather-only log is the
 * record of a rain day or a weekend. Returns the reading or null.
 */
async function recordWeatherForProject(db, project, slot) {
  const geo = await siteCoordinates(db, project);
  if (!geo) return null;
  const obs = await currentObservation(geo.lat, geo.lon);
  if (!obs) return null;
  const date = todayIn(SITE_TZ);
  const reading = { slot, at: Date.now(), tempF: obs.tempF ?? null, conditions: obs.conditions || "", windMph: obs.windMph ?? null, precip: !!obs.precip };
  const ref = logRef(db, project.id, date);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const log = normalizeLog(project.id, date, snap.exists ? snap.data() : {});
    log.weatherReadings = log.weatherReadings.filter((r) => r.slot !== slot).concat([reading]);
    if (!log.createdBy) { log.createdBy = "weather"; log.createdAt = Date.now(); }
    // Weather alone doesn't bump updatedAt/updatedBy - that's for people's words - but a PDF built
    // before this reading is stale, so nudge pdfBuiltAt back when needed.
    if (log.pdfUrl && log.pdfBuiltAt >= log.updatedAt) log.updatedAt = Date.now();
    tx.set(ref, logWriteMap(log));
  });
  return reading;
}

/** Runs at 7 AM, noon and 4 PM site time: one reading per active project with an address. */
const recordSiteWeather = onSchedule({ schedule: "0 7,12,16 * * *", timeZone: SITE_TZ, timeoutSeconds: 300, memory: "256MiB" }, async () => {
  const db = getFirestore();
  const hour = +new Intl.DateTimeFormat("en-US", { timeZone: SITE_TZ, hour: "numeric", hourCycle: "h23" }).format(new Date());
  const slot = slotForHour(hour);
  const snap = await db.collection("projects").get();
  let done = 0, skipped = 0;
  for (const d of snap.docs) {
    const p = { ...d.data(), id: d.id };
    if (!p.location || p.archived) { skipped++; continue; }
    try { if (await recordWeatherForProject(db, p, slot)) done++; else skipped++; } catch (e) { logger.warn(`recordSiteWeather ${p.name}: ${e.message}`); skipped++; }
  }
  logger.info(`recordSiteWeather ${slot}: ${done} recorded, ${skipped} skipped`);
});

/** Kept for upsertLog: a first-touch reading when the day has none yet (e.g. a backdated or weekend entry). */
async function fetchWeather(address, db, project) {
  try {
    if (db && project?.id) {
      const hour = +new Intl.DateTimeFormat("en-US", { timeZone: SITE_TZ, hour: "numeric", hourCycle: "h23" }).format(new Date());
      const r = await recordWeatherForProject(db, project, slotForHour(hour));
      return r ? readingSummary(r) : null;
    }
  } catch (e) { logger.warn("dailyReport weather lookup failed", e); }
  return null;
}

// ---------------------------------------------------------------------------------------------
// Firestore access (Admin SDK - callers must have checked project membership already)
// ---------------------------------------------------------------------------------------------

function logRef(db, projectId, date) { return db.collection("projects").doc(projectId).collection("dailyLogs").doc(date); }

function normalizeLog(projectId, date, data) {
  const d = data || {};
  const arr = (k) => (Array.isArray(d[k]) ? d[k].filter((x) => typeof x === "string" && x.trim()).map((x) => x.trim()) : []);
  return {
    id: date, projectId, date,
    weather: d.weather || "",
    weatherReadings: Array.isArray(d.weatherReadings) ? d.weatherReadings : [],
    crewOnSite: arr("crewOnSite"), workCompleted: arr("workCompleted"), deliveriesInspections: arr("deliveriesInspections"),
    issuesDelays: arr("issuesDelays"), tomorrowPlan: arr("tomorrowPlan"),
    notes: d.notes || "",
    photos: Array.isArray(d.photos) ? d.photos : [],
    entries: Array.isArray(d.entries) ? d.entries : [],
    contributors: Array.isArray(d.contributors) ? d.contributors : [],
    createdBy: d.createdBy || "", createdAt: Number(d.createdAt) || 0,
    updatedBy: d.updatedBy || "", updatedAt: Number(d.updatedAt) || 0,
    pdfUrl: d.pdfUrl || "", pdfBuiltAt: Number(d.pdfBuiltAt) || 0,
  };
}

/** Same empty-list rule as the Kotlin side: never write an empty array. */
function logWriteMap(log) {
  const out = {};
  for (const [k, v] of Object.entries(log)) {
    if (v === undefined || v === null) continue;
    if (Array.isArray(v) && v.length === 0) continue;
    out[k] = v;
  }
  return out;
}

async function getLog(db, projectId, date) {
  const snap = await logRef(db, projectId, date).get();
  return snap.exists ? normalizeLog(projectId, date, snap.data()) : null;
}

async function listLogs(db, projectId, { from, to, limit = 60 } = {}) {
  const snap = await db.collection("projects").doc(projectId).collection("dailyLogs").get();
  let logs = snap.docs.map((d) => normalizeLog(projectId, d.id, d.data()));
  if (from) logs = logs.filter((l) => l.date >= from);
  if (to) logs = logs.filter((l) => l.date <= to);
  logs.sort((a, b) => b.date.localeCompare(a.date));
  return logs.slice(0, limit);
}

/**
 * Add to a day's log without wiping what's there. `add` holds section -> lines to append (deduped
 * against existing lines), `notes` is appended as a new paragraph, `replace` (optional) holds
 * section -> full new line lists when the caller really means to overwrite. The raw `text` of the
 * addition is kept in `entries` with who/when/source.
 */
async function upsertLog(db, { projectId, project, date, by, source = "claude", add = {}, replace = {}, notes, weather, rawText }) {
  const ref = logRef(db, projectId, date);
  const now = Date.now();
  const result = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const log = normalizeLog(projectId, date, snap.exists ? snap.data() : {});
    const changed = [];
    for (const [key] of SECTIONS) {
      if (Array.isArray(replace[key])) { log[key] = replace[key].map((s) => String(s).trim()).filter(Boolean); changed.push(key); }
      if (Array.isArray(add[key]) && add[key].length) {
        const have = new Set(log[key].map((s) => s.toLowerCase()));
        for (const raw of add[key]) { const s = String(raw).trim(); if (s && !have.has(s.toLowerCase())) { log[key].push(s); have.add(s.toLowerCase()); } }
        changed.push(key);
      }
    }
    if (typeof notes === "string" && notes.trim()) { log.notes = log.notes ? `${log.notes.trim()}\n\n${notes.trim()}` : notes.trim(); changed.push("notes"); }
    if (typeof weather === "string" && weather.trim()) { log.weather = weather.trim(); changed.push("weather"); }
    if (rawText && rawText.trim()) log.entries.push({ by, at: now, text: rawText.trim(), source });
    if (by && !log.contributors.includes(by)) log.contributors.push(by);
    if (!log.createdBy) { log.createdBy = by; log.createdAt = now; }
    log.updatedBy = by; log.updatedAt = now;
    tx.set(ref, logWriteMap(log));
    return { log, changed: [...new Set(changed)] };
  });
  // First touch of TODAY with no automatic reading yet (log started before the 7 AM run, or a
  // project added mid-day): take one now so the day isn't blank. Past days get no made-up weather.
  if (!result.log.weatherReadings.length && project?.location && date === todayIn(SITE_TZ)) {
    await fetchWeather(project.location, db, project);
    const fresh = await getLog(db, projectId, date);
    if (fresh) result.log.weatherReadings = fresh.weatherReadings;
  }
  return result;
}

// ---------------------------------------------------------------------------------------------
// PDF
// ---------------------------------------------------------------------------------------------

async function fetchBytes(url, maxBytes = 8 * 1024 * 1024) {
  try {
    const r = await fetch(url);
    if (!r.ok) return null;
    const buf = Buffer.from(await r.arrayBuffer());
    return buf.length <= maxBytes ? buf : null;
  } catch (_) { return null; }
}

/** Renders the day as a PDF and resolves with its bytes. `names` maps email -> display name. */
async function renderPdf({ project, log, names = {}, builtBy }) {
  const photos = log.photos.filter((p) => p && p.url && IMAGE_EXT.test(p.name || p.url));
  const images = await Promise.all(photos.slice(0, 12).map(async (p) => ({ ...p, bytes: await fetchBytes(p.url) })));

  return new Promise((resolve, reject) => {
    const doc = new PDFDocument({ size: "LETTER", bufferPages: true, margins: { top: 54, bottom: 54, left: 54, right: 54 }, info: { Title: `Daily Report - ${project.name} - ${log.date}`, Author: "Crewsync" } });
    const chunks = [];
    doc.on("data", (c) => chunks.push(c));
    doc.on("end", () => resolve(Buffer.concat(chunks)));
    doc.on("error", reject);

    const W = doc.page.width - doc.page.margins.left - doc.page.margins.right;
    const L = doc.page.margins.left;
    const ink = "#111111", muted = "#666666", rule = "#BBBBBB", band = "#1F3A5F";

    // Header band
    doc.rect(0, 0, doc.page.width, 78).fill(band);
    doc.fillColor("#FFFFFF").font("Helvetica-Bold").fontSize(20).text("DAILY REPORT", L, 22, { width: W * 0.6 });
    doc.font("Helvetica").fontSize(11).text(project.name || "", L, 48, { width: W * 0.6 });
    doc.font("Helvetica-Bold").fontSize(12).text(longDate(log.date), L + W * 0.55, 26, { width: W * 0.45, align: "right" });
    if (project.location) doc.font("Helvetica").fontSize(9).text(project.location, L + W * 0.45, 46, { width: W * 0.55, align: "right" });
    doc.fillColor(ink);
    doc.y = 96;

    // Meta line
    const meta = [];
    const wl = weatherLine(log);
    if (wl) meta.push(`Weather: ${wl}`);
    const by = (log.contributors.length ? log.contributors : [log.createdBy]).filter(Boolean).map((e) => names[e] || e);
    if (by.length) meta.push(`Prepared by: ${by.join(", ")}`);
    if (meta.length) { doc.font("Helvetica").fontSize(10).fillColor(muted); meta.forEach((m) => doc.text(m, L, doc.y, { width: W })); doc.fillColor(ink); doc.moveDown(0.4); }

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

    for (const [key, title] of SECTIONS) {
      heading(title);
      const lines = log[key];
      doc.font("Helvetica").fontSize(10.5);
      if (!lines.length) { doc.fillColor(muted).text("None", L + 14, doc.y, { width: W - 14 }); doc.fillColor(ink); continue; }
      for (const line of lines) {
        ensureRoom(16);
        const y = doc.y;
        doc.text("•", L + 4, y, { width: 10, lineBreak: false });
        doc.text(line, L + 16, y, { width: W - 16 });
        doc.moveDown(0.3);
      }
    }
    if (log.notes) { heading("Notes"); doc.font("Helvetica").fontSize(10.5).text(log.notes, L + 4, doc.y, { width: W - 4 }); }

    // Photos: contact sheet, 2 per row
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
        doc.font("Helvetica").fontSize(8).fillColor(muted).text(p.name || "", x, rowY + cellH - 14, { width: cellW, align: "center", lineBreak: false });
        doc.fillColor(ink);
        col = (col + 1) % 2;
        if (col === 0) rowY += cellH + 10;
      }
    }

    // Footer on every page (bufferPages lets us revisit them)
    const range = doc.bufferedPageRange();
    const stamp = new Date().toLocaleString("en-US", { timeZone: "America/Detroit" });
    for (let i = range.start; i < range.start + range.count; i++) {
      doc.switchToPage(i);
      const savedBottom = doc.page.margins.bottom;
      doc.page.margins.bottom = 0; // writing inside the margin must not trigger a new page
      doc.font("Helvetica").fontSize(8).fillColor(muted)
        .text(`${project.name}  ·  Daily Report  ·  ${log.date}  ·  generated ${stamp}${builtBy ? " by " + (names[builtBy] || builtBy) : ""}  ·  page ${i - range.start + 1} of ${range.count}`,
          L, doc.page.height - 36, { width: W, align: "center", lineBreak: false });
      doc.page.margins.bottom = savedBottom;
    }
    doc.end();
  });
}

function safeName(s) { return String(s || "").replace(/[\\/:*?"<>|]+/g, "-").replace(/\s+/g, " ").trim(); }

/**
 * Builds the PDF for one day, stores it, files it in the project's Files tab, records it on the
 * log. Returns { url, fileName }.
 */
async function buildAndFilePdf(db, { projectId, project, log, names, builtBy }) {
  const bytes = await renderPdf({ project, log, names, builtBy });
  const fileName = `${safeName(`Daily Report - ${project.name} - ${log.date}`)}.pdf`;
  const storagePath = `projects/${projectId}/daily_reports/${log.date}.pdf`;
  const bucket = module.exports.storageBucket();
  const token = crypto.randomUUID();
  await bucket.file(storagePath).save(bytes, {
    contentType: "application/pdf",
    metadata: { metadata: { firebaseStorageDownloadTokens: token }, cacheControl: "no-cache" },
  });
  const url = `https://firebasestorage.googleapis.com/v0/b/${bucket.name}/o/${encodeURIComponent(storagePath)}?alt=media&token=${token}`;

  // File it under Files → "Daily Reports" (create the folder once; replace the day's entry).
  const projRef = db.collection("projects").doc(projectId);
  const folders = await projRef.collection("folders").get();
  let folder = folders.docs.find((d) => (d.data().name || "").trim().toLowerCase() === DAILY_REPORTS_FOLDER.toLowerCase());
  if (!folder) {
    const fref = projRef.collection("folders").doc();
    await fref.set({ id: fref.id, projectId, name: DAILY_REPORTS_FOLDER, parentFolderId: null, color: "#1F3A5F", createdAt: Date.now() });
    folder = { id: fref.id };
  }
  const files = await projRef.collection("files").where("folderId", "==", folder.id).get();
  const existing = files.docs.find((d) => d.data().name === fileName);
  const fileDoc = { name: fileName, url, folderId: folder.id, uploadedBy: builtBy || "crewsync", uploadedAt: Date.now() };
  if (existing) await existing.ref.set({ ...fileDoc, id: existing.id }); else { const r = projRef.collection("files").doc(); await r.set({ ...fileDoc, id: r.id }); }

  await logRef(db, projectId, log.date).set({ pdfUrl: url, pdfBuiltAt: Date.now() }, { merge: true });
  return { url, fileName };
}

async function namesForEmails(db, emails) {
  const out = {};
  const uniq = [...new Set(emails.filter(Boolean).map((e) => e.toLowerCase()))];
  for (let i = 0; i < uniq.length; i += 30) {
    const snap = await db.collection("users").where("email", "in", uniq.slice(i, i + 30)).get();
    snap.forEach((d) => { const u = d.data(); if (u.email && u.name) out[u.email.toLowerCase()] = u.name; });
  }
  return out;
}

// ---------------------------------------------------------------------------------------------
// HTTPS endpoint for the app: POST { projectId, date } with a Firebase ID token.
// ---------------------------------------------------------------------------------------------

const dailyReportPdf = onRequest({ timeoutSeconds: 120, memory: "512MiB" }, async (req, res) => {
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
  const date = typeof body?.date === "string" ? body.date.trim() : "";
  if (!projectId || projectId.includes("/") || !isLogDate(date)) { res.status(400).json({ error: "Missing project or date." }); return; }

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

    const log = (await getLog(db, projectId, date)) || normalizeLog(projectId, date, {});
    const names = await namesForEmails(db, [...log.contributors, log.createdBy, email]);
    const { url, fileName } = await buildAndFilePdf(db, { projectId, project, log, names, builtBy: email });
    logger.info(`dailyReportPdf ${projectId} ${date} by ${email}`);
    res.status(200).json({ ok: true, url, fileName });
  } catch (error) {
    logger.error(`dailyReportPdf ${projectId} ${date} failed`, error);
    res.status(500).json({ error: "Something went wrong building the report. Try again." });
  }
});

// Overridable so the offline test suite can swap in a fake bucket.
module.exports = { storageBucket: () => getStorage().bucket(), dailyReportPdf, recordSiteWeather, recordWeatherForProject, weatherLine, readingSummary, SLOT_LABEL, SECTIONS, isLogDate, todayIn, getLog, listLogs, upsertLog, buildAndFilePdf, namesForEmails, normalizeLog, renderPdf, fetchWeather };
