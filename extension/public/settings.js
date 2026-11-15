// Shared by the background worker (importScripts), the content script and the options page.
// Settings live in chrome.storage.local so nothing needs editing in source or rebuilding.
(function (root) {
  const DEFAULTS = {
    apiBaseUrl: "http://localhost:8080",   // the dashboard API (mode=api or all)
    apiKey: "dev-only-change-me",
    sendMode: "ask",                       // ask | separate | shared  (what to do with several recipients)
    trackLinks: true,                      // rewrite links so clicks are tracked
    notifyOpens: true,                     // desktop alert when a mail is likely opened or a link is clicked
    emailAlerts: true,                     // also send an email (through EmailJS) on the first open or click
    alertEmail: "",                        // where that email goes (blank: whatever the EmailJS template is set to)
    emailjsServiceId: "",
    emailjsTemplateId: "",
    emailjsPublicKey: ""                   // EmailJS public keys are meant for browser use; nothing secret is stored here
  };

  function ensureAlive() {
    // After the extension is reloaded, tabs that were already open keep a dead copy of this script.
    if (typeof chrome === "undefined" || !chrome.storage || !chrome.storage.local) {
      throw new Error("The extension was reloaded or updated. Refresh this Gmail tab (F5) and try again; your draft is saved.");
    }
  }

  async function getSettings() {
    ensureAlive();
    const stored = await chrome.storage.local.get("settings");
    return { ...DEFAULTS, ...(stored.settings || {}) };
  }

  async function saveSettings(patch) {
    const next = { ...(await getSettings()), ...patch };
    ensureAlive();
    await chrome.storage.local.set({ settings: next });
    return next;
  }

  /** Send one email through EmailJS's REST API. Throws with EmailJS's own message on failure. */
  async function sendEmailAlert(settings, params) {
    if (!settings.emailjsServiceId || !settings.emailjsTemplateId || !settings.emailjsPublicKey) {
      throw new Error("EmailJS service ID, template ID and public key are not filled in (Settings).");
    }
    const template_params = { ...params };
    if (settings.alertEmail) { template_params.to_email = settings.alertEmail; template_params.reply_to = settings.alertEmail; }
    const response = await fetch("https://api.emailjs.com/api/v1.0/email/send", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        service_id: settings.emailjsServiceId.trim(),
        template_id: settings.emailjsTemplateId.trim(),
        user_id: settings.emailjsPublicKey.trim(),
        template_params
      })
    });
    if (!response.ok) {
      const detail = (await response.text().catch(() => "")).slice(0, 200);
      throw new Error(detail || `EmailJS returned ${response.status}`);
    }
  }

  const api = { DEFAULTS, getSettings, saveSettings, sendEmailAlert };
  root.GOT_SETTINGS = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
