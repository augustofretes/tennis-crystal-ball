/*selected*/,
activity AS (
    SELECT season, winner_id AS player_id FROM selected WHERE outcome IS NULL
    UNION SELECT season, loser_id FROM selected WHERE outcome IS NULL
), ranked AS (
    SELECT extract(year FROM r.rank_date)::int AS season, r.rank_date, r.player_id, r./*rating*/ AS elo,
        row_number() OVER (PARTITION BY rank_date ORDER BY r./*rating*/ DESC, r.player_id) AS position,
        max(r./*rating*/) OVER (PARTITION BY rank_date) AS leader
    FROM player_elo_ranking r
    JOIN activity a ON a.player_id = r.player_id AND a.season = extract(year FROM r.rank_date)
    WHERE r.rank_date >= :fromDate AND r.rank_date < :untilDate AND r./*rating*/ IS NOT NULL
), observations AS (
    SELECT *, count(*) FILTER (WHERE elo >= leader - :distance) OVER (PARTITION BY rank_date) AS contenders
    FROM ranked WHERE position <= 200
)
SELECT season, position::int, avg(elo) AS elo, count(*) AS snapshots,
    avg(contenders) AS contenders
FROM observations GROUP BY season, position ORDER BY season, position
