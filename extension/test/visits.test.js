import test from "node:test";
import assert from "node:assert/strict";
import { groupVisits, formatGap } from "../src/visits.js";

const ev = (iso, extra = {}) => ({ at: iso, counted: true, kind: "PIXEL", uaClass: "GMAIL_PROXY", secondsAfterSend: 120, ...extra });

test("requests close together are one visit; a long gap starts a new one", () => {
  const v = groupVisits([
    ev("2026-11-14T02:53:00Z"), ev("2026-11-14T02:53:02Z"), ev("2026-11-14T02:55:00Z"),
    ev("2026-11-14T06:00:00Z"), ev("2026-11-14T06:00:01Z")
  ]);
  assert.equal(v.length, 2);
  assert.equal(v[0].requests, 3);
  assert.equal(v[1].requests, 2);
  assert.equal(v[1].gapSincePreviousSeconds, Math.round((Date.parse("2026-11-14T06:00:00Z") - Date.parse("2026-11-14T02:55:00Z")) / 1000));
});

test("uncounted (scanner) requests and bad input are ignored; order does not matter", () => {
  assert.deepEqual(groupVisits([ev("2026-11-14T02:53:00Z", { counted: false })]), []);
  assert.deepEqual(groupVisits(null), []);
  const v = groupVisits([ev("2026-11-14T08:00:00Z"), ev("2026-11-14T02:00:00Z")]);
  assert.equal(v.length, 2);
  assert.equal(v[0].number, 1);
  assert.equal(v[0].start, "2026-11-14T02:00:00.000Z");
});

test("a click marks the visit and the main client is the most frequent", () => {
  const v = groupVisits([ev("2026-11-14T02:00:00Z"), ev("2026-11-14T02:00:05Z", { kind: "CLICK", uaClass: "OTHER" }), ev("2026-11-14T02:00:06Z")]);
  assert.equal(v.length, 1);
  assert.equal(v[0].clicks, 1);
  assert.equal(v[0].client, "GMAIL_PROXY");
});

test("formatGap", () => {
  assert.equal(formatGap(30), "1 min later");
  assert.equal(formatGap(5400), "2 h later");
  assert.equal(formatGap(86400 * 3), "3 days later");
});
