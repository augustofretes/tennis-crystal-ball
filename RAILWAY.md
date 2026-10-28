# Railway deployment

Deploy from the repository root. `railway.toml` selects the root multi-stage Dockerfile, which builds the Java 11 application and CSV loader. The Railway profile listens on `PORT` and exposes only the health endpoint. Production visitor tracking is disabled because its GeoIP database is not bundled. Background scraping jobs are not enabled.

Create a persistent Railway PostgreSQL service and an application service in the same project. On the application service, add these reference variables (replace `Postgres` if your database service has a different name):

```text
PGHOST=${{Postgres.PGHOST}}
PGPORT=${{Postgres.PGPORT}}
PGDATABASE=${{Postgres.PGDATABASE}}
PGUSER=${{Postgres.PGUSER}}
PGPASSWORD=${{Postgres.PGPASSWORD}}
```

The PostgreSQL service must have a persistent volume. The application container is stateless. Store credentials in Railway variables; do not commit them. Set a service memory limit appropriate for the dataset; start with at least 1 GB for the web application and monitor usage.

Populate the database before declaring the app ready. Restore a trusted database backup using Railway's database connection details, then apply `crystal-ball/src/main/db/migration/V1.0.3__modern_tournament_entries.sql` if it has not been applied. For a new empty database, initialize this project's schema and load procedures before importing data. The bundled loader is at `/app/data-load/bin/data-load`; its `-url` argument takes a JDBC URL, not Railway's `postgresql://` URL. Use the README's CSV import workflow and recompute Elo, derived statistics and records before switching traffic to the updated dataset. Do not use the partial validation database as a production data source.

With an authenticated Railway CLI:

```sh
railway link
railway up --service tennis-crystal-ball
railway domain --service tennis-crystal-ball
```

Select the intended project/environment during linking. Railway's `/actuator/health` check verifies database connectivity; additionally check the home page and `/autocompletePlayer?term=Federer` on the generated public domain to verify populated application data. Schema migrations and data imports are explicit maintenance operations, not automatic web startup actions.
