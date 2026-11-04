WITH selected AS (
    SELECT m.*, e.season, e.level, e.draw_type, e.draw_size, e.name AS event_name
    FROM match m JOIN tournament_event e USING (tournament_event_id)
    WHERE e.season BETWEEN :fromSeason AND :toSeason
      AND e.level IN ('G', 'F', 'L', 'M', 'O', 'A', 'B', 'D', 'T')
      AND (:surface = '' OR m.surface::text = :surface)
      AND (:indoor = '' OR m.indoor = (:indoor = 'true'))
      AND (:level = '' OR e.level::text = :level)
)
