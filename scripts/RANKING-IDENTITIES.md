# Reviewed ranking identities

The 2026-11-02 read-only production preview rejected 26 source rows. The public
player profiles and dated ranking API were audited before adding these rules.
`ranking-identities.json` contains the exact preconditions; none of these rules
uses the external CSV player IDs, which conflate several older namesakes.

Nine display-name variants normalize to the database's full names. For duplicates
with the same name, birthdate and country, a single already-ranked profile is
retained. Known birthdates take precedence over an incomplete duplicate; multiple
confirmed candidates with no unique ranked profile still block the import.
The June 8 ranking API identifies the retained profiles: Munar 44493, Wong 53999,
Demin 63214, Compagnucci 51504, Maxted 62415 and Faucon 54507. Aguilar Cardozo's
63660 profile has the verified 2005-04-08 birthdate; 58531 has no birthdate.

Six existing profiles have legacy metadata differences. Their guarded bindings
require the exact ID, stored birthdate/country, source name/birthdate/country and
a second fetched profile. They affect ranking resolution only; no profile field
is updated. Any drift that cannot resolve through the ordinary strict matching
rules blocks the import.

| Player | Existing ID | Stored birthdate / country | Ranking source birthdate / country |
| --- | --- | --- | --- |
| Alexander Shevchenko | 52300 | 2000-12-29 / KAZ | 2000-11-29 / KAZ |
| Stefano Travaglia | 5868 | 1991-12-18 / ITA | 1991-12-28 / ITA |
| Daniel Masur | 9038 | 1994-06-11 / GER | 1994-11-06 / GER |
| Kasidit Samrej | 53548 | 2001-01-26 / THA | 2001-01-20 / THA |
| Alexandr Binda | 52853 | 2001-09-22 / RUS | 2001-09-22 / ITA |
| Colin Sinclair | 23873 | 1994-12-19 / AUS | 1994-12-19 / NMI |

The fetched TennisLive profiles independently match the first three source
birthdates. Samrej's profile retains 26 January, while ATP's profile and Tennis
Abstract list 20 January; the ITF Australian Open preview lists 26 January.
This disagreement is recorded rather than changing his birthdate. Binda and
Sinclair's profile birthdates match both sources; their profile nationality
metadata retains the older countries. The source country labels come from the dated ranking lists. The database's
profile countries, including those rendered by the table, remain unchanged.

Four present-day players need separate profiles from older namesakes. Their
birthdates agree between Tennis Abstract and distinct TennisLive profiles, and
their country codes agree between the two ranking lists. Creation is restricted
to these exact reviewed cases and only occurs if a valid current profile is
absent and the older namesake still matches its recorded preconditions.

| Current player | Birthdate | Country | Older namesake ID / birthdate |
| --- | --- | --- | --- |
| Martin Damm | 2003-09-30 | USA | 2093 / 1972-08-01 |
| Marat Sharipov | 2002-10-09 | RUS | 29710 / 1987-03-31 |
| Miguel Tobon | 2006-06-19 | COL | 1495 / 1968-06-22 |
| Ivan Ivanov | 2008-10-30 | BUL | 29613 / 1988-06-24 |

The registry contains the distinct source profile URLs. The import creates these
four minimal active player records and their current ranking rows atomically.
It leaves the older profiles, historical rankings, matches and external mappings
untouched. Existing historical misattributions and profile metadata need their
own audited repair; this change does not claim to correct those histories.

Evidence is available from the public application endpoints:
`/playerProfileTab?playerId=<id>` and
`/rankingsTableTable?date=08-06-2026&searchPhrase=<name>&rowCount=1000`.
Source identity links are preserved in the registry; source ranking URLs are in
`WeeklyRankingsSync.URLS`.
