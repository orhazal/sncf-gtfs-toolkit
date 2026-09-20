# GTFS-RT bridge

Takes the SNCF GTFS-RT feeds published by the PAN, rewrites the trip ids they carry into the ids of the SNCF GTFS, and serves the result. One Node process, no database, no files: the two patched feeds live in memory and are swapped whole every 30 seconds, so a consumer always gets a complete feed.

| Endpoint | Content |
|---|---|
| `/trip-updates` | Patched [trip updates](https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-trip-updates), protobuf |
| `/service-alerts` | Patched [service alerts](https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-service-alerts), protobuf |
| `/` | JSON status: 200 when both feeds were refreshed in the last 5 minutes, 503 otherwise |
| `/stats` | The status plus counters: refreshes and errors per feed with the last error, lookup size and load time, process uptime and memory |

A feed answers 503 until its first fetch. When the PAN fails, the previous feed stays served.

## The trip ids

A static trip id looks like `OCESN865039F1187_F:TER:FR:Line::394b…::87775007:87775817:5:1315:20261211`. Most trip updates carry exactly that. Added trip updates, some canceled ones and every trip in service alerts carry the internal id instead: `OCESN865039F`, that is `OCE`, two letters for the network, the train number, and `F` (ferré) or `R` (route). Four networks appear in the current feed, `SN` with 36,168 static trips, `SA` with 1,181, `EA` with 388 and `LO` with 153. The internal id is the prefix of the static ids of that train, one per period the train runs.

The lookup is built from `trips.txt` and `calendar_dates.txt` of the raw SNCF GTFS, downloaded from opendatasoft. Its `Last-Modified` is checked every 5 minutes and the 4 MB zip is downloaded again when it changed.

| Entity | Rule |
|---|---|
| Trip update with a static id | Untouched |
| Trip update with an internal id, scheduled or canceled | Replaced by the static trips of that train running on `start_date`, one update per trip (entity ids get `#1`, `#2`…). None: dropped |
| Trip update with an internal id, added | Dropped: see below |
| Alert informed entity with an internal id | One informed entity per static trip of that train that runs at least once while the alert is in effect, whatever day that is. No such trip: dropped. An alert left with no informed entity is dropped |

The trip updates of 2026-09-20 at 19:12 UTC, by `schedule_relationship`:

| | Received | With a static id | With an internal id | Served |
|---|---|---|---|---|
| absent | 784 | 781 | 3 | 781 |
| `CANCELED` | 13 | 11 | 2 | 11 |
| `ADDED` | 94 | 0 | 94 | 0 |

SNCF omits the field for a normal update rather than setting `SCHEDULED`, which the specification reads the same way, and no other value has ever appeared. The handful of updates lost in each category carry a train number with no static trip at all, on any date, so their cancellation reaches no consumer.

Most of those updates change nothing. In the same feed, 635 announce every event on time and 256 carry a delay, the worst being six hours, while 25 skip at least one stop. They are predictions rather than corrections, refreshed continuously: half of them were stamped within 66 minutes of the feed. A trip update saying zero delay is the operator confirming the timetable, which is why a consumer marks the run as having realtime data even when no time moved.

A train number is unique on a given day: over the 799,730 train number and date combinations of the SNCF calendar, none has two trips running. The expansion of a scheduled or canceled update therefore yields one entity, never more, and the suffix rule only guards against a feed where that stops being true. It also means the `start_time` carried over from the original update belongs to the trip it lands on. Across periods it is different, 3,334 of the 18,074 train numbers have trips departing at different times, which is why the day filter is on the calendar and not on the time.

## Added trips are dropped

About a hundred updates per feed announce an extra train, with `schedule_relationship` ADDED, and none of them can be placed. Their train number is absent from the static GTFS, so there is no route and no sibling trip to borrow one from, and some ids do not even have the internal shape, `OCESNDTS107F` for instance. No SNCF update carries `route_id` or `direction_id` either, whatever the relationship. A consumer would receive a trip it cannot attach to anything, so the bridge drops them.

