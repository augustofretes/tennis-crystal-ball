# Railway production

The GitHub default branch, `master`, is the production release branch. Both
repository-backed services use `augustofretes/tennis-crystal-ball@master` and build
from the repository root with the Java 11 multi-stage `Dockerfile`.

Project: [tennis-crystal-ball](https://railway.com/project/07f2bfde-2f96-4885-965c-da6127f6a12c)
Environment: `production`.

| Service | Source / config | Purpose |
| --- | --- | --- |
| `tennis-stats` | `master`, `/railway.toml` | Public web application; health check `/actuator/health` |
| `tennis-weekly-sync` | `master`, `/railway.sync.toml` | Weekly job, Monday 11:00 UTC (06:00 Cancun); restart NEVER, no HTTP health check |
| `tennis-db` | Pinned `mcekovic/uts-database` image | PostgreSQL with persistent `tennis-data` volume |
| `tennis-maintenance` | `postgres:14` | Maintenance runner with persistent `tennis-import-backups` at `/backups` |
| `atp-access-diagnostic` | Railway Function | Existing diagnostic utility |
| `atp-playwright-diagnostic` | Playwright image | Existing diagnostic utility |

The database, maintenance runner and diagnostics are not branch-based deployments.
Their images, commands, variables and volumes are managed in Railway. Keep their
lifecycle separate from application releases.

## Configuration

Set the **Railway Config File** separately on each repo-backed service, exactly
as listed above. The worker config intentionally has no web health check.
Service-specific config files keep the shared Docker image usable for both roles.
The Dockerfile builds the web libraries, data-load distribution and scripts.

Use Railway reference variables for `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`
and `PGPASSWORD`, referencing the intended database role on `tennis-db`. Web
Spring datasource variables and the `railway` profile remain configured in Railway.
Keep credentials out of Git. The web container is stateless; database and backup
volumes must persist. Both application services currently have a 1 GB memory limit.

`SYNC_APPLY=false` keeps the weekly worker in planning mode. Changing branches or
deploying code must not turn imports on. Preserve the current bounds/seed overrides
unless performing a separately reviewed ingestion operation. See
[scripts/WEEKLY-RESULTS.md](scripts/WEEKLY-RESULTS.md) for the import and repair runbooks.
Schema migrations, imports and repairs are explicit maintenance operations.

## Release and verify

Merge a tested pull request into `master` using [CONTRIBUTING.md](CONTRIBUTING.md).
Railway follows the two services' configured branch. Check that each new deployment
reaches `SUCCESS` and reports the expected `master` commit.

The public app is https://tennis-stats-production.up.railway.app. Verify:

- `/actuator/health` reports `UP` (including database connectivity).
- The home page renders.
- `/autocompletePlayer?term=Federer` returns populated player data.
- Worker logs show the expected planning mode and no import writes.

The web Railway profile listens on `PORT`, exposes only the health endpoint and
disables visitor tracking because its GeoIP database is not bundled. Background
scraping jobs are not enabled in the web process.

## Provisioning or recovery

Create the app and persistent PostgreSQL service in the same project. Restore a
trusted database backup before declaring the app ready. On an existing database,
apply `crystal-ball/src/main/db/migration/V1.0.3__modern_tournament_entries.sql` only
if missing and through the normal reviewed migration process. For an empty database,
initialize this project's schema and load procedures before importing data. Never
run schema creation against the live populated database.

The CSV loader is `/app/data-load/bin/data-load`; its `-url` argument takes a JDBC
URL. Follow the README's CSV import workflow and recompute Elo, derived statistics
and records before switching traffic to an updated dataset. Never use a partial
validation database as production. Use explicit project, environment and service
IDs when performing manual Railway operations to avoid deploying to the wrong target.
