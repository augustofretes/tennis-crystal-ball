# Analytics Lab

The Lab is available at `/analytics`; `/eraExplorer` is an alias. It extends the
existing Java/Spring/Thymeleaf application and uses the existing PostgreSQL tables.
It does not require schema migrations, new feeds or production data repairs.
Navigation, player profiles and tournament event pages link to the new views.

## Comparisons and their interpretation

### Era Explorer

Choose two periods, with optional surface, indoor/outdoor and tournament-level
filters. Periods can be kept equal in length or selected independently. Drag the
timeline or use its keyboard-accessible range controls. The field curves retain
their original positions when the leading players are excluded from the plot.
Click a curve position or activate it with Enter to inspect its contributors.
The all-surfaces comparison opens four additional plots.

The yearly cohort contains players with a completed match in that year and scope.
Ranking snapshots are ordered by the selected surface Elo, indoor/outdoor Elo, or
overall Elo. A combined surface-and-conditions filter uses the surface rating and
restricts the eligible cohort; the database has no joint surface/conditions rating.
This is a descriptive yearly cohort, not a forecast of who was known to participate
at a particular point during that year.

Within a year, stored ranking dates have equal weight. Across a period, years have
equal weight. The No. 1, top-three and top-eight averages measure the elite;
positions 4–10, 11–32 and 33–100 describe depth. A band requires all its positions.
Missing years and bands are not filled with zero or the starting Elo rating.
The shaded range is the minimum/maximum of annual averages, not a model confidence
interval. A partial season still contributes a year; inspect snapshot counts and
coverage before comparing it with completed seasons.

Contender density counts players in the top 200 within the selected Elo distance
of the leader on the same ranking date. Title concentration is the sum of squared
annual title shares. Finals of individual tournaments count titles; team events
do not. Elo upset rates use completed matches with both recorded ratings. These
competitive-balance measures are distinct from estimated playing strength.

The reference-title scenario places a chosen fixed-rating player into a random
32-player field, replacing position 32. Its 31 opponents use the mean ratings of
contemporaneous positions 1–31. Exact knockout probabilities are averaged over
100 deterministic random brackets. The scenario is unavailable if fewer than 32
positions have observations. This scenario is not a reconstructed historical draw.
The probability scale can be changed to test sensitivity; changing it does not
change the underlying Elo curves.

### Performance and opposition

Expected wins sum probabilities from recorded **pre-match overall Elo**:
`p = 1 / (1 + 10^((opponent Elo - player Elo) / 400))`.
Surface/conditions/level filters select the matches; this view does not silently
substitute surface snapshot ratings for recorded overall match ratings.

Excess wins subtract expected wins from wins **in the same rated matches**.
Unrated matches remain in the total record and are excluded from both sides of
the expected-win calculation. The 95% expected-residual range is centered at zero
under the model, uses `1.96 * sqrt(sum(p*(1-p)))`, and is clipped to possible win
counts. It is a normal predictive approximation conditional on fixed independent
probabilities, not a confidence interval for a player's ability. Shrunk excess
per match adds 20 neutral prior matches to the denominator.

The chart can show excess wins, average opponent Elo, or ATP top-ten encounters
over time. Opponent strength and ATP rank are taken from the match record. Match
drilldowns identify the date, event, players, ratings, ranks, score and residual.

### Era-relative statistics

Serve/return point rates are compared with point-weighted baselines for the same
season and surface within the selected scope. Player selection does not restrict
the comparison baseline. Above-baseline point rates use 100 baseline-rate prior
points. Aces and hold rates show their raw values and matching baselines.

Only complete matches with valid positive point denominators contribute. Missing
ace or break-point figures do not contribute their denominators to those rates.
Invalid ace and hold counts are excluded. Wilson intervals for raw point rates
are approximate: points in a match are correlated. No opponent adjustment or
physical court-speed measurement is claimed.

### Historical Draw Explorer

The event selector lists up to 300 recent events in the selected scope. A linked
event ID can open an older event directly. Entry Elo is the first recorded
pre-match rating for each entrant in the event, never a later-round replacement.

For a consistent recorded knockout bracket of 2–128 entrants with all entry
ratings, exact title probabilities integrate over potential opponents. Seeded
redraws preserve seed tiers, separate leading seeds across bracket sections, and
assign byes to the highest seeds first. A deterministic seed makes repeated
comparisons reproducible. The displayed 95% redraw margin describes Monte Carlo
bracket-sampling error, not uncertainty in ratings or model assumptions.

Round-robin events, incomplete/inconsistent brackets and missing entry ratings
retain their recorded routes and explain why redraw probabilities are unavailable.
The standard seed tiers do not reproduce every historical tournament convention.
Entry-rating probabilities are held fixed through the draw. Walkovers contribute
one for the advancing player and zero for the withdrawing player in recorded-route
probabilities. Other special outcomes remain visible; injuries are not modeled.

### Surface convergence, matchups and sustained peaks

Surface overlap compares top tens on the same ranking date using Jaccard overlap
(intersection/union). Rank correlation uses common top-100 players and requires
at least ten. Annual values average the available dates. Specialist-title shares
require a pre-final surface rating at least 150 Elo above the mean of at least two
other available surface ratings. Ratings must predate the final by less than a
year. Missing ratings are excluded from the classified-title denominator.

