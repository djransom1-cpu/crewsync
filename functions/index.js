const crypto = require("crypto");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { onRequest } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAuth } = require("firebase-admin/auth");
const { getStorage } = require("firebase-admin/storage");
const { logger } = require("firebase-functions");

initializeApp();

/**
 * Proxies the Census Bureau geocoder server-side. Census has the best US address
 * coverage (official TIGER/Line address ranges, works for rural roads Nominatim/OSM
 * doesn't have) but never sends CORS headers, so browsers can never call it directly.
 * Server-to-server calls aren't subject to CORS, so this function calls Census and
 * hands back just {lat, lon} with our own CORS header set.
 */
exports.geocode = onRequest(async (req, res) => {
  res.set("Access-Control-Allow-Origin", "*");

  const address = req.query.address;
  if (!address || typeof address !== "string") {
    res.status(400).json({ error: "Missing address query parameter" });
    return;
  }

  try {
    const url = new URL("https://geocoding.geo.census.gov/geocoder/locations/onelineaddress");
    url.searchParams.set("address", address);
    url.searchParams.set("benchmark", "Public_AR_Current");
    url.searchParams.set("format", "json");

    const censusResponse = await fetch(url);
    const data = await censusResponse.json();
    const match = data.result?.addressMatches?.[0];

    if (!match) {
      res.status(404).json({ error: "No match" });
      return;
    }

    res.status(200).json({ lat: match.coordinates.y, lon: match.coordinates.x });
  } catch (error) {
    logger.error("Geocode proxy failed", error);
    res.status(502).json({ error: "Geocode lookup failed" });
  }
});

/**
 * Public, tokenless-auth ICS feed for a project's appointments, so people who aren't Crewsync
 * users (subs, owners) can subscribe to a project's calendar from their own calendar app
 * (Google Calendar / Apple Calendar / Outlook). See Project.calendarShareEnabled /
 * calendarShareToken and ShareUtils.kt on the client. Deliberately the only endpoint in this
 * file with no Firebase Auth check - the token in the query string is the sole access control,
 * so every check below (share enabled, token match) has to hold before any appointment data
 * is returned.
 */
exports.calendarFeed = onRequest(async (req, res) => {
  const projectId = req.query.projectId;
  const token = req.query.token;

  if (!projectId || typeof projectId !== "string" || !token || typeof token !== "string") {
    res.status(400).send("Missing projectId or token");
    return;
  }

  try {
    const db = getFirestore();
    const projectSnap = await db.collection("projects").doc(projectId).get();
    const project = projectSnap.data();

    if (!project || !project.calendarShareEnabled || !isTokenMatch(token, project.calendarShareToken)) {
      res.status(403).send("Not found");
      return;
    }

    const appointmentsSnap = await db.collection("projects").doc(projectId).collection("appointments").get();
    const eventLines = appointmentsSnap.docs.flatMap((doc) => buildIcsEvent(doc.id, doc.data()));

    const ics = [
      "BEGIN:VCALENDAR",
      "VERSION:2.0",
      "PRODID:-//Crewsync//Calendar Feed//EN",
      "CALSCALE:GREGORIAN",
      `X-WR-CALNAME:${escapeIcsText(project.name || "Crewsync Project")}`,
      ...eventLines,
      "END:VCALENDAR",
    ].join("\r\n");

    // The token is the only auth on this endpoint - never let a CDN or browser cache a response
    // keyed on a URL that could later be revoked/regenerated (see "Regenerate link" client-side).
    res.set("Content-Type", "text/calendar; charset=utf-8");
    res.set("Cache-Control", "no-store");
    res.status(200).send(ics);
  } catch (error) {
    logger.error("Calendar feed failed", error);
    res.status(500).send("Calendar feed failed");
  }
});

// crypto.timingSafeEqual throws on mismatched buffer lengths rather than returning false, and
// requires both inputs the same length up front - the length check below happens before it, so
// this leaks the stored token's length on a mismatch, but not any of its content.
function isTokenMatch(provided, actual) {
  if (typeof actual !== "string" || actual.length === 0) return false;
  const providedBuf = Buffer.from(provided);
  const actualBuf = Buffer.from(actual);
  if (providedBuf.length !== actualBuf.length) return false;
  return crypto.timingSafeEqual(providedBuf, actualBuf);
}

