package org.strangeforest.tcb.stats.analytics;

import java.time.*;
import java.util.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.data.Offset.*;
import static org.strangeforest.tcb.stats.analytics.AnalyticsMath.*;

/** The target must have the application's schema installed; no public data is changed. */
@EnabledIfEnvironmentVariable(named = "ANALYTICS_TEST_DB_URL", matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):.*")
class AnalyticsRepositoryIT {

	private JdbcTemplate admin, jdbc;
	private AnalyticsRepository repository;
	private AnalyticsService service;
	private String schema;
	private final AnalyticsFilter filter = new AnalyticsFilter(2010, 2010, "", "", "", 1);

	@BeforeEach void fixture() {
		String url = System.getenv("ANALYTICS_TEST_DB_URL");
		String user = System.getenv().getOrDefault("ANALYTICS_TEST_DB_USER", "tcb");
		String password = System.getenv().getOrDefault("ANALYTICS_TEST_DB_PASSWORD", "");
		admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
		schema = "analytics_test_" + UUID.randomUUID().toString().replace("-", "");
		admin.execute("CREATE SCHEMA " + schema);
		var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public", user, password);
		jdbc = new JdbcTemplate(source);
		for (var table : List.of("player", "tournament", "tournament_event", "match", "match_stats", "player_elo_ranking"))
			jdbc.execute("CREATE TABLE " + table + " (LIKE public." + table + " INCLUDING ALL)");
		repository = new AnalyticsRepository(new NamedParameterJdbcTemplate(jdbc)); service = new AnalyticsService(repository);
		for (int id = 1; id <= 4; id++) jdbc.update("INSERT INTO player(player_id, first_name, last_name, country_id) VALUES (?, 'Fixture', ?, 'USA')", id, "P" + id);
		for (int id = 10; id <= 13; id++) {
			String surface = List.of("H", "C", "G", "P").get(id - 10);
			jdbc.update("INSERT INTO tournament(tournament_id,name,level,surface,indoor) VALUES (?, 'Fixture Open', 'G', ?::surface, false)", id, surface);
			jdbc.update("INSERT INTO tournament_event(tournament_event_id,tournament_id,original_tournament_id,season,date,name,level,surface,indoor,draw_type,draw_size) VALUES (?, ?, ?, 2010, '2010-01-10', 'Fixture Open', 'G', ?::surface, false, 'KO', 4)", id, id, id, surface);
		}
		insertMatch(1, 10, 1, 2, 2000, 1800, null);
		insertMatch(2, 11, 1, 3, 2000, 2000, null);
		insertMatch(3, 10, 3, 2, null, 1800, null);
		insertMatch(4, 10, 2, 1, 1800, 2000, "RET");
		insertMatch(5, 12, 4, 1, 1600, 2000, null);
		for (int id : List.of(1, 2)) jdbc.update("INSERT INTO match_stats(match_id,set,w_sv_pt,w_1st_won,w_2nd_won,l_sv_pt,l_1st_won,l_2nd_won,w_ace,l_ace,w_sv_gms,l_sv_gms,w_bp_fc,w_bp_sv,l_bp_fc,l_bp_sv) VALUES (?,0,60,30,12,65,25,12,8,3,10,10,3,3,4,2)", id);
		jdbc.update("INSERT INTO match_stats(match_id,set,w_sv_pt,w_1st_won,w_2nd_won,l_sv_pt,l_1st_won,l_2nd_won) VALUES (5,0,0,0,0,0,0,0)");
		for (var date = LocalDate.of(2010, 1, 4); date.isBefore(LocalDate.of(2017, 1, 1)); date = date.plusWeeks(1))
			for (int id = 1; id <= 4; id++) jdbc.update("INSERT INTO player_elo_ranking(rank_date,player_id,rank,elo_rating,hard_elo_rating,clay_elo_rating,grass_elo_rating,carpet_elo_rating,indoor_elo_rating,outdoor_elo_rating) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				java.sql.Date.valueOf(date), id, id, 2200 - id * 100, 2250 - id * 100, 2200 - id * 100, 2150 - id * 100, 2100 - id * 100, 2200 - id * 100, 2200 - id * 100);
		for (int year = 2011; year <= 2016; year++) {
			int event = year;
			jdbc.update("INSERT INTO tournament_event(tournament_event_id,tournament_id,original_tournament_id,season,date,name,level,surface,indoor,draw_type,draw_size) VALUES (?,10,10,?,?,'Fixture Open','G','H',false,'KO',4)", event, year, java.sql.Date.valueOf(LocalDate.of(year, 7, 10)));
			insertMatch(year, event, 1, 2, 2000, 1800, null);
			jdbc.update("UPDATE match SET date = ? WHERE match_id = ?", java.sql.Date.valueOf(LocalDate.of(year, 7, 10)), (long)year);
		}
	}

	private void insertMatch(long id, int event, int winner, int loser, Integer winnerElo, Integer loserElo, String outcome) {
		jdbc.update("INSERT INTO match(match_id,tournament_event_id,match_num,date,surface,indoor,round,best_of,winner_id,winner_country_id,winner_rank,winner_elo_rating,loser_id,loser_country_id,loser_rank,loser_elo_rating,outcome) SELECT ?, ?, ?, '2010-01-10', surface, false, 'F', 3, ?, 'USA', ?, ?, ?, 'USA', ?, ?, ?::match_outcome FROM tournament_event WHERE tournament_event_id = ?",
			id, event, (int)id, winner, winner, winnerElo, loser, loser, loserElo, outcome, event);
	}

	@AfterEach void clean() { if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

	@Test void coverageAndExcessHaveConsistentDenominators() {
		var coverage = service.coverage(filter, 0);
		assertThat(integer(coverage, "recorded_matches")).isEqualTo(5);
		assertThat(integer(coverage, "completed_matches")).isEqualTo(4);
		assertThat(integer(coverage, "rated_matches")).isEqualTo(3);
		assertThat(integer(coverage, "stats_matches")).isEqualTo(2);
		var player = rows(service.performance(filter, 1)).get(0);
		assertThat(integer(player, "matches")).isEqualTo(3);
		assertThat(integer(player, "wins")).isEqualTo(2);
		assertThat(number(player, "excess_wins")).isCloseTo(2 - winProbability(2000, 1800, 400) - .5 - winProbability(2000, 1600, 400), offset(1e-10));
	}

	@Test void relativeStatsUseSurfaceBaselinesAndExcludeZeroDenominators() {
		var rows = rows(service.relativeStats(filter, 1));
		assertThat(rows).hasSize(2);
		var hard = rows.stream().filter(r -> "H".equals(r.get("surface"))).findFirst().orElseThrow();
		assertThat(number(hard, "serve_rate")).isCloseTo(.7, offset(1e-12));
		assertThat(number(hard, "serve_baseline")).isCloseTo(79.0 / 125, offset(1e-12));
		assertThat(number(hard, "serve_above")).isCloseTo((42 - 60 * 79.0 / 125) / 160, offset(1e-12));
	}

	@Test void filtersRestrictBothStatisticsAndRankingCohorts() {
		var hard = new AnalyticsFilter(2010, 2010, "H", "", "G", 1);
		assertThat(integer(service.coverage(hard, 0), "completed_matches")).isEqualTo(2);
		assertThat(repository.query("era", hard, Map.of("distance", 200)).stream().mapToInt(r -> integer(r, "position")).max()).hasValue(3);
		assertThat(repository.query("eraPlayers", hard, Map.of("position", 1))).hasSize(1);
		assertThat(service.era(hard, 200, 400, 2000).get("rating_column")).isEqualTo("hard_elo_rating");
	}

	@Test void sustainedPeaksNeedFullWindowsAndActivity() {
		var full = new AnalyticsFilter(2010, 2016, "", "", "", 1);
		assertThat(rows(service.peaks(full, 1, 1))).hasSize(1);
		assertThat(rows(service.peaks(full, 1, 3))).hasSize(1);
		assertThat(rows(service.peaks(full, 1, 5))).hasSize(1);
		assertThat(rows(service.peaks(filter, 1, 3))).isEmpty();
	}

	@Test void everyOtherSqlQueryExecutesAgainstTheActualPostgresSchema() {
		for (var query : List.of("balance", "calendar", "surfaces", "specialists", "events", "draw", "matches"))
			assertThatCode(() -> repository.query(query, filter, Map.of("eventId", 10))).doesNotThrowAnyException();
		var manager = new DataSourceTransactionManager(jdbc.getDataSource());
		new TransactionTemplate(manager).execute(status -> {
			var result = service.matchups(filter, List.of(1, 2));
			assertThat(rows(result)).hasSize(2);
			assertThat(rows(result).stream().mapToDouble(r -> number(r, "excess_wins")).sum()).isCloseTo(0, offset(1e-12));
			return null;
		});
	}

	@SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Map<String, Object> result) {
		return (List<Map<String, Object>>)result.get("rows");
	}
}
