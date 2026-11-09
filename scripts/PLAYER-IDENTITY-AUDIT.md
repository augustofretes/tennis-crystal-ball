# Player identity audit — 2026-11-09

The weekly planner previously resolved a known name before consulting ESPN's
biography. The legacy SQL `find_player(name,date)` also selected the oldest
eligible namesake with `ORDER BY dob, player_id`. These are demonstrated code
paths, not proof of which importer originally wrote each affected record.

The fix checks available ESPN biographies first, preserves source-ID conflicts
as blocking errors, uses birth date plus compatible name/country to distinguish
namesakes, and rejects ambiguity without creating another player. Source IDs
are scoped to providers; ESPN IDs are not positive Tennis Abstract mapping IDs.
Ranking is supporting evidence only: contaminated or stale ranking data must
not override a conflicting birth date. The rankings importer already uses a
separate reviewed identity registry; it is unchanged.

The legacy name-only SQL function now rejects multiple candidates, including
case variants and aliases. Historical dates can still exclude a player who was
younger than ten. This guard does not infer retirement or change any data.

## Live read-only evidence

- [Older profile 2093](https://tennis-stats-production.up.railway.app/playerProfileTab?playerId=2093): DOB 1972-08-01, currently country USA.
- [Younger profile 66208](https://tennis-stats-production.up.railway.app/playerProfileTab?playerId=66208): DOB 2003-09-30, country USA.
- [ESPN athlete 10130](https://sports.core.api.espn.com/v2/sports/tennis/athletes/10130): Martin Damm, DOB 2003-09-30.
- [ESPN Brussels scoreboard](https://site.api.espn.com/apis/site/v2/sports/tennis/atp/scoreboard?dates=20261019&limit=1000): athlete 10130 beats Yannick Hanfmann 7-5 6-3 on October 19 (competition 185049), then loses to Francisco Cerundolo 4-6 6-7 on October 21 (185050). These agree with production matches 200022 and 200065 respectively, which currently reference 2093.
- [Older profile's 2026 matches](https://tennis-stats-production.up.railway.app/matchesTable?playerId=2093&season=2026&rowCount=1000): 26 rows. [Younger profile](https://tennis-stats-production.up.railway.app/matchesTable?playerId=66208&season=2026&rowCount=1000): zero rows.

The 26 candidate match IDs are:
200065, 200022, 199848, 199829, 199708, 199651, 199635, 199620, 199453,
199383, 199175, 199122, 198998, 198958, 198945, 198093, 198125, 197882,
197794, 197826, 197730, 197353, 197357, 197364, 197375, 197275.
Only the two Brussels matches were independently cross-checked in this task.
The other 24 are an audit queue, not an approved repair manifest. Earlier seasons,
rankings, mappings and mixed profile metadata still require a broader audit.
The namesake registry also identifies Marat Sharipov, Miguel Tobon and Ivan Ivanov
as separate people; do not merge those profiles or assume identical repair scope.

## Verification and release limits

Regression fixtures cover unique-name bypass, identical names, suffix variants,
row-order independence, stale provider mappings, conflicting upstream birth
dates, duplicate candidates, absent biography evidence, temporary fetch failure,
and existing misattributed matches blocking insertion. Parser tests and the
application unit tests/distribution build pass with Java 11. The SQL test runs
against a disposable local PostgreSQL database and rolls back its schema.
CI runs it using the existing disposable PostgreSQL service.

No production data, schema, deployment, or branch merge was performed. A merge
to master automatically releases both Railway services, so release needs separate
authorization. Installing the changed SQL function in production is a separate
schema maintenance action; code release alone does not authorize it.

Before data repair: verify source evidence for each row, audit provider mappings
and ranking/profile contamination, prepare a transaction with exact old-value
preconditions and a rollback manifest, check for colliding existing matches, and
obtain authorization for that concrete manifest. After repair, refresh derived
statistics/Elo as appropriate and run a fresh read-only results plan. Never
reassign every historical row from father to son. The independent ATP Finals
format issue remains outside this fix and can still block a full import.
