# Development and releases

`master` is the default branch and the production release branch. Both the web
application and weekly ingestion worker deploy from it in Railway's `production`
environment. Merging a pull request to `master` releases both services.

1. Branch from the latest `master` using `feat/<topic>`, `fix/<topic>` or
   `chore/<topic>`.
2. Open a pull request targeting `master`. Keep application, loader and operational
   changes together when they depend on one another.
3. Pass the `build` check, review the diff and merge. Delete the merged branch.
   There is no permanent `work` or worker-specific release branch.
4. Verify both Railway deployments and the public health/data endpoints using
   [RAILWAY.md](RAILWAY.md).

After a successful push build on `master`, the Production maintenance workflow
checks public health and populated player data, then removes fully merged
`feat/*`, `fix/*`, `chore/*` and the retired `work` / `weekly-results-sync` branches.
Branches with unmerged commits are retained. Deletions use a lease so a branch
that advances during cleanup is not removed. This does not replace GitHub branch
protection; configure protection for `master` separately.

Use Java 11. The CI command builds both artifacts and runs the unit tests:

```sh
./gradlew --no-daemon --max-workers=2 -PgradleGitPropertiesVersion=2.4.2 \
  test :tennis-stats:distTar :data-load:installDist
java -cp 'data-load/build/install/data-load/lib/*' groovy.ui.GroovyMain scripts/sync-recent-results.groovy --test
java -cp 'data-load/build/install/data-load/lib/*' groovy.ui.GroovyMain scripts/test-result-identities.groovy
```

The plugin override avoids the old JCenter-only Git-properties dependency.
Database integration tests and data repairs require a separate disposable database
or an explicitly approved maintenance operation. A code release does not authorize
a production import, schema change or data repair. Keep secrets, backups and source
caches outside Git.

For a code rollback, revert the offending change through a new pull request to
`master`, then verify both deployments. Railway can temporarily roll back a
service to a known successful deployment while the revert is prepared. Code
rollbacks do not reverse database writes.
