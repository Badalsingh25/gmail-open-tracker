#!/usr/bin/env node
// Saves a readable archive of everything the tracker knows (mails, recipients, verdicts, raw events) as JSON,
// through the same API the extension uses. No database access or extra dependencies needed.
//
//   node backup.mjs --api https://YOUR-SERVICE.onrender.com --key YOUR_API_KEY [--dir ~/gmail-tracker-backups] [--keep 14]
//
// Or set TRACKER_API and TRACKER_KEY in the environment. The archive contains recipient addresses and subjects:
// it defaults to a folder OUTSIDE the repository, and you should never commit it.
// This is an archive for reading and analysis. For a full database dump use mysqldump (see docs/OPERATIONS.md).
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith("--")) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith("--") ? all[i + 1] : "true"]);
  return acc;
}, []));

const api = (args.api || process.env.TRACKER_API || "").replace(/\/+$/, "");
const key = args.key || process.env.TRACKER_KEY || "";
const dir = path.resolve((args.dir || path.join(os.homedir(), "gmail-tracker-backups")).replace(/^~(?=$|[\\/])/, os.homedir()));
const keep = Math.max(1, Number(args.keep) || 14);
if (!api || !key) { console.error("Provide --api and --key (or TRACKER_API and TRACKER_KEY)."); process.exit(2); }

async function get(pathname, attempt = 1) {
  try {
    // A sleeping free-tier service needs up to a minute to wake: allow for it.
    const res = await fetch(api + pathname, { headers: { "X-Tracker-Key": key }, signal: AbortSignal.timeout(90_000) });
    if (res.status === 401) throw Object.assign(new Error("API key rejected"), { fatal: true });
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${pathname}`);
    return await res.json();
  } catch (e) {
    if (e.fatal || attempt >= 3) throw e;
    await new Promise(r => setTimeout(r, 5000 * attempt));
    return get(pathname, attempt + 1);
  }
}

try {
  const stats = await get("/api/stats");
  console.log(`Tracker reports: ${stats.trackedMessages} mails, ${stats.trackedRecipients} recipients, ${stats.totalEvents} events.`);
  if (!stats.trackedMessages) {
    console.log("Nothing to export: the tracker itself reports no tracked mails. Check that this is the right service and database.");
    process.exit(0);
  }
  const messages = [];
  for (let page = 0; page < 200; page++) {
    const chunk = await get(`/api/messages/search?q=&filter=all&page=${page}&size=50`);
    messages.push(...(chunk.content || []));
    if (chunk.last || !(chunk.content || []).length) break;
  }
  let events = 0;
  for (const m of messages) {
    for (const r of m.recipients) {
      r.events = await get(`/api/recipients/${r.id}/events`);
      events += r.events.length;
    }
  }

  if (!messages.length) throw new Error(`The tracker counts ${stats.trackedMessages} mails but the listing returned none. Nothing was saved.`);

  fs.mkdirSync(dir, { recursive: true });
  const stamp = new Date().toISOString().replace(/[:T]/g, "-").slice(0, 16);
  const file = path.join(dir, `tracker-${stamp}.json`);
  fs.writeFileSync(file, JSON.stringify({ exportedAt: new Date().toISOString(), api, stats, messages }, null, 2));

  const old = fs.readdirSync(dir).filter(f => /^tracker-.*\.json$/.test(f)).sort().reverse().slice(keep);
  for (const f of old) fs.unlinkSync(path.join(dir, f));

  const recipients = messages.reduce((n, m) => n + m.recipients.length, 0);
  console.log(`Saved ${messages.length} mails, ${recipients} recipients, ${events} events to ${file}`);
  if (old.length) console.log(`Removed ${old.length} older archive(s); keeping the latest ${keep}.`);
  // TiDB Starter's free allowance is 5 GiB of storage; each event is a few hundred bytes.
  console.log(`Rough size: about ${((Number(stats.totalEvents) || 0) * 300 / 1048576).toFixed(2)} MB of events in the database (free allowance: 5 GiB).`);
} catch (e) {
  console.error("Backup failed: " + (e.message || e));
  process.exit(1);
}
