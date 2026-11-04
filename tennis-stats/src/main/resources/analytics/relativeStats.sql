/*selected*/,
exposures AS (
    SELECT m.season, m.surface, m.winner_id AS player_id,
        s.w_sv_pt AS serve_points, s.w_1st_won + s.w_2nd_won AS serve_won,
        s.l_sv_pt AS return_points, s.l_sv_pt - s.l_1st_won - s.l_2nd_won AS return_won,
        s.w_ace AS aces, s.w_sv_gms AS service_games,
        s.w_bp_fc - s.w_bp_sv AS broken_games
    FROM selected m JOIN match_stats s ON s.match_id = m.match_id AND s.set = 0
    WHERE m.outcome IS NULL
    UNION ALL
    SELECT m.season, m.surface, m.loser_id, s.l_sv_pt, s.l_1st_won + s.l_2nd_won,
        s.w_sv_pt, s.w_sv_pt - s.w_1st_won - s.w_2nd_won, s.l_ace, s.l_sv_gms, s.l_bp_fc - s.l_bp_sv
    FROM selected m JOIN match_stats s ON s.match_id = m.match_id AND s.set = 0
    WHERE m.outcome IS NULL
), valid AS (
    SELECT * FROM exposures WHERE surface IS NOT NULL AND serve_points > 0 AND return_points > 0
        AND serve_won BETWEEN 0 AND serve_points AND return_won BETWEEN 0 AND return_points
), clean AS (
    SELECT *, CASE WHEN aces BETWEEN 0 AND serve_points THEN aces END AS valid_aces,
        CASE WHEN service_games > 0 AND broken_games BETWEEN 0 AND service_games THEN broken_games END AS valid_breaks
    FROM valid
), baselines AS (
    SELECT season, surface, sum(serve_won)::double precision / sum(serve_points) AS serve_baseline,
        sum(return_won)::double precision / sum(return_points) AS return_baseline,
        sum(valid_aces)::double precision / nullif(sum(serve_points) FILTER (WHERE valid_aces IS NOT NULL), 0) AS ace_baseline,
        1.0 - sum(valid_breaks)::double precision / nullif(sum(service_games) FILTER (WHERE valid_breaks IS NOT NULL), 0) AS hold_baseline
    FROM clean GROUP BY season, surface
)
SELECT v.player_id, full_name(p.first_name, p.last_name) AS name, v.season, v.surface::text,
    count(*) AS matches, sum(serve_points) AS serve_points, sum(return_points) AS return_points,
    sum(serve_won)::double precision / sum(serve_points) AS serve_rate,
    sum(return_won)::double precision / sum(return_points) AS return_rate,
    b.serve_baseline, b.return_baseline, b.ace_baseline, b.hold_baseline,
    sum(valid_aces)::double precision / nullif(sum(serve_points) FILTER (WHERE valid_aces IS NOT NULL), 0) AS ace_rate,
    1.0 - sum(valid_breaks)::double precision / nullif(sum(service_games) FILTER (WHERE valid_breaks IS NOT NULL), 0) AS hold_rate,
    (sum(serve_won) + 100.0 * b.serve_baseline) / (sum(serve_points) + 100.0) - b.serve_baseline AS serve_above,
    (sum(return_won) + 100.0 * b.return_baseline) / (sum(return_points) + 100.0) - b.return_baseline AS return_above
FROM clean v JOIN baselines b USING (season, surface) JOIN player p USING (player_id)
WHERE :playerId = 0 OR v.player_id = :playerId
GROUP BY v.player_id, p.first_name, p.last_name, v.season, v.surface,
    b.serve_baseline, b.return_baseline, b.ace_baseline, b.hold_baseline
HAVING count(*) >= :minMatches ORDER BY season, serve_above DESC, player_id
