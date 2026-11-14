import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import { webcrypto } from "node:crypto";
import { JSDOM } from "jsdom";

// public/lib.js is a plain browser script (no module system), so evaluate it the way a browser would.
const ctx = vm.createContext({ TextEncoder, btoa, URL, crypto: webcrypto });
vm.runInContext(fs.readFileSync(new URL("../public/lib.js", import.meta.url), "utf8"), ctx);
const GOT = ctx.GOT;

function dom(html) {
  const d = new JSDOM(`<!doctype html><body><div id="r">${html}</div></body>`, { url: "https://mail.google.com/mail/u/0/" });
  return { document: d.window.document, root: d.window.document.getElementById("r"), parser: new d.window.DOMParser() };
}

test("isTrackableUrl accepts web links only", () => {
  assert.equal(GOT.isTrackableUrl("https://example.com/a"), true);
  assert.equal(GOT.isTrackableUrl("http://example.com"), true);
  for (const bad of ["mailto:a@b.com", "tel:123", "javascript:alert(1)", "#top", "/relative", "https://mail.google.com/mail/u/0/", "data:text/html,x"]) {
    assert.equal(GOT.isTrackableUrl(bad), false, bad);
  }
});

test("collectLinks returns unique absolute trackable URLs in order and honours data-got-skip", () => {
  const { root } = dom(`<a href="https://a.com/x">A</a><a href="mailto:me@x.com">M</a><a href="https://b.com">B</a>
    <a href="https://a.com/x">dup</a><a data-got-skip href="https://skip.com/">S</a><a href="#frag">F</a>`);
  assert.deepEqual(Array.from(GOT.collectLinks(root)), ["https://a.com/x", "https://b.com/"]);
});

test("applyTracking rewrites matched links, keeps text, and adds exactly one pixel", () => {
  const { root } = dom(`<p>Hi <a href="https://a.com/x">https://a.com/x</a> and <a href="https://other.com">other</a></p>`);
  const ref = { pixelUrl: "https://t.example/t/TOKEN.gif", trackedLinks: { "https://a.com/x": "https://t.example/l/TOKEN/7" } };
  const n = GOT.applyTracking(root, ref);
  GOT.applyTracking(root, ref); // idempotent
  assert.equal(n, 1);
  const links = [...root.querySelectorAll("a")];
  assert.equal(links[0].getAttribute("href"), "https://t.example/l/TOKEN/7");
  assert.equal(links[0].textContent, "https://a.com/x");
  assert.equal(links[1].getAttribute("href"), "https://other.com");
  const pixels = root.querySelectorAll(`img[${GOT.PIXEL_ATTR}]`);
  assert.equal(pixels.length, 1);
  assert.equal(pixels[0].getAttribute("src"), "https://t.example/t/TOKEN.gif");
});

test("renderCopy gives each recipient their own pixel and links without touching the original", () => {
  const { parser } = dom("");
  const original = `<div>Hello <a href="https://a.com/x">link</a></div>`;
  const a = GOT.renderCopy(original, { pixelUrl: "https://t/t/AAA.gif", trackedLinks: { "https://a.com/x": "https://t/l/AAA/1" } }, parser);
  const b = GOT.renderCopy(original, { pixelUrl: "https://t/t/BBB.gif", trackedLinks: { "https://a.com/x": "https://t/l/BBB/1" } }, parser);
  assert.match(a, /t\/AAA\.gif/); assert.match(a, /l\/AAA\/1/); assert.doesNotMatch(a, /BBB/);
  assert.match(b, /t\/BBB\.gif/); assert.doesNotMatch(b, /AAA/);
  assert.doesNotMatch(a, /data-got-tracking-pixel/);
  assert.equal(original, `<div>Hello <a href="https://a.com/x">link</a></div>`);
});

