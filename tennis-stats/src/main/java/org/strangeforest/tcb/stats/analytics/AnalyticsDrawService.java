package org.strangeforest.tcb.stats.analytics;

import java.util.*;

import org.springframework.cache.annotation.*;
import org.springframework.stereotype.*;

import static org.strangeforest.tcb.stats.analytics.AnalyticsMath.*;
import static org.strangeforest.tcb.stats.analytics.DrawSimulator.*;

@Service
public class AnalyticsDrawService {

	private static final List<String> ROUNDS = List.of("R128", "R64", "R32", "R16", "QF", "SF", "F");
	private final AnalyticsRepository repository;

	public AnalyticsDrawService(AnalyticsRepository repository) { this.repository = repository; }

	@Cacheable("Analytics.Draw")
	public Map<String, Object> draw(AnalyticsFilter filter, int eventId, int iterations, int scale) {
		var matches = repository.query("draw", filter, Map.of("eventId", eventId));
		if (matches.isEmpty()) throw new IllegalArgumentException("Tournament event was not found.");
		var entrants = new LinkedHashMap<Integer, Entrant>();
		for (var m : matches) for (var side : List.of("winner", "loser")) {
			int id = integer(m, side + "_id");
			entrants.computeIfAbsent(id, key -> new Entrant(id, (String)m.get(side),
				m.get(side + "_seed") == null ? null : integer(m, side + "_seed"),
				m.get(side + "_elo") == null ? null : number(m, side + "_elo")));
		}
		var details = new ArrayList<Map<String, Object>>();
		for (var entrant : entrants.values()) {
			var path = new ArrayList<Map<String, Object>>();
			double pathProbability = 1;
			boolean rated = entrant.elo != null;
			for (var m : matches) {
				boolean winner = integer(m, "winner_id") == entrant.id;
				if (!winner && integer(m, "loser_id") != entrant.id) continue;
				var opponent = entrants.get(integer(m, winner ? "loser_id" : "winner_id"));
				boolean walkover = "W/O".equals(m.get("outcome"));
				Double probability = null;
				if (walkover) probability = winner ? 1.0 : 0.0;
				else if (entrant.elo != null && opponent.elo != null) probability = winProbability(entrant.elo, opponent.elo, scale);
				if (probability == null) rated = false;
				else pathProbability *= probability;
				path.add(row("match_id", m.get("match_id"), "round", m.get("round"), "opponent_id", opponent.id,
					"opponent", opponent.name, "opponent_elo", opponent.elo, "won", winner,
					"probability", probability, "outcome", m.get("outcome"), "score", m.get("score")));
			}
			details.add(row("player_id", entrant.id, "name", entrant.name, "seed", entrant.seed, "entry_elo", entrant.elo,
				"path_probability", rated ? pathProbability : null, "path", path));
		}
		var meta = matches.get(0);
		var result = row("event_id", eventId, "name", meta.get("name"), "season", meta.get("season"),
			"surface", meta.get("surface"), "rows", details, "entrants", entrants.size(),
			"rated_entrants", entrants.values().stream().filter(e -> e.elo != null).count(), "simulated", false,
			"method", "Entry ratings are each player's first recorded pre-match overall Elo in this event. Actual route probability multiplies chances against the opponents faced, through elimination; a walkover contributes 1. Actual-bracket title probabilities integrate over all opponents, without fixing historical winners. Redraws preserve standard seed tiers and assign byes to the highest seeds first; they do not reproduce every historical seeding convention. Monte Carlo error describes bracket sampling only. Retirements remain visible and are not modeled separately.");
		if (!"KO".equals(meta.get("draw_type"))) return unavailable(result, "Redraws support standard knockout events only. Recorded routes remain available.");
		if (entrants.size() < 2 || entrants.size() > 128) return unavailable(result, "Redraws require 2–128 recorded entrants.");
		if (entrants.values().stream().anyMatch(e -> e.elo == null)) return unavailable(result, "Some entry ratings are missing; probabilities are not imputed.");
		int size = 2;
		while (size < entrants.size()) size *= 2;
		if (meta.get("draw_size") != null && integer(meta, "draw_size") > size)
			return unavailable(result, "The recorded field is incomplete for this draw size.");
		var finalMatches = matches.stream().filter(m -> "F".equals(m.get("round"))).toArray();
		if (finalMatches.length != 1) return unavailable(result, "A single completed final is required to reconstruct the bracket.");
		if (matches.stream().anyMatch(m -> "RR".equals(m.get("round")))
			|| matches.stream().filter(m -> !"BR".equals(m.get("round"))).count() != entrants.size() - 1)
			return unavailable(result, "The recorded match count does not form a complete knockout bracket.");
		try {
			@SuppressWarnings("unchecked") var finalMatch = (Map<String, Object>)finalMatches[0];
			if ("ABD".equals(finalMatch.get("outcome"))) return unavailable(result, "The recorded final was abandoned.");
			int firstRound = 7 - Integer.numberOfTrailingZeros(size);
			var slots = new ArrayList<Entrant>();
			slots.addAll(branch(integer(finalMatch, "winner_id"), 5, firstRound, matches, entrants));
			slots.addAll(branch(integer(finalMatch, "loser_id"), 5, firstRound, matches, entrants));
			var present = new HashSet<Integer>();
			for (var e : slots) if (e != null && !present.add(e.id)) throw new IllegalArgumentException("Recorded rounds do not form a consistent bracket.");
			if (!present.equals(entrants.keySet()) || slots.size() != size) throw new IllegalArgumentException("Recorded rounds do not cover the full field.");
			var actual = probabilities(slots, scale);
			var sums = new HashMap<Integer, Double>();
			var squares = new HashMap<Integer, Double>();
			var random = new Random(1973L + eventId);
			var field = new ArrayList<>(entrants.values());
			for (int i = 0; i < iterations; i++) {
				var probabilities = probabilities(seededDraw(field, size, random), scale);
				probabilities.forEach((id, p) -> { sums.merge(id, p, Double::sum); squares.merge(id, p * p, Double::sum); });
			}
			for (var r : details) {
				int id = integer(r, "player_id");
				double mean = sums.get(id) / iterations;
				double variance = Math.max(0, (squares.get(id) - iterations * mean * mean) / (iterations - 1));
				r.putAll(row("actual_title_probability", actual.get(id), "redraw_title_probability", mean,
					"draw_bonus", actual.get(id) - mean, "simulation_error", 1.96 * Math.sqrt(variance / iterations)));
			}
			result.put("simulated", true); result.put("iterations", iterations); result.put("draw_size", size);
		}
		catch (IllegalArgumentException e) { return unavailable(result, e.getMessage()); }
		return result;
	}

	private static Map<String, Object> unavailable(Map<String, Object> result, String reason) {
		result.put("reason", reason); return result;
	}

	private static List<Entrant> branch(int player, int round, int firstRound, List<Map<String, Object>> matches, Map<Integer, Entrant> entrants) {
		if (round < firstRound) return new ArrayList<>(Collections.singletonList(entrants.get(player)));
		var previous = matches.stream().filter(m -> ROUNDS.get(round).equals(m.get("round")) && integer(m, "winner_id") == player).findFirst();
		if (previous.isEmpty()) {
			if (round != firstRound) throw new IllegalArgumentException("Earlier rounds are missing; the actual bracket cannot be reconstructed reliably.");
			return new ArrayList<>(Arrays.asList(entrants.get(player), null));
		}
		var m = previous.get();
		var result = branch(integer(m, "winner_id"), round - 1, firstRound, matches, entrants);
		result.addAll(branch(integer(m, "loser_id"), round - 1, firstRound, matches, entrants));
		return result;
	}
}
