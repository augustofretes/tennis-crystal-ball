package org.strangeforest.tcb.stats.api;

import java.sql.*;
import java.util.*;

import org.springframework.cache.annotation.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.*;

/** Public columns only; all user input is bound and all ordering is deterministic. */
@Repository
@Transactional(readOnly = true, timeout = 20)
public class PublicDataRepository {

	private final NamedParameterJdbcTemplate jdbc;
	private static final String MAIN_LEVELS = "('G','F','L','M','O','A','B','D','T')";
	private static final String PLAYER = "p.player_id, full_name(p.first_name,p.last_name) AS name, p.country_id AS country, p.dob, p.hand::text, p.backhand::text, p.height, p.active";
	private static final String MATCH = "m.match_id, m.date, e.season, m.tournament_event_id AS event_id, e.name AS event, e.level::text, m.surface::text, m.indoor, m.round::text, m.best_of, "
		+ "m.winner_id, full_name(w.first_name,w.last_name) AS winner, m.loser_id, full_name(l.first_name,l.last_name) AS loser, "
		+ "m.score, m.outcome::text, m.winner_rank, m.loser_rank, m.winner_elo_rating, m.loser_elo_rating, "
		+ "s.minutes, s.w_ace, s.l_ace, s.w_sv_pt, s.l_sv_pt, s.w_1st_in, s.l_1st_in, s.w_1st_won, s.l_1st_won, s.w_2nd_won, s.l_2nd_won";
	private static final String MATCH_FROM = " FROM match m JOIN tournament_event e USING(tournament_event_id) JOIN player w ON w.player_id=m.winner_id JOIN player l ON l.player_id=m.loser_id LEFT JOIN match_stats s ON s.match_id=m.match_id AND s.set=0 ";
	private static final String EVENT = "e.tournament_event_id AS event_id, e.tournament_id, e.name, e.season, e.date, e.level::text, e.surface::text, e.indoor, e.draw_type::text, e.draw_size";
	private static final String SCOPE = "e.level IN " + MAIN_LEVELS + " AND e.season BETWEEN :fromSeason AND :toSeason AND (:surface='' OR e.surface::text=:surface) AND (:level='' OR e.level::text=:level)";
	private static final String RATINGS = "rank_date AS date, player_id, rank, elo_rating, hard_elo_rating, clay_elo_rating, grass_elo_rating, carpet_elo_rating, indoor_elo_rating, outdoor_elo_rating";

	public PublicDataRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

	public List<Map<String, Object>> list(String resource, ApiQuery query, Integer playerId) {
		var params = query.params();
		String sql;
		switch (resource) {
			case "players": sql = "SELECT " + PLAYER + " FROM player p WHERE (:q='' OR strpos(lower(full_name(p.first_name,p.last_name)),lower(:q))>0) "
				+ "AND (:country='' OR p.country_id=:country) AND (:active='' OR p.active::text=:active) ORDER BY p.player_id"; break;
			case "matches": sql = "SELECT " + MATCH + MATCH_FROM + " WHERE " + SCOPE.replace("e.surface::text", "m.surface::text")
				+ " AND (:playerId=0 OR m.winner_id=:playerId OR m.loser_id=:playerId) AND (:eventId=0 OR m.tournament_event_id=:eventId) "
				+ "AND (:outcome='all' OR m.outcome IS NULL) ORDER BY m.date DESC,m.match_id DESC"; break;
			case "events": sql = "SELECT " + EVENT + " FROM tournament_event e WHERE " + SCOPE + " ORDER BY e.date DESC,e.tournament_event_id DESC"; break;
			case "rankings": sql = "SELECT r.rank_date AS date,r.rank,r.rank_points,p.player_id,full_name(p.first_name,p.last_name) AS name,p.country_id AS country "
				+ "FROM player_ranking r JOIN player p USING(player_id) WHERE r.rank_date=(SELECT max(rank_date) FROM player_ranking WHERE rank_date<=:date) ORDER BY r.rank,r.player_id"; break;
			case "ratings":
				params.put("id", playerId);
				sql = "SELECT " + RATINGS + " FROM player_elo_ranking WHERE player_id=:id AND rank_date BETWEEN :fromDate AND :toDate ORDER BY rank_date DESC"; break;
			default: throw new IllegalArgumentException("Unknown resource.");
		}
		return rows(sql + " LIMIT :limit OFFSET :offset", params);
	}

	public Map<String, Object> one(String resource, long id) {
		String sql;
		switch (resource) {
			case "players": sql = "SELECT " + PLAYER + " FROM player p WHERE p.player_id=:id"; break;
			case "matches": sql = "SELECT " + MATCH + MATCH_FROM + " WHERE m.match_id=:id AND e.level IN " + MAIN_LEVELS; break;
			case "events": sql = "SELECT " + EVENT + " FROM tournament_event e WHERE e.tournament_event_id=:id AND e.level IN " + MAIN_LEVELS; break;
			default: throw new IllegalArgumentException("Unknown resource.");
		}
		var result = rows(sql, Map.of("id", id));
		return result.isEmpty() ? null : result.get(0);
	}

	@Cacheable("PublicApi.Coverage")
	public Map<String, Object> coverage() {
		var result = rows("SELECT count(*) AS recorded_matches, count(*) FILTER(WHERE m.outcome IS NULL) AS completed_matches, "
			+ "count(*) FILTER(WHERE m.outcome IS NULL AND m.winner_elo_rating IS NOT NULL AND m.loser_elo_rating IS NOT NULL) AS rated_matches, "
			+ "count(*) FILTER(WHERE s.w_sv_pt>0 AND s.l_sv_pt>0 AND s.w_1st_won+s.w_2nd_won BETWEEN 0 AND s.w_sv_pt AND s.l_1st_won+s.l_2nd_won BETWEEN 0 AND s.l_sv_pt) AS valid_serve_stat_matches, "
			+ "min(m.date) AS first_match, max(m.date) AS matches_through, (SELECT max(rank_date) FROM player_ranking) AS rankings_through, "
			+ "(SELECT max(rank_date) FROM player_elo_ranking) AS elo_through " + MATCH_FROM + "WHERE e.level IN " + MAIN_LEVELS, Map.of()).get(0);
		String checkpoint = jdbc.queryForObject("SELECT to_regclass('result_sync_run')::text", Map.of(), String.class);
		if (checkpoint != null) {
			var latest = rows("SELECT completed_at AS last_successful_ingestion_at FROM result_sync_run WHERE derived_completed AND completed_at IS NOT NULL ORDER BY completed_at DESC LIMIT 1", Map.of());
			if (!latest.isEmpty()) result.putAll(latest.get(0));
		}
		return Collections.unmodifiableMap(result);
	}

	private List<Map<String, Object>> rows(String sql, Map<String, ?> params) {
		var rows = jdbc.queryForList(sql, params);
		for (var row : rows) row.replaceAll((key, value) -> {
			if (value instanceof java.sql.Date) return ((java.sql.Date)value).toLocalDate().toString();
			if (value instanceof Timestamp) return ((Timestamp)value).toInstant().toString();
			return value;
		});
		return rows;
	}
}