test("encodeHeader leaves ASCII alone and encodes non-ASCII within the 75-char word limit", () => {
  assert.equal(GOT.encodeHeader("Hello there"), "Hello there");
  assert.equal(GOT.encodeHeader("Line1\r\nBcc: evil@x.com"), "Line1 Bcc: evil@x.com"); // no header injection
  const long = "नमस्ते दुनिया ".repeat(12) + "😀";
  const encoded = GOT.encodeHeader(long);
  const words = encoded.split("\r\n ");
  assert.ok(words.length > 1);
  for (const w of words) assert.ok(w.length <= 75, `word too long: ${w.length}`);
  const decoded = words.map(w => Buffer.from(w.slice(10, -2), "base64").toString("utf8")).join("");
  assert.equal(decoded, long.trim());
});

test("buildMime produces a single-recipient multipart message that round-trips", () => {
  const mime = GOT.buildMime({ to: "alice@example.com", subject: "Héllo ✓", html: "<p>Hi <b>Alice</b> — ok</p>", text: "Hi Alice — ok" });
  assert.match(mime, /^MIME-Version: 1\.0\r\nTo: alice@example\.com\r\nSubject: =\?UTF-8\?B\?/);
  assert.equal((mime.match(/^To: /gm) || []).length, 1);
  const html = mime.split("Content-Type: text/html")[1].split("\r\n\r\n")[1].split("\r\n--")[0].replace(/\r\n/g, "");
  assert.equal(Buffer.from(html, "base64").toString("utf8"), "<p>Hi <b>Alice</b> — ok</p>");
  for (const line of mime.split("\r\n")) assert.ok(line.length <= 998);
});

test("buildMime rejects addresses that could smuggle extra recipients or headers", () => {
  for (const bad of ["a@b.com, c@d.com", "a@b.com\r\nBcc: x@y.com", "Name <a@b.com>", "no-at-sign", "a@b.com;c@d.com", ""]) {
    assert.throws(() => GOT.buildMime({ to: bad, subject: "s", html: "h", text: "t" }), /Invalid recipient/, JSON.stringify(bad));
  }
});

test("base64Url is URL-safe and unpadded", () => {
  const out = GOT.base64Url(new Uint8Array([251, 255, 254, 250]));
  assert.doesNotMatch(out, /[+/=]/);
  assert.equal(Buffer.from(out.replace(/-/g, "+").replace(/_/g, "/"), "base64").toString("hex"), "fbfffefa");
});

test("trackerTokenFromProxiedSrc finds our token in Gmail-proxied pixels only", () => {
  const tok = "A".repeat(22) + "b".repeat(16);
  assert.equal(GOT.trackerTokenFromProxiedSrc(`https://ci3.googleusercontent.com/meips/ABC=s0-d-e1-ft#https://track.example.com/t/${tok}.gif`), tok);
  assert.equal(GOT.trackerTokenFromProxiedSrc(`https://ci3.googleusercontent.com/proxy/xyz#https://abc.trycloudflare.com/t/${tok}.gif`), tok);
  assert.equal(GOT.isProxiedTrackerPixel(`https://track.example.com/t/${tok}.gif`), false);   // not proxied: that's the recipient's copy of the URL
  assert.equal(GOT.isProxiedTrackerPixel("https://ci3.googleusercontent.com/meips/ABC#https://cdn.example.com/logo.png"), false);
  assert.equal(GOT.isProxiedTrackerPixel(null), false);
});

test("accountFromTitle and tokenFromPixelUrl", () => {
  assert.equal(GOT.accountFromTitle("Sent Mail - BadalBoss47@gmail.com - Gmail"), "badalboss47@gmail.com");
  assert.equal(GOT.accountFromTitle("Inbox (112) - me@school.edu.in - Gmail"), "me@school.edu.in");
  assert.equal(GOT.accountFromTitle("Gmail"), null);
  const tok = "A".repeat(22) + "b".repeat(16);
  assert.equal(GOT.tokenFromPixelUrl(`https://track.example.com/t/${tok}.gif`), tok);
  assert.equal(GOT.tokenFromPixelUrl("https://t.example/t/R0.gif"), null);
});
