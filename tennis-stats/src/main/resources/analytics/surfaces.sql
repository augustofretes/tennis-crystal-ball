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
), sizes AS (
    SELECT season, rank_date, surface, count(*) FILTER (WHERE position <= 10) AS top10_size
    FROM ranked GROUP BY season, rank_date, surface
), pairs AS (
    SELECT a.season, a.rank_date, a.surface AS surface1, b.surface AS surface2,
        count(*) AS common_players,
        count(*) FILTER (WHERE a.position <= 10 AND b.position <= 10) AS shared_top10,
        CASE WHEN count(*) >= 10 THEN corr(a.position::double precision, b.position::double precision) END AS rank_correlation
    FROM ranked a JOIN ranked b ON a.rank_date = b.rank_date AND a.player_id = b.player_id AND a.surface < b.surface
    WHERE a.position <= 100 AND b.position <= 100
    GROUP BY a.season, a.rank_date, a.surface, b.surface
)
SELECT a.season, a.surface AS surface1, b.surface AS surface2, count(*) AS snapshots,
    avg(coalesce(p.common_players, 0)) AS common_players, avg(coalesce(p.shared_top10, 0)) AS shared_top10,
    avg(coalesce(p.shared_top10, 0)::double precision / nullif(a.top10_size + b.top10_size - coalesce(p.shared_top10, 0), 0)) AS top10_overlap,
    avg(rank_correlation) AS rank_correlation,
    count(rank_correlation) AS correlation_snapshots
FROM sizes a JOIN sizes b ON a.rank_date = b.rank_date AND a.surface < b.surface
LEFT JOIN pairs p ON p.rank_date = a.rank_date AND p.surface1 = a.surface AND p.surface2 = b.surface
GROUP BY a.season, a.surface, b.surface ORDER BY a.season, a.surface, b.surface
