/*selected*/
SELECT count(*) AS recorded_matches,
    count(*) FILTER (WHERE m.outcome IS NULL) AS completed_matches,
    count(*) FILTER (WHERE m.outcome IS NULL AND winner_elo_rating IS NOT NULL AND loser_elo_rating IS NOT NULL) AS rated_matches,
    count(*) FILTER (WHERE m.outcome IS NULL AND s.w_sv_pt > 0 AND s.l_sv_pt > 0
        AND s.w_1st_won IS NOT NULL AND s.w_2nd_won IS NOT NULL
        AND s.l_1st_won IS NOT NULL AND s.l_2nd_won IS NOT NULL
        AND s.w_1st_won + s.w_2nd_won BETWEEN 0 AND s.w_sv_pt
        AND s.l_1st_won + s.l_2nd_won BETWEEN 0 AND s.l_sv_pt) AS stats_matches,
    min(m.date) AS first_match, max(m.date) AS latest_match,
    min(m.date) FILTER (WHERE s.w_sv_pt > 0 AND s.l_sv_pt > 0) AS first_stats,
    (SELECT max(rank_date) FROM player_elo_ranking) AS latest_elo,
    (SELECT max(date) FROM match) AS latest_recorded_match
FROM selected m LEFT JOIN match_stats s ON s.match_id = m.match_id AND s.set = 0
WHERE (:playerId = 0 OR :playerId IN (m.winner_id, m.loser_id))
