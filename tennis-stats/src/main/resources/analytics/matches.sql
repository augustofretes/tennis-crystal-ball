/*selected*/
SELECT m.match_id, m.tournament_event_id, m.event_name, m.season, m.date, m.surface::text, m.indoor,
    m.level::text, m.round::text, m.best_of, m.winner_id, m.loser_id, m.winner_age, m.loser_age,
    full_name(w.first_name, w.last_name) AS winner, full_name(l.first_name, l.last_name) AS loser,
    m.winner_elo_rating AS winner_elo, m.loser_elo_rating AS loser_elo, m.score,
    m.winner_rank, m.loser_rank
FROM selected m JOIN player w ON w.player_id = m.winner_id JOIN player l ON l.player_id = m.loser_id
WHERE m.outcome IS NULL AND (:playerId = 0 OR :playerId IN (m.winner_id, m.loser_id))
    AND (NOT :withStats OR EXISTS (
        SELECT 1 FROM match_stats s WHERE s.match_id = m.match_id AND s.set = 0
            AND s.w_sv_pt > 0 AND s.l_sv_pt > 0
            AND s.w_1st_won + s.w_2nd_won BETWEEN 0 AND s.w_sv_pt
            AND s.l_1st_won + s.l_2nd_won BETWEEN 0 AND s.l_sv_pt
    ))
ORDER BY m.date, m.match_num, m.match_id
