package org.strangeforest.tcb.stats.analytics;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;

import org.springframework.core.io.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.*;
import org.springframework.util.*;

@Repository
public class AnalyticsRepository {

	private final NamedParameterJdbcTemplate jdbc;
	private final Map<String, String> queries = new HashMap<>();

	public AnalyticsRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
		for (var name : List.of("selected", "coverage", "era", "eraPlayers", "balance", "performance", "matches", "relativeStats", "surfaces", "specialists", "peaks", "calendar", "events", "draw")) {
			try (var input = new ClassPathResource("analytics/" + name + ".sql").getInputStream()) {
				queries.put(name, StreamUtils.copyToString(input, StandardCharsets.UTF_8));
			}
			catch (IOException e) { throw new UncheckedIOException(e); }
		}
	}

	public List<Map<String, Object>> query(String name, AnalyticsFilter filter, Map<String, Object> extra) {
		var sql = queries.get(name);
		if (sql == null || name.equals("selected")) throw new IllegalArgumentException("Unknown analytics query.");
		// Only trusted query fragments and a validated enum-derived column enter SQL text.
		sql = sql.replace("/*selected*/", queries.get("selected")).replace("/*rating*/", filter.ratingColumn());
		var params = filter.params();
		params.put("playerId", 0);
		params.put("eventId", 0);
		params.put("position", 1);
		params.put("distance", 200);
		params.put("windowYears", 1);
		params.put("withStats", false);
		params.putAll(extra);
		return jdbc.queryForList(sql, params);
	}

	public List<Map<String, Object>> query(String name, AnalyticsFilter filter) {
		return query(name, filter, Collections.emptyMap());
	}

	/** Call inside a read-only transaction so PostgreSQL streams rather than buffering history. */
	public void scanMatches(AnalyticsFilter filter, Consumer<Map<String, Object>> consumer) {
		var sql = queries.get("matches").replace("/*selected*/", queries.get("selected"));
		var params = filter.params(); params.put("playerId", 0); params.put("withStats", false);
		jdbc.execute(sql, params, (PreparedStatementCallback<Void>) statement -> {
			statement.setFetchSize(1000);
			try (var result = statement.executeQuery()) {
				var mapper = new ColumnMapRowMapper();
				int row = 0;
				while (result.next()) consumer.accept(mapper.mapRow(result, row++));
			}
			return null;
		});
	}
}
