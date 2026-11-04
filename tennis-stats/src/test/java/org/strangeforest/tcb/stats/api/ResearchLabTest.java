package org.strangeforest.tcb.stats.api;

import java.math.*;
import java.util.*;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.data.Offset.*;

class ResearchLabTest {
	private final ResearchLab lab = new ResearchLab();

	@Test void equalPlayersStayEqualUnderEveryScoringRule() {
		for (var row : rows(lab.rules(.65, .35)))
			assertThat(((Number)row.get("player1_probability")).doubleValue()).isCloseTo(.5, offset(1e-10));
		assertThat(rows(lab.rules(.65, .35))).hasSize(12);
	}

	@Test void longerMatchesFavorTheStrongerPlayerAndProbabilitiesAreComplementary() {
		var rows = rows(lab.rules(.65, .4));
		assertThat(((Number)rows.get(6).get("player1_probability")).doubleValue()).isGreaterThan(((Number)rows.get(0).get("player1_probability")).doubleValue());
		for (var row : rows) assertThat(number(row, "player1_probability") + number(row, "player2_probability")).isCloseTo(1, offset(1e-12));
	}

	@Test void equalFixedFieldConservesEveryRoundsProbabilityMass() {
		var input = Map.<String, Object>of("players", players(8, 2000), "draw", "fixed");
		var rows = rows(lab.tournament(input));
		for (int round = 0; round < 3; round++) {
			double sum = 0;
			for (var row : rows) sum += ((double[])row.get("round_probabilities"))[round];
			assertThat(sum).isCloseTo(8.0 / (1 << (round + 1)), offset(1e-10));
		}
		for (var row : rows) assertThat(number(row, "title_probability")).isCloseTo(.125, offset(1e-12));
	}

	@Test void seededRedrawsAreReproducibleWithByes() {
		var input = Map.<String, Object>of("players", players(5, 2000), "seed", 42, "iterations", 50);
		var first = lab.tournament(input); var second = lab.tournament(input);
		for (int i = 0; i < 5; i++) assertThat((double[])rows(first).get(i).get("round_probabilities")).containsExactly((double[])rows(second).get(i).get("round_probabilities"));
		assertThat(rows(first).stream().mapToDouble(r -> number(r, "title_probability")).sum()).isCloseTo(1, offset(1e-10));
	}

	@Test void invalidOrExcessiveExperimentsAreRejected() {
		for (var input : List.of(Map.<String,Object>of("players", players(65, 2000)), Map.<String,Object>of("players", players(3, 2000), "draw", "fixed"),
			Map.<String,Object>of("players", players(4, 2000), "iterations", 501), Map.<String,Object>of("players", players(4, 2000), "iterations", 1.5),
			Map.<String,Object>of("players", List.of(Map.of("id", 1, "elo", 2000), Map.of("id", 1, "elo", 2000))),
			Map.<String,Object>of("players", List.of(Map.of("elo", 2000, "seed", 1), Map.of("elo", 2000, "seed", 1)))))
			assertThatThrownBy(() -> lab.tournament(input)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> lab.rules(Double.NaN, .4)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> lab.rules(.95, 1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test void integerInputsAreExactBeforeRangeChecking() {
		for (var invalid : List.of(new BigDecimal("2147483647.0000000001"), new BigDecimal("-0.0000000000000000001"),
			new BigInteger("18446744073709551616"), Long.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, "1"))
			assertThatThrownBy(() -> lab.tournament(Map.of("players", players(2, 2000), "seed", invalid)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("seed must be an integer");
		assertThatThrownBy(() -> lab.tournament(Map.of("players", players(2, 2000), "iterations", new BigDecimal("1.0000000000000000001"))))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("iterations must be an integer");
		var boundary = lab.tournament(Map.of("players", List.of(Map.of("id", new BigDecimal("2147483647.0"), "elo", 2000), Map.of("id", 1L, "elo", 2000)),
			"seed", new BigInteger("2147483647"), "draw", "fixed"));
		assertThat(boundary.get("seed")).isEqualTo(Integer.MAX_VALUE);
		assertThat(rows(boundary).get(0).get("id")).isEqualTo(Integer.MAX_VALUE);
	}

	private static List<Map<String,Object>> players(int count, int elo) {
		var rows = new ArrayList<Map<String,Object>>(); for (int i = 0; i < count; i++) rows.add(Map.of("id", i + 1, "elo", elo)); return rows;
	}
	@SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> result) { return (List<Map<String,Object>>)result.get("data"); }
	private static double number(Map<String,Object> row, String key) { return ((Number)row.get(key)).doubleValue(); }
}
