/*selected*/
SELECT tournament_event_id, event_name AS name, season, surface::text, level::text, draw_type::text,
    count(*) AS matches, max(date) AS date
FROM selected GROUP BY tournament_event_id, event_name, season, surface, level, draw_type
ORDER BY season DESC, date DESC, tournament_event_id DESC LIMIT 300
