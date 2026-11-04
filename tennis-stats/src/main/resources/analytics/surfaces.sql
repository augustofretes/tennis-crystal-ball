/*selected*/,
activity AS (
    SELECT season, winner_id AS player_id, surface FROM selected WHERE outcome IS NULL
    UNION SELECT season, loser_id, surface FROM selected WHERE outcome IS NULL
), surface_ratings AS (
    SELECT extract(year FROM r.rank_date)::int AS season, r.rank_date, r.player_id, s.surface, s.elo
    FROM player_elo_ranking r CROSS JOIN LATERAL (VALUES
        ('H', hard_elo_rating), ('C', clay_elo_rating), ('G', grass_elo_rating), ('P', carpet_elo_rating)
    ) AS s(surface, elo)
    JOIN activity a ON a.player_id = r.player_id AND a.season = extract(year FROM r.rank_date) AND a.surface::text = s.surface
    WHERE r.rank_date >= :fromDate AND r.rank_date < :untilDate AND s.elo IS NOT NULL
), ranked AS (
    SELECT *, row_number() OVER (PARTITION BY rank_date, surface ORDER BY elo DESC, player_id) AS position
    FROM surface_ratings
)
 , sizes AS (
    SELECT rank_date, surface, count(*) FILTER (WHERE position <= 10) AS top10_size
    FROM ranked GROUP BY rank_date, surface
), pairs AS (
    SELECT a.season, a.rank_date, a.surface AS surface1, b.surface AS surface2,
        count(*) AS common_players,
        count(*) FILTER (WHERE a.position <= 10 AND b.position <= 10) AS shared_top10,
        CASE WHEN count(*) >= 10 THEN corr(a.position::double precision, b.position::double precision) END AS rank_correlation
    FROM ranked a JOIN ranked b ON a.rank_date = b.rank_date AND a.player_id = b.player_id AND a.surface < b.surface
    WHERE a.position <= 100 AND b.position <= 100
    GROUP BY a.season, a.rank_date, a.surface, b.surface
)
SELECT p.season, p.surface1, p.surface2, count(*) AS snapshots,
    avg(common_players) AS common_players, avg(shared_top10) AS shared_top10,
    avg(shared_top10::double precision / nullif(a.top10_size + b.top10_size - shared_top10, 0)) AS top10_overlap,
    avg(rank_correlation) AS rank_correlation,
    count(rank_correlation) AS correlation_snapshots
FROM pairs p JOIN sizes a ON a.rank_date = p.rank_date AND a.surface = p.surface1
JOIN sizes b ON b.rank_date = p.rank_date AND b.surface = p.surface2
GROUP BY p.season, p.surface1, p.surface2 ORDER BY p.season, p.surface1, p.surface2
