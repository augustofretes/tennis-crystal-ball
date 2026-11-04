package org.strangeforest.tcb.stats.analytics;

import java.time.*;
import java.util.*;
import java.util.stream.*;

import org.springframework.cache.annotation.*;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.*;

import static org.strangeforest.tcb.stats.analytics.AnalyticsMath.*;

@Service
public class AnalyticsService {

	private final AnalyticsRepository repository;

	public AnalyticsService(AnalyticsRepository repository) { this.repository = repository; }

	@Cacheable("Analytics.Coverage")
	public Map<String, Object> coverage(AnalyticsFilter filter, int playerId) {
		var result = repository.query("coverage", filter, Map.of("playerId", playerId)).get(0);
		result.put("scope", filter);
		result.put("rating_basis", "Recorded pre-match overall Elo; filters restrict the matches being compared.");
		return result;
	}

	@Cacheable("Analytics.Era")
	public Map<String, Object> era(AnalyticsFilter filter, int distance, int scale, int reference) {
		var observations = repository.query("era", filter, Map.of("distance", distance));
		var byPosition = new TreeMap<Integer, List<Double>>();
		var byYear = new TreeMap<Integer, List<Map<String, Object>>>();
		for (var observation : observations) {
			byPosition.computeIfAbsent(integer(observation, "position"), k -> new ArrayList<>()).add(number(observation, "elo"));
			byYear.computeIfAbsent(integer(observation, "season"), k -> new ArrayList<>()).add(observation);
		}
		var curve = new ArrayList<Map<String, Object>>();
		for (var entry : byPosition.entrySet()) {
			var values = entry.getValue();
			curve.add(row("position", entry.getKey(), "elo", average(values), "low", Collections.min(values),
				"high", Collections.max(values), "years", values.size()));
		}
		var balance = repository.query("balance", filter);
		var seasons = new ArrayList<Map<String, Object>>();
		for (var entry : byYear.entrySet()) {
			var rows = entry.getValue();
			var ratings = rows.stream().map(r -> number(r, "elo")).collect(Collectors.toList());
			var season = row("season", entry.getKey(), "leader", band(rows, 1, 1), "top3", band(rows, 1, 3),
				"top8", band(rows, 1, 8), "depth4_10", band(rows, 4, 10), "depth11_32", band(rows, 11, 32),
				"depth33_100", band(rows, 33, 100), "contenders", rows.get(0).get("contenders"),
				"snapshots", rows.get(0).get("snapshots"), "positions", rows.size(),
				"reference_title_probability", DrawSimulator.referenceTitleChance(ratings, reference, scale));
			balance.stream().filter(b -> integer(b, "season") == entry.getKey()).findFirst().ifPresent(season::putAll);
			seasons.add(season);
		}
		var missing = new ArrayList<Integer>();
		for (int year = filter.fromSeason; year <= filter.toSeason; year++) if (!byYear.containsKey(year)) missing.add(year);
		return row("curve", curve, "seasons", seasons, "missing_seasons", missing, "scope", filter,
			"rating_column", filter.ratingColumn(), "distance", distance, "scale", scale, "reference", reference,
			"method", "Each year has equal weight; within a year each stored ranking date has equal weight. The cohort contains players with a completed match in that year and scope. Bands require every position in that band. Shading is the range of yearly averages, not a confidence interval. Contender counts cover the top 200. The reference player replaces position 32 in a random 32-player field built from mean contemporaneous positions; this is a model scenario, not a historical title probability.");
	}

	private static Double band(List<Map<String, Object>> rows, int from, int to) {
		var values = rows.stream().filter(r -> integer(r, "position") >= from && integer(r, "position") <= to)
			.map(r -> number(r, "elo")).collect(Collectors.toList());
		return values.size() == to - from + 1 ? average(values) : null;
	}

	public List<Map<String, Object>> eraPlayers(AnalyticsFilter filter, int position) {
		return repository.query("eraPlayers", filter, Map.of("position", position));
	}

	@Cacheable("Analytics.Performance")
	public Map<String, Object> performance(AnalyticsFilter filter, int playerId) {
		var rows = repository.query("performance", filter, Map.of("playerId", playerId));
		for (var r : rows) {
			int n = integer(r, "rated_matches");
			if (n > 0) {
				double excess = number(r, "rated_wins") - number(r, "expected_wins");
				double margin = 1.96 * Math.sqrt(number(r, "variance"));
				double expected = number(r, "expected_wins");
				r.putAll(row("excess_wins", excess, "excess_low", Math.max(0, expected - margin) - expected,
					"excess_high", Math.min(n, expected + margin) - expected,
					"shrunk_excess_rate", excess / (n + 20.0)));
			}
			var interval = wilson(number(r, "wins"), integer(r, "matches"));
			r.put("win_low", interval[0]); r.put("win_high", interval[1]);
		}
		rows.sort(Comparator.comparingDouble((Map<String, Object> r) -> r.get("excess_wins") == null ? -Double.MAX_VALUE : number(r, "excess_wins")).reversed());
		return table(rows, "Expected wins sum pre-match overall Elo probabilities. Excess uses only matches with both ratings. The 95% expected residual range is centered at zero under the Elo model, uses a normal approximation and is clipped to possible win counts. Compare observed excess with this range; it excludes rating/model uncertainty. Shrunk excess uses 20 neutral prior matches. Opponent Elo and top-ten encounters describe opposition at the time.");
	}

