# Weekly result ingestion

`sync-recent-results.groovy` reconciles ESPN's ATP singles scoreboard with Tennis
Abstract's full player histories. The latter provides raw serve counts, player
identifiers, tournament IDs and match format. Rankings are not imported: the
weekly ranking page is only used as a directory of player-profile URLs.

The worker reads PGHOST, PGPORT, PGDATABASE, PGUSER and PGPASSWORD from Railway
reference variables. The dedicated Railway worker uses the repository's Dockerfile
with start command `java -Xmx768m -cp '/app/data-load/lib/*' groovy.ui.GroovyMain
/app/scripts/sync-recent-results.groovy --sync` and restart policy NEVER.
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

Requests are serialized and spaced out. A 429 stops further Tennis Abstract
requests for that run; ESPN results continue with missing statistics left null.
The prior tournament metadata in PostgreSQL supplies fallback tournament context.
A failed run must not advance the checkpoint. Cache files in SYNC_CACHE_DIR avoid repeated requests
within a run; SYNC_REUSE_CACHE=true is for controlled retries and offline review,
not normal weekly operation.

Each run validates source agreement, identities and tournament mappings before
writing. Missing ESPN results or conflicting scores/winners stop the import.
The transaction inserts missing matches, set scores and raw statistics, then
records provenance in result_sync_match and a checkpoint in result_sync_run.
Existing results are not overwritten; newly available raw statistics can enrich
matches whose serve counts were missing. A database advisory lock covers ingestion
and derived computations. An interrupted derived refresh is retried even when
all matches were already imported. Elo, materialized statistics and records are
recomputed before marking the checkpoint complete.

Run parser checks with the existing data-load distribution:

```sh
java -cp 'data-load/build/install/data-load/lib/*' groovy.ui.GroovyMain \
  scripts/sync-recent-results.groovy --test
java -cp 'data-load/build/install/data-load/lib/*' groovy.ui.GroovyMain \
  scripts/test-result-identities.groovy
```

`--collect FROM THROUGH` writes collection.json and `--plan collection.json`
validates that collection against PostgreSQL without writing. `--apply` applies
only the ingestion stage; normal production runs use `--sync` to also refresh
derived data. Never publish source caches or database credentials to the repo.

For a validated initial backfill, SYNC_SEED_ID selects a private staged snapshot
from result_sync_seed. Clear this override after the backfill so weekly runs fetch
fresh sources. ESPN-only new identities are checked against country and birthdate;
their negative player_mapping IDs reserve a namespace separate from positive
Tennis Abstract/Sackmann IDs.

Identity resolution checks normalized names (including explicit `player_alias`
entries), birth dates and country against a mapped player before accepting that
mapping. ESPN biography responses must also agree with the requested identity.
Conflicts are reported in `validation.json` and block the write transaction.
A knockout match that shares a participant with a different stored pairing in
the same event/season/round is rejected as an identity conflict. The bracket
size guard remains enabled.

The reviewed repair in `repairs/2026-11-02-arnaldi.sql` addresses two distinct
players: Matteo Arnaldi (54581, external ID 208286) and Harry Thursfield (52898,
external ID 210162). Its manifest identifies 165 verified matches and 235 ranking
rows. It preserves Thursfield's 30 verified ranking rows and his own mapping;
one identical Arnaldi ranking already exists and is retained. The correction
also restores the two profiles and invalidates misattributed derived Elo rows.
It does not create a player alias or change scores, rounds, match IDs or raw stats.

Production execution requires explicit approval for this data repair. After
approval, use `repairs/run-arnaldi-repair.sh` in the maintenance service with both
repair files present. It creates a durable schema backup on `/backups`, tests the
transaction with `ROLLBACK`, then applies it. Exact row checks reject drift and
repeat execution. Original rows are also retained in `identity_repair_backup`.
Keep the full dump until verification is complete; it includes derived tables.

Run `repairs/refresh-arnaldi-derived.groovy` using the data-load distribution to
recompute Elo from 2022-05-09, materialized statistics and records. That helper
requires the recorded repair and imports no results. Restart the web service
after the refresh to clear application caches, then verify both profiles and
the four reported match IDs.

`preview-arnaldi-repair.groovy` uses a database connection enforced as read-only.
It compares the live plan with a preview that changes returned SQL rows in memory;
it never applies the repair. It uses ESPN's complete results fallback and leaves
raw statistics absent. An expected 17-insert preview does not authorize an import:
after the approved correction, run a fresh ordinary `--plan collection.json`
against the corrected production database and review validation before applying.
