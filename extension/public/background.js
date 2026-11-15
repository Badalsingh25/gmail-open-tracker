importScripts("settings.js", "lib.js");

const POLL_ALARM = "gmail-open-tracker-poll";
const RANKS_KEY = "lastStatusRanks";
const UNSEEN_KEY = "unseenOpens";
const RANK = { NO_ACTIVITY: 0, AUTOMATED_ONLY: 1, POSSIBLY_OPENED: 2, OPENED: 3, ENGAGED: 4 };
const GMAIL_SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";

// ---------- Tracker API ----------

async function apiFetch(path, options = {}) {
  const settings = await GOT_SETTINGS.getSettings();
  const base = settings.apiBaseUrl.replace(/\/+$/, "");
  const response = await fetch(base + path, {
    ...options,
    headers: { "Content-Type": "application/json", "X-Tracker-Key": settings.apiKey, ...(options.headers || {}) }
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const hint = response.status === 401 ? "API key rejected. Check Settings." : "";
    throw new Error(body.message || hint || `Tracker API returned ${response.status}`);
  }
  return body;
}

// ---------- Gmail API (only used to send separate per-recipient copies) ----------

function gmailConfigured() {
  const clientId = chrome.runtime.getManifest().oauth2?.client_id || "";
  return clientId !== "" && !clientId.startsWith("YOUR_CLIENT_ID");
}

async function gmailToken(interactive) {
  const result = await chrome.identity.getAuthToken({ interactive });
  const token = typeof result === "string" ? result : result?.token;
  if (!token) throw new Error("Google sign-in was cancelled or is not set up");
  return token;
}

async function gmailSend(raw, retry = true) {
  const token = await gmailToken(true);
  const response = await fetch(GMAIL_SEND_URL, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ raw })
  });
  if (response.status === 401 && retry) { // stale cached token
    await chrome.identity.removeCachedAuthToken({ token });
    return gmailSend(raw, false);
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error?.message || `Gmail returned ${response.status}`);
  }
}

// ---------- Messages from the content script ----------

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  const handlers = {
    REGISTER: async () => {
      if (!message.payload?.clientRequestId) throw new Error("Missing request ID. Please click Send again.");
      return { ok: true, data: await apiFetch("/api/messages", { method: "POST", body: JSON.stringify(message.payload) }) };
    },
    OWN_VIEW: async () => {
      await apiFetch("/api/own-views", { method: "POST", body: JSON.stringify({ token: message.token }) });
      return { ok: true };
    },
    GMAIL_STATUS: async () => ({ ok: true, configured: gmailConfigured() }),
    SEND_SEPARATE: async () => {
      if (!gmailConfigured()) throw new Error("Gmail sending is not set up");
      const results = [];
      const attachments = Array.isArray(message.attachments) ? message.attachments : [];
      for (const item of message.items || []) { // sequential: stay well inside Gmail's rate limits
        try {
          const mime = GOT.buildMime({ ...item, attachments });
          await gmailSend(GOT.base64Url(GOT.utf8(mime)));
          results.push({ to: item.to, ok: true });
        } catch (error) {
          results.push({ to: item.to, ok: false, error: error.message || String(error) });
        }
      }
      return { ok: true, results };
    }
  };
  const handler = handlers[message?.type];
  if (!handler) return;
  handler().then(sendResponse).catch(error => sendResponse({ ok: false, error: error.message || "Request failed" }));
  return true;
});

// ---------- Notifications: only when the evidence gets strong, never for scanner noise ----------

async function setBadge(count) {
  try {
    await chrome.action.setBadgeText({ text: count > 0 ? String(Math.min(count, 99)) : "" });
    if (count > 0) await chrome.action.setBadgeBackgroundColor({ color: "#16845b" });
  } catch (_) { /* badge is a nicety */ }
}

function whenText(iso) {
  if (!iso) return "";
  try { return new Date(iso).toLocaleString([], { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }); } catch (_) { return ""; }
}

async function checkForNewActivity() {
  try {
    const settings = await GOT_SETTINGS.getSettings();
    const messages = await apiFetch("/api/messages/recent");
    const stored = await chrome.storage.local.get([RANKS_KEY, UNSEEN_KEY]);
    const hasBaseline = Object.prototype.hasOwnProperty.call(stored, RANKS_KEY);
    const previous = stored[RANKS_KEY] || {};
    const next = {};
    let unseen = Number(stored[UNSEEN_KEY]) || 0;

    for (const message of messages) {
      for (const recipient of message.recipients) {
        const id = String(recipient.id);
        const rank = RANK[recipient.status] ?? 0;
        next[id] = rank;
        const before = previous[id] ?? 0;
        const crossed = (before < RANK.OPENED && rank >= RANK.OPENED) || (before < RANK.ENGAGED && rank === RANK.ENGAGED);
        if (!hasBaseline || !crossed) continue;
        unseen += 1;
        if (settings.emailAlerts !== false && settings.emailjsServiceId && settings.emailjsTemplateId && settings.emailjsPublicKey) {
          const clicked = rank === RANK.ENGAGED;
          const who = String(recipient.address || "").split(",")[0].trim() || "A recipient";
          const when = whenText(recipient.lastOpenAt || recipient.firstOpenAt) || new Date().toLocaleString();
          const subject = message.subject || "(No subject)";
          try {
            await GOT_SETTINGS.sendEmailAlert(settings, {
              to_name: "there", from_name: "Gmail Open Tracker",
              title: clicked ? "Link clicked" : "Email likely opened",
              subject, recipient: who, status: clicked ? "Clicked a link" : "Likely opened",
              confidence: String(recipient.score), opened_at: when,
              message: `${who} ${clicked ? "clicked a link in" : "likely opened"} "${subject}" at ${when}. Confidence ${recipient.score}/100. This is an estimate, not proof.`
            });
          } catch (_) { /* email is best effort; the desktop alert below still fires */ }
        }
        if (settings.notifyOpens !== false && chrome.notifications?.create) {
          const who = String(recipient.address || "").split(",")[0].trim() || "A recipient";
          const when = whenText(recipient.lastOpenAt || recipient.firstOpenAt);
          await chrome.notifications.create(`got-${id}-${rank}`, {
            type: "basic",
            iconUrl: "icon.png",
            title: rank === RANK.ENGAGED ? "Link clicked" : "Email likely opened",
            message: `${who} · ${message.subject || "(No subject)"}`,
            contextMessage: `${when ? when + " · " : ""}Confidence ${recipient.score}/100`
          });
        }
      }
    }
    await chrome.storage.local.set({ [RANKS_KEY]: next, [UNSEEN_KEY]: unseen }); // first run only sets the baseline
    await setBadge(unseen);
  } catch (_) {
    // Backend may be offline. Saved state is not advanced on failure.
  }
}

chrome.notifications?.onClicked?.addListener((id) => {
  if (!String(id).startsWith("got-")) return;
  chrome.tabs.create({ url: "https://mail.google.com/" });
  chrome.notifications.clear(id);
});

function schedulePolling() { chrome.alarms.create(POLL_ALARM, { periodInMinutes: 1 }); }
chrome.runtime.onInstalled.addListener(() => { schedulePolling(); checkForNewActivity(); });
chrome.runtime.onStartup.addListener(() => { schedulePolling(); checkForNewActivity(); });
chrome.alarms.onAlarm.addListener(alarm => { if (alarm.name === POLL_ALARM) checkForNewActivity(); });
