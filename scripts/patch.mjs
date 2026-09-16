// The pure part of the bridge: build the lookup from the SNCF GTFS, rewrite the two feeds.
import { unzipSync, strFromU8 } from 'fflate';

// RT internal trip id: OCESN, train number, F (ferré) or R (route). It is the prefix of the static ids of that train.
const SHORT_ID = /^OCESN\d+[FR]/;
const ADDED = 1, CANCELED = 3;

// { 'trips.txt': text, 'calendar_dates.txt': text } -> lookup
export function lookupFrom(files) {
  if (files['calendar.txt']?.trim().split('\n').length > 1) throw new Error('calendar.txt has rows: the SNCF feed used to date everything through calendar_dates.txt');
  const trips = new Map(), byShort = new Map(), dates = new Map();
  for (const r of csv(files['trips.txt'])) {
    trips.set(r.trip_id, { routeId: r.route_id, serviceId: r.service_id });
    const short = r.trip_id.match(SHORT_ID)?.[0];
    if (short) (byShort.get(short) ?? byShort.set(short, []).get(short)).push(r.trip_id);
  }
  for (const r of csv(files['calendar_dates.txt'])) if (r.exception_type === '1') (dates.get(r.service_id) ?? dates.set(r.service_id, new Set()).get(r.service_id)).add(r.date);
  return { trips, byShort, dates };
}

export function lookupFromZip(bytes) {
  const wanted = new Set(['trips.txt', 'calendar_dates.txt', 'calendar.txt']);
  const files = unzipSync(bytes, { filter: f => wanted.has(f.name) });
  return lookupFrom(Object.fromEntries(Object.entries(files).map(([name, data]) => [name, strFromU8(data)])));
}

const activeOn = (lk, tripId, date) => lk.dates.get(lk.trips.get(tripId).serviceId)?.has(date) ?? false;

// The static trips an RT trip descriptor designates: itself when it carries a static id, otherwise the trips of its
// train number, only those running on start_date when there is one.
function resolve(lk, { tripId, startDate }) {
  if (lk.trips.has(tripId)) return [tripId];
  const siblings = lk.byShort.get(tripId.trim()) ?? [];
  return startDate ? siblings.filter(id => activeOn(lk, id, startDate)) : siblings;
}

const mostCommon = xs => [...xs.reduce((m, x) => m.set(x, (m.get(x) ?? 0) + 1), new Map())].sort((a, b) => b[1] - a[1])[0][0];

export function patchTripUpdates(entities, lk) {
  const out = [];
  for (const e of entities) {
    const trip = e.tripUpdate?.trip;
    if (!trip || lk.trips.has(trip.tripId)) { out.push(e); continue; }
    if (trip.scheduleRelationship === ADDED) {
      // a new run of a known train number: kept, with the route of that train so that consumers can place it
      const siblings = lk.byShort.get(trip.tripId.trim()) ?? [];
      if (!siblings.length) continue;
      trip.routeId ||= mostCommon(siblings.map(id => lk.trips.get(id).routeId));
      out.push(e);
      continue;
    }
    // scheduled or canceled: one update per static trip of that train running that day, none means dropped
    resolve(lk, trip).forEach((tripId, i) =>
      out.push({ ...e, id: i ? `${e.id}#${i}` : e.id, tripUpdate: { ...e.tripUpdate, trip: { ...trip, tripId } } }));
  }
  return out;
}

// today: the service day consumers resolve a trip against when the descriptor has no start_date. nigiri takes the
// UTC date of the feed header's timestamp, so the bridge passes it and only emits the trips running that day.
export function patchAlerts(entities, lk, today) {
  const out = [];
  for (const e of entities) {
    if (!e.alert) { out.push(e); continue; }
    const informed = e.alert.informedEntity.flatMap(ie => ie.trip?.tripId
      ? resolve(lk, { tripId: ie.trip.tripId, startDate: ie.trip.startDate || today }).map(tripId => ({ ...ie, trip: { ...ie.trip, tripId } }))
      : [ie]);
    if (informed.length) { e.alert.informedEntity = informed; out.push(e); }
  }
  return out;
}

function* csv(text) {
  const lines = text.split(/\r?\n/).filter(l => l.length);
  const header = fields(lines[0].replace(/^﻿/, ''));
  for (let i = 1; i < lines.length; i++) yield Object.fromEntries(fields(lines[i]).map((v, j) => [header[j], v]));
}

function fields(line) { // one record, quotes honoured; these files have no embedded newlines
  if (!line.includes('"')) return line.split(',');
  const out = []; let cur = '', quoted = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (quoted) { if (c !== '"') cur += c; else if (line[i + 1] === '"') { cur += '"'; i++; } else quoted = false; }
    else if (c === '"') quoted = true;
    else if (c === ',') { out.push(cur); cur = ''; }
    else cur += c;
  }
  out.push(cur);
  return out;
}
