const $ = (id) => document.getElementById(id);
const status = (msg, ok) => { $("status").textContent = msg; $("status").className = ok === undefined ? "" : ok ? "ok" : "bad"; };

function normalisedApi() {
  const raw = $("api").value.trim().replace(/\/+$/, "");
  let url;
  try { url = new URL(raw); } catch (_) { throw new Error("That isn't a valid URL."); }
  const local = url.hostname === "localhost" || url.hostname === "127.0.0.1";
  if (url.protocol !== "https:" && !(local && url.protocol === "http:")) throw new Error("Use https:// (plain http is only allowed for localhost).");
  return { raw, origin: url.origin, local };
}

function emailFields() {
  return { emailAlerts: $("emailAlerts").checked, alertEmail: $("alertEmail").value.trim(), emailjsServiceId: $("ejsService").value.trim(),
    emailjsTemplateId: $("ejsTemplate").value.trim(), emailjsPublicKey: $("ejsKey").value.trim() };
}

async function load() {
  const s = await GOT_SETTINGS.getSettings();
  $("api").value = s.apiBaseUrl; $("key").value = s.apiKey; $("mode").value = s.sendMode; $("links").checked = !!s.trackLinks; $("notify").checked = s.notifyOpens !== false;
  $("emailAlerts").checked = s.emailAlerts !== false; $("alertEmail").value = s.alertEmail || ""; $("ejsService").value = s.emailjsServiceId || ""; $("ejsTemplate").value = s.emailjsTemplateId || ""; $("ejsKey").value = s.emailjsPublicKey || "";
}

$("save").addEventListener("click", async () => {
  let target;
  try { target = normalisedApi(); } catch (e) { return status(e.message, false); }
  // Must be the first await so Chrome still sees the user's click.
  if (!target.local) {
    const granted = await chrome.permissions.request({ origins: [target.origin + "/*"] });
    if (!granted) return status("Permission to contact that server was not granted.", false);
  }
  await GOT_SETTINGS.saveSettings({ apiBaseUrl: target.raw, apiKey: $("key").value, sendMode: $("mode").value, trackLinks: $("links").checked, notifyOpens: $("notify").checked, ...emailFields() });
  status("Saved.", true);
});

$("test").addEventListener("click", async () => {
  let target;
  try { target = normalisedApi(); } catch (e) { return status(e.message, false); }
  status("Testing…");
  try {
    const health = await (await fetch(target.raw + "/api/health")).json();
    const stats = await fetch(target.raw + "/api/stats", { headers: { "X-Tracker-Key": $("key").value } });
    if (stats.status === 401) return status(`Server reachable (mode: ${health.mode}) but the API key was rejected.`, false);
    if (!stats.ok) return status(`Server reachable (mode: ${health.mode}) but /api/stats returned ${stats.status}. Is this the API service?`, false);
    status(`Connected. Service mode: ${health.mode}.`, true);
  } catch (e) {
    status("Could not reach the server: " + e.message + "\nIf it is a new address, press Save first to grant access.", false);
  }
});

$("testEmail").addEventListener("click", async () => {
  status("Sending test email…");
  try {
    const settings = { ...(await GOT_SETTINGS.getSettings()), ...emailFields() };
    await GOT_SETTINGS.sendEmailAlert(settings, {
      to_name: "there", from_name: "Gmail Open Tracker", title: "Test alert", subject: "Test email", recipient: "test@example.com",
      status: "Likely opened", confidence: "90", opened_at: new Date().toLocaleString(),
      message: "This is a test alert from Gmail Open Tracker. If you can read this, email alerts work."
    });
    status("Test email sent. Check the inbox (and spam) of the address your EmailJS template sends to.", true);
  } catch (e) { status("Could not send: " + e.message, false); }
});

load();
