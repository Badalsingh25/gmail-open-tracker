// The extension must hide the tracker pixel only in the account that SENT the email. In any other account
// (the recipient's) the pixel has to load normally, or the recipient's open would never be recorded.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { JSDOM } from "jsdom";

const read = (name) => fs.readFileSync(new URL(`../public/${name}`, import.meta.url), "utf8");
const TOKEN = "A".repeat(22) + "b".repeat(16);
const PROXIED = `https://ci3.googleusercontent.com/meips/ABC=s0-d-e1-ft#https://track.example.com/t/${TOKEN}.gif`;

async function open(title, senderAccounts) {
  const dom = new JSDOM(`<!doctype html><title>${title}</title><body><div id="msg"></div></body>`,
    { url: "https://mail.google.com/mail/u/0/", runScripts: "outside-only" });
  const w = dom.window;
  const sent = [];
  w.chrome = {
    storage: { local: { get: async () => ({ senderAccounts, settings: {} }), set: async () => {} }, onChanged: { addListener() {} } },
    runtime: { sendMessage: async (m) => { sent.push(m); return { ok: true }; } }
  };
  for (const f of ["settings.js", "lib.js"]) w.eval(read(f));
  w.eval(read("content.js"));
  await new Promise(r => setTimeout(r, 20));
  const img = w.document.createElement("img");
  img.setAttribute("src", PROXIED);
  w.document.getElementById("msg").appendChild(img);
  await new Promise(r => setTimeout(r, 20));
  return { src: img.getAttribute("src"), sent };
}

test("in the sender's own account the pixel is blocked and reported", async () => {
  const r = await open("Sent Mail - sender@gmail.com - Gmail", { [TOKEN]: "sender@gmail.com" });
  assert.notEqual(r.src, PROXIED);
  assert.deepEqual(r.sent.map(m => m.type), ["OWN_VIEW"]);
});

test("in the recipient's account the pixel is left alone and nothing is reported", async () => {
  const r = await open("Inbox (1) - recipient@gmail.com - Gmail", { [TOKEN]: "sender@gmail.com" });
  assert.equal(r.src, PROXIED);
  assert.equal(r.sent.length, 0);
});

test("a token this browser never sent is left alone", async () => {
  const r = await open("Inbox - sender@gmail.com - Gmail", {});
  assert.equal(r.src, PROXIED);
  assert.equal(r.sent.length, 0);
});
