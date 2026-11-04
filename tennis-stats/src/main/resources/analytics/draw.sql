SELECT m.match_id, m.match_num, m.date, m.round::text, m.winner_id, m.loser_id,
    m.winner_seed, m.loser_seed, m.winner_elo_rating AS winner_elo, m.loser_elo_rating AS loser_elo,
    m.outcome::text, m.score,
    full_name(w.first_name, w.last_name) AS winner, full_name(l.first_name, l.last_name) AS loser,
    e.name, e.season, e.surface::text, e.draw_size, e.draw_type::text
FROM match m JOIN tournament_event e USING (tournament_event_id)
JOIN player w ON w.player_id = m.winner_id JOIN player l ON l.player_id = m.loser_id
WHERE tournament_event_id = :eventId
ORDER BY m.round, m.match_num, m.match_id
