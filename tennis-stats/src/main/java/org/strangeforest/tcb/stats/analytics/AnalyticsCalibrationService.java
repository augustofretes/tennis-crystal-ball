package org.strangeforest.tcb.stats.analytics;

import java.time.*;
import java.util.*;

import org.springframework.cache.annotation.*;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.*;
import org.strangeforest.tcb.stats.model.core.*;
import org.strangeforest.tcb.stats.service.*;

import static org.strangeforest.tcb.stats.analytics.AnalyticsMath.*;

@Service
public class AnalyticsCalibrationService {

	private final AnalyticsRepository repository;
	private final MatchPredictionService predictions;

	public AnalyticsCalibrationService(AnalyticsRepository repository, MatchPredictionService predictions) {
		this.repository = repository; this.predictions = predictions;
	}

	@Cacheable("Analytics.Calibration")
	@Transactional(readOnly = true)
	public Map<String, Object> calibration(AnalyticsFilter filter, String model) {
		var eligible = new ArrayList<Map<String, Object>>();
		var groups = new LinkedHashMap<String, Bucket>();
		var total = new Bucket("All", "All");
		int[] counts = {0, 0};
		var random = new Random(1973);
		repository.scanMatches(filter, m -> {
			counts[0]++;
			if (m.get("winner_elo") == null || m.get("loser_elo") == null) return;
			counts[1]++;
			if (model.equals("current")) {
				if (eligible.size() < 200) eligible.add(m);
				else {
					int slot = random.nextInt(counts[1]);
					if (slot < 200) eligible.set(slot, m);
				}
			}
			else record(m, winProbability(number(m, "winner_elo"), number(m, "loser_elo"), 400), total, groups);
		});
		int skipped = 0;
		for (var m : eligible) {
			double winnerProbability;
			if (model.equals("current")) {
				// A full-day embargo avoids snapshots/results recorded on the match date.
				var date = ((java.sql.Date)m.get("date")).toLocalDate().minusDays(1);
				var prediction = predictions.predictMatch(integer(m, "winner_id"), integer(m, "loser_id"), date,
					null, integer(m, "tournament_event_id"), false, Surface.safeDecode((String)m.get("surface")),
					(Boolean)m.get("indoor"), TournamentLevel.decode((String)m.get("level")),
					((Number)m.get("best_of")).shortValue(), Round.decode((String)m.get("round")));
				if (prediction.isEmpty()) { skipped++; continue; }
				winnerProbability = prediction.getWinProbability1();
			}
			else winnerProbability = winProbability(number(m, "winner_elo"), number(m, "loser_elo"), 400);
			if (!Double.isFinite(winnerProbability) || winnerProbability < 0 || winnerProbability > 1) { skipped++; continue; }
			record(m, winnerProbability, total, groups);
		}
		var rows = new ArrayList<Map<String, Object>>();
		for (var bucket : groups.values()) rows.add(bucket.result());
		double error = groups.values().stream().filter(b -> b.dimension.equals("Probability"))
			.mapToDouble(b -> Math.abs(b.predicted - b.favoriteWins)).sum();
		return row("summary", total.result(), "rows", rows, "model", model, "available_matches", counts[1],
			"sampled_matches", model.equals("current") ? eligible.size() : counts[1], "skipped_matches", skipped, "recorded_matches", counts[0],
			"calibration_error", total.n == 0 ? null : error / total.n,
			"method", model.equals("current")
				? "Retrospective replay of the current prediction model with a one-day input embargo. At most 200 deterministically sampled rated matches are evaluated. Current weights may have been tuned on historical results: this is not an out-of-sample or prospective score. Wilson intervals cover observed favorite win rates only."
				: "Recorded pre-match overall Elo baseline, using completed matches with both ratings. Favorite bins show mean predicted probability versus observed wins. Equal-rating favorites are chosen by player ID, independently of the result. Brier score and log loss use a canonical player ordering. Wilson intervals omit model uncertainty.");
	}

	private static void record(Map<String, Object> m, double winnerProbability, Bucket total, Map<String, Bucket> groups) {
		boolean firstWon = integer(m, "winner_id") < integer(m, "loser_id");
		double p = firstWon ? winnerProbability : 1 - winnerProbability;
		double outcome = firstWon ? 1 : 0;
		double fp = Math.max(p, 1 - p), fy = p >= .5 ? outcome : 1 - outcome;
		total.add(p, outcome, fp, fy);
		int bin = Math.min(4, (int)Math.floor((fp - .5) * 10));
		add(groups, "Probability", (50 + bin * 10) + "–" + (60 + bin * 10) + "%", p, outcome, fp, fy);
		add(groups, "Surface", Objects.toString(m.get("surface"), "Unknown"), p, outcome, fp, fy);
		add(groups, "Season", m.get("season").toString(), p, outcome, fp, fy);
	}

	private static void add(Map<String, Bucket> groups, String dimension, String label, double p, double y, double fp, double fy) {
		groups.computeIfAbsent(dimension + ":" + label, key -> new Bucket(dimension, label)).add(p, y, fp, fy);
	}

	private static final class Bucket {
		final String dimension, label;
		int n;
		double predicted, favoriteWins, brier, loss;
		Bucket(String dimension, String label) { this.dimension = dimension; this.label = label; }
		void add(double p, double y, double fp, double fy) {
			n++; predicted += fp; favoriteWins += fy; brier += (p - y) * (p - y);
			double clipped = Math.max(1e-12, Math.min(1 - 1e-12, p));
			loss -= y * Math.log(clipped) + (1 - y) * Math.log(1 - clipped);
		}
		Map<String, Object> result() {
			var interval = wilson(favoriteWins, n);
			return row("dimension", dimension, "label", label, "matches", n, "predicted", n == 0 ? null : predicted / n,
				"observed", n == 0 ? null : favoriteWins / n, "low", interval[0], "high", interval[1],
				"brier", n == 0 ? null : brier / n, "log_loss", n == 0 ? null : loss / n);
		}
	}
}
