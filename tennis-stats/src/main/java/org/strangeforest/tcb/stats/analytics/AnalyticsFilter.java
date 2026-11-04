package org.strangeforest.tcb.stats.analytics;

import java.time.*;
import java.util.*;

/** Validated, immutable scope shared by every analytics query. */
public final class AnalyticsFilter {

	public final int fromSeason, toSeason, minMatches;
	public final String surface, indoor, level;

	public AnalyticsFilter(int fromSeason, int toSeason, String surface, String indoor, String level, int minMatches) {
		if (fromSeason < 1968 || toSeason > Year.now().getValue() + 1 || fromSeason > toSeason)
			throw new IllegalArgumentException("Choose an ordered season range from 1968 through next year.");
		if (!Set.of("", "H", "C", "G", "P").contains(surface))
			throw new IllegalArgumentException("Unknown surface.");
		if (!Set.of("", "true", "false").contains(indoor))
			throw new IllegalArgumentException("Indoor must be true, false, or empty.");
		if (!Set.of("", "G", "F", "L", "M", "O", "A", "B", "D", "T").contains(level))
			throw new IllegalArgumentException("Unknown main-tour tournament level.");
		if (minMatches < 1 || minMatches > 200)
			throw new IllegalArgumentException("Minimum matches must be between 1 and 200.");
		this.fromSeason = fromSeason;
		this.toSeason = toSeason;
		this.surface = surface;
		this.indoor = indoor;
		this.level = level;
		this.minMatches = minMatches;
	}

	public Map<String, Object> params() {
		var params = new HashMap<String, Object>();
		params.put("fromSeason", fromSeason);
		params.put("toSeason", toSeason);
		params.put("fromDate", LocalDate.of(fromSeason, 1, 1));
		params.put("untilDate", LocalDate.of(toSeason + 1, 1, 1));
		params.put("surface", surface);
		params.put("indoor", indoor);
		params.put("level", level);
		params.put("minMatches", minMatches);
		return params;
	}

	public String ratingColumn() {
		switch (surface) {
			case "H": return "hard_elo_rating";
			case "C": return "clay_elo_rating";
			case "G": return "grass_elo_rating";
			case "P": return "carpet_elo_rating";
			default: return indoor.isEmpty() ? "elo_rating" : (indoor.equals("true") ? "indoor_elo_rating" : "outdoor_elo_rating");
		}
	}

	@Override public boolean equals(Object other) {
		if (!(other instanceof AnalyticsFilter)) return false;
		var f = (AnalyticsFilter)other;
		return fromSeason == f.fromSeason && toSeason == f.toSeason && minMatches == f.minMatches
			&& surface.equals(f.surface) && indoor.equals(f.indoor) && level.equals(f.level);
	}

	@Override public int hashCode() {
		return Objects.hash(fromSeason, toSeason, surface, indoor, level, minMatches);
	}
}
