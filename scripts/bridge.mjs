// GTFS-RT bridge. Every 30 s: fetch the SNCF trip updates and service alerts from the PAN, rewrite their RT trip ids
// into the ids of the SNCF GTFS, keep the result in memory and serve it over HTTP. Every 5 min: revalidate the SNCF
// GTFS and rebuild the lookup when it changed.
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import gtfsRealtime from 'gtfs-realtime-bindings';
import { lookupFromZip, patchTripUpdates, patchAlerts } from './patch.mjs';

const { FeedMessage } = gtfsRealtime.transit_realtime;
const config = JSON.parse(readFileSync(new URL('../data/config.json', import.meta.url), 'utf8'));
const FEEDS = {
  'trip-updates': { url: 'https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-trip-updates', patch: patchTripUpdates },
  'service-alerts': { url: 'https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-service-alerts', patch: patchAlerts },
};
const PORT = Number(process.env.PORT ?? 8080);
const FEED_INTERVAL = 30_000, GTFS_INTERVAL = 5 * 60_000, STALE_AFTER = 5 * 60_000;

let lookup, gtfsModified;
const served = new Map(); // feed name -> { body, at, timestamp, entities }

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
  const gtfs = await lastModified(config.gtfs_url);
  if (gtfs === gtfsModified) return;
  lookup = lookupFromZip(new Uint8Array(await (await get(config.gtfs_url)).arrayBuffer()));
  gtfsModified = gtfs;
  console.log(`GTFS of ${gtfs}: ${lookup.trips.size} trips, ${lookup.byShort.size} train numbers`);
  if (!served.size) await refreshFeeds();
}

async function refreshFeeds() {
  if (!lookup) return;
  await Promise.all(Object.entries(FEEDS).map(async ([name, feed]) => {
    try {
      const message = FeedMessage.decode(new Uint8Array(await (await get(feed.url)).arrayBuffer()));
      const before = message.entity.length;
      message.entity = feed.patch(message.entity, lookup);
      served.set(name, { body: FeedMessage.encode(message).finish(), at: new Date(), timestamp: Number(message.header.timestamp), entities: `${before} -> ${message.entity.length}` });
    } catch (e) {
      console.error(`${name}: ${e.message}`); // the previous feed stays served
    }
  }));
}

function status() {
  const feeds = Object.fromEntries([...served].map(([name, f]) => [name, { fetched: f.at, feedTimestamp: new Date(f.timestamp * 1000), entities: f.entities }]));
  const fresh = Object.keys(FEEDS).every(name => served.has(name) && Date.now() - served.get(name).at < STALE_AFTER);
  return { fresh, gtfsModified, feeds };
}

createServer((req, res) => {
  const name = req.url.slice(1).split('?')[0];
  if (name === '') {
    const s = status();
    return res.writeHead(s.fresh ? 200 : 503, { 'content-type': 'application/json' }).end(JSON.stringify(s, null, 1));
  }
  const f = served.get(name);
  if (!f) return res.writeHead(name in FEEDS ? 503 : 404).end();
  res.writeHead(200, { 'content-type': 'application/x-protobuf', 'content-length': f.body.length, 'last-modified': f.at.toUTCString(), 'cache-control': 'no-cache' }).end(f.body);
}).listen(PORT, () => console.log(`listening on ${PORT}`));

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function every(ms, fn) { for (;;) { try { await fn(); } catch (e) { console.error(e.message); } await sleep(ms); } }
every(GTFS_INTERVAL, refreshLookup);
every(FEED_INTERVAL, refreshFeeds);