function buildIcsEvent(id, appt) {
  const isAllDay = !!appt.isAllDay;
  const lines = ["BEGIN:VEVENT", `UID:${id}@crewsync.app`, `DTSTAMP:${formatIcsDateTimeUTC(Date.now())}`];

  if (isAllDay) {
    lines.push(`DTSTART;VALUE=DATE:${formatIcsDate(appt.startDate)}`);
    // ICS all-day DTEND is exclusive - a single-day event's end date has to be the day after
    // its start, or calendar apps render it as zero-length.
    const endDate = new Date(appt.endDate || appt.startDate);
    endDate.setUTCDate(endDate.getUTCDate() + 1);
    lines.push(`DTEND;VALUE=DATE:${formatIcsDate(endDate.getTime())}`);
  } else {
    lines.push(`DTSTART:${formatIcsDateTimeUTC(appt.startDate)}`);
    lines.push(`DTEND:${formatIcsDateTimeUTC(appt.endDate)}`);
  }

  lines.push(`SUMMARY:${escapeIcsText(appt.title || "")}`);
  if (appt.description) lines.push(`DESCRIPTION:${escapeIcsText(appt.description)}`);
  if (appt.location) lines.push(`LOCATION:${escapeIcsText(appt.location)}`);

  const rrule = recurrenceToRRule(appt.recurrence);
  if (rrule) lines.push(`RRULE:${rrule}`);

  lines.push("END:VEVENT");
  return lines;
}

function recurrenceToRRule(recurrence) {
  switch (recurrence) {
    case "Daily":
      return "FREQ=DAILY";
    case "Weekly":
      return "FREQ=WEEKLY";
    case "Monthly":
      return "FREQ=MONTHLY";
    default:
      return null;
  }
}

function formatIcsDateTimeUTC(timestampMs) {
  return new Date(timestampMs).toISOString().replace(/[-:]/g, "").split(".")[0] + "Z";
}

function formatIcsDate(timestampMs) {
  return new Date(timestampMs).toISOString().slice(0, 10).replace(/-/g, "");
}

function escapeIcsText(text) {
  return String(text)
    .replace(/\\/g, "\\\\")
    .replace(/;/g, "\\;")
    .replace(/,/g, "\\,")
    .replace(/\n/g, "\\n");
}

/**
 * Looks up the fcmToken for a set of user emails. Project membership is stored as emails
 * (see Project.members in the app), but user docs are keyed by uid, so this needs a query
 * rather than a direct doc lookup.
 */
async function tokensForEmails(db, emails) {
  const lowered = [...new Set(emails.map((e) => (e || "").toLowerCase()).filter(Boolean))];
  if (lowered.length === 0) return [];

  const tokens = [];
  // Firestore "in" queries are capped at 30 values - chunk defensively even though project
  // rosters are expected to be small.
  for (let i = 0; i < lowered.length; i += 30) {
    const chunk = lowered.slice(i, i + 30);
    const snap = await db.collection("users").where("email", "in", chunk).get();
    snap.forEach((doc) => {
      const token = doc.data().fcmToken;
      if (token) tokens.push(token);
    });
  }
  return tokens;
}

async function sendToTokens(tokens, data) {
  if (tokens.length === 0) return;
  // Data-only payload (no "notification" block) so CrewsyncMessagingService.onMessageReceived
  // fires consistently in every app state and builds the notification itself, instead of
  // Android's default OS-level notification display bypassing our custom styling/actions.
  const response = await getMessaging().sendEachForMulticast({
    tokens,
    data,
    android: { priority: "high" },
  });
  logger.info(`Sent ${response.successCount}/${tokens.length} pushes`, { data });
}

exports.onNewChatMessage = onDocumentCreated(
  "projects/{projectId}/messages/{messageId}",
  async (event) => {
    const message = event.data?.data();
    if (!message) return;

    const { projectId } = event.params;
    const db = getFirestore();

    const projectSnap = await db.collection("projects").doc(projectId).get();
    const project = projectSnap.data();
    if (!project) return;

    const senderEmail = (message.senderEmail || "").toLowerCase();
    const recipientEmails = (project.members || []).filter(
      (email) => (email || "").toLowerCase() !== senderEmail
    );

    const tokens = await tokensForEmails(db, recipientEmails);
    const senderName = senderEmail.split("@")[0] || "Someone";

    await sendToTokens(tokens, {
      type: "chat",
      projectId,
      title: senderName,
      body: message.text || "",
    });
  }
);

exports.onNewBroadcast = onDocumentCreated("broadcasts/{broadcastId}", async (event) => {
  const broadcast = event.data?.data();
  if (!broadcast) return;

  const db = getFirestore();
  // Broadcasts are project alerts sent to every registered user, matching the existing
  // client-side listener in App.kt (which also doesn't filter by project membership).
  const usersSnap = await db.collection("users").get();
  const tokens = [];
  usersSnap.forEach((doc) => {
    const token = doc.data().fcmToken;
    if (token) tokens.push(token);
  });

  await sendToTokens(tokens, {
    type: "alert",
    title: `SITE ALERT: ${broadcast.title || ""}`,
    body: broadcast.message || "",
  });
});

