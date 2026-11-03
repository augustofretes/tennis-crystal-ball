# Career prize money

The CSV, results and ranking imports do not update `player.prize_money`. Those
profile values came from an old Wikipedia import; importing newer matches does
not make them current. For example, Carlos Alcaraz Garfia (52602) still has
`$1,089,590`, while Wikipedia's career total on 2026-11-03 is `$68,050,698`.

`sync-prize-money.groovy` updates only this column. It uses stored English
Wikipedia article links and requires both the player's name (including an
infobox full name) and exact birthdate to agree. It parses the infobox's USD
career total, excluding references and all-time earnings rankings. Other
currencies, ambiguous amounts and unverifiable identities are skipped and
reported. Missing article links or birthdates are not invented. Wikipedia is
the source for the total, not an independently audited ledger of match earnings.

## Review and apply

Use the existing `PG*` reference variables and data-load distribution. Collect
and preview use a database connection enforced as read-only:

```sh
java -cp '/app/data-load/lib/*' groovy.ui.GroovyMain /app/scripts/sync-prize-money.groovy --collect /tmp/prize-money.json
java -cp '/app/data-load/lib/*' groovy.ui.GroovyMain /app/scripts/sync-prize-money.groovy --plan /tmp/prize-money.json
```

`--preview` collects and prints the plan without saving a snapshot. To narrow
either collection mode, set `PRIZE_MONEY_PLAYER_IDS=52602` (comma-separated for
multiple players). Otherwise every stored profile with a Wikipedia link and
birthdate is considered, including retired players. Requests are serialized
and spaced by 1.2 seconds; access denial or rate limiting stops the collection.

Review `PRIZE_MONEY_PLAN`, including skipped profiles, and retain the snapshot
outside Git in the maintenance backup storage. It includes the original values,
source URLs, verified identities, fetch time and checksum. After explicit
production maintenance approval under [CONTRIBUTING.md](../CONTRIBUTING.md):

```sh
java -cp '/app/data-load/lib/*' groovy.ui.GroovyMain /app/scripts/sync-prize-money.groovy --apply /tmp/prize-money.json
```

Apply rejects snapshots older than seven days and rechecks stored identities,
article links and old values under the ingestion advisory lock. A conflicting
row rolls back the entire transaction. Reapplying an identical snapshot is a
no-op. No schema migration, result import or derived-data refresh is needed.
`PRIZE_MONEY_UPDATED` logs each old/new value and the snapshot checksum after
commit; `PRIZE_MONEY_APPLIED` reports the total updated.
Keep the saved snapshot for a guarded rollback if a source total needs correction.

## Weekly refresh

The weekly worker supports this as a separate, opt-in stage. Set
`PRIZE_MONEY_SYNC=true` to collect and preview each Monday. Only after reviewing
a production plan, set `PRIZE_MONEY_APPLY=true` to apply those identity-verified
totals each run. Both default to false; neither inherits or changes `SYNC_APPLY`
or `RANKINGS_APPLY`. A prize-money failure is reported without preventing the
ranking and result stages from running, and makes the worker exit with failure.
The existing Monday schedule is unchanged.
Weekly collection considers existing Wikipedia-linked profiles in the current
top 500; the standalone collection also covers retired and lower-ranked players.
An explicit `PRIZE_MONEY_PLAYER_IDS` list overrides the weekly scope.

The Player cache expires 30 minutes after loading a profile, even when it is
frequently read. Updated totals therefore become visible within 30 minutes.
After the initial repair, a `tennis-stats` restart can clear it immediately;
verify `/playerProfileTab?playerId=52602`.

Run fixture checks with:

```sh
java -cp 'data-load/build/install/data-load/lib/*' groovy.ui.GroovyMain scripts/test-prize-money.groovy
```
