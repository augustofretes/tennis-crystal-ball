/*selected*/,
activity AS (
    SELECT date, winner_id AS player_id FROM selected WHERE outcome IS NULL
    UNION ALL SELECT date, loser_id FROM selected WHERE outcome IS NULL
), weekly AS (
    SELECT r.player_id, r.rank_date AS date, r./*rating*/::double precision AS rating, 0::bigint AS match_count
    FROM player_elo_ranking r
    JOIN (SELECT DISTINCT player_id FROM activity) a USING (player_id)
    WHERE r.rank_date >= :fromDate AND r.rank_date < :untilDate AND r./*rating*/ IS NOT NULL
      AND (:playerId = 0 OR r.player_id = :playerId)
), points AS (
    SELECT * FROM weekly
    UNION ALL
    SELECT player_id, date, NULL::double precision, count(*) FROM activity
    WHERE :playerId = 0 OR player_id = :playerId GROUP BY player_id, date
), windows AS (
    SELECT player_id, date AS rank_date, rating,
        sum(match_count) OVER w AS matches, avg(rating) OVER w AS elo,
        count(rating) OVER w AS snapshots,
        min(date) FILTER (WHERE rating IS NOT NULL) OVER w AS first_snapshot
    FROM points
    WINDOW w AS (PARTITION BY player_id ORDER BY date RANGE BETWEEN
        (CASE WHEN :windowYears = 1 THEN interval '364 days' ELSE make_interval(years => :windowYears) END)
        PRECEDING AND CURRENT ROW)
), eligible AS (
    SELECT *, row_number() OVER (PARTITION BY player_id ORDER BY elo DESC, rank_date) AS peak
    FROM windows WHERE rating IS NOT NULL AND matches >= :minMatches * :windowYears
        AND snapshots >= 40 * :windowYears
        AND first_snapshot <= rank_date - make_interval(days => (365 * :windowYears - 14))
)
SELECT w.player_id, full_name(p.first_name, p.last_name) AS name,
    first_snapshot AS from_date, rank_date AS to_date, elo, snapshots, matches
FROM eligible w JOIN player p USING (player_id) WHERE peak = 1 ORDER BY elo DESC, player_id LIMIT 100
