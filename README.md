# sncf-gtfs-toolkit

Patches the SNCF open data GTFS feed with information the feed does not carry on its own:

1. **Transfers** (`transfers.txt`), computed from the SNCF transfer-time rules (IDH export), with `calendar_dates.txt` extended so that date-limited rules only apply on their dates.
2. **Route types** (`routes.txt`, `trips.txt`): `route_type` refined to the extended type of the brand serving each route, plus a `trip_route_type` on the trips that differ from their route (replacement buses on a train line).

The result is the original feed, unchanged except for those four files, written to `output/sncf_patched.zip`.

## Inputs

| Input | Source | Used for |
|---|---|---|
| SNCF GTFS feed | `Export_OpenData_SNCF_GTFS_NewTripId.zip` (opendatasoft) | Everything |
| SNCF transfer-time rules | `INFOTRAINS_Export_IDH.zip` (opendatasoft), files `Export_CONNECTION_TIMES.csv` and `Export_TRAIN_CONNECTION_TIMES.csv` | Transfers |
| `unusual_uic_referential.properties` | `src/main/resources`, bundled in the jar | Matching stations between the two datasets |

Both archives are downloaded on every run into `feeds/`.

## How the two datasets are matched

The rules and the feed do not describe things the same way, so a few conventions bridge them. Everything below is read from the feed itself.

**Station.** Rules identify a station by its 7-digit UIC code. A GTFS stop point id ends with the 8-digit UIC code (`StopPoint:OCETGV INOUI-87391003`). Dropping the last digit gives the 7-digit code for every French station and almost every foreign one. The few foreign stations (Germany, Spain, Italy, Switzerland) where the rules use an unrelated code are listed in `unusual_uic_referential.properties` as an explicit 8-digit to 7-digit mapping.

**Stop point.** A station has one stop point per brand or carrier serving it. The brand is the text between `OCE` and the UIC code in the stop point id: `TGV INOUI`, `OUIGO`, `Lyria`, `ICE`, `INTERCITES`, `INTERCITES de nuit`, `Train TER`, `Train` (OUIGO Train Classique), `TramTrain`, `Navette`, `Car TER`, `Car à réservation`. An unknown brand stops the run, because it means the feed format changed.

**Mode.** Rail (`F`) or road (`R`). Read from the trip id, which embeds `_F:` or `_R:`.

**RICS.** The railway undertaking code, for example `1187` for SNCF Voyageurs. It is the agency part of the trip id.

**Train number.** The trip headsign, with leading zeros removed.

**Feed validity.** `feed_start_date` and `feed_end_date` from `feed_info.txt`.

## Use case 1: transfers

### Station rules (`Export_CONNECTION_TIMES.csv`)

A station rule says: *between arrival station A and departure station B, for connections matching a criterion, the minimum transfer time is N minutes* (or the transfer is forbidden). A and B are usually the same station.

Each row carries exactly one criterion, given as an arrival/departure pair. Rows with a half-filled pair or with more than one criterion are logged and skipped.

| Criterion | Columns | What it selects at each station |
|---|---|---|
| Mode | `ARRIVAL_MODE`, `DEPARTURE_MODE` | Every stop point used by at least one trip of that mode |
| RICS | `ARRIVAL_RICS`, `DEPARTURE_RICS` | Every (route, stop point) pair used by trips of that railway undertaking. `ALL` selects every undertaking |
| Brand / carrier | `ARRIVAL_CONNECTION_TYPE`, `DEPARTURE_CONNECTION_TYPE` | Every stop point whose brand matches. `ALL` selects every brand |

Resolution is the same for all three: take the set selected on the arrival side, the set selected on the departure side, and emit one transfer per (arrival element, departure element) pair. Mode and brand rules produce stop-to-stop transfers. RICS rules produce route-to-route transfers, since the route is what tells undertakings apart in GTFS.

Brand matching is a case-insensitive "contains" with two exceptions:
- `TER` must not match `INTERCITES` (which contains the letters TER), so it is excluded explicitly.
- `OUIGO` also matches the `Train` brand, which is how OUIGO Train Classique is labelled in the feed.

If either side selects nothing at that station, the rule produces no transfer.

### Train rules (`Export_TRAIN_CONNECTION_TIMES.csv`)

A train rule says: *between arrival train X at station A and departure train Y at station B, the minimum transfer time is N minutes* (or the transfer is forbidden), *from START_DATE to END_DATE*.

Resolution: every trip whose train number is X and that stops at A, paired with every trip whose train number is Y and that stops at B. Each pair becomes a trip-to-trip transfer between the two stop points actually used by those trips.

### Transfer time and type

Both rule files use the same encoding:

| `MIN_DELAY` | GTFS `transfer_type` | GTFS `min_transfer_time` |
|---|---|---|
| N ≥ 0 minutes | `2` (minimum time required) | N × 60 seconds |
| `-1` | `3` (transfer not possible) | empty |

### Rules with dates: service ids and `calendar_dates.txt`

Train rules are the only ones with a validity period. GTFS has no date column on transfers, so the period is expressed through a `service_id`, the same way trips are dated.

