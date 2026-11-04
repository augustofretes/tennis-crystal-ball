/*selected*/,
activity AS (
    SELECT season, winner_id AS player_id FROM selected WHERE outcome IS NULL
    UNION SELECT season, loser_id FROM selected WHERE outcome IS NULL
), ranked AS (
    SELECT r.player_id, r./*rating*/ AS elo,
        row_number() OVER (PARTITION BY rank_date ORDER BY r./*rating*/ DESC, r.player_id) AS position
    FROM player_elo_ranking r
    JOIN activity a ON a.player_id = r.player_id AND a.season = extract(year FROM r.rank_date)
    WHERE r.rank_date >= :fromDate AND r.rank_date < :untilDate AND r./*rating*/ IS NOT NULL
)
SELECT r.player_id, full_name(p.first_name, p.last_name) AS name, count(*) AS snapshots, avg(elo) AS elo
FROM ranked r JOIN player p USING (player_id)
WHERE position = :position
GROUP BY r.player_id, p.first_name, p.last_name ORDER BY snapshots DESC, r.player_id LIMIT 20
