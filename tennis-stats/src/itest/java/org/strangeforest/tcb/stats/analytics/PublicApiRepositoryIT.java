package org.strangeforest.tcb.stats.analytics;

import java.util.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.*;
import org.strangeforest.tcb.stats.api.*;

import static org.assertj.core.api.Assertions.*;

/** Uses an isolated schema in a local disposable database, never production data. */
@EnabledIfEnvironmentVariable(named = "ANALYTICS_TEST_DB_URL", matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):.*")
class PublicApiRepositoryIT {
	private JdbcTemplate admin, jdbc;
	private String schema;
	private PublicDataRepository repository;

	@BeforeEach void fixture() {
		String url = System.getenv("ANALYTICS_TEST_DB_URL"), user = System.getenv().getOrDefault("ANALYTICS_TEST_DB_USER", "tcb"), password = System.getenv().getOrDefault("ANALYTICS_TEST_DB_PASSWORD", "");
		admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
		schema = "public_api_test_" + UUID.randomUUID().toString().replace("-", ""); admin.execute("CREATE SCHEMA " + schema);
		jdbc = new JdbcTemplate(new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public", user, password));
		for (var table : List.of("player", "tournament", "tournament_event", "match", "match_stats", "player_ranking", "player_elo_ranking")) jdbc.execute("CREATE TABLE " + table + " (LIKE public." + table + " INCLUDING ALL)");
		repository = new PublicDataRepository(new NamedParameterJdbcTemplate(jdbc));
		jdbc.update("INSERT INTO player(player_id,first_name,last_name,country_id,active) VALUES (1,'Roger','Example','SUI',false),(2,'Rafael','Fixture','ESP',false),(3,'Unknown','Stats','USA',true)");
		jdbc.update("INSERT INTO tournament(tournament_id,name,level,surface,indoor) VALUES (1,'Fixture','G','H',false)");
		jdbc.update("INSERT INTO tournament_event(tournament_event_id,tournament_id,original_tournament_id,season,date,name,level,surface,indoor) VALUES (1,1,1,2010,'2010-01-01','Fixture Open','G','H',false),(2,1,1,2011,'2011-01-01','Fixture Open','G','C',false)");
		jdbc.update("INSERT INTO match(match_id,tournament_event_id,match_num,date,surface,indoor,round,best_of,winner_id,winner_country_id,loser_id,loser_country_id,winner_elo_rating,loser_elo_rating,score,outcome) VALUES (1,1,1,'2010-01-02','H',false,'F',3,1,'SUI',2,'ESP',2000,1800,'6-4 6-4',null),(2,1,2,'2010-01-02','H',false,'SF',3,2,'ESP',3,'USA',1800,null,'6-1 RET','RET'),(3,2,1,'2011-01-02','C',false,'F',3,1,'SUI',2,'ESP',2100,1900,'6-3 6-3',null)");
		jdbc.update("INSERT INTO match_stats(match_id,set,w_sv_pt,l_sv_pt,w_1st_won,w_2nd_won,l_1st_won,l_2nd_won,w_ace) VALUES (1,0,60,65,30,12,25,12,0)");
		jdbc.update("INSERT INTO player_ranking(rank_date,player_id,rank,rank_points) VALUES ('2010-01-04',1,1,10000),('2010-01-04',2,2,9000),('2010-01-11',2,1,11000)");
		jdbc.update("INSERT INTO player_elo_ranking(rank_date,player_id,rank,elo_rating,hard_elo_rating) VALUES ('2010-01-04',1,1,2000,2050),('2010-01-11',1,1,2100,2150)");
	}
	@AfterEach void clean() { if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

	@Test void playerSearchIsBoundAndLimitedWithStableIds() {
		assertThat(list("players", Map.of("q", "roger", "country", "sui"))).hasSize(1);
		assertThat(list("players", Map.of("q", "' OR TRUE --"))).isEmpty();
		assertThat(list("players", Map.of("q", "%_"))).isEmpty();
		assertThat(list("players", Map.of("active", "true"))).hasSize(1);
		var page = list("players", Map.of("limit", "1"));
		assertThat(page).hasSize(2); // One lookahead row, removed by the controller.
		assertThat(((Number)page.get(0).get("player_id")).intValue()).isEqualTo(1);
		assertThat(list("players", Map.of("offset", "2"))).hasSize(1);
	}

	@Test void matchScopeOutcomeAndRawUnknownsArePreserved() {
		var results = list("matches", Map.of("fromSeason", "2010", "toSeason", "2010", "outcome", "completed"));
		assertThat(results).hasSize(1); assertThat(results.get(0).get("date")).isEqualTo("2010-01-02");
		assertThat(results.get(0).get("w_ace")).isEqualTo(0); assertThat(results.get(0).get("l_ace")).isNull();
		assertThat(list("matches", Map.of("playerId", "3"))).hasSize(1);
		assertThat(list("matches", Map.of("surface", "C"))).hasSize(1);
		assertThat(list("matches", Map.of("eventId", "1"))).hasSize(2);
		assertThat(repository.one("matches", 1).get("winner")).isEqualTo("Roger Example");
		assertThat(repository.one("players", 999)).isNull();
		assertThat(repository.one("events", 2).get("surface")).isEqualTo("C");
	}

	@Test void rankingsUseOneSnapshotOnOrBeforeTheRequestedDate() {
		var ranking = list("rankings", Map.of("date", "2010-01-10")); assertThat(ranking).hasSize(2);
		assertThat(ranking).allSatisfy(row -> assertThat(row.get("date")).isEqualTo("2010-01-04"));
		assertThat(list("rankings", Map.of("date", "2010-01-01"))).isEmpty();
		var ratings = repository.list("ratings", new ApiQuery("ratings", Map.of("fromDate", "2010-01-05", "toDate", "2010-01-12")), 1);
		assertThat(ratings).hasSize(1); assertThat(ratings.get(0).get("date")).isEqualTo("2010-01-11");
	}

	@Test void coverageDistinguishesRecordedCompletedRatedAndIngested() {
		var coverage = repository.coverage();
		assertThat(((Number)coverage.get("recorded_matches")).intValue()).isEqualTo(3);
		assertThat(((Number)coverage.get("completed_matches")).intValue()).isEqualTo(2);
		assertThat(((Number)coverage.get("rated_matches")).intValue()).isEqualTo(2);
		assertThat(((Number)coverage.get("valid_serve_stat_matches")).intValue()).isEqualTo(1);
		assertThat(coverage.get("matches_through")).isEqualTo("2011-01-02");
		assertThat(coverage).doesNotContainKey("last_successful_ingestion_at");
		jdbc.execute("CREATE TABLE result_sync_run(completed_at timestamptz,derived_completed boolean)");
		jdbc.update("INSERT INTO result_sync_run VALUES ('2011-01-03 11:00:00+00',true),('2011-01-04 11:00:00+00',false)");
		assertThat(repository.coverage().get("last_successful_ingestion_at")).isEqualTo("2011-01-03T11:00:00Z");
	}

	private List<Map<String,Object>> list(String name, Map<String,String> params) { return repository.list(name, new ApiQuery(name, params), null); }
}