1. The rule's dates are capped to the feed's validity period. A rule that does not overlap the feed at all is dropped.
2. Each matched trip pair gets one transfer row, valid on the union of the periods of every rule that matched it (see the next section).
3. Every distinct set of dates gets a new service id, allocated right after the highest service id already present in `calendar_dates.txt`, with the same zero-padded width (after `000222` comes `000223`). Trip pairs valid on the same dates share the same service id.
4. The transfer row carries that service id.
5. `calendar_dates.txt` is the original file, untouched, with one row per day of each new service appended, `exception_type = 1` (service active that day), grouped by service id in ascending order.

A forbidding rule (`transfer_type = 3`) also gets `exception_type = 1`. The service id only says *when the row applies*. Whether the row allows or forbids is carried by the transfer type.

`service_id` on a transfer is proposed for the GTFS spec in [google/transit#659](https://github.com/google/transit/pull/659) (still open). Consumers that do not support it yet will apply the transfer on every day.

### Several train rules for the same trip pair

The rules file keys train rules by train number and period, so the same train pair often appears several times with different periods. Seen in the IDH export of 2026-09-14 (files stamped 19:20), against GTFS `feed_version` 2026-09-14:

```
ARRIVAL_TRAIN;DEPARTURE_TRAIN;MIN_DELAY;ARRIVAL_STATION_UIC;DEPARTURE_STATION_UIC;START_DATE;END_DATE
881472;7836;150;8775108;8775100;09/11/2026;14/11/2026
881472;7836;150;8775108;8775100;19/09/2026;19/09/2026
```

Both rules match the same GTFS trips. Written naively, that gives two `transfers.txt` rows identical except for `service_id`, which the MobilityData validator reports as a `duplicate_key` error since `service_id` is not part of the transfers primary key it knows. So the rows are merged before writing:

- One row per (from stop, to stop, from trip, to trip). Its service covers the union of the rules' dates: here 19 September plus 9 to 14 November.
- When the rules disagree on `MIN_DELAY`, the most restrictive wins: `-1` (forbidden) beats any duration, otherwise the largest duration. Each such merge is logged as a warning with the train numbers, station, delays seen and delay kept.

### Conflicts between rules

Several rules can produce the same transfer. Transfers are grouped by their full identity (from/to stop, from/to trip, from/to route, service id, transfer type) and one is kept per group:

1. The one coming from the most specific rule wins. From most to least specific: train rule, one-to-one RICS or brand, one-to-all RICS or brand, all-to-all RICS or brand, mode.
2. If several rules of the same specificity remain, the one with the largest minimum transfer time wins, being the most restrictive.

### Filtering

Rules referring to a station that is not in the feed are dropped before anything else, and counted in the logs. Rows outside the feed's validity period are also counted.

### Output columns

`transfers.txt`: `from_stop_id`, `to_stop_id`, `transfer_type`, `min_transfer_time`, `from_route_id`, `to_route_id`, `from_trip_id`, `to_trip_id`, `service_id`.

Route and trip columns are only filled by the rule kind that produced the row (RICS rules fill routes, train rules fill trips). `service_id` is only filled by train rules.

## Use case 2: route types

GTFS `route_type` lives on the route, but an SNCF route can mix modes: a train line and the replacement buses running on it share the same route. The mode is only visible on the stop points, so it is recovered from them, at route level and at trip level.

1. For each route, collect the brands of every stop point served by its trips.
2. Map brands to extended route types:

| Brand in stop id | Extended route type |
|---|---|
| `TGV INOUI`, `OUIGO`, `Lyria`, `ICE` | 101 (high speed rail) |
| `INTERCITES` | 102 (long distance rail) |
| `INTERCITES de nuit` | 105 (sleeper rail) |
| `Train TER` | 106 (regional rail) |
| `Train` (OUIGO Train Classique) | 100 (railway) |
| `TramTrain` | 900 (tram) |
| `Navette` | 711 (shuttle bus) |
| `Car TER` | 701 (regional bus) |
| `Car à réservation` | 715 (demand and response bus) |

3. `routes.txt`: `route_type` becomes the extended type. A route served by one brand takes that brand's type. A route mixing brands keeps the family of its original `route_type`: type 2 (rail) served by `Train TER` and `Car TER` becomes 106, the rail one. Several brands in that family (`Navette` and `Car à réservation` on a bus route): the basic `route_type` is not precise enough to pick one, so the route keeps it and every trip gets a `trip_route_type`, logged as a warning. No brand in that family: the run stops, the feed changed shape.
4. `trips.txt`: each trip takes the brand of the first stop point it serves. When its extended type differs from its route's patched `route_type` (the `Car TER` trips of that line), it is written in `trip_route_type`, the [MBTA GTFS extension](https://github.com/mbta/gtfs-documentation/blob/master/reference/gtfs.md#tripstxt) for exactly this case. Otherwise the column is empty. [MOTIS](https://github.com/motis-project/motis) consumes it.

In the 2026-09-14 feed, 282 of 688 routes mix brands, every one of them a train brand plus a road brand on a `route_type` 2. The values are the extended route types used by Google and most routers.

## Running

Requires a JDK 25 toolchain (Gradle downloads it if missing) and network access.

```bash
./gradlew run
```

Downloads go to `feeds/`, results to `output/`: the four patched files and `sncf_patched.zip`.

## License

- **Code**: [MIT](LICENSE).
- **Data** (`unusual_uic_referential.properties` and the patched feed this tool produces): [Open Database License (ODbL) v1.0](https://opendatacommons.org/licenses/odbl/1-0/), see [LICENSE-ODbL](LICENSE-ODbL).
