package org.strangeforest.tcb.stats.api;

import java.util.*;

import org.springframework.stereotype.*;
import org.strangeforest.tcb.stats.analytics.*;
import org.strangeforest.tcb.stats.model.core.*;
import org.strangeforest.tcb.stats.model.prediction.*;

@Service
public class ResearchLab {

	public Map<String, Object> rules(double serve, double returns) {
		finite("pServe", serve, .05, .95); finite("pReturn", returns, .05, .95);
		var rows = new ArrayList<Map<String, Object>>();
		for (int bestOf : List.of(3, 5)) for (boolean noAd : List.of(false, true)) for (String deciding : List.of("standard", "super", "advantage")) {
			var game = noAd ? new GameRules(4, 1) : GameRules.COMMON_GAME;
			var normal = new SetRules(6, 2, game, 6, TieBreakRules.COMMON_TIE_BREAK);
			var last = new SetRules(6, 2, game, deciding.equals("advantage") ? null : 6,
				deciding.equals("advantage") ? null : deciding.equals("super") ? TieBreakRules.SUPER_TIE_BREAK : TieBreakRules.COMMON_TIE_BREAK);
			double probability = new MatchOutcome(serve, returns, new MatchRules(bestOf, normal, last)).pWin();
			rows.add(Map.of("best_of", bestOf, "no_ad", noAd, "deciding_set", deciding, "player1_probability", probability, "player2_probability", 1 - probability));
		}
		return Map.of("data", rows, "inputs", Map.of("pServe", serve, "pReturn", returns), "model_version", "stationary-scoring-v1",
			"assumptions", List.of("pServe and pReturn are Player 1's point-win probabilities when serving and returning.",
				"Point probabilities are fixed and independent. No fatigue, injuries or tactical adaptation is modeled.",
				"Super means a first-to-ten, win-by-two tiebreak at 6–6 in the deciding set. Advantage means no deciding-set tiebreak."));
	}

	public Map<String, Object> prediction(Map<String, Object> input) {
		keys(input, Set.of("player1Elo", "player2Elo", "scale"));
		double a = number(input, "player1Elo", null, 500, 3500), b = number(input, "player2Elo", null, 500, 3500);
		double scale = number(input, "scale", 400.0, 200, 800);
		double p = AnalyticsMath.winProbability(a, b, scale);
		return Map.of("data", Map.of("player1_probability", p, "player2_probability", 1 - p), "inputs", input,
			"model_version", "elo-logistic-v1", "scale", scale,
			"assumptions", List.of("An Elo baseline using supplied ratings; not the application's combined match-prediction model.", "Probabilities are conditional on the ratings and scale, not confidence intervals."));
	}

	public Map<String, Object> tournament(Map<String, Object> input) {
		keys(input, Set.of("players", "seed", "iterations", "scale", "draw"));
		double scale = number(input, "scale", 400.0, 200, 800);
		int seed = integer(input, "seed", 1973, 0, Integer.MAX_VALUE);
		String mode = Objects.toString(input.getOrDefault("draw", "seeded"));
		if (!Set.of("seeded", "fixed").contains(mode)) throw new IllegalArgumentException("draw must be seeded or fixed.");
		int iterations = integer(input, "iterations", mode.equals("fixed") ? 1 : 200, 1, 500);
		if (mode.equals("fixed") && iterations != 1) throw new IllegalArgumentException("A fixed draw uses exactly one iteration.");
		if (!(input.get("players") instanceof List)) throw new IllegalArgumentException("players must be an array.");
		var players = (List<?>)input.get("players");
		if (players.size() < 2 || players.size() > 64) throw new IllegalArgumentException("Choose between 2 and 64 players.");
		var entrants = new ArrayList<DrawSimulator.Entrant>();
		var ids = new HashSet<Integer>();
		var seeds = new HashSet<Integer>();
		int size = Integer.highestOneBit(players.size() - 1) * 2;
		for (var object : players) {
			if (!(object instanceof Map)) throw new IllegalArgumentException("Each player must be an object.");
			@SuppressWarnings("unchecked") var player = (Map<String, Object>)object;
			keys(player, Set.of("id", "name", "elo", "seed"));
			int id = integer(player, "id", entrants.size() + 1, 1, Integer.MAX_VALUE);
			if (!ids.add(id)) throw new IllegalArgumentException("Player IDs must be unique within a simulation.");
			String name = Objects.toString(player.getOrDefault("name", "Player " + id));
			if (name.isBlank() || name.length() > 100) throw new IllegalArgumentException("Names need between 1 and 100 characters.");
			Integer rank = player.containsKey("seed") ? integer(player, "seed", 0, 1, size / 2) : null;
			if (rank != null && !seeds.add(rank)) throw new IllegalArgumentException("Seeds must be unique.");
			entrants.add(new DrawSimulator.Entrant(id, name, rank, number(player, "elo", null, 500, 3500)));
		}
		if (mode.equals("fixed") && size != entrants.size()) throw new IllegalArgumentException("Fixed draws require a power-of-two field; use seeded draws for byes.");
		var totals = new HashMap<Integer, double[]>();
		int roundCount = Integer.numberOfTrailingZeros(size);
		for (var entrant : entrants) totals.put(entrant.id, new double[roundCount]);
		var random = new Random(seed);
		for (int i = 0; i < iterations; i++) {
			var bracket = mode.equals("fixed") ? entrants : DrawSimulator.seededDraw(entrants, size, random);
			var rounds = DrawSimulator.roundProbabilities(bracket, scale);
			for (int round = 0; round < rounds.size(); round++)
				for (var entry : rounds.get(round).entrySet()) totals.get(entry.getKey())[round] += entry.getValue() / iterations;
		}
		var rows = new ArrayList<Map<String, Object>>();
		for (var entrant : entrants) rows.add(Map.of("id", entrant.id, "name", entrant.name, "elo", entrant.elo,
			"round_probabilities", totals.get(entrant.id), "title_probability", totals.get(entrant.id)[roundCount - 1]));
		return Map.of("data", rows, "inputs", input, "seed", seed, "iterations", iterations, "draw", mode, "draw_size", size, "scale", scale,
			"model_version", "elo-knockout-v1", "assumptions", List.of("Supplied ratings remain fixed for the tournament; no injuries or fatigue.",
				"Round probabilities mean advancing past each round, including byes; the last value is the title probability.",
				"Fixed brackets pair adjacent input players. Seeded draws use standard seed tiers and assign byes to leading seeds first.",
				"Exact probabilities within each bracket; seeded results average sampled brackets. Variation across brackets is not uncertainty in player ability."));
	}

	private static void keys(Map<String, ?> input, Set<String> allowed) {
		for (var key : input.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown input: " + key);
	}

	private static double number(Map<String, ?> input, String key, Double fallback, double min, double max) {
		Object value = input.containsKey(key) ? input.get(key) : fallback;
		if (!(value instanceof Number)) throw new IllegalArgumentException(key + " must be a number.");
		double result = ((Number)value).doubleValue(); finite(key, result, min, max); return result;
	}

	private static int integer(Map<String, ?> input, String key, int fallback, int min, int max) {
		double value = number(input, key, (double)fallback, min, max);
		if (value != Math.rint(value)) throw new IllegalArgumentException(key + " must be an integer.");
		return (int)value;
	}

	private static void finite(String key, double value, double min, double max) {
		if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ".");
	}
}
