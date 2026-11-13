#!/usr/bin/env node
// Compares what the tracker REPORTS with what you KNOW happened, and prints accuracy numbers.
//
//   node calibrate.mjs --truth ground-truth.csv --api https://api.example.com --key YOUR_API_KEY
//   node calibrate.mjs --truth ground-truth.csv --input recent.json        (offline, from a saved /api/messages/recent)
//
// Ground truth CSV columns: tag,address,client,scenario,expected
//   tag       text that appears in the test email's subject (e.g. CAL-gmail-web-S2)
//   address   the recipient address that received it
//   expected  NOT_OPENED | OPENED | CLICKED     (what you actually did)
import fs from "node:fs";

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith("--")) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith("--") ? all[i + 1] : "true"]);
  return acc;
}, []));

function parseCsv(text) {
  const rows = []; let row = [], cell = "", quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) { if (c === '"' && text[i + 1] === '"') { cell += '"'; i++; } else if (c === '"') quoted = false; else cell += c; }
    else if (c === '"') quoted = true;
    else if (c === ",") { row.push(cell); cell = ""; }
    else if (c === "\n" || c === "\r") { if (c === "\r" && text[i + 1] === "\n") i++; row.push(cell); rows.push(row); row = []; cell = ""; }
    else cell += c;
  }
  if (cell !== "" || row.length) { row.push(cell); rows.push(row); }
  const [header, ...body] = rows.filter(r => r.some(x => x.trim() !== "") && !r[0].trim().startsWith("#"));
  return body.map(r => Object.fromEntries(header.map((h, i) => [h.trim(), (r[i] ?? "").trim()])));
}

const PREDICTED = { ENGAGED: "CLICKED", OPENED: "OPENED", POSSIBLY_OPENED: "UNSURE", AUTOMATED_ONLY: "NOT_OPENED", NO_ACTIVITY: "NOT_OPENED" };
const CLASSES = ["CLICKED", "OPENED", "UNSURE", "NOT_OPENED"];
const isPositive = (c) => c === "OPENED" || c === "CLICKED";

async function loadMessages() {
  if (args.input) return JSON.parse(fs.readFileSync(args.input, "utf8"));
  if (!args.api || !args.key) { console.error("Provide --api and --key, or --input recent.json"); process.exit(2); }
  const res = await fetch(args.api.replace(/\/+$/, "") + "/api/messages/recent", { headers: { "X-Tracker-Key": args.key } });
  if (!res.ok) { console.error(`API returned ${res.status}`); process.exit(2); }
  return res.json();
}

const pct = (n, d) => (d === 0 ? "n/a" : (100 * n / d).toFixed(1) + "%");

const truth = parseCsv(fs.readFileSync(args.truth || "ground-truth.csv", "utf8"));
const messages = await loadMessages();

const results = [];
const unmatched = [];
for (const t of truth) {
  const message = messages.find(m => (m.subject || "").includes(t.tag));
  const recipient = message?.recipients.find(r => r.address.toLowerCase().includes(t.address.toLowerCase()));
  if (!recipient) { unmatched.push(t); continue; }
  results.push({ ...t, predicted: PREDICTED[recipient.status] ?? "NOT_OPENED", status: recipient.status, score: recipient.score, evidence: recipient.evidence || [] });
}

console.log(`\nMatched ${results.length} of ${truth.length} ground-truth rows.`);
for (const u of unmatched) console.log(`  not found in tracker: tag=${u.tag} address=${u.address}`);
if (!results.length) process.exit(1);

console.log("\nConfusion matrix (rows = what really happened, columns = what the tracker said)");
const w = 12;
console.log("".padEnd(w) + CLASSES.map(c => c.padStart(w)).join(""));
for (const e of ["CLICKED", "OPENED", "NOT_OPENED"]) {
  const row = results.filter(r => r.expected === e);
  console.log(e.padEnd(w) + CLASSES.map(c => String(row.filter(r => r.predicted === c).length).padStart(w)).join(""));
}

const truePos = results.filter(r => isPositive(r.expected));
const trueNeg = results.filter(r => r.expected === "NOT_OPENED");
const strictTP = truePos.filter(r => isPositive(r.predicted)).length;
const lenientTP = truePos.filter(r => isPositive(r.predicted) || r.predicted === "UNSURE").length;
const strictFP = trueNeg.filter(r => isPositive(r.predicted)).length;
const lenientFP = trueNeg.filter(r => isPositive(r.predicted) || r.predicted === "UNSURE").length;

console.log("\nOpen detection (positive = the person really opened or clicked)");
console.log(`  Recall    (real opens found):           strict ${pct(strictTP, truePos.length)}   lenient(+unsure) ${pct(lenientTP, truePos.length)}`);
console.log(`  Precision (reported opens that are real): strict ${pct(strictTP, strictTP + strictFP)}   lenient(+unsure) ${pct(lenientTP, lenientTP + lenientFP)}`);
console.log(`  False-alarm rate on unopened mail:        strict ${pct(strictFP, trueNeg.length)}   lenient(+unsure) ${pct(lenientFP, trueNeg.length)}`);

console.log("\nBy client (strict accuracy: correct = predicted OPENED/CLICKED for real opens, NOT_OPENED for unopened)");
const clients = [...new Set(results.map(r => r.client))].sort();
for (const c of clients) {
  const rows = results.filter(r => r.client === c);
  const correct = rows.filter(r => isPositive(r.expected) ? isPositive(r.predicted) : r.predicted === "NOT_OPENED").length;
  console.log(`  ${c.padEnd(24)} ${String(correct).padStart(2)}/${rows.length}  ${pct(correct, rows.length)}`);
}

const wrong = results.filter(r => isPositive(r.expected) ? !isPositive(r.predicted) : isPositive(r.predicted));
if (wrong.length) {
  console.log("\nMistakes (use these to tune tracker.scoring.* or the user-agent / IP rules)");
  for (const r of wrong) {
    const kind = isPositive(r.expected) ? "MISSED OPEN " : "FALSE ALARM ";
    console.log(`  ${kind} ${r.client} / ${r.scenario}: expected ${r.expected}, tracker said ${r.status} (${r.score})`);
    for (const line of r.evidence.slice(0, 3)) console.log(`      - ${line}`);
  }
}
console.log("\nSample size matters: with fewer than ~30 rows per class treat these percentages as a rough guide only.\n");
