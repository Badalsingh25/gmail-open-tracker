import { useCallback, useEffect, useRef, useState } from 'react';
import { groupVisits, formatGap } from './visits.js';

const PAGE_SIZE = 25;
const SORTS = {
  newest: 'Newest sent',
  oldest: 'Oldest sent',
  opened: 'Recently opened',
  confidence: 'Highest confidence'
};

function lastOpened(message) {
  const times = message.recipients.map(r => r.lastOpenAt).filter(Boolean).map(t => new Date(t).getTime());
  return times.length ? Math.max(...times) : 0;
}

function sortMessages(list, sort) {
  const copy = [...list];
  const created = m => new Date(m.createdAt).getTime();
  if (sort === 'oldest') copy.sort((a, b) => created(a) - created(b));
  else if (sort === 'opened') copy.sort((a, b) => lastOpened(b) - lastOpened(a) || created(b) - created(a));
  else if (sort === 'confidence') copy.sort((a, b) => b.score - a.score || created(b) - created(a));
  else copy.sort((a, b) => created(b) - created(a));
  return copy;
}

function csvCell(value) {
  const text = value == null ? '' : String(value);
  return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}
const DEFAULTS = { apiBaseUrl: 'http://localhost:8080', apiKey: 'dev-only-change-me' };

async function loadSettings() {
  const stored = await chrome.storage.local.get('settings');
  return { ...DEFAULTS, ...(stored.settings || {}) };
}

async function api(path, options = {}) {
  const settings = await loadSettings();
  const response = await fetch(`${settings.apiBaseUrl.replace(/\/+$/, '')}${path}`, {
    ...options,
    headers: { 'X-Tracker-Key': settings.apiKey, ...(options.headers || {}) }
  });
  if (response.status === 204) return null;
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(body.message || (response.status === 401
      ? 'API key rejected. Check Settings.'
      : `Backend request failed (${response.status}).`));
  }
  return body;
}

const STATUS = {
  ENGAGED: { label: 'Clicked a link', tone: 'engaged' },
  OPENED: { label: 'Likely opened', tone: 'opened' },
  POSSIBLY_OPENED: { label: 'Possibly opened', tone: 'possible' },
  AUTOMATED_ONLY: { label: 'Automated requests only', tone: 'automated' },
  NO_ACTIVITY: { label: 'Waiting', tone: 'none' }
};

const UA_LABELS = {
  GMAIL_PROXY: 'Gmail image proxy', YAHOO_PROXY: 'Yahoo image proxy', OUTLOOK_CLIENT: 'Outlook',
  SCANNER: 'Scanner / bot', OTHER: 'Other client', NONE: 'No user-agent'
};

