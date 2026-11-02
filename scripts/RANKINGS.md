# Weekly ATP rankings

The bundled rankings end on 2026-06-08. `sync-rankings.groovy` imports a current
weekly ATP singles snapshot of the top 500, matching the old `LoadNewRankings`
limit. It runs before the existing weekly match job; either job can finish if
the other's sources fail, and any failure makes the worker fail visibly.
`RANKINGS_APPLY` defaults to false and is independent of `SYNC_APPLY`.

The collector requires the same official ranking date from TennisLive (points),
Tennis Abstract (ranks and player identities), and Tennis Explorer (an independent
check of all top-50 points and names). It rejects incomplete, future, non-Monday
or more than 14-day-old snapshots and mismatched names, ranks or points. Where
Tennis Abstract lacks a birthdate, the matching TennisLive profile supplies it.
The source names have explicit normalization rules for documented full-name and
transliteration variants; these never create database aliases or mappings.

A player must resolve uniquely by name and country, with a matching birthdate
when the stored profile has one. A missing stored birthdate is allowed but is
not filled in by this import. A conflicting birthdate, ambiguous name, missing
player or conflicting stored ranking blocks the entire import. No player,
external mapping, match, Elo or profile row is changed. Applying rechecks the
plan under the same advisory lock used by the match worker, inserts missing
rankings and refreshes the four ranking materialized views in one transaction.
Retries accept identical rows without overwriting them.

## Preview and apply

From the deployed data-load distribution:

```sh
java -cp '/app/data-load/lib/*' groovy.ui.GroovyMain /app/scripts/sync-rankings.groovy --preview
```

`--preview` always enforces a read-only connection, even if `RANKINGS_APPLY=true`.
It logs `RANKINGS_SOURCE_VERIFIED` with the date, 500 rows and checksum, followed
by `RANKINGS_IMPORT_PLAN` with the previous date, insert count and rejects.
Read database credentials from the worker's existing `PG*` reference variables.
Keep source snapshots outside Git.

For a saved reviewable snapshot, `--collect /tmp/rankings.json` collects without
connecting to PostgreSQL; `--plan /tmp/rankings.json` validates against PostgreSQL
read-only. `--apply /tmp/rankings.json` applies that exact checksum-verified
snapshot after approval. Neither mode invents missing weekly history.

Production imports require explicit maintenance approval under CONTRIBUTING.md.
Review a clean production preview before enabling `RANKINGS_APPLY=true` on
`tennis-weekly-sync`. Keep its existing Monday 11:00 UTC cron and keep
`SYNC_APPLY=false`. The next `--sync` then imports rankings weekly. A one-time
approved catch-up can use the standalone script with `--sync` or `--apply`.

The web app queries the current date from PostgreSQL instead of retaining it in
an access-refreshed cache, so an external import becomes visible without a web
restart. Dated ranking tables remain cached by date. Verify `/rankingsDate` and
`/rankingsTableTable?rowCount=10`, then check the page and profile ranks.

This fixes the current table and future weekly snapshots. It does not backfill
the missing June-to-October weeks; use independently dated historical sources
and a separate reviewed import for that history.

## Validation

```sh
java -cp 'data-load/build/install/data-load/lib/*' groovy.ui.GroovyMain scripts/test-rankings.groovy
```

The fixture tests cover source disagreement, stale/incomplete snapshots, profile
parsing, player identity conflicts, conflicting existing rows, concurrency, and
idempotent planning. The standard Java 11 build also runs in GitHub Actions.