	@Cacheable("Analytics.RelativeStats")
	public Map<String, Object> relativeStats(AnalyticsFilter filter, int playerId) {
		var rows = repository.query("relativeStats", filter, Map.of("playerId", playerId));
		for (var r : rows) {
			var serve = wilson(number(r, "serve_rate") * integer(r, "serve_points"), integer(r, "serve_points"));
			var returns = wilson(number(r, "return_rate") * integer(r, "return_points"), integer(r, "return_points"));
			r.putAll(row("serve_low", serve[0], "serve_high", serve[1], "return_low", returns[0], "return_high", returns[1]));
		}
		return table(rows, "Raw point, ace and hold rates sit beside point-weighted season-and-surface baselines in the selected scope. Above-baseline point rates shrink toward that baseline with 100 prior points. Wilson intervals describe raw point sampling only; points within a match are correlated. No opponent adjustment is claimed. Missing or invalid point totals are excluded.");
	}

	@Cacheable("Analytics.SurfaceConvergence")
	public Map<String, Object> surfaces(AnalyticsFilter filter) {
		return row("rows", repository.query("surfaces", filter), "specialists", repository.query("specialists", filter),
			"method", "Compare the same stored ranking dates and active yearly cohorts. Top-ten Jaccard overlap is intersection divided by union. Rank correlation uses common top-100 players and needs at least ten. Specialist titles require a surface rating at least 150 above the mean of at least two other available surface ratings before the final. These measures describe competitive specialization; they do not measure physical court speed.");
	}

	@Cacheable("Analytics.Peaks")
	public Map<String, Object> peaks(AnalyticsFilter filter, int playerId, int years) {
		return table(repository.query("peaks", filter, Map.of("playerId", playerId, "windowYears", years)),
			"Best trailing " + (years == 1 ? "52-week" : years + "-year") + " mean of stored weekly Elo snapshots entirely inside the selected range. Each window needs at least "
				+ (40 * years) + " snapshots, nearly the full calendar span and " + (filter.minMatches * years) + " completed matches. Each player appears once. Surface and level filters determine match eligibility; the rating column follows the selected surface or indoor/outdoor setting.");
	}

	@Cacheable("Analytics.Calendar")
	public Map<String, Object> calendar(AnalyticsFilter filter, int playerId, List<Integer> weights, int reference, int scale) {
		var rows = repository.query("calendar", filter, Map.of("playerId", playerId));
		var grouped = rows.stream().collect(Collectors.groupingBy(r -> integer(r, "player_id"), TreeMap::new, Collectors.toList()));
		var surfaces = List.of("H", "C", "G", "P");
		double totalWeight = weights.stream().mapToInt(Integer::intValue).sum();
		var results = new ArrayList<Map<String, Object>>();
		for (var entry : grouped.entrySet()) {
			double standardized = 0, modeled = 0, low = 0, high = 0, wins = 0, matches = 0;
			int requiredSurfaces = (int)weights.stream().filter(w -> w > 0).count();
			double z = new double[]{0, 1.96, 2.2414, 2.3940, 2.4977}[requiredSurfaces];
			var missing = new ArrayList<String>();
			var missingRatings = new ArrayList<String>();
			for (var r : entry.getValue()) { wins += number(r, "wins"); matches += number(r, "matches"); }
			for (int i = 0; i < surfaces.size(); i++) {
				if (weights.get(i) == 0) continue;
				var surface = surfaces.get(i);
				var found = entry.getValue().stream().filter(r -> surface.equals(r.get("surface"))).findFirst();
				if (found.isEmpty()) { missing.add(surface); continue; }
				var r = found.get();
				double weight = weights.get(i) / totalWeight;
				double rate = number(r, "win_rate");
				standardized += weight * rate;
				var interval = wilson(number(r, "wins"), integer(r, "matches"), z);
				low += weight * interval[0]; high += weight * interval[1];
				if (r.get("surface_elo") == null) missingRatings.add(surface);
				else modeled += weight * winProbability(number(r, "surface_elo"), reference, scale);
			}
			results.add(row("player_id", entry.getKey(), "name", entry.getValue().get(0).get("name"), "matches", matches,
				"observed_win_rate", wins / matches, "standardized_win_rate", missing.isEmpty() ? standardized : null,
				"standardized_low", missing.isEmpty() ? low : null,
				"standardized_high", missing.isEmpty() ? high : null,
				"modeled_wins_per100", missing.isEmpty() && missingRatings.isEmpty() ? 100 * modeled : null,
				"missing_surfaces", missing, "missing_rating_surfaces", missingRatings, "surfaces", entry.getValue()));
		}
		results.sort(Comparator.comparingDouble((Map<String, Object> r) -> r.get("standardized_win_rate") == null ? -1 : number(r, "standardized_win_rate")).reversed());
		var result = table(results, "Observed win rates are reweighted to your common surface mix, retaining historical opponents. The separate model scenario uses mean surface Elo against a reference opponent on every surface and reports expected wins per 100 matches, not titles. Positive-weight surfaces need the minimum sample; missing surfaces are never silently renormalized. The approximate 95% interval combines surface Wilson bounds with a Bonferroni correction and assumes independent matches; it omits schedule and model uncertainty.");
		result.put("weights", weights); result.put("reference", reference); result.put("scale", scale);
		return result;
	}

