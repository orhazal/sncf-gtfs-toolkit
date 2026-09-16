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

A static trip id looks like `OCESN865039F1187_F:TER:FR:Line::394b…::87775007:87775817:5:1315:20261211`. Most trip updates carry exactly that. Added and canceled trip updates, and every trip in service alerts, carry the internal id instead: `OCESN865039F`, the `OCESN` prefix, the train number and `F` (ferré) or `R` (route). It is the prefix of the static ids of that train, one per period the train runs.

The lookup is built from `trips.txt` and `calendar_dates.txt` of the raw SNCF GTFS, downloaded from opendatasoft. Its `Last-Modified` is checked every 5 minutes and the 4 MB zip is downloaded again when it changed.

| Entity | Rule |
|---|---|
| Trip update with a static id | Untouched |
| Trip update with an internal id, scheduled or canceled | Replaced by the static trips of that train running on `start_date`, one update per trip (entity ids get `#1`, `#2`…). None: dropped |
| Trip update with an internal id, added | Kept, with the `route_id` of the train's static trips so that consumers can place it. A train number with no static trip: dropped |
| Alert informed entity with an internal id | One informed entity per static trip of that train running on the feed's day, the UTC date of the header timestamp: that is the day a consumer resolves a trip against when the descriptor has no `start_date`, so trips of other periods could never match. No such trip: dropped. An alert left with no informed entity is dropped |

## Alerts, their period and the day they resolve on

An alert often covers weeks or months, works on a line for instance, and names its trains by internal id, without a date. The active period and the trip resolution are two different things for a consumer. MOTIS keeps the period as is, that is what decides when the alert shows. But to decide what to show it on, it has to attach each informed entity to one concrete run, a trip on a given day, and without `start_date` nigiri tests exactly one day: the UTC date of the feed header's timestamp ([`gtfsrt_resolve_run.h`](https://github.com/motis-project/nigiri/blob/master/include/nigiri/rt/gtfsrt_resolve_run.h)). There is no loop over other days.

So the bridge emits, for each train, only its static trips running on that day. Over the life of the alert this is enough: the feed is fetched again every 30 seconds, and every day the alert gets attached to that day's run of the train. Emitting the trips of the train's other periods, as a first version did, only produced entities that could never resolve, 77% of them in the first test against MOTIS.

What is lost, and was lost with the raw feed too since it carries no date either: an alert on a train that does not run today is attached to nothing today, and a route search for a future day does not show the alert on that day's run, because only today's run was ever resolved. Covering future days would mean one informed entity per train and per day of the period, each with its `start_date`, which nigiri honours. On the feeds seen so far that is about 9,600 entities per day covered, 15 MB for a 14 day window. Not done, to be decided if alerts on future runs matter.

Measured on the feeds of 2026-09-16 against the GTFS of 2026-09-15:

| | Entities | Trips in informed entities | Size |
|---|---|---|---|
| Trip updates | 1,435 → 1,290 | | 1.0 MB |
| Service alerts | 497 → 429 | 9,666 → 5,840 | 1.0 → 1.4 MB |

Not handled yet: added trips reference stations (`StopArea:OCE87476606`) where scheduled ones reference platforms (`StopPoint:OCETGV INOUI-87192039`). Whether MOTIS accepts that is to be checked.

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
