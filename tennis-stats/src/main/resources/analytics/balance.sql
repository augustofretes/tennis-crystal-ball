/*selected*/,
titles AS (
    SELECT season, winner_id, count(*) AS titles FROM selected
    WHERE round = 'F' AND level NOT IN ('D', 'T') AND (outcome IS NULL OR outcome IN ('RET', 'DEF', 'W/O'))
    GROUP BY season, winner_id
), concentration AS (
    SELECT season, count(*) AS champions, sum(titles) AS titles,
        sum(titles * titles)::double precision / power(sum(titles), 2) AS title_concentration
    FROM titles GROUP BY season
)
SELECT m.season, count(*) FILTER (WHERE m.outcome IS NULL) AS matches,
    count(*) FILTER (WHERE m.outcome IS NULL AND winner_elo_rating IS NOT NULL AND loser_elo_rating IS NOT NULL) AS rated_matches,
    avg((winner_elo_rating < loser_elo_rating)::int) FILTER (WHERE m.outcome IS NULL) AS upset_rate,
    c.champions, c.titles, c.title_concentration
FROM selected m LEFT JOIN concentration c USING (season)
GROUP BY m.season, c.champions, c.titles, c.title_concentration ORDER BY season
