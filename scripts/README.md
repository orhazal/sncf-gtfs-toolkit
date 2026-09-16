# Scripts

Two companion programs of the toolkit. Both read the source URLs from [`data/config.json`](../data/config.json).

- **The GTFS-RT bridge**, [`bridge.mjs`](bridge.mjs) and [`patch.mjs`](patch.mjs): rewrites the trip ids of the SNCF GTFS-RT feeds into the ids of the SNCF GTFS and serves the result.
- **The release trigger**, [`release-trigger.sh`](release-trigger.sh): launches the [release workflow](../.github/workflows/release.yml) when SNCF publishes new data.

## GTFS-RT bridge

Takes the SNCF GTFS-RT feeds published by the PAN, rewrites the trip ids they carry into the ids of the SNCF GTFS, and serves the result. One Node process, no database, no files: the two patched feeds live in memory and are swapped whole every 30 seconds, so a consumer always gets a complete feed.

| Endpoint | Content |
|---|---|
| `/trip-updates` | Patched [trip updates](https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-trip-updates), protobuf |
| `/service-alerts` | Patched [service alerts](https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-service-alerts), protobuf |
| `/` | JSON status: 200 when both feeds were refreshed in the last 5 minutes, 503 otherwise |

A feed answers 503 until its first fetch. When the PAN fails, the previous feed stays served.

### The trip ids

A static trip id looks like `OCESN865039F1187_F:TER:FR:Line::394b…::87775007:87775817:5:1315:20261211`. Most trip updates carry exactly that. Added and canceled trip updates, and every trip in service alerts, carry the internal id instead: `OCESN865039F`, the `OCESN` prefix, the train number and `F` (ferré) or `R` (route). It is the prefix of the static ids of that train, one per period the train runs.

The lookup is built from `trips.txt` and `calendar_dates.txt` of the raw SNCF GTFS, the `gtfs_url` of the config. Its `Last-Modified` is checked every 5 minutes and the 4 MB zip is downloaded again when it changed.

| Entity | Rule |
|---|---|
| Trip update with a static id | Untouched |
| Trip update with an internal id, scheduled or canceled | Replaced by the static trips of that train running on `start_date`, one update per trip (entity ids get `#1`, `#2`…). None: dropped |
| Trip update with an internal id, added | Kept, with the `route_id` of the train's static trips so that consumers can place it. A train number with no static trip: dropped |
| Alert informed entity with an internal id | One informed entity per static trip of that train, every period. A train number with no static trip: dropped. An alert left with no informed entity is dropped |

Measured on the feeds of 2026-09-16 against the GTFS of 2026-09-15:

| | Entities | Trips in informed entities | Size |
|---|---|---|---|
| Trip updates | 1,435 → 1,290 | | 1.0 MB |
| Service alerts | 497 → 487 | 9,666 → 25,743 | 1.0 → 3.7 MB |

Not handled yet: added trips reference stations (`StopArea:OCE87476606`) where scheduled ones reference platforms (`StopPoint:OCETGV INOUI-87192039`). Whether MOTIS accepts that is to be checked.

## Release trigger

Every 5 minutes, the script reads the `Last-Modified` of the GTFS and of the IDH transfer rules, builds the tag the workflow would use (`2026-09-15T18-36-43Z_2026-09-16T12-37-39Z`), and when no release has that tag, dispatches the workflow on `master`. The workflow keeps its own check as its first step. A tag is dispatched at most once an hour, so a run that fails does not start a new run every 5 minutes, and a transient failure is retried.

Needs `curl`, `jq`, and `GITHUB_TOKEN`: a fine-grained personal access token restricted to the repository with the permission *Actions: read and write*. Without it the script still computes the tag and checks the release, and fails at the dispatch.

## Running

```bash
cd scripts && npm ci && npm test && npm start        # the bridge, then http://localhost:8080/
scripts/release-trigger.sh                           # one pass of the trigger
```

`PORT` changes the bridge port, `GITHUB_REPO=owner/repo` points the trigger at a fork.

Systemd units are provided for a Linux host with Node 20 or later, with the repository cloned in `/opt/sncf-gtfs-toolkit`:

```bash
cd /opt/sncf-gtfs-toolkit/scripts && npm ci
sudo cp sncf-gtfs-rt-bridge.service sncf-release-trigger.service sncf-release-trigger.timer /etc/systemd/system/
sudo install -m 600 /dev/null /etc/sncf-release-trigger.env && sudoedit /etc/sncf-release-trigger.env   # GITHUB_TOKEN=github_pat_…
sudo systemctl enable --now sncf-gtfs-rt-bridge sncf-release-trigger.timer
```

The bridge unit listens on port 80 as an unprivileged transient user through `AmbientCapabilities`. Behind a reverse proxy, set `PORT` in the unit and drop that line. The timer runs the trigger every 5 minutes, `systemctl list-timers sncf-release-trigger.timer` shows the next run, and `journalctl -u sncf-release-trigger` what it did.
