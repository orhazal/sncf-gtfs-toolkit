// GTFS-RT bridge. Every 30 s: fetch the SNCF trip updates and service alerts from the PAN, rewrite their RT trip ids
// into the ids of the SNCF GTFS, keep the result in memory and serve it over HTTP. Every 5 min: revalidate the SNCF
// GTFS and rebuild the lookup when it changed.
import { createServer } from 'node:http';
import gtfsRealtime from 'gtfs-realtime-bindings';
import { lookupFromZip, patchTripUpdates, patchAlerts } from './patch.mjs';

const { FeedMessage } = gtfsRealtime.transit_realtime;
const GTFS_URL = 'https://eu.ftp.opendatasoft.com/sncf/plandata/Export_OpenData_SNCF_GTFS_NewTripId.zip';
const FEEDS = {
  'trip-updates': { url: 'https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-trip-updates', patch: patchTripUpdates },
  'service-alerts': { url: 'https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-service-alerts', patch: patchAlerts },
};
const PORT = Number(process.env.PORT ?? 8080);
const FEED_INTERVAL = 30_000, GTFS_INTERVAL = 5 * 60_000, STALE_AFTER = 5 * 60_000;

let lookup, gtfsModified, gtfsLoadedAt;
const served = new Map(); // feed name -> { body, at, timestamp, entities }
const counters = Object.fromEntries(Object.keys(FEEDS).map(name => [name, { refreshes: 0, errors: 0, lastError: undefined }]));
const startedAt = new Date();

async function get(url, init) {
  const r = await fetch(url, { signal: AbortSignal.timeout(60_000), ...init });
  if (!r.ok) throw new Error(`${init?.method ?? 'GET'} ${url}: ${r.status}`);
  return r;
}

async function lastModified(url) {
  const value = (await get(url, { method: 'HEAD' })).headers.get('last-modified');
  if (!value) throw new Error(`no Last-Modified on ${url}`);
  return value;
}

async function refreshLookup() {
  const gtfs = await lastModified(GTFS_URL);
  if (gtfs === gtfsModified) return;
  lookup = lookupFromZip(new Uint8Array(await (await get(GTFS_URL)).arrayBuffer()));
  gtfsModified = gtfs;
  gtfsLoadedAt = new Date();
  console.log(`GTFS of ${gtfs}: ${lookup.trips.size} trips, ${lookup.byShort.size} train numbers`);
  if (!served.size) await refreshFeeds();
}

async function refreshFeeds() {
  if (!lookup) return;
  await Promise.all(Object.entries(FEEDS).map(async ([name, feed]) => {
    try {
      const message = FeedMessage.decode(new Uint8Array(await (await get(feed.url)).arrayBuffer()));
      const before = message.entity.length;
      const today = new Date(Number(message.header.timestamp) * 1000).toISOString().slice(0, 10).replace(/-/g, '');
      message.entity = feed.patch(message.entity, lookup, today);
      served.set(name, { body: FeedMessage.encode(message).finish(), at: new Date(), timestamp: Number(message.header.timestamp), entities: `${before} -> ${message.entity.length}` });
      counters[name].refreshes++;
    } catch (e) {
      counters[name].errors++;
      counters[name].lastError = { at: new Date(), message: e.message };
      console.error(`${name}: ${e.message}`); // the previous feed stays served
    }
  }));
}

const feedInfo = name => { const f = served.get(name); return f && { fetched: f.at, feedTimestamp: new Date(f.timestamp * 1000), entities: f.entities, bytes: f.body.length }; };

function status() {
  const feeds = Object.fromEntries(Object.keys(FEEDS).filter(name => served.has(name)).map(name => [name, feedInfo(name)]));
  const fresh = Object.keys(FEEDS).every(name => served.has(name) && Date.now() - served.get(name).at < STALE_AFTER);
  return { fresh, gtfsModified, feeds };
}

function stats() {
  return {
    fresh: status().fresh,
    bridge: { startedAt, uptimeSeconds: Math.round(process.uptime()), rssMB: Math.round(process.memoryUsage().rss / 1e6), node: process.version },
    gtfs: lookup && { modified: gtfsModified, loadedAt: gtfsLoadedAt, trips: lookup.trips.size, trainNumbers: lookup.byShort.size },
    feeds: Object.fromEntries(Object.keys(FEEDS).map(name => [name, { ...feedInfo(name), ...counters[name] }])),
  };
}

const json = (res, code, body) => res.writeHead(code, { 'content-type': 'application/json' }).end(JSON.stringify(body, null, 1));

createServer((req, res) => {
  const name = req.url.slice(1).split('?')[0];
  if (name === '') { const s = status(); return json(res, s.fresh ? 200 : 503, s); }
  if (name === 'stats') return json(res, 200, stats());
  const f = served.get(name);
  if (!f) return res.writeHead(name in FEEDS ? 503 : 404).end();
  res.writeHead(200, { 'content-type': 'application/x-protobuf', 'content-length': f.body.length, 'last-modified': f.at.toUTCString(), 'cache-control': 'no-cache' }).end(f.body);
}).listen(PORT, () => console.log(`listening on ${PORT}`));

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function every(ms, fn) { for (;;) { try { await fn(); } catch (e) { console.error(e.message); } await sleep(ms); } }
every(GTFS_INTERVAL, refreshLookup);
every(FEED_INTERVAL, refreshFeeds);
