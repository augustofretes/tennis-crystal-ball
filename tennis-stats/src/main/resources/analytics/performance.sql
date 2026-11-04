/*selected*/,
exposures AS (
    SELECT season, date, winner_id AS player_id, loser_id AS opponent_id, 1 AS won,
        winner_elo_rating AS player_elo, loser_elo_rating AS opponent_elo, loser_rank AS opponent_rank
    FROM selected WHERE outcome IS NULL
    UNION ALL
    SELECT season, date, loser_id, winner_id, 0, loser_elo_rating, winner_elo_rating, winner_rank
    FROM selected WHERE outcome IS NULL
), probabilities AS (
    SELECT *, CASE WHEN player_elo IS NOT NULL AND opponent_elo IS NOT NULL
        THEN 1.0 / (1 + power(10.0, (opponent_elo - player_elo) / 400.0)) END AS probability
    FROM exposures WHERE :playerId = 0 OR player_id = :playerId
)
SELECT m.player_id, full_name(p.first_name, p.last_name) AS name, season,
    count(*) AS matches, sum(won) AS wins,
    count(probability) AS rated_matches, sum(won) FILTER (WHERE probability IS NOT NULL) AS rated_wins,
    sum(probability) AS expected_wins, sum(probability * (1 - probability)) AS variance,
    avg(opponent_elo) AS opponent_elo,
    count(*) FILTER (WHERE opponent_rank <= 10) AS top10_matches,
    avg(opponent_elo) FILTER (WHERE opponent_rank <= 10) AS top10_opponent_elo
FROM probabilities m JOIN player p USING (player_id)
GROUP BY m.player_id, p.first_name, p.last_name, season
HAVING count(*) >= :minMatches ORDER BY season, wins DESC, player_id
