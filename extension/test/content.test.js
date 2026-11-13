// Simulates the content script against a fake Gmail compose window. This checks OUR logic (interception,
// registration, per-recipient rendering, fallbacks). It cannot prove Gmail's real DOM matches these selectors.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { JSDOM } from "jsdom";

const read = (name) => fs.readFileSync(new URL(`../public/${name}`, import.meta.url), "utf8");

function setup({ recipients = ["alice@example.com"], settings = {}, configured = false, registerFails = false, confirmAnswer = true, html } = {}) {
  const chips = recipients.map(r => `<span email="${r}"></span>`).join("");
  const dom = new JSDOM(`<!doctype html><body>
    <div role="dialog" id="compose"><div role="toolbar"></div>
      <div role="button" aria-label="Send" id="send"></div>${chips}
      <input name="subjectbox" value="Hello">
      <div contenteditable="true" role="textbox" id="body">${html ?? 'Hi <a href="https://example.com/x">my site</a>'}</div>
      <div aria-label="Discard draft" id="discard"></div></div></body>`,
    { url: "https://mail.google.com/mail/u/0/", runScripts: "outside-only", pretendToBeVisual: true });
  const w = dom.window;
  const log = { messages: [], gmailSends: 0, discards: 0, alerts: [], confirms: [], separateItems: null };

  w.chrome = {
    storage: { local: { get: async () => ({ settings: { sendMode: "ask", trackLinks: true, ...settings } }), set: async () => {} } },
    runtime: {
      sendMessage: async (message) => {
        log.messages.push(message);
        if (message.type === "GMAIL_STATUS") return { ok: true, configured };
        if (message.type === "REGISTER") {
          if (registerFails) return { ok: false, error: "backend down" };
          const { recipients: rs, links, mode } = message.payload;
          const addrs = mode === "SHARED" && rs.length > 1 ? [rs.join(", ")] : rs;
          return { ok: true, data: { recipients: addrs.map((address, i) => ({
            address, pixelUrl: `https://t.example/t/R${i}.gif`,
            trackedLinks: Object.fromEntries(links.map((l, j) => [l, `https://t.example/l/R${i}/${j + 1}`])) })) } };
        }
        if (message.type === "SEND_SEPARATE") { log.separateItems = message.items; return { ok: true, results: message.items.map(i => ({ to: i.to, ok: true })) }; }
      }
    }
  };
  w.alert = (m) => log.alerts.push(m);
  w.confirm = (m) => { log.confirms.push(m); return confirmAnswer; };
  Object.defineProperty(w, "crypto", { value: { randomUUID: () => "11111111-2222-3333-4444-555555555555" }, configurable: true });
  for (const f of ["settings.js", "lib.js"]) w.eval(read(f));
  w.GOT = w.GOT; w.GOT_SETTINGS = w.GOT_SETTINGS;
  w.eval(read("content.js"));

  const $ = (id) => w.document.getElementById(id);
  $("send").addEventListener("click", () => { log.gmailSends++; });   // stands in for Gmail's own send handler
  $("discard").addEventListener("click", () => { log.discards++; });
  const tracking = w.document.querySelector(".got-track-toggle input");
  return { w, $, log, tracking, done: () => w.close() };
}

async function until(condition, ms = 1500) {
  const start = Date.now();
  while (!condition()) { if (Date.now() - start > ms) throw new Error("timed out waiting"); await new Promise(r => setTimeout(r, 10)); }
}

test("the toggle is installed in the compose window", () => {
  const t = setup(); assert.ok(t.tracking); t.done();
});

test("unticked: Send passes straight through, nothing is registered", async () => {
  const t = setup();
  t.$("send").click();
  assert.equal(t.log.gmailSends, 1);
  assert.equal(t.log.messages.length, 0);
  t.done();
});

test("one recipient: registers, adds pixel and tracked link, then lets Gmail send once", async () => {
  const t = setup(); t.tracking.checked = true;
  t.$("send").click();
  await until(() => t.log.gmailSends === 1);
  const reg = t.log.messages.find(m => m.type === "REGISTER");
  assert.deepEqual(Array.from(reg.payload.recipients), ["alice@example.com"]);
  assert.deepEqual(Array.from(reg.payload.links), ["https://example.com/x"]);
  const html = t.$("body").innerHTML;
  assert.match(html, /t\.example\/t\/R0\.gif/);
  assert.match(html, /href="https:\/\/t\.example\/l\/R0\/1"/);
  assert.match(html, />my site</);
  assert.equal(t.$("body").querySelectorAll("img").length, 1);
  assert.equal(t.log.alerts.length, 0);
  t.done();
});

