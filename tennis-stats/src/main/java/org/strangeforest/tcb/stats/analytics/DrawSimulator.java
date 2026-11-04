package org.strangeforest.tcb.stats.analytics;

import java.util.*;

/** Exact knockout probabilities conditional on a bracket; seeded Monte Carlo over brackets. */
public final class DrawSimulator {

	public static final class Entrant {
		public final int id;
		public final String name;
		public final Integer seed;
		public final Double elo;

		public Entrant(int id, String name, Integer seed, Double elo) {
			this.id = id;
			this.name = name;
			this.seed = seed;
			this.elo = elo;
		}
	}

	private DrawSimulator() {}

	public static Map<Integer, Double> probabilities(List<Entrant> slots, double scale) {
		var rounds = roundProbabilities(slots, scale);
		return rounds.isEmpty() ? (slots.get(0) == null ? Collections.emptyMap() : Map.of(slots.get(0).id, 1.0)) : rounds.get(rounds.size() - 1);
	}

	/** Probability of advancing past each round, including a bye where present. */
	public static List<Map<Integer, Double>> roundProbabilities(List<Entrant> slots, double scale) {
		if (slots.isEmpty() || (slots.size() & (slots.size() - 1)) != 0)
			throw new IllegalArgumentException("A knockout bracket must have a power-of-two number of slots.");
		var entrants = new HashMap<Integer, Entrant>();
		var rounds = new ArrayList<Map<Integer, Double>>();
		for (var entrant : slots) {
			var probability = new HashMap<Integer, Double>();
			if (entrant != null) {
				if (entrant.elo == null) throw new IllegalArgumentException("Every simulated entrant needs a rating.");
				if (entrants.put(entrant.id, entrant) != null) throw new IllegalArgumentException("Duplicate entrant.");
				probability.put(entrant.id, 1.0);
			}
			rounds.add(probability);
		}
		var progression = new ArrayList<Map<Integer, Double>>();
		while (rounds.size() > 1) {
			var next = new ArrayList<Map<Integer, Double>>();
			for (int i = 0; i < rounds.size(); i += 2) {
				var left = rounds.get(i);
				var right = rounds.get(i + 1);
				var result = new HashMap<Integer, Double>();
				advance(left, right, entrants, scale, result);
				advance(right, left, entrants, scale, result);
				next.add(result);
			}
			rounds = next;
			var combined = new HashMap<Integer, Double>();
			for (var bracket : rounds) combined.putAll(bracket);
			progression.add(combined);
		}
		return progression;
	}

	private static void advance(Map<Integer, Double> side, Map<Integer, Double> opponents,
		Map<Integer, Entrant> entrants, double scale, Map<Integer, Double> result) {
		for (var player : side.entrySet()) {
			double chance = opponents.isEmpty() ? 1 : 0;
			for (var opponent : opponents.entrySet())
				chance += opponent.getValue() * AnalyticsMath.winProbability(entrants.get(player.getKey()).elo, entrants.get(opponent.getKey()).elo, scale);
			result.put(player.getKey(), player.getValue() * chance);
		}
	}

	/** Seed tiers occupy distinct halves/quarters/etc.; highest seeds receive byes first. */
	public static List<Entrant> seededDraw(List<Entrant> entrants, int size, Random random) {
		if (size < entrants.size() || (size & (size - 1)) != 0 || size < 2)
			throw new IllegalArgumentException("Invalid draw size.");
		var slots = new ArrayList<Entrant>(Collections.nCopies(size, null));
		var seeded = new ArrayList<Entrant>();
		var unseeded = new ArrayList<Entrant>();
		for (var entrant : entrants) {
			if (entrant.seed != null && entrant.seed > 0) seeded.add(entrant);
			else unseeded.add(entrant);
		}
		seeded.sort(Comparator.comparingInt(e -> e.seed));
		if (seeded.size() > size / 2) throw new IllegalArgumentException("Too many seeds for a standard knockout draw.");
		var order = new ArrayList<Integer>(List.of(1, 2));
		while (order.size() < size) {
			var expanded = new ArrayList<Integer>();
			int complement = order.size() * 2 + 1;
			for (var seed : order) { expanded.add(seed); expanded.add(complement - seed); }
			order = expanded;
		}
		var seedSlots = new HashMap<Integer, Integer>();
		for (int i = 0; i < order.size(); i++) seedSlots.put(order.get(i), i);
		var tiers = new TreeMap<Integer, List<Entrant>>();
		var usedSeeds = new HashSet<Integer>();
		for (var entrant : seeded) {
			if (entrant.seed > size / 2 || !usedSeeds.add(entrant.seed)) throw new IllegalArgumentException("Unsupported seeding.");
			int tier = entrant.seed <= 2 ? entrant.seed : Integer.highestOneBit(entrant.seed - 1) * 2;
			tiers.computeIfAbsent(tier, key -> new ArrayList<>()).add(entrant);
		}
		for (var tier : tiers.values()) {
			var shuffled = new ArrayList<>(tier);
			Collections.shuffle(shuffled, random);
			for (int i = 0; i < tier.size(); i++) slots.set(seedSlots.get(tier.get(i).seed), shuffled.get(i));
		}
		var byeSlots = new HashSet<Integer>();
		int byes = size - entrants.size();
		for (int i = 0; i < Math.min(byes, seeded.size()); i++) {
			var entrant = seeded.get(i);
			int position = slots.indexOf(entrant);
			byeSlots.add(position ^ 1);
		}
		var free = new ArrayList<Integer>();
		for (int i = 0; i < size; i++) if (slots.get(i) == null && !byeSlots.contains(i)) free.add(i);
		Collections.shuffle(free, random);
		Collections.shuffle(unseeded, random);
		for (int i = 0; i < unseeded.size(); i++) slots.set(free.get(i), unseeded.get(i));
		return slots;
	}

	public static Double referenceTitleChance(List<Double> orderedRatings, double reference, double scale) {
		if (orderedRatings.size() < 32) return null;
		var field = new ArrayList<Entrant>();
		field.add(new Entrant(-1, "Reference player", null, reference));
		for (int i = 0; i < 31; i++) field.add(new Entrant(i, "Position " + (i + 1), null, orderedRatings.get(i)));
		var random = new Random(1973);
		double sum = 0;
		for (int i = 0; i < 100; i++) sum += probabilities(seededDraw(field, 32, random), scale).get(-1);
		return sum / 100;
	}
}