The rule is not unconditional: an added trip whose train number does exist in the static GTFS is kept and given that train's `route_id`. No feed seen so far has contained one. If that changes, note that added trips reference stations (`StopArea:OCE87476606`) where scheduled ones reference platforms (`StopPoint:OCETGV INOUI-87192039`), which consumers may or may not accept.

Worth knowing for whoever revisits this: ADDED is [deprecated in the specification](https://gtfs.org/documentation/realtime/reference/#enum-schedulerelationship), which asks for NEW when the extra trip is unrelated to an existing one and DUPLICATED when it copies a scheduled trip.

## Alerts name a train, not a run

An alert often covers weeks or months, works on a line for instance, and names its trains by internal id, without a date. Nothing in the feed ties it to a day: the [entity selector](https://gtfs.org/documentation/realtime/reference/#message-entityselector) carries no time, the [alert's own periods](https://gtfs.org/documentation/realtime/reference/#message-alert) do. So the bridge replaces an internal id by the static trips of that train number rather than by one run, and leaves the timing to the alert.

That puts a requirement on the consumer: a `trip_id` without `start_date` has to apply to the trip on all of its service days. nigiri resolved such a descriptor against one day only, the UTC date of the feed header's timestamp, which is [motis-project/nigiri#415](https://github.com/motis-project/nigiri/issues/415). Against a consumer that still does this, the extra trips simply never resolve and the alerts that do show are the ones the train runs today, as before.

A train is only kept when it runs at least once inside one of the alert's periods, those of `impact_period` or, when the producer sends neither of the two newer fields, those of `active_period`. Several ranges are handled one by one, the four weekends of works in a month for instance, and a train running only between them is left out. Without that filter the expansion emits every trip of the train number, including those of periods the alert cannot reach: 25,625 informed entities and 3.74 MB instead of the figures below.

Measured on the same feeds, against the GTFS of 2026-09-19:

| | Entities | Trips in informed entities | Size |
|---|---|---|---|
| Trip updates | 891 → 792 | | 0.59 → 0.57 MB |
| Service alerts | 476 → 443 | 9,447 → 14,140 | 1.13 → 2.45 MB |

Of the 33 alerts that disappear, one names a train number absent from the static GTFS and 32 name trains whose trips all run outside the alert's own period. The second group is what the filter is for: works announced until 9 October on a train whose every trip starts on 11 October would otherwise hang the alert on October and November runs the disruption never touches. Most of the others are alerts whose period has just ended, their timetable version being gone from the GTFS.

Every alert in that feed carries exactly one range, and none of them uses `impact_period` or `communication_period`.

## Running

```bash
cd scripts && npm ci && npm test && npm start        # then http://localhost:8080/
```

`PORT` changes the port.

A systemd unit is provided for a Linux host with Node 20 or later, with the repository cloned in `/opt/sncf-gtfs-toolkit`:

```bash
cd /opt/sncf-gtfs-toolkit/scripts && npm ci
sudo cp sncf-gtfs-rt-bridge.service /etc/systemd/system/ && sudo systemctl enable --now sncf-gtfs-rt-bridge
```

The unit listens on port 80 as an unprivileged transient user through `AmbientCapabilities`. Behind a reverse proxy, set `PORT` in the unit and drop that line.

## Operating

```bash
sudo systemctl status sncf-gtfs-rt-bridge                                   # running since when
sudo journalctl -u sncf-gtfs-rt-bridge --no-pager --since today             # GTFS reloads and fetch errors
sudo journalctl -u sncf-gtfs-rt-bridge -f                                   # follow live
curl -s localhost/                                                          # status, 200 when both feeds are fresh
curl -s localhost/stats                                                     # counters
```

Update after a `git pull`:

```bash
cd /opt/sncf-gtfs-toolkit && git pull && (cd scripts && npm ci) && sudo systemctl restart sncf-gtfs-rt-bridge
```
