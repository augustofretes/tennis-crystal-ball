/*selected*/,
exposures AS (
    SELECT season, surface, winner_id AS player_id, 1 AS won,
        winner_elo_rating AS player_elo, loser_elo_rating AS opponent_elo FROM selected WHERE outcome IS NULL
    UNION ALL
    SELECT season, surface, loser_id, 0, loser_elo_rating, winner_elo_rating FROM selected WHERE outcome IS NULL
), ratings AS (
    SELECT r.player_id, s.surface, avg(s.elo) AS surface_elo
    FROM player_elo_ranking r CROSS JOIN LATERAL (VALUES
        ('H', hard_elo_rating), ('C', clay_elo_rating), ('G', grass_elo_rating), ('P', carpet_elo_rating)
    ) s(surface, elo)
    WHERE r.rank_date >= :fromDate AND r.rank_date < :untilDate
      AND (:playerId = 0 OR r.player_id = :playerId)
    GROUP BY r.player_id, s.surface
)
SELECT m.player_id, full_name(p.first_name, p.last_name) AS name, m.surface::text,
    count(*) AS matches, sum(won) AS wins, avg(won) AS win_rate,
    r.surface_elo
FROM exposures m JOIN player p USING (player_id)
LEFT JOIN ratings r ON r.player_id = m.player_id AND r.surface = m.surface::text
WHERE m.surface IS NOT NULL AND (:playerId = 0 OR m.player_id = :playerId)
GROUP BY m.player_id, p.first_name, p.last_name, m.surface, r.surface_elo HAVING count(*) >= :minMatches
ORDER BY player_id, m.surface
