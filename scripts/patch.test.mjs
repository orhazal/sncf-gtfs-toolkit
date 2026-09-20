import test from 'node:test';
import assert from 'node:assert/strict';
import { lookupFrom, patchTripUpdates, patchAlerts } from './patch.mjs';

const T = (train, mode, date, net = 'SN') => `OCE${net}${train}${mode}1187_${mode}:TER:FR:Line::L${train}::87000001:87000002:1:1200:${date}`;
const lk = lookupFrom({
  'trips.txt': ['route_id,service_id,trip_id,trip_headsign', `RA,S1,${T(100, 'F', 20260101)},100`, `RA,S2,${T(100, 'F', 20260301)},100`, `RB,S1,${T(200, 'R', 20260101)},200`, `RC,S1,${T(300, 'F', 20260101, 'SA')},300`].join('\n'),
  'calendar_dates.txt': ['service_id,date,exception_type', 'S1,20260916,1', 'S2,20260917,1'].join('\n'),
});
const tu = (tripId, scheduleRelationship, startDate = '20260916') => ({ id: tripId, tripUpdate: { trip: { tripId, startDate, scheduleRelationship } } });

test('trip updates', () => {
  const out = patchTripUpdates([
    tu(T(100, 'F', 20260101), 0),   // static id: kept as is
    tu('OCESN100F', 3),             // train 100 canceled on the 16th: the S1 trip
    tu('OCESN100F', 3, '20260918'), // canceled on a day it does not run: dropped
    tu('OCESN200R', 1),             // added run of train 200: kept, with the route of train 200
    tu('OCESN999F', 1),             // added unknown train: dropped
    tu('OCESN999F', 3),             // canceled unknown train: dropped
  ], lk);
  assert.deepEqual(out.map(e => [e.tripUpdate.trip.tripId, e.tripUpdate.trip.routeId]),
    [[T(100, 'F', 20260101), undefined], [T(100, 'F', 20260101), undefined], ['OCESN200R', 'RB']]);
});

test('service alerts', () => {
  const out = patchAlerts([
    { id: 'a', alert: { informedEntity: [{ trip: { tripId: 'OCESN100F' } }, { stopId: 'StopArea:OCE87000001' }] } }, // every trip of train 100
    { id: 'b', alert: { informedEntity: [{ trip: { tripId: 'OCESN100F', startDate: '20260917' } }] } }, // a date, when there is one, still filters
    { id: 'c', alert: { informedEntity: [{ trip: { tripId: 'OCESN999F' } }] } }, // nothing left: dropped
    { id: 'd', alert: { activePeriod: [{ start: 1789516800, end: 1789603199 }], informedEntity: [{ trip: { tripId: 'OCESN100F' } }] } }, // 20260916 only: the S1 trip
    { id: 'e', alert: { informedEntity: [{ trip: { tripId: 'OCESA300F' } }] } }, // another network prefix
  ], lk);
  assert.deepEqual(out.map(e => e.alert.informedEntity.map(ie => ie.trip?.tripId ?? ie.stopId)),
    [[T(100, 'F', 20260101), T(100, 'F', 20260301), 'StopArea:OCE87000001'], [T(100, 'F', 20260301)], [T(100, 'F', 20260101)], [T(300, 'F', 20260101, 'SA')]]);
});
