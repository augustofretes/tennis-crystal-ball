package org.strangeforest.tcb.stats.analytics;

import java.util.*;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.data.Offset.*;
import static org.mockito.Mockito.*;
import static org.strangeforest.tcb.stats.analytics.AnalyticsMath.*;

class AnalyticsDrawServiceTest {

	private final AnalyticsRepository repository = mock(AnalyticsRepository.class);
	private final AnalyticsDrawService service = new AnalyticsDrawService(repository);
	private final AnalyticsFilter filter = new AnalyticsFilter(2010, 2010, "", "", "", 1);

	@Test void reconstructionUsesEntryRatingsRatherThanLaterRoundRatings() {
		var matches = List.of(match("SF", 1, 3, 2000.0, 2000.0), match("SF", 2, 4, 2000.0, 2000.0), match("F", 1, 2, 2600.0, 1400.0));
		when(repository.query("draw", filter, Map.of("eventId", 10))).thenReturn(matches);
		var result = service.draw(filter, 10, 100, 400);
		assertThat(result.get("simulated")).isEqualTo(true);
		for (var player : rows(result)) {
			assertThat(number(player, "entry_elo")).isEqualTo(2000);
			assertThat(number(player, "actual_title_probability")).isCloseTo(.25, offset(1e-12));
			assertThat(number(player, "redraw_title_probability")).isCloseTo(.25, offset(1e-12));
		}
	}

	@Test void laterRatingsDoNotFillAMissingEntryRating() {
		when(repository.query("draw", filter, Map.of("eventId", 10))).thenReturn(List.of(
			match("SF", 1, 3, null, 2000.0), match("SF", 2, 4, 2000.0, 2000.0), match("F", 1, 2, 2500.0, 2000.0)));
		var result = service.draw(filter, 10, 100, 400);
		assertThat(result.get("simulated")).isEqualTo(false);
		assertThat(rows(result).stream().filter(p -> integer(p, "player_id") == 1).findFirst().orElseThrow().get("entry_elo")).isNull();
	}

	@Test void roundRobinRoutesRemainAvailableWithoutKnockoutSimulation() {
		var match = match("RR", 1, 2, 2000.0, 2000.0); match.put("draw_type", "RR");
		when(repository.query("draw", filter, Map.of("eventId", 10))).thenReturn(List.of(match));
		var result = service.draw(filter, 10, 100, 400);
		assertThat(result.get("simulated")).isEqualTo(false);
		assertThat(rows(result)).hasSize(2);
	}

	@Test void abandonedFinalsDoNotProduceACompletedDrawComparison() {
		var finalMatch = match("F", 1, 2, 2000.0, 2000.0); finalMatch.put("outcome", "ABD");
		when(repository.query("draw", filter, Map.of("eventId", 10))).thenReturn(List.of(
			match("SF", 1, 3, 2000.0, 2000.0), match("SF", 2, 4, 2000.0, 2000.0), finalMatch));
		var result = service.draw(filter, 10, 100, 400);
		assertThat(result.get("simulated")).isEqualTo(false);
		assertThat(result.get("reason")).isEqualTo("The recorded final was abandoned.");
	}

	@Test void extraMatchesCannotBeSilentlyDroppedFromAKnockoutBracket() {
		when(repository.query("draw", filter, Map.of("eventId", 10))).thenReturn(List.of(
			match("SF", 1, 3, 2000.0, 2000.0), match("SF", 2, 4, 2000.0, 2000.0),
			match("SF", 1, 4, 2000.0, 2000.0), match("F", 1, 2, 2000.0, 2000.0)));
		assertThat(service.draw(filter, 10, 100, 400).get("simulated")).isEqualTo(false);
	}

	private static Map<String, Object> match(String round, int winner, int loser, Double winnerElo, Double loserElo) {
		return row("match_id", winner * 10 + loser, "round", round, "winner_id", winner, "loser_id", loser,
			"winner", "P" + winner, "loser", "P" + loser, "winner_seed", winner <= 2 ? winner : null,
			"loser_seed", loser <= 2 ? loser : null, "winner_elo", winnerElo, "loser_elo", loserElo,
			"outcome", null, "score", "6-4 6-4", "name", "Fixture Open", "season", 2010, "surface", "H", "draw_size", 4, "draw_type", "KO");
	}

	@SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Map<String, Object> result) {
		return (List<Map<String, Object>>)result.get("rows");
	}
}
