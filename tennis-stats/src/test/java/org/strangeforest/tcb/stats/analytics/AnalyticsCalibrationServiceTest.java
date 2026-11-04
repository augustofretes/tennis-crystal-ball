package org.strangeforest.tcb.stats.analytics;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

import org.junit.jupiter.api.*;
import org.strangeforest.tcb.stats.model.core.*;
import org.strangeforest.tcb.stats.model.prediction.*;
import org.strangeforest.tcb.stats.service.*;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.data.Offset.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.strangeforest.tcb.stats.analytics.AnalyticsMath.*;

class AnalyticsCalibrationServiceTest {

	private final AnalyticsRepository repository = mock(AnalyticsRepository.class);
	private final MatchPredictionService predictions = mock(MatchPredictionService.class);
	private final AnalyticsCalibrationService service = new AnalyticsCalibrationService(repository, predictions);
	private final AnalyticsFilter filter = new AnalyticsFilter(2010, 2010, "", "", "", 1);

	@Test void equalRatingFavoritesAreChosenWithoutLookingAtTheWinner() {
		scan(List.of(match(1, 2), match(2, 1)));
		var result = service.calibration(filter, "elo");
		@SuppressWarnings("unchecked") var summary = (Map<String, Object>)result.get("summary");
		assertThat(number(summary, "predicted")).isEqualTo(.5);
		assertThat(number(summary, "observed")).isEqualTo(.5);
		assertThat(number(summary, "brier")).isEqualTo(.25);
		assertThat(number(summary, "log_loss")).isCloseTo(Math.log(2), offset(1e-12));
	}

	@Test void currentModelReplaysPreserveTournamentIdentityAndSamplingCutoff() {
		var matches = new ArrayList<Map<String, Object>>();
		for (int i = 0; i < 401; i++) matches.add(match(i % 2 + 1, 2 - i % 2));
		scan(matches);
		var prediction = new MatchPrediction(1, (short)3);
		prediction.addItemProbability1(RankingPredictionItem.RANK, 1, .5);
		when(predictions.predictMatch(anyInt(), anyInt(), any(LocalDate.class), any(), anyInt(), eq(false), any(Surface.class), anyBoolean(), any(TournamentLevel.class), any(Short.class), any(Round.class))).thenReturn(prediction);
		var result = service.calibration(filter, "current");
		assertThat(integer(result, "available_matches")).isEqualTo(401);
		assertThat(integer(result, "sampled_matches")).isEqualTo(200);
		verify(predictions, times(200)).predictMatch(anyInt(), anyInt(), eq(LocalDate.of(2010, 1, 9)), eq(7), eq(10), eq(false), eq(Surface.HARD), eq(false), eq(TournamentLevel.GRAND_SLAM), eq((short)3), eq(Round.F));
	}

	@SuppressWarnings("unchecked") private void scan(List<Map<String, Object>> matches) {
		doAnswer(invocation -> { var consumer = (Consumer<Map<String, Object>>)invocation.getArgument(1); matches.forEach(consumer); return null; })
			.when(repository).scanMatches(eq(filter), any());
	}

	private static Map<String, Object> match(int winner, int loser) {
		return row("winner_id", winner, "loser_id", loser, "winner_elo", 2000.0, "loser_elo", 2000.0,
			"date", java.sql.Date.valueOf("2010-01-10"), "tournament_id", 7, "tournament_event_id", 10, "season", 2010, "surface", "H", "indoor", false, "level", "G", "round", "F", "best_of", 3);
	}
}
