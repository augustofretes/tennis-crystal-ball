package org.strangeforest.tcb.stats.api;

import java.time.*;
import java.util.*;

/** Validates the public contract before any SQL is executed. */
public final class ApiQuery {

	public final Map<String, String> values;
	public final int limit, offset;
	public final String format;

	public ApiQuery(String resource, Map<String, String> values) {
		var allowed = new HashSet<>(Set.of("limit", "offset", "format"));
		switch (resource) {
			case "players": allowed.addAll(Set.of("q", "country", "active")); break;
			case "matches": allowed.addAll(Set.of("playerId", "eventId", "fromSeason", "toSeason", "surface", "level", "outcome")); break;
			case "events": allowed.addAll(Set.of("fromSeason", "toSeason", "surface", "level")); break;
			case "rankings": allowed.add("date"); break;
			case "ratings": allowed.addAll(Set.of("fromDate", "toDate")); break;
			default: throw new IllegalArgumentException("Unknown data resource.");
		}
		for (var key : values.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown parameter: " + key);
		this.values = Map.copyOf(values);
		limit = integer("limit", 50, 1, 200);
		offset = integer("offset", 0, 0, 100000);
		format = choice("format", "json", Set.of("json", "csv"));
		if (resource.equals("players")) {
			String q = values.getOrDefault("q", "").trim();
			if (!q.isEmpty() && (q.length() < 2 || q.length() > 100)) throw new IllegalArgumentException("Search needs between 2 and 100 characters.");
			if (!values.getOrDefault("country", "").matches("[A-Za-z]{0,3}")) throw new IllegalArgumentException("country must be a three-letter code.");
			if (values.containsKey("country") && values.get("country").length() != 3) throw new IllegalArgumentException("country must be a three-letter code.");
			choice("active", "", Set.of("", "true", "false"));
		}
		if (Set.of("matches", "events").contains(resource)) {
			if (integer("fromSeason", 1968, 1968, Year.now().getValue() + 1) > integer("toSeason", Year.now().getValue(), 1968, Year.now().getValue() + 1))
				throw new IllegalArgumentException("fromSeason must not follow toSeason.");
			choice("surface", "", Set.of("", "H", "C", "G", "P"));
			choice("level", "", Set.of("", "G", "F", "L", "M", "O", "A", "B", "D", "T"));
		}
		if (resource.equals("matches")) {
			integer("playerId", 0, values.containsKey("playerId") ? 1 : 0, Integer.MAX_VALUE);
			integer("eventId", 0, values.containsKey("eventId") ? 1 : 0, Integer.MAX_VALUE);
			choice("outcome", "all", Set.of("all", "completed"));
		}
		if (resource.equals("rankings")) date("date", LocalDate.now());
		if (resource.equals("ratings") && date("fromDate", LocalDate.of(1968, 1, 1)).isAfter(date("toDate", LocalDate.now())))
			throw new IllegalArgumentException("fromDate must not follow toDate.");
	}

	public int integer(String key, int fallback, int min, int max) {
		try {
			int value = values.containsKey(key) ? Integer.parseInt(values.get(key)) : fallback;
			if (value >= min && value <= max) return value;
		}
		catch (NumberFormatException ignored) { }
		throw new IllegalArgumentException(key + " must be an integer between " + min + " and " + max + ".");
	}

	public String choice(String key, String fallback, Set<String> choices) {
		String value = values.getOrDefault(key, fallback);
		if (!choices.contains(value)) throw new IllegalArgumentException("Unsupported " + key + ". Choose " + choices + ".");
		return value;
	}

	public LocalDate date(String key, LocalDate fallback) {
		try { return values.containsKey(key) ? LocalDate.parse(values.get(key)) : fallback; }
		catch (DateTimeException e) { throw new IllegalArgumentException(key + " must be an ISO date (YYYY-MM-DD)."); }
	}

	public Map<String, Object> params() {
		var params = new HashMap<String, Object>();
		params.put("limit", limit + 1); params.put("offset", offset);
		params.put("q", values.getOrDefault("q", "").trim());
		params.put("country", values.getOrDefault("country", "").toUpperCase(Locale.ROOT));
		params.put("active", values.getOrDefault("active", ""));
		params.put("playerId", values.containsKey("playerId") ? Integer.parseInt(values.get("playerId")) : 0);
		params.put("eventId", values.containsKey("eventId") ? Integer.parseInt(values.get("eventId")) : 0);
		params.put("fromSeason", values.containsKey("fromSeason") ? Integer.parseInt(values.get("fromSeason")) : 1968);
		params.put("toSeason", values.containsKey("toSeason") ? Integer.parseInt(values.get("toSeason")) : Year.now().getValue());
		params.put("surface", values.getOrDefault("surface", "")); params.put("level", values.getOrDefault("level", ""));
		params.put("outcome", values.getOrDefault("outcome", "all"));
		params.put("date", date("date", LocalDate.now()));
		params.put("fromDate", date("fromDate", LocalDate.of(1968, 1, 1)));
		params.put("toDate", date("toDate", LocalDate.now()));
		return params;
	}
}
