const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { onRequest } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
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