// MCP connector for AI assistants (Claude custom connector) - see README-mcp.md
/**
 * Permanently deletes a project and everything that belongs to it. A project isn't one bundle in
 * Firestore: its tasks and alerts live in top-level collections tagged with projectId, its calendar,
 * files, notes, folders and chat are subcollections that a plain document delete would leave behind,
 * and its photos/documents are in Storage. This clears all of it with the Admin SDK in one pass.
 *
 * POST {projectId} with "Authorization: Bearer <Firebase ID token>". Allowed for the project's
 * creator (teamLeaderId), an Admin of the project's workspace, or a SuperAdmin.
 */
exports.deleteProject = onRequest({ timeoutSeconds: 540, memory: "512MiB" }, async (req, res) => {
  res.set("Access-Control-Allow-Origin", "*");
  res.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
  res.set("Access-Control-Allow-Methods", "POST, OPTIONS");
  if (req.method === "OPTIONS") { res.status(204).send(""); return; }
  if (req.method !== "POST") { res.status(405).json({ error: "Use POST." }); return; }

  const bearer = (req.get("Authorization") || "").match(/^Bearer (.+)$/);
  if (!bearer) { res.status(401).json({ error: "You're not signed in." }); return; }
  let uid;
  try {
    uid = (await getAuth().verifyIdToken(bearer[1])).uid;
  } catch (_) {
    res.status(401).json({ error: "Your sign-in has expired. Sign in again and retry." });
    return;
  }

  let body = req.body;
  if (typeof body === "string") { try { body = JSON.parse(body); } catch (_) { body = {}; } }
  const projectId = typeof body?.projectId === "string" ? body.projectId.trim() : "";
  if (!projectId || projectId.includes("/")) { res.status(400).json({ error: "Missing project." }); return; }

  const db = getFirestore();
  const projectRef = db.collection("projects").doc(projectId);
  const snap = await projectRef.get();
  if (!snap.exists) { res.status(404).json({ error: "That project no longer exists." }); return; }
  const project = snap.data();

  const me = (await db.collection("users").doc(uid).get()).data() || {};
  const myEnvs = [...(me.environmentIds || []), me.activeEnvironmentId].filter(Boolean);
  const isCreator = !!project.teamLeaderId && project.teamLeaderId === uid;
  const isAdmin = me.role === "SuperAdmin" || (me.role === "Admin" && myEnvs.includes(project.environmentId));
  if (!isCreator && !isAdmin) {
    res.status(403).json({ error: "Only the person who made this project, or an admin, can delete it." });
    return;
  }

  try {
    const writer = db.bulkWriter();
    let tasks = 0;
    let alerts = 0;
    for (const doc of (await db.collection("tasks").where("projectId", "==", projectId).get()).docs) {
      writer.delete(doc.ref); tasks++;
    }
    for (const doc of (await db.collection("broadcasts").where("projectId", "==", projectId).get()).docs) {
      writer.delete(doc.ref); alerts++;
    }
    await writer.close();

    // The project document plus its appointments, files (and markups), notes (and actions), folders and messages
    await db.recursiveDelete(projectRef);

    // Photos and documents. A failure here is logged, not fatal: the project is already gone from the app.
    let storageProblem = false;
    try {
      const bucket = getStorage().bucket();
      for (const prefix of [`projects/${projectId}/`, `chat_attachments/${projectId}/`, `task_attachments/${projectId}/`]) {
        await bucket.deleteFiles({ prefix, force: true });
      }
    } catch (error) {
      storageProblem = true;
      logger.error(`deleteProject ${projectId}: storage cleanup failed`, error);
    }

    logger.info(`deleteProject ${projectId} "${project.name}" by ${uid}: ${tasks} tasks, ${alerts} alerts`);
    res.status(200).json({ ok: true, name: project.name || "", tasks, alerts, storageProblem });
  } catch (error) {
    logger.error(`deleteProject ${projectId} failed`, error);
    res.status(500).json({ error: "Something went wrong deleting the project. Part of it may already be gone; try again." });
  }
});

exports.crewsyncMcp = require("./mcp").crewsyncMcp;

// Daily log PDF for the app's Daily Log tab - see functions/dailyReport.js
exports.dailyReportPdf = require("./dailyReport").dailyReportPdf;
// 7 AM / noon / 4 PM site-time weather readings onto each project's daily log
exports.recordSiteWeather = require("./dailyReport").recordSiteWeather;

// Weekly report from the daily logs - see functions/weeklyReport.js. The app calls weeklyReport;
// buildWeeklyReports drafts each active project's report on Saturday at 7 AM Eastern.
exports.weeklyReport = require("./weeklyReport").weeklyReport;
exports.buildWeeklyReports = require("./weeklyReport").buildWeeklyReports;
