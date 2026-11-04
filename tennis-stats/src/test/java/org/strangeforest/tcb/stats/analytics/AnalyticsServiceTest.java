package org.strangeforest.tcb.stats.analytics;

import java.util.*;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.data.Offset.*;
import static org.mockito.Mockito.*;
import static org.strangeforest.tcb.stats.analytics.AnalyticsMath.*;

class AnalyticsServiceTest {

	private final AnalyticsRepository repository = mock(AnalyticsRepository.class);
	private final AnalyticsService service = new AnalyticsService(repository);
	private final AnalyticsFilter filter = new AnalyticsFilter(2010, 2011, "", "", "", 1);

	@Test void eraYearsAreEquallyWeightedDespiteDifferentSnapshotCounts() {
		when(repository.query("era", filter, Map.of("distance", 200))).thenReturn(List.of(
			row("season", 2010, "position", 1, "elo", 2400.0, "snapshots", 52, "contenders", 3.0),
			row("season", 2011, "position", 1, "elo", 2200.0, "snapshots", 1, "contenders", 2.0)));
		when(repository.query("balance", filter)).thenReturn(List.of());
		var result = service.era(filter, 200, 400, 2000);
		var curve = rows(result, "curve");
		assertThat(number(curve.get(0), "elo")).isEqualTo(2300);
		assertThat(number(curve.get(0), "low")).isEqualTo(2200);
		assertThat(rows(result, "seasons").get(0).get("top3")).isNull();
		assertThat(result.get("missing_seasons")).isEqualTo(List.of());
	}

	@Test void missingSeasonsAndBandsRemainExplicitlyMissing() {
		when(repository.query("era", filter, Map.of("distance", 200))).thenReturn(List.of());
		when(repository.query("balance", filter)).thenReturn(List.of());
		assertThat(service.era(filter, 200, 400, 2000).get("missing_seasons")).isEqualTo(List.of(2010, 2011));
	}

	@Test void excessWinsUsesWinsInRatedMatchesRatherThanAllWins() {
		when(repository.query("performance", filter, Map.of("playerId", 1))).thenReturn(new ArrayList<>(List.of(row(
			"player_id", 1, "matches", 20, "wins", 18, "rated_matches", 10, "rated_wins", 8, "expected_wins", 7.0, "variance", 2.0))));
		var result = rows(service.performance(filter, 1), "rows").get(0);
		assertThat(number(result, "excess_wins")).isEqualTo(1);
		assertThat(number(result, "shrunk_excess_rate")).isCloseTo(1 / 30.0, offset(1e-12));
		assertThat(number(result, "excess_low")).isLessThan(0);
	}

	@Test void aMissingRequiredSurfaceNeverSilentlyRenormalizesTheCalendar() {
		when(repository.query("calendar", filter, Map.of("playerId", 1))).thenReturn(List.of(row(
			"player_id", 1, "name", "A", "surface", "H", "wins", 8, "matches", 10, "win_rate", .8, "surface_elo", 2000.0)));
		var result = rows(service.calendar(filter, 1, List.of(50, 30, 20, 0), 2000, 400), "rows").get(0);
		assertThat(result.get("standardized_win_rate")).isNull();
		assertThat(result.get("modeled_wins_per100")).isNull();
		assertThat(result.get("missing_surfaces")).isEqualTo(List.of("C", "G"));
		result = rows(service.calendar(filter, 1, List.of(100, 0, 0, 0), 2000, 400), "rows").get(0);
		assertThat(number(result, "standardized_win_rate")).isCloseTo(.8, offset(1e-12));
		assertThat(number(result, "modeled_wins_per100")).isCloseTo(50, offset(1e-12));
	}

	@Test void invalidScopesCannotBecomeSqlFragments() {
		assertThatThrownBy(() -> new AnalyticsFilter(2011, 2010, "", "", "", 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AnalyticsFilter(2010, 2011, "H; DROP TABLE match", "", "", 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AnalyticsFilter(2010, 2011, "", "sometimes", "", 1)).isInstanceOf(IllegalArgumentException.class);
		assertThat(new AnalyticsFilter(2010, 2011, "C", "true", "G", 1).ratingColumn()).isEqualTo("clay_elo_rating");
	}

	@SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Map<String, Object> result, String key) {
		return (List<Map<String, Object>>)result.get(key);
	}
}
