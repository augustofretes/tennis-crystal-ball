# Men's tennis successor: enhancement backlog

The public data API, Data Explorer, Analytics Lab request reproduction, scoring
laboratory and bounded tournament playground are implemented in this change.
The items below are deferred enhancements with explicit acceptance criteria.
Women's tennis is outside this project's scope.

## Import point and shot histories for match replay and playing-style exploration

Requires new point-by-point and Match Charting Project ingestion.

- Import versioned men's source snapshots with attribution, identity resolution,
  match-date precision, point order and scoring/aggregate reconciliation checks.
- Build decisive-point and comeback replay with missing-coverage indicators.
- Support changing one point and simulating the subsequent match, with stated
  point-probability assumptions.
- Add playing-style fingerprints and historical lookalikes only where shot
  coverage supports them; disclose charted-match selection bias.

## Extend men's coverage to qualifying and Challenger events

Requires reviewed schema/ingestion work and a data backfill.

- Extend event, entry, round and identity mappings with reversible import tools.
- Track qualifying-to-main-draw transitions and prevent duplicate identities.
- Expose explicit level filters while preserving main-tour defaults.
- Validate cross-level rating initialization and coverage with database fixtures.

## Archive prospective forecasts and publish a model benchmark

Requires upcoming fixtures and durable forecast storage; the current worker
consumes completed results.

- Store forecasts before matches, with cutoff time, inputs and model/weight hash.
- Resolve outcomes without overwriting predictions; handle cancellations,
  retirements, corrections and missing outcomes explicitly.
- Publish calibration, Brier score and log loss by surface/level/sample size,
  with a simple baseline and a read-only archive API.
- Define bounded community-model submissions and evaluate on future matches.

## Add historical data vintages and a tennis time machine

Requires temporal data/version storage; the initial API uses today's corrected
history rather than recreating what was known at a historical point.

- Distinguish match/event, ingestion and correction times.
- Store immutable dataset/model versions with reproducible pagination/exports.
- Build an explicit vintage/as-of contract and a reveal-the-future interface.
- Validate model training cutoffs and prevent future-data leakage.

## Expand tournament experiments into season and career scenarios

Requires new forecasting models and persistent asynchronous job infrastructure.

- Add bounded jobs with quotas, cancellation, retention, versioned inputs/seeds.
- Specify schedules, event entry, rating evolution and ranking-points rules.
- Model alternative calendars and rival removal with outcome distributions and
  sensitivity analyses; separate assumptions from causal claims.
- Validate age/stage-matched historical cohorts on held-out data before exposing
  career trajectories and forecasts.

## Map GOAT ranking robustness across user preferences

Builds on existing configurable scoring; deferred behind the public data and
bounded experiment foundation.

- Publish the parameter space, normalization and scoring components.
- Show leaders across preference regions, rank ranges and changes that flip them.
- Explore Pareto-efficient profiles with disclosed sampling/optimization limits.
- Make configurations shareable, exportable and reproducible through the API.

## Build saved research investigations and reusable public API clients

Builds on the OpenAPI contract and current request-generation interfaces.

- Add structured hypotheses/cohorts, sample checks and contributing-match links.
- Publish Python/JavaScript clients and runnable notebooks.
- Add an MCP interface backed by the same bounded operations.
- Evaluate natural-language query construction with visible definitions and
  validated structured execution rather than unrestricted SQL.

## Complete mobile, shareable-filter and download coverage across legacy pages

The initial implementation modernizes the new exploration pages and adds profile
data links plus Analytics Lab request reproduction. Legacy parity remains work.

- Audit narrow screens, keyboard workflows and assistive-technology support.
- Persist filters and add exact-data/request links to remaining charts/tables.
- Preserve sorting, navigation and record-to-match drilldowns.
- Extend the public API to records, derived statistics and rivalry summaries,
  with endpoint-specific schemas and regression fixtures.

## Enrich venue conditions, workload and public data provenance

Requires venue curation, new enrichments and a public per-record provenance model.

- Curate venue coordinates, altitude and historical changes; join weather with
  attribution, temporal precision and coverage checks.
- Derive rest, workload and travel estimates only where dates support them;
  distinguish statistical associations from causal effects.
- Expose per-match source URLs, date precision and correction history.
- Track ranking-ingestion freshness independently of result checkpoints.

## Add tennis discovery games using historical data

Builds on stable data and reproducible experiment infrastructure.

- Add anonymized career-curve guesses, season/rivalry identification and
  forecast-a-draw challenges.
- Use seeded puzzles with explanations and underlying-record links.
- Keep games free, account-free, responsive and keyboard accessible.
- Prevent future information leaking into historical forecasting challenges.