test("Ctrl+Enter is intercepted too (previously sent untracked)", async () => {
  const t = setup(); t.tracking.checked = true;
  const ev = new t.w.KeyboardEvent("keydown", { key: "Enter", ctrlKey: true, bubbles: true, cancelable: true });
  t.$("body").dispatchEvent(ev);
  assert.equal(ev.defaultPrevented, true);
  assert.equal(t.log.gmailSends, 0, "must not send before tracking is added");
  await until(() => t.log.gmailSends === 1);
  assert.match(t.$("body").innerHTML, /R0\.gif/);
  t.done();
});

test("a second click while preparing does not register twice", async () => {
  const t = setup(); t.tracking.checked = true;
  t.$("send").click(); t.$("send").click();
  await until(() => t.log.gmailSends >= 1);
  assert.equal(t.log.messages.filter(m => m.type === "REGISTER").length, 1);
  t.done();
});

test("several recipients + separate mode: one personalised copy each, original untouched, draft discarded", async () => {
  const t = setup({ recipients: ["alice@example.com", "bob@example.com"], settings: { sendMode: "separate" }, configured: true });
  t.tracking.checked = true;
  t.$("send").click();
  await until(() => t.log.discards === 1);
  assert.equal(t.log.gmailSends, 0, "Gmail's own send must not also fire");
  const [a, b] = t.log.separateItems;
  assert.equal(a.to, "alice@example.com"); assert.equal(b.to, "bob@example.com");
  assert.match(a.html, /R0\.gif/); assert.doesNotMatch(a.html, /R1/);
  assert.match(b.html, /R1\.gif/); assert.doesNotMatch(b.html, /R0/);
  assert.match(a.html, /l\/R0\/1/); assert.match(b.html, /l\/R1\/1/);
  assert.equal(t.$("body").querySelectorAll("img").length, 0, "live compose body stays clean");
  assert.equal(t.log.messages.find(m => m.type === "REGISTER").payload.mode, "PER_RECIPIENT");
  t.done();
});

test("ask mode: cancelling the dialog falls back to one shared copy", async () => {
  const t = setup({ recipients: ["alice@example.com", "bob@example.com"], configured: true, confirmAnswer: false });
  t.tracking.checked = true;
  t.$("send").click();
  await until(() => t.log.gmailSends === 1);
  assert.equal(t.log.messages.find(m => m.type === "REGISTER").payload.mode, "SHARED");
  assert.equal(t.log.separateItems, null);
  assert.equal(t.$("body").querySelectorAll("img").length, 1);
  t.done();
});

test("separate copies are refused for replies, with a clear reason, and fall back to shared", async () => {
  const t = setup({ recipients: ["alice@example.com", "bob@example.com"], settings: { sendMode: "separate" }, configured: true,
    html: 'New text<div class="gmail_quote">On Monday, X wrote: old text</div>' });
  t.tracking.checked = true;
  t.$("send").click();
  await until(() => t.log.gmailSends === 1);
  assert.ok(t.log.confirms.some(c => /reply or forward/.test(c)));
  assert.equal(t.log.separateItems, null);
  t.done();
});

test("separate copies are refused when Google sign-in is not configured", async () => {
  const t = setup({ recipients: ["alice@example.com", "bob@example.com"], settings: { sendMode: "separate" }, configured: false });
  t.tracking.checked = true;
  t.$("send").click();
  await until(() => t.log.gmailSends === 1);
  assert.ok(t.log.confirms.some(c => /sign-in/.test(c)));
  assert.equal(t.log.separateItems, null);
  t.done();
});

test("registration failure stops the send, explains why, and a retry works", async () => {
  const t = setup({ registerFails: true }); t.tracking.checked = true;
  t.$("send").click();
  await until(() => t.log.alerts.length === 1);
  assert.match(t.log.alerts[0], /backend down/);
  assert.equal(t.log.gmailSends, 0);
  t.$("send").click();
  await until(() => t.log.alerts.length === 2);
  t.done();
});

test("trackLinks off: pixel only, links untouched", async () => {
  const t = setup({ settings: { trackLinks: false } }); t.tracking.checked = true;
  t.$("send").click();
  await until(() => t.log.gmailSends === 1);
  assert.deepEqual(Array.from(t.log.messages.find(m => m.type === "REGISTER").payload.links), []);
  assert.match(t.$("body").innerHTML, /href="https:\/\/example\.com\/x"/);
  t.done();
});