function formatDate(value, empty = '—') {
  if (!value) return empty;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Unknown time' : date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

function formatAfter(seconds) {
  if (seconds < 60) return `${seconds}s after send`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}m after send`;
  if (seconds < 172800) return `${Math.round(seconds / 3600)}h after send`;
  return `${Math.round(seconds / 86400)}d after send`;
}

function Icon({ name, size = 18 }) {
  const common = { width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true };
  const paths = {
    mail: <><rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/></>,
    refresh: <><path d="M20 7v5h-5"/><path d="M4 17v-5h5"/><path d="M5.5 9A7 7 0 0 1 18 6l2 6M4 12l2 6a7 7 0 0 0 12.5-3"/></>,
    search: <><circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4.5 4.5"/></>,
    activity: <><path d="M3 12h4l3-8 4 16 3-8h4"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></>,
    trash: <><path d="M3 6h18M8 6V4h8v2m3 0-1 14H6L5 6m4 4v6m6-6v6"/></>,
    chevronLeft: <path d="m15 18-6-6 6-6"/>,
    chevronRight: <path d="m9 18 6-6-6-6"/>,
    alert: <><path d="m12 3 10 18H2L12 3Z"/><path d="M12 9v4m0 3h.01"/></>,
    inbox: <><path d="M4 4h16l2 11v5H2v-5L4 4Z"/><path d="M2 15h6l2 3h4l2-3h6"/></>,
    chevronDown: <path d="m6 9 6 6 6-6"/>,
    cog: <><circle cx="12" cy="12" r="3"/><path d="M12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M19 5l-2 2M7 17l-2 2"/></>
  };
  return <svg {...common}>{paths[name] || null}</svg>;
}

function Stat({ label, value, icon, tone = '' }) {
  return <div className={`stat ${tone}`}>
    <div className="stat-top"><span>{label}</span><span className="stat-icon"><Icon name={icon}/></span></div>
    <strong>{value ?? '—'}</strong>
  </div>;
}

function VisitTimeline({ events }) {
  const visits = groupVisits(events);
  return <section className="visits">
    <div className="history-title">Opens <span>{visits.length}</span></div>
    {visits.length === 0 && <p className="visits-empty">No counted opens yet. Requests below were treated as automated.</p>}
    <ol className="visit-list">
      {visits.map(v => <li key={v.number} className={v.clicks ? 'visit visit-click' : 'visit'}>
        {v.gapSincePreviousSeconds !== null && <span className="visit-gap">{formatGap(v.gapSincePreviousSeconds)}</span>}
        <span className="visit-dot"/>
        <div className="visit-body">
          <strong>{v.number === 1 ? 'First open' : `Open ${v.number}`}{v.clicks > 0 ? ' · clicked a link' : ''}</strong>
          <p>{formatDate(v.start)}{v.number === 1 ? ` · ${formatAfter(v.secondsAfterSend)}` : ''}</p>
          <small>{UA_LABELS[v.client] || v.client} · {v.requests} request{v.requests === 1 ? '' : 's'}</small>
        </div>
      </li>)}
    </ol>
  </section>;
}

function RecipientRow({ recipient, sharedCopy }) {
  const [open, setOpen] = useState(false);
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const status = STATUS[recipient.status] || STATUS.NO_ACTIVITY;
  const people = String(recipient.address || '').split(',').map(a => a.trim()).filter(Boolean);

  async function toggle() {
    if (open) { setOpen(false); return; }
    setOpen(true);
    setLoading(true);
    setError('');
    try { setEvents(await api(`/api/recipients/${recipient.id}/events`) || []); }
    catch (err) { setError(err.message || 'Could not load events.'); }
    finally { setLoading(false); }
  }

  return <div className="recipient-row">
    <div className="status-line">
      {sharedCopy
        ? <span className="recipient-address" title={recipient.address}>Shared copy · {people.length} people</span>
        : <span className="recipient-address" title={recipient.address}>{recipient.address}</span>}
      <span className={`status-pill tone-${status.tone}`}><span className="status-indicator"/>{sharedCopy && status.tone === 'opened' ? 'Opened by someone' : status.label}</span>
    </div>
    {sharedCopy && <ul className="shared-people">{people.map(a => <li key={a}>{a}<span>can't tell who</span></li>)}</ul>}
    <div className="recipient-meta">
      <span>Confidence <strong>{recipient.score}</strong>/100</span>
      <span>{recipient.pixelEvents} image · {recipient.clickEvents} click requests</span>
      {recipient.firstOpenAt && <span>First: {formatDate(recipient.firstOpenAt)}</span>}
      {recipient.lastOpenAt && recipient.lastOpenAt !== recipient.firstOpenAt && <span>Latest: {formatDate(recipient.lastOpenAt)}</span>}
    </div>
    {recipient.evidence?.[0]?.startsWith('Arrived near your own view') && <p className="history-caveat">A request arrived close to when you viewed your own copy, so it is shown as possible, not certain. A later open will confirm it.</p>}
    {recipient.notMeasurable && <p className="history-caveat">Apple Mail Privacy Protection hides this person's real opens, so they can't be measured by an image.</p>}
    {sharedCopy && <p className="history-caveat">One copy went to everyone, so the tracker can tell that someone opened it but not who. To see who, send separate copies (needs no attachments).</p>}
    {(recipient.pixelEvents + recipient.clickEvents) > 0 && <button className="button button-ghost" onClick={toggle} aria-expanded={open}>
      <Icon name="clock" size={14}/>{open ? 'Hide history' : 'Open history'}<Icon name="chevronDown" size={12}/>
    </button>}
    {open && <div className="history-panel">
      {!loading && !error && <VisitTimeline events={events}/>}
      {recipient.evidence?.length > 0 && <ul className="evidence">{recipient.evidence.map((line, i) => <li key={i}>{line}</li>)}</ul>}
      <details className="raw-requests"><summary>All requests <span>{events.length}</span></summary>
      {loading && <div className="inline-state">Loading…</div>}
      {error && <div className="inline-error">{error}</div>}
      {!loading && !error && events.map(event => <div className="event-row" key={event.id}>
        <span className="event-marker"><Icon name={event.counted ? 'mail' : 'activity'} size={13}/></span>
        <div>
          <strong>{event.kind === 'CLICK' ? 'Link click' : 'Image fetch'} · {event.counted ? 'counted' : 'not counted'} ({event.score})</strong>
          <p>{formatDate(event.at)} · {formatAfter(event.secondsAfterSend)}</p>
          <small>{event.reason} — {UA_LABELS[event.uaClass] || event.uaClass}</small>
        </div>
      </div>)}
      </details>
    </div>}
  </div>;
}

function MessageCard({ message, onDelete }) {
  const [deleting, setDeleting] = useState(false);
  const status = STATUS[message.status] || STATUS.NO_ACTIVITY;

  async function remove() {
    if (!window.confirm(`Delete "${message.subject || '(No subject)'}" and all of its tracking history? This cannot be undone.`)) return;
    setDeleting(true);
    try { await onDelete(message); } finally { setDeleting(false); }
  }

  return <article className="email-card">
    <div className="email-card-heading">
      <div className="mail-avatar"><Icon name="mail" size={19}/></div>
      <div className="email-heading-copy">
        <h3 title={message.subject || '(No subject)'}>{message.subject || '(No subject)'}</h3>
        <p>{formatDate(message.createdAt)} · {message.recipients.length} tracked {message.recipients.length === 1 ? 'recipient' : 'recipients'}</p>
        {lastOpened(message) > 0 && <p className="last-opened">Last opened {formatDate(new Date(lastOpened(message)).toISOString())}</p>}
      </div>
      <span className={`status-dot tone-${status.tone}`} title={status.label}/>
    </div>
    {message.recipients.map(r => <RecipientRow key={r.id} recipient={r} sharedCopy={message.sharedCopy}/>)}
    <div className="card-actions">
      <span/>
      <button className="button button-danger" onClick={remove} disabled={deleting}><Icon name="trash" size={14}/>{deleting ? 'Deleting…' : 'Delete'}</button>
    </div>
  </article>;
}

export default function App() {
  const [stats, setStats] = useState(null);
  const [result, setResult] = useState(null);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('all');
  const [sort, setSort] = useState('newest');
  const [exporting, setExporting] = useState(false);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');
  const [lastUpdated, setLastUpdated] = useState(null);
  const [notice, setNotice] = useState('');
  const requestNumber = useRef(0);

  const load = useCallback(async ({ quiet = false } = {}) => {
    const requestId = ++requestNumber.current;
    if (!quiet) setLoading(true); else setRefreshing(true);
    setError('');
    try {
      const params = new URLSearchParams({ q: query.trim(), filter, page: String(page), size: String(PAGE_SIZE) });
      const [nextStats, nextResult] = await Promise.all([api('/api/stats'), api(`/api/messages/search?${params}`)]);
      if (requestId !== requestNumber.current) return;
      setStats(nextStats);
      setResult(nextResult);
      setLastUpdated(new Date());
    } catch (err) {
      if (requestId !== requestNumber.current) return;
      setError(err.message || 'Could not reach the tracker.');
    } finally {
      if (requestId === requestNumber.current) { setLoading(false); setRefreshing(false); }
    }
  }, [query, filter, page]);

  useEffect(() => { const t = window.setTimeout(() => load(), 220); return () => window.clearTimeout(t); }, [load]);
  useEffect(() => { const t = window.setInterval(() => load({ quiet: true }), 30000); return () => window.clearInterval(t); }, [load]);

  useEffect(() => {
    try { chrome.storage.local.set({ unseenOpens: 0 }); chrome.action?.setBadgeText({ text: '' }); } catch (_) { /* not critical */ }
  }, []);

  async function exportCsv() {
    setExporting(true);
    setError('');
    try {
      const rows = [['Subject', 'Sent', 'Recipient', 'Status', 'Confidence', 'Image requests', 'Click requests', 'First open', 'Last open']];
      for (let p = 0; p < 40; p++) {
        const params = new URLSearchParams({ q: query.trim(), filter, page: String(p), size: '50' });
        const chunk = await api(`/api/messages/search?${params}`);
        for (const m of chunk.content || []) {
          for (const r of m.recipients) {
            rows.push([m.subject || '(No subject)', m.createdAt, r.address, (STATUS[r.status] || STATUS.NO_ACTIVITY).label,
              r.score, r.pixelEvents, r.clickEvents, r.firstOpenAt || '', r.lastOpenAt || '']);
          }
        }
        if (chunk.last || !chunk.content?.length) break;
      }
      const blob = new Blob(['\ufeff' + rows.map(r => r.map(csvCell).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `email-tracker-${new Date().toISOString().slice(0, 10)}.csv`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 2000);
      setNotice(`Exported ${rows.length - 1} rows.`);
    } catch (err) { setError(`Could not export: ${err.message}`); }
    finally { setExporting(false); }
  }

  async function deleteMessage(message) {
    try {
      await api(`/api/messages/${message.id}`, { method: 'DELETE' });
      await load({ quiet: true });
      setNotice('Tracking record and history deleted.');
    } catch (err) { setError(`Could not delete: ${err.message}`); throw err; }
  }

  const messages = sortMessages(result?.content || [], sort);
  const totalPages = result?.totalPages || 0;
  const totalElements = result?.totalElements || 0;
  const filtered = query || filter !== 'all';

  return <main className="app-shell">
    <header className="app-header">
      <div className="brand-mark"><Icon name="mail" size={23}/><span /></div>
      <div className="brand-copy"><h1>Open Tracker</h1><p>Gmail · per-recipient estimates</p></div>
      <button className="icon-button" onClick={() => chrome.runtime.openOptionsPage()} aria-label="Settings" title="Settings"><Icon name="cog" size={18}/></button>
      <button className={`icon-button ${refreshing ? 'spinning' : ''}`} onClick={() => load({ quiet: true })} disabled={refreshing} aria-label="Refresh dashboard" title="Refresh dashboard"><Icon name="refresh" size={18}/></button>
    </header>

    <section className="stats-grid" aria-label="Tracking statistics">
      <Stat label="Tracked emails" value={stats?.trackedMessages} icon="mail"/>
      <Stat label="Likely opened" value={stats?.likelyOpened} icon="activity" tone="stat-green"/>
      <Stat label="Recipients" value={stats?.trackedRecipients} icon="clock" tone="stat-blue"/>
    </section>


    {error && <div className="error-banner" role="alert"><Icon name="alert" size={17}/><div><strong>Couldn't connect to the tracker</strong><p>{error}</p></div><button onClick={() => load()} className="retry-button">Retry</button></div>}
    {notice && <div className="success-banner" role="status">{notice}<button onClick={() => setNotice('')} aria-label="Dismiss message">×</button></div>}

    <section className="list-section">
      <div className="list-heading"><div><h2>Tracked emails</h2><p>{totalElements} records · updates every 30 seconds</p></div><button className="button button-ghost" onClick={exportCsv} disabled={exporting || !totalElements} title="Download all matching emails as a CSV file">{exporting ? 'Exporting…' : 'Export CSV'}</button></div>
      <div className="toolbar">
        <label className="search-box"><Icon name="search" size={17}/><input value={query} onChange={e => { setQuery(e.target.value); setPage(0); }} placeholder="Search recipient or subject" aria-label="Search recipient or subject"/>{query && <button onClick={() => { setQuery(''); setPage(0); }} aria-label="Clear search">×</button>}</label>
        <select value={filter} onChange={e => { setFilter(e.target.value); setPage(0); }} aria-label="Filter">
          <option value="all">All emails</option><option value="opened">Likely opened</option><option value="activity">Any request</option><option value="none">No requests</option>
        </select>
        <select value={sort} onChange={e => setSort(e.target.value)} aria-label="Sort" title="Sort the emails on this page">
          {Object.entries(SORTS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </div>

      {loading && !result ? <div className="loading-state"><span className="spinner"/><strong>Loading your tracker</strong><p>Connecting to your tracker API…</p></div>
        : messages.length ? <div className="email-list">{messages.map(m => <MessageCard message={m} onDelete={deleteMessage} key={m.id}/>)}</div>
        : <div className="empty-state"><div className="empty-icon"><Icon name="inbox" size={25}/></div><h3>{filtered ? 'No matching emails' : 'Nothing tracked yet'}</h3><p>{filtered ? 'Try changing your search or filter.' : 'Compose an email in Gmail and tick Track opens.'}</p><button className="button button-primary" onClick={() => chrome.tabs.create({ url: 'https://mail.google.com/' })}>Open Gmail</button></div>}

      {result && totalPages > 0 && <div className="pagination">
        <span>Page <strong>{page + 1}</strong> of <strong>{totalPages}</strong></span>
        <div><button className="button button-ghost" disabled={page <= 0 || loading} onClick={() => setPage(v => Math.max(0, v - 1))}><Icon name="chevronLeft" size={15}/>Previous</button><button className="button button-ghost" disabled={result.last || loading} onClick={() => setPage(v => v + 1)}>Next<Icon name="chevronRight" size={15}/></button></div>
      </div>}
    </section>

    <footer className="app-footer"><span>Gmail Open Tracker <b>•</b> v0.7</span><span>{lastUpdated ? `Updated ${lastUpdated.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}` : 'Waiting for data'}</span></footer>
  </main>;
}
