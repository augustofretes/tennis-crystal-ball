# Weekly result ingestion

`sync-recent-results.groovy` reconciles ESPN's ATP singles scoreboard with Tennis
Abstract's full player histories. The latter provides raw serve counts, player
identifiers, tournament IDs and match format. Rankings are not imported: the
weekly ranking page is only used as a directory of player-profile URLs.

The worker reads PGHOST, PGPORT, PGDATABASE, PGUSER and PGPASSWORD from Railway
reference variables. Use `railway.sync.toml` for the dedicated worker service.
`SYNC_APPLY` defaults to false; set it to `true` after reviewing the logged
`IMPORT_PLAN`. No public domain is needed. Use Monday 11:00 UTC (06:00 Cancun)
for the weekly cron after the initial backfill succeeds.

Optional bounds: SYNC_FROM and SYNC_THROUGH, in YYYY-MM-DD form. With no override,
the worker starts 21 days before the last successful checkpoint, or 21 days
before the last stored tournament when bootstrapping. It requests completed
singles matches only, including completed matches at tournaments in progress.
Unplayed matches, qualifying and doubles are excluded. Historical match dates
come from ESPN where matched; Tennis Abstract's tournament date is retained
otherwise and explicitly marked `tournament` in result_sync_match.date_precision.

Requests are serialized and spaced out. A 429 stops the run. A failed run must
not advance the checkpoint. Cache files in SYNC_CACHE_DIR avoid repeated requests
within a run; SYNC_REUSE_CACHE=true is for controlled retries and offline review,
not normal weekly operation.

Each run validates source agreement, identities and tournament mappings before
writing. Missing ESPN results or conflicting scores/winners stop the import.
The transaction inserts missing matches, set scores and raw statistics, then
records provenance in result_sync_match and a checkpoint in result_sync_run.
Existing matches are not overwritten. A database advisory lock covers ingestion
and derived computations. An interrupted derived refresh is retried even when
all matches were already imported. Elo, materialized statistics and records are
recomputed before marking the checkpoint complete.

Run parser checks with the existing data-load distribution:

```sh
java -cp 'data-load/build/install/data-load/lib/*' groovy.ui.GroovyMain \
  scripts/sync-recent-results.groovy --test
```

`--collect FROM THROUGH` writes collection.json and `--plan collection.json`
validates that collection against PostgreSQL without writing. `--apply` applies
only the ingestion stage; normal production runs use `--sync` to also refresh
derived data. Never publish source caches or database credentials to the repo.
