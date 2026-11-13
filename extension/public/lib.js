// Pure helpers shared by the content script (DOM) and the background worker (MIME). No chrome.* calls,
// so everything here is unit-tested in Node (see test/lib.test.js).
(function (root) {
  const PIXEL_ATTR = "data-got-tracking-pixel";

  /** True for ordinary web links worth tracking. Skips mailto:, tel:, anchors and Google's own pages. */
  function isTrackableUrl(href) {
    let u;
    try { u = new URL(href); } catch (_) { return false; }
    if (u.protocol !== "http:" && u.protocol !== "https:") return false;
    if (u.hostname === "mail.google.com" || u.hostname.endsWith(".google.com") && u.hostname.startsWith("mail.")) return false;
    return href.length <= 2048;
  }

  /** Unique, absolute, trackable link targets found in an element, in document order. */
  function collectLinks(container) {
    const seen = new Set();
    for (const a of container.querySelectorAll("a[href]")) {
      if (a.hasAttribute("data-got-skip")) continue;
      const href = a.href; // absolute and normalised by the DOM
      if (isTrackableUrl(href)) seen.add(href);
    }
    return [...seen];
  }

  /**
   * Point tracked links at their per-recipient URLs and append the recipient's pixel. Operates in place
   * on {root}. Link text is never changed. Idempotent: an existing pixel is replaced, not duplicated.
   * ref = { pixelUrl, trackedLinks: { originalUrl: trackedUrl } }
   */
  function applyTracking(rootEl, ref) {
    const map = ref.trackedLinks || {};
    let rewritten = 0;
    for (const a of rootEl.querySelectorAll("a[href]")) {
      const tracked = map[a.href];
      if (tracked) { a.setAttribute("href", tracked); rewritten++; }
    }
    for (const old of rootEl.querySelectorAll(`img[${PIXEL_ATTR}]`)) old.remove();
    const img = rootEl.ownerDocument.createElement("img");
    img.setAttribute("src", ref.pixelUrl);
    img.setAttribute("width", "1");
    img.setAttribute("height", "1");
    img.setAttribute("alt", "");
    img.setAttribute("style", "width:1px;height:1px;display:inline!important;border:0!important;");
    img.setAttribute(PIXEL_ATTR, "true");
    rootEl.appendChild(img);
    return rewritten;
  }

  /** HTML string for one recipient's copy: a detached clone of the original body with tracking applied. */
  function renderCopy(originalHtml, ref, parser) {
    const doc = (parser || new DOMParser()).parseFromString("<!doctype html><body>" + originalHtml + "</body>", "text/html");
    applyTracking(doc.body, ref);
    // The marker attribute is only needed to find the pixel inside the live compose window.
    for (const img of doc.body.querySelectorAll(`img[${PIXEL_ATTR}]`)) img.removeAttribute(PIXEL_ATTR);
    return doc.body.innerHTML;
  }

  // ---------- MIME (for sending separate copies through the Gmail API) ----------

  const ADDRESS = /^[^\s<>,;"()\[\]\\]+@[^\s<>,;"()\[\]\\]+$/;

  function utf8(s) { return new TextEncoder().encode(s); }

  function toBase64(bytes) {
    let binary = "";
    for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(binary);
  }

  function base64Url(bytes) { return toBase64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); }

  function wrap76(b64) { return b64.replace(/(.{76})/g, "$1\r\n"); }

  /** RFC 2047 header encoding, split on code-point boundaries so each encoded word stays under 75 chars. */
  function encodeHeader(value) {
    const clean = String(value).replace(/[\r\n]+/g, " ").trim();
    if (/^[\x20-\x7e]*$/.test(clean)) return clean;
    const words = [];
    let chunk = [], size = 0;
    const flush = () => { if (chunk.length) { words.push("=?UTF-8?B?" + toBase64(utf8(chunk.join(""))) + "?="); chunk = []; size = 0; } };
    for (const ch of clean) { // iterates by code point
      const n = utf8(ch).length;
      if (size + n > 45) flush();
      chunk.push(ch); size += n;
    }
    flush();
    return words.join("\r\n ");
  }

  function boundary() {
    const bytes = new Uint8Array(12);
    (globalThis.crypto || require("node:crypto").webcrypto).getRandomValues(bytes);
    return "got_" + Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
  }

  /** Build an RFC 5322 multipart/alternative message addressed to exactly one recipient. */
  function buildMime({ to, subject, html, text }) {
    if (!ADDRESS.test(to)) throw new Error("Invalid recipient address: " + to);
    const b = boundary();
    const part = (type, body) => `Content-Type: ${type}; charset="UTF-8"\r\nContent-Transfer-Encoding: base64\r\n\r\n${wrap76(toBase64(utf8(body)))}\r\n`;
    return [
      "MIME-Version: 1.0",
      "To: " + to,
      "Subject: " + encodeHeader(subject || ""),
      `Content-Type: multipart/alternative; boundary="${b}"`,
      "",
      `--${b}`,
      part("text/plain", text || ""),
      `--${b}`,
      part("text/html", html),
      `--${b}--`,
      ""
    ].join("\r\n");
  }

  const api = { PIXEL_ATTR, isTrackableUrl, collectLinks, applyTracking, renderCopy, buildMime, encodeHeader, base64Url, utf8 };
  root.GOT = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
