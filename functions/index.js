const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { logger } = require("firebase-functions");

initializeApp();

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
