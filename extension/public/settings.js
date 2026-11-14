// Shared by the background worker (importScripts), the content script and the options page.
// Settings live in chrome.storage.local so nothing needs editing in source or rebuilding.
(function (root) {
  const DEFAULTS = {
    apiBaseUrl: "http://localhost:8080",   // the dashboard API (mode=api or all)
    apiKey: "dev-only-change-me",
    sendMode: "ask",                       // ask | separate | shared  (what to do with several recipients)
    trackLinks: true                       // rewrite links so clicks are tracked
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

  const api = { DEFAULTS, getSettings, saveSettings };
  root.GOT_SETTINGS = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