	public Map<String, Object> matchDetails(AnalyticsFilter filter, int playerId) {
		var rows = repository.query("matches", filter, Map.of("playerId", playerId));
		for (var r : rows) {
			if (r.get("winner_elo") == null || r.get("loser_elo") == null) continue;
			boolean winner = integer(r, "winner_id") == playerId;
			double probability = winProbability(number(r, "winner_elo"), number(r, "loser_elo"), 400);
			r.put("expected", winner ? probability : 1 - probability);
			r.put("residual", winner ? 1 - probability : -(1 - probability));
		}
		Collections.reverse(rows);
		return table(rows, "Completed matches in scope. Ratings and opponent rank are recorded at the match; positive residual means the result exceeded pre-match overall Elo expectation.");
	}

	@Cacheable("Analytics.Matchups")
	@Transactional(readOnly = true)
	public Map<String, Object> matchups(AnalyticsFilter filter, List<Integer> players) {
		var counts = new HashMap<Integer, Integer>();
		var names = new HashMap<Integer, String>();
		repository.scanMatches(filter, m -> {
			for (var side : List.of("winner", "loser")) {
				int id = integer(m, side + "_id"); counts.merge(id, 1, Integer::sum); names.put(id, (String)m.get(side));
			}
		});
		var ids = players.isEmpty() ? counts.keySet().stream().sorted(Comparator.<Integer>comparingInt(counts::get).reversed().thenComparingInt(Integer::intValue)).limit(10).collect(Collectors.toList()) : players;
		var pairs = new LinkedHashMap<String, Map<String, Object>>();
		repository.scanMatches(filter, m -> {
			int winner = integer(m, "winner_id"), loser = integer(m, "loser_id");
			if (!ids.contains(winner) || !ids.contains(loser) || m.get("winner_elo") == null || m.get("loser_elo") == null) return;
			for (int player : List.of(winner, loser)) {
				int opponent = player == winner ? loser : winner;
				String key = player + ":" + opponent;
				var pair = pairs.computeIfAbsent(key, k -> row("player_id", player, "opponent_id", opponent, "name", names.get(player), "opponent", names.get(opponent), "matches", 0, "wins", 0, "expected_wins", 0.0, "variance", 0.0));
				double p = winProbability(number(m, player == winner ? "winner_elo" : "loser_elo"), number(m, player == winner ? "loser_elo" : "winner_elo"), 400);
				pair.put("matches", integer(pair, "matches") + 1);
				pair.put("wins", integer(pair, "wins") + (player == winner ? 1 : 0));
				pair.put("expected_wins", number(pair, "expected_wins") + p);
				pair.put("variance", number(pair, "variance") + p * (1 - p));
			}
		});
		for (var pair : pairs.values()) {
			double excess = number(pair, "wins") - number(pair, "expected_wins");
			pair.putAll(row("excess_wins", excess, "shrunk_excess_rate", excess / (integer(pair, "matches") + 20.0),
				"enough_matches", integer(pair, "matches") >= filter.minMatches,
				"margin", 1.96 * Math.sqrt(number(pair, "variance"))));
		}
		return row("players", ids.stream().map(id -> row("player_id", id, "name", names.getOrDefault(id, "Player " + id))).collect(Collectors.toList()),
			"rows", new ArrayList<>(pairs.values()), "method", "Actual wins minus expected wins from contemporaneous overall Elo, within the selected surface, conditions and period. Matrix color uses 20 neutral prior matches; cells below the minimum sample stay uncolored. These residuals do not establish a causal matchup advantage or independently adjust for age.");
	}

	public List<Map<String, Object>> events(AnalyticsFilter filter) { return repository.query("events", filter); }

	static Map<String, Object> table(List<Map<String, Object>> rows, String method) {
		return row("rows", new ArrayList<>(rows.subList(0, Math.min(500, rows.size()))), "total_rows", rows.size(), "row_limit", 500, "method", method);
	}
}
