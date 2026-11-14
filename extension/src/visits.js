// Turns raw tracker requests into "visits": one entry per separate time the recipient looked at the email.
// Requests from one visit arrive close together (images, retries, several previews), so a new visit starts
// only after a quiet gap. Only requests the server counted are used; scanner noise never appears here.
export const VISIT_GAP_SECONDS = 10 * 60;

export function groupVisits(events, gapSeconds = VISIT_GAP_SECONDS) {
  const counted = (events || [])
    .filter(e => e && e.counted)
    .map(e => ({ ...e, t: new Date(e.at).getTime() }))
    .filter(e => Number.isFinite(e.t))
    .sort((a, b) => a.t - b.t);
  const visits = [];
  for (const e of counted) {
    const last = visits[visits.length - 1];
    if (last && e.t - last.endMs <= gapSeconds * 1000) {
      last.endMs = e.t; last.requests += 1; last.clicks += e.kind === 'CLICK' ? 1 : 0;
      last.clients[e.uaClass] = (last.clients[e.uaClass] || 0) + 1;
    } else {
      visits.push({ startMs: e.t, endMs: e.t, requests: 1, clicks: e.kind === 'CLICK' ? 1 : 0,
        secondsAfterSend: e.secondsAfterSend, clients: { [e.uaClass]: 1 } });
    }
  }
  return visits.map((v, i) => ({
    ...v, number: i + 1,
    start: new Date(v.startMs).toISOString(),
    gapSincePreviousSeconds: i === 0 ? null : Math.round((v.startMs - visits[i - 1].endMs) / 1000),
    client: Object.entries(v.clients).sort((a, b) => b[1] - a[1])[0][0]
  }));
}

export function formatGap(seconds) {
  if (seconds < 3600) return `${Math.max(1, Math.round(seconds / 60))} min later`;
  if (seconds < 172800) return `${Math.round(seconds / 3600)} h later`;
  return `${Math.round(seconds / 86400)} days later`;
}