The matchup matrix uses actual minus expected wins from contemporaneous overall
Elo within the selected scope. Colors use the 20-match shrinkage and stay neutral
below the minimum sample. Expected residual ranges use the same normal
approximation as performance comparisons. Residuals do not establish a causal
matchup advantage or independently adjust for age. Without selected players,
the ten most active players in scope are shown; up to twelve can be selected.

Sustained peaks use the best mean weekly rating over 52 weeks, three years or
five years. A qualifying window is entirely within the selected range, spans
nearly the full period, has at least 40 snapshots per year and meets the chosen
minimum completed-match activity per year of window length. Each player appears
once. Snapshot and match counts are shown rather than imputing missing weeks.

### Common calendar

Observed win percentages are reweighted to a common surface mix. Positive weights
are normalized, but a required surface without the minimum sample makes that
comparison unavailable; missing surfaces are never silently redistributed.
The displayed observed record uses the qualifying surface samples shown in the
table. Approximate 95% bounds combine per-surface Wilson intervals with a
Bonferroni correction, so perfect small samples retain nonzero uncertainty.

The separate model scenario averages available surface Elo and estimates wins
per 100 matches against a fixed reference opponent on each surface. It is not an
estimate of historical titles or a causal effect of a different calendar. Ratings,
opponents and playing opportunities differ across eras.

### Prediction report card

The Elo baseline evaluates every completed match with both ratings in scope.
Favorite probabilities are grouped into five bins from 50–60% through 90–100%,
with season and surface breakdowns. Equal-rating favorites use player ID order,
independently of the winner. Brier score and log loss use canonical player ordering.

Current-model replay uses a deterministic reservoir sample of at most 200 eligible
matches. Inputs have a full-day embargo before the recorded match date. Replays
use current model weights, which may have been tuned on historical results; they
are explicitly retrospective, not an out-of-sample or prospective evaluation.
Available, sampled, skipped and evaluated counts are shown. Wilson intervals cover
observed rates, not model uncertainty.

## Coverage, links and exports

Coverage separates recorded matches, completed matches, rated matches and valid
detailed-stat matches. Recorded dates describe dataset coverage, not ingestion
timestamps. Completed comparisons exclude retirements, defaults, walkovers and
abandoned matches. Title and recorded-route views state their separate rules.

Filter state, selected players and chart measures are stored in the URL. CSV
exports contain the rows displayed, preserve numeric precision and escape formula
prefixes in text cells. Player-season tables return at most 500 rows, sustained
peak rankings at most 100; truncation is disclosed. Narrow the scope to inspect
additional results. SVG charts use local code, with keyboard-accessible
contributor controls and no external chart-loader dependency.

## API and implementation

All endpoints are read-only GET requests under `/api/analytics/{view}`:
`coverage`, `era`, `contributors`, `performance`, `stats`, `surfaces`, `peaks`,
`calendar`, `matchups`, `matches`, `events`, `draw`, and `calibration`.

Shared parameters: `fromSeason`, `toSeason`, `surface` (`H/C/G/P` or empty),
`indoor` (`true/false` or empty), `level`, `minMatches` (1–200), `playerId`.
Additional parameters: `position` (1–200), `distance` (50–400), `scale` (200–800),
`reference` (1200–2800), `windowYears` (1/3/5), `players` (up to 12 comma-separated
IDs), four surface `*Weight` values (0–100), `eventId`, `iterations` (50–1000),
`model` (`elo/current`), and `statsOnly` (`true/false`, match drilldowns only).
Statistics drilldowns retain their row's season, surface and valid-point sample.
Invalid requests return HTTP 400 with an error.

Query values are bound parameters. Dynamic rating columns come from an allowlist.
Matchup analysis and calibration stream history in read-only transactions. Each
analytics cache holds at most eight comparisons for 15 minutes, uses the existing
cache-clearing machinery, and registers existing cache metrics.

## Validation

Unit tests run with the normal Java 11 build. PostgreSQL integration tests opt in
through `ANALYTICS_TEST_DB_URL`, which accepts localhost/127.0.0.1 only. Install
the application's base schema in a disposable database first. The tests create
and drop a unique schema, clone the relevant table structures, and insert only
explicit synthetic fixtures; they never change public data.

```sh
ANALYTICS_TEST_DB_URL=jdbc:postgresql://localhost:5432/tcb_analytics_test \
ANALYTICS_TEST_DB_USER=tcb \
./gradlew --no-daemon --max-workers=2 -PgradleGitPropertiesVersion=2.4.2 \
  test :tennis-stats:analyticsIntegrationTest :tennis-stats:distTar :data-load:installDist
```

Use `ANALYTICS_TEST_DB_PASSWORD` when required. CI provisions a disposable
PostgreSQL service, installs the base schema and runs these tests. Golden fixture
checks cover denominators, missing ratings, zero point totals, scope/cohort filters,
full sustained windows and SQL compatibility. Mathematical tests check bracket
probability conservation, seed tiers, byes, entry-rating leakage, uncertainty,
calendar completeness and prediction replay cutoff/sampling rules.
