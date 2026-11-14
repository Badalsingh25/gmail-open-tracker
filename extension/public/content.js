(() => {
  const CHECKBOX_CLASS = "got-track-toggle";
  const installed = new WeakSet();
  const ask = (message) => chrome.runtime.sendMessage(message);

  function hash(text) { // small stable hash, so a retry with different recipients/links is a new registration
    let h = 5381;
    for (let i = 0; i < text.length; i++) h = ((h << 5) + h + text.charCodeAt(i)) >>> 0;
    return h.toString(36);
  }

  function addToggle(compose) {
    if (!compose || installed.has(compose) || compose.querySelector(`.${CHECKBOX_CLASS}`)) return;
    const sendButton = compose.querySelector('[data-tooltip^="Send"], [aria-label^="Send"]');
    if (!sendButton) return;
    // Prefer Gmail's toolbar; otherwise sit in the row that holds the Send button (works when the toolbar is collapsed).
    const toolbar = compose.querySelector('[role="toolbar"]') || compose.querySelector('[aria-label*="Formatting options"]')?.parentElement
      || sendButton.closest("td, tr, div[role=group]")?.parentElement || sendButton.parentElement?.parentElement;
    if (!toolbar) return;
    installed.add(compose);

    const label = document.createElement("label");
    label.className = CHECKBOX_CLASS;
    label.style.cssText = "display:inline-flex;align-items:center;gap:5px;margin:4px 8px;font:12px Arial,sans-serif;color:#444;cursor:pointer;";
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.setAttribute("aria-label", "Track opens and link clicks for this email");
    const text = document.createElement("span");
    text.textContent = "Track opens";
    label.append(checkbox, text);
    // Gmail hides the formatting toolbar until "Aa" is pressed, so a checkbox placed there is invisible.
    // Put it right beside Send, which is always on screen.
    const sendGroup = sendButton.parentElement;
    if (sendGroup && sendGroup.parentElement) sendGroup.parentElement.insertBefore(label, sendGroup.nextSibling);
    else toolbar.appendChild(label);

    let state = "IDLE"; // IDLE -> PREPARING -> READY_TO_SEND | DONE_SEPARATE. Errors return to IDLE for a safe retry.

    function readRecipients() {
      // Gmail marks recipient chips with email="" or data-hovercard-id="" depending on the layout; read both.
      const chips = [...compose.querySelectorAll("[email], [data-hovercard-id*='@']")]
        .map(n => (n.getAttribute("email") || n.getAttribute("data-hovercard-id") || "").trim()).filter(v => v && v.includes("@"));
      const input = compose.querySelector('input[name="to"], input[peoplekit-id], [name="to"]');
      const typed = input?.value?.split(/[;,]/).map(v => v.trim()) || [];
      const seen = new Map();
      for (const v of [...chips, ...typed]) if (v && v.includes("@") && !seen.has(v.toLowerCase())) seen.set(v.toLowerCase(), v);
      return [...seen.values()];
    }

    /** Separate copies are only safe for plain new messages; anything else falls back to one shared copy. */
    async function separateEligibility(body, subject) {
      const status = await ask({ type: "GMAIL_STATUS" });
      if (!status?.configured) return { ok: false, reason: "can't be split because Google sign-in isn't set up (see README)" };
      if (compose.querySelector('[aria-label^="Remove attachment"], [data-tooltip^="Remove attachment"], .aZo, .dL, [download_url]')) {
        return { ok: false, reason: "has attachments, which can't be split into separate copies" };
      }
      if (body.querySelector(`img:not([${GOT.PIXEL_ATTR}])`)) return { ok: false, reason: "contains inline images, which can't be split into separate copies" };
      if (body.querySelector(".gmail_quote") || /^\s*(re|fwd?|fw)\s*:/i.test(subject)) {
        return { ok: false, reason: "is a reply or forward, which would lose its conversation thread" };
      }
      return { ok: true };
    }

    async function prepareAndSend() {
      if (state === "PREPARING") return;
      state = "PREPARING";
      sendButton.setAttribute("aria-disabled", "true");
      text.textContent = "Preparing tracking…";

      try {
        const recipients = readRecipients();
        const subject = compose.querySelector('input[name="subjectbox"]')?.value?.trim() || "";
        if (!recipients.length) throw new Error("Could not read a valid recipient from the To field. Please check the recipient and retry.");
        const body = compose.querySelector('[contenteditable="true"][role="textbox"]') || compose.querySelector('[contenteditable="true"]');
        if (!body) throw new Error("Could not find the email body. Please reopen the compose window and retry.");

        const settings = await GOT_SETTINGS.getSettings();
        const links = settings.trackLinks ? GOT.collectLinks(body) : [];

        let separate = false;
        let note = "";
        if (recipients.length > 1 && settings.sendMode !== "shared") {
          const eligibility = await separateEligibility(body, subject);
          if (!eligibility.ok) note = `One shared copy will be sent: this message ${eligibility.reason}.`;
          else if (settings.sendMode === "separate") separate = true;
          else {
            separate = window.confirm(
              `${recipients.length} recipients.\n\nOK: send ${recipients.length} separate tracked copies, so you can see who opened (they won't see each other).\n` +
              `Cancel: send one shared copy (opens can't be attributed to a person).`);
          }
        }
        if (note && !window.confirm(`${note}\n\nContinue with tracking?`)) throw new Error("Cancelled. Uncheck Track opens to send without tracking.");

        if (!compose.dataset.gotRequestId) compose.dataset.gotRequestId = crypto.randomUUID();
        const mode = separate ? "PER_RECIPIENT" : "SHARED";
        // Same compose + same content => same id, so a timeout retry can't create duplicate records.
        const clientRequestId = `${compose.dataset.gotRequestId}-${hash(JSON.stringify([mode, recipients, links]))}`;
        const registration = await ask({ type: "REGISTER", payload: { subject, clientRequestId, mode, recipients, links } });
        if (!registration?.ok) throw new Error(registration?.error || "Could not create the tracking record. Confirm the backend is running.");
        const refs = registration.data.recipients;
        rememberSenderTokens(refs);

        if (separate) {
          await sendSeparately(body, subject, refs);
          return;
        }

        GOT.applyTracking(body, refs[0]);
        body.dispatchEvent(new InputEvent("input", { bubbles: true })); // let Gmail's editor notice the change
        state = "READY_TO_SEND";
        text.textContent = recipients.length > 1 ? `Tracking added (${recipients.length} recipients share one pixel) — sending…` : "Tracking added — sending…";
        sendButton.removeAttribute("aria-disabled");
        setTimeout(() => {
          sendButton.click();
          setTimeout(() => { state = "IDLE"; text.textContent = "Track opens"; }, 3000);
        }, 0);
      } catch (error) {
        state = "IDLE";
        sendButton.removeAttribute("aria-disabled");
        text.textContent = "Tracking failed — retry";
        alert(`Gmail Open Tracker: ${error.message}\n\nThe extension stopped this send attempt. You can retry tracking or uncheck Track opens to send normally.`);
      }
    }

    async function sendSeparately(body, subject, refs) {
      const alreadySent = new Set(JSON.parse(compose.dataset.gotSent || "[]"));
      const originalHtml = body.innerHTML; // never modified: each copy is rendered from this
      const plain = body.innerText || "";
      const items = refs.filter(r => !alreadySent.has(r.address))
        .map(r => ({ to: r.address, subject, html: GOT.renderCopy(originalHtml, r), text: plain }));

      text.textContent = `Sending ${items.length} separate copies…`;
      const outcome = await ask({ type: "SEND_SEPARATE", items });
      if (!outcome?.ok) throw new Error(outcome?.error || "Could not send the copies");

      for (const r of outcome.results) if (r.ok) alreadySent.add(r.to);
      compose.dataset.gotSent = JSON.stringify([...alreadySent]);
      const failed = outcome.results.filter(r => !r.ok);
      if (failed.length) {
        throw new Error(`Sent to: ${[...alreadySent].join(", ") || "nobody"}.\nFailed: ${failed.map(f => `${f.to} (${f.error})`).join("; ")}.\n\n` +
          `Retrying will only send to the ones that failed.`);
      }

      state = "DONE_SEPARATE";
      sendButton.removeAttribute("aria-disabled");
      text.textContent = `Sent ${alreadySent.size} separate tracked copies`;
      const discard = compose.querySelector('[aria-label^="Discard draft"], [data-tooltip^="Discard draft"]');
      if (discard) discard.click();
      else alert(`Sent ${alreadySent.size} separate tracked copies. You can discard this draft now.`);
    }

    function intercept(event) {
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();
      if (state === "DONE_SEPARATE") return; // everything was already sent
      prepareAndSend();
    }

    sendButton.addEventListener("click", (event) => {
      if (!checkbox.checked || state === "READY_TO_SEND") return;
      intercept(event);
    }, true);

    // Ctrl/Cmd+Enter is Gmail's send shortcut and never touches the Send button.
    compose.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" || !(event.ctrlKey || event.metaKey)) return;
      if (!checkbox.checked || state === "READY_TO_SEND") return;
      intercept(event);
    }, true);
  }

  // Don't let the SENDER's own reading of a tracked message (Sent folder, a bounce notice) count as the recipient's open.
  // This must only act in the account that sent the email: the same browser may be signed in to the recipient's account,
  // and opening the mail there is exactly the open we want to record.
  const BLANK = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";
  let senderAccounts = {};            // token -> the Gmail account that sent it (kept in chrome.storage.local)
  const currentAccount = () => GOT.accountFromTitle(document?.title);

  function rememberSenderTokens(refs) {
    const account = currentAccount();
    if (!account) return; // unknown account: never block anything for these tokens
    for (const r of refs) { const t = GOT.tokenFromPixelUrl(r.pixelUrl); if (t) senderAccounts[t] = account; }
    const keys = Object.keys(senderAccounts);
    if (keys.length > 500) for (const k of keys.slice(0, keys.length - 500)) delete senderAccounts[k];
    try { chrome.storage.local.set({ senderAccounts }); } catch (_) { /* best effort */ }
  }
  try {
    chrome.storage.local.get("senderAccounts").then((v) => { senderAccounts = v?.senderAccounts || {}; if (typeof document !== "undefined") guardOwnViews(document.documentElement); }, () => {});
    chrome.storage.onChanged?.addListener((changes) => { if (changes.senderAccounts) senderAccounts = changes.senderAccounts.newValue || {}; });
  } catch (_) { /* extension reloaded; the tab needs a refresh */ }

  const reported = new Map(); // token -> time, so a re-rendering Gmail doesn't flood the server
  function reportOwnView(token) {
    const now = Date.now();
    if (now - (reported.get(token) || 0) < 20000) return;
    reported.set(token, now);
    try { ask({ type: "OWN_VIEW", token }).catch(() => {}); } catch (_) { /* extension reloaded; nothing to report to */ }
  }
  function guardOwnViews(root) {
    if (typeof document === "undefined") return; // page is being torn down
    const me = currentAccount();
    if (!me || !root || root.nodeType !== 1) return;
    const imgs = root.tagName === "IMG" ? [root] : root.querySelectorAll("img");
    for (const img of imgs) {
      if (img.hasAttribute(GOT.PIXEL_ATTR) || img.closest('[contenteditable="true"]')) continue;
      const token = GOT.trackerTokenFromProxiedSrc(img.getAttribute("src"));
      if (!token || senderAccounts[token] !== me) continue; // not sent from this account: leave it alone so the open counts
      img.setAttribute("src", BLANK);
      reportOwnView(token); // backup: the server ignores fetches around this moment even if blocking came too late
      console.info("[Gmail Open Tracker] your own view of a tracked email: blocked and reported so it is not counted as an open");
    }
  }
  new MutationObserver((records) => {
    for (const r of records) {
      r.addedNodes.forEach(guardOwnViews);
      if (r.type === "attributes" && r.target.tagName === "IMG") guardOwnViews(r.target);
    }
  }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ["src"] });
  guardOwnViews(document.documentElement);

  const scan = () => typeof document !== "undefined" && document.querySelectorAll('[role="dialog"], .M9').forEach(addToggle);
  new MutationObserver(scan).observe(document.documentElement, { childList: true, subtree: true });
  scan();
})();
