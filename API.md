# Public data and research API

The free API is available at `/api/v1`. No account or key is required. The browser
Data Explorer at `/data` uses the same endpoints and generates JSON, CSV, curl
and Python examples for the current filters. `/api` is a documentation alias.
The interactive experiment UI is at `/lab`.

The executable contract is `/api/v1/openapi.json`
([source](tennis-stats/src/main/resources/public-api/openapi.json)).

## History

| Endpoint | Filters |
| --- | --- |
| `GET /api/v1/players` | `q` (2–100 characters), `country` (three letters), `active` (true/false) |
| `GET /api/v1/players/{id}` | One player profile |
| `GET /api/v1/players/{id}/ratings` | `fromDate`, `toDate` (ISO dates) |
| `GET /api/v1/matches` | `playerId`, `eventId`, `fromSeason`, `toSeason`, `surface`, `level`, `outcome` (all/completed) |
| `GET /api/v1/matches/{id}` | Result and available aggregate serve counts |
| `GET /api/v1/events` | `fromSeason`, `toSeason`, `surface`, `level` |
| `GET /api/v1/events/{id}` | One historical tournament event |
| `GET /api/v1/rankings` | `date`: latest stored ATP snapshot on or before that date (today by default) |
| `GET /api/v1/coverage` | Global recorded/completed/rated/valid-stat counts and observation dates |

All lists accept `limit` (1–200, default 50), `offset` (0–100000), and `format`
(json/csv). Season ranges default to 1968 through the current year and allow
seasons through next year. Ratings default to 1968-01-01 through today. Surface
codes are H/C/G/P; main-tour level codes are G/F/L/M/O/A/B/D/T.

JSON lists contain `data`, `pagination`, `links`, and `meta`. A one-row lookahead
determines `has_more`; it is not returned as data. Follow `links.next` (a relative
URL) or the HTTP Link header. At the offset cap, narrow the filters; the response
sets `offset_limit_reached` and omits the next link. CSV exports contain **one
page**, preserve numeric precision, escape text formulas, and provide a next-page
Link header where available. Empty CSV results have no inferred column schema.

Players are ordered by ID; matches and events by date then ID descending;
rankings by rank then player ID; rating histories by date descending. Dates are
ISO strings, and IDs are database identities. List ordering is deterministic,
but concurrent imports and corrections can shift offset pages. This is not an
immutable dataset or historical-vintage API.

Match and event results cover men's main-tour singles. Player and ranking
registries may include players without main-tour matches. Qualifying and
Challenger expansion are deferred. Women's tennis is out of scope.

Missing statistics and ratings are unknown, not zero; fields can be null or
absent. Match `w_`/`l_` statistics are winner/loser aggregate serve counts, not
point sequences. `outcome=completed` excludes all non-null special outcomes,
including retirements and walkovers. Some historical match dates use tournament
dates; per-match source/date precision is not yet exposed.

Coverage dates describe observations, not import times or completeness. The
optional `last_successful_ingestion_at` comes from the latest completed result
sync whose derived refresh succeeded. Missing checkpoint tables/rows leave it
unavailable. This is separate from ranking ingestion. Coverage has a dedicated
five-minute, single-entry cache, cleared by existing cache maintenance; list
responses permit one minute of HTTP caching. No per-filter API data caches grow
with public queries. Repository queries run in read-only transactions with a
20-second timeout.

Lists reject unknown/repeated query parameters, bad types and invalid ranges with HTTP
400 and `{"error":{"code":"invalid_request","message":"..."}}`; missing
individual records return 404. Cross-origin GET/POST access is enabled without
credentials. The legacy UI routes and `/api/analytics/{view}` remain available;
see [ANALYTICS.md](ANALYTICS.md) for their separate contract. The Analytics Lab's
"Get this data" button exposes the actual requests behind each comparison.

## Experiments

`GET /api/v1/lab/rules?pServe=0.65&pReturn=0.38` compares twelve combinations:
best-of-three/five, advantage/no-ad games, and standard/super/advantage deciding
sets. Probabilities are Player 1's point-win chances on serve and return and must
be between .05 and .95. A super tiebreak is first to ten, win by two, at 6–6 in
the deciding set. The model assumes stationary independent point probabilities.

`POST /api/v1/predictions/match` accepts:

```json
{"player1Elo":2200,"player2Elo":2000,"scale":400}
```

This is a logistic Elo baseline using supplied ratings, **not** the combined
match-prediction model. The scale is 200–800 and ratings are 500–3500.

`POST /api/v1/simulations` accepts:

```json
{
  "players": [
    {"id":1,"name":"Player A","elo":2200,"seed":1},
    {"id":2,"name":"Player B","elo":2100,"seed":2},
    {"id":3,"name":"Player C","elo":2000},
    {"id":4,"name":"Player D","elo":1900}
  ],
  "draw":"seeded",
  "iterations":200,
  "seed":1973,
  "scale":400
}
```

Fields contain 2–64 entrants, each with an Elo rating. Names and positive unique
IDs are optional and local to the experiment. Seeds, when present, are unique
and no larger than half the next power-of-two bracket size. Seeded redraws use
the existing seed-tier and bye-placement algorithm, 1–500 iterations, and an
integer random seed. Fixed draws pair adjacent inputs, require a power-of-two
field and exactly one iteration. Inputs never create database records.
Integer fields are validated exactly from the JSON number, so fractional values
cannot become valid IDs, seeds or iteration counts through floating-point rounding.

Round probabilities represent passing each round, including a bye; the last
entry equals the title probability. Each bracket is solved exactly with fixed
ratings. Seeded results average sampled brackets. These probabilities do not
include fatigue, injury, rating uncertainty or adaptation. Every experiment
returns its inputs, resolved seed/scale where relevant, model version and
assumptions. Calculations are bounded and synchronous; persistent background
jobs, season simulations and prospective forecast storage are deferred.

## Validation

Use Java 11 and the repository's [normal checks](CONTRIBUTING.md). Unit tests
exercise HTTP contracts, pagination, validation, CSV protection and scoring/
bracket probability invariants. `:tennis-stats:analyticsIntegrationTest` also
includes public API SQL fixtures, covering date selection, filters, missing
statistics, ordering and optional sync metadata. It requires a local disposable
PostgreSQL database and creates/drops only isolated fixture schemas.

No production database migration or data import is required by this change.
Deferred features and their acceptance criteria are in [ROADMAP.md](ROADMAP.md).
