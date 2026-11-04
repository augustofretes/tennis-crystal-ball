/*selected*/,
champions AS (
    SELECT m.match_id, m.season, m.surface, r.hard_elo_rating, r.clay_elo_rating, r.grass_elo_rating, r.carpet_elo_rating
    FROM selected m
    LEFT JOIN LATERAL (
        SELECT * FROM player_elo_ranking r WHERE r.player_id = m.winner_id
            AND r.rank_date < m.date AND r.rank_date >= m.date - interval '1 year'
        ORDER BY rank_date DESC LIMIT 1
    ) r ON true
    WHERE m.round = 'F' AND m.level NOT IN ('D', 'T') AND (m.outcome IS NULL OR m.outcome IN ('RET', 'DEF', 'W/O'))
), classified AS (
    SELECT c.season, c.surface,
        max(s.elo) FILTER (WHERE s.surface = c.surface::text) AS own_elo,
        avg(s.elo) FILTER (WHERE s.surface <> c.surface::text) AS other_elo,
        count(s.elo) FILTER (WHERE s.surface <> c.surface::text) AS other_surfaces
    FROM champions c CROSS JOIN LATERAL (VALUES
        ('H', hard_elo_rating), ('C', clay_elo_rating), ('G', grass_elo_rating), ('P', carpet_elo_rating)
    ) s(surface, elo)
    GROUP BY c.match_id, c.season, c.surface, c.hard_elo_rating, c.clay_elo_rating, c.grass_elo_rating, c.carpet_elo_rating
)
SELECT season, surface::text, count(*) AS classified_titles,
    count(*) FILTER (WHERE own_elo - other_elo >= 150) AS specialist_titles
FROM classified WHERE own_elo IS NOT NULL AND other_surfaces >= 2
GROUP BY season, surface ORDER BY season, surface
