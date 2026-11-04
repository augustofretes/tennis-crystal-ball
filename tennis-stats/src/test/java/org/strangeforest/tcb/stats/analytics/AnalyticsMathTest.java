package org.strangeforest.tcb.stats.analytics;

import java.util.*;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.data.Offset.*;
import static org.strangeforest.tcb.stats.analytics.AnalyticsMath.*;
import static org.strangeforest.tcb.stats.analytics.DrawSimulator.*;

class AnalyticsMathTest {

	@Test void eloProbabilitiesAreComplementaryAndRespectTheScale() {
		assertThat(winProbability(2000, 2000, 400)).isEqualTo(.5);
		assertThat(winProbability(2400, 2000, 400)).isCloseTo(10.0 / 11, offset(1e-12));
		assertThat(winProbability(2200, 2000, 400) + winProbability(2000, 2200, 400)).isCloseTo(1, offset(1e-12));
		assertThat(winProbability(2200, 2000, 500)).isLessThan(winProbability(2200, 2000, 300));
	}

	@Test void uncertaintyDoesNotDisappearForTinyOrPerfectSamples() {
		assertThat(wilson(0, 0)).containsOnlyNulls();
		assertThat(wilson(1, 1)[0]).isBetween(.2, .21);
		assertThat(wilson(1, 1)[1]).isCloseTo(1, offset(1e-12));
		assertThat(wilson(50, 100)[0]).isBetween(.40, .41);
		assertThat(wilson(50, 100)[1]).isBetween(.59, .60);
	}

	@Test void tiesReceiveAverageRanks() {
		assertThat(ranks(List.of(30.0, 10.0, 10.0, 40.0))).containsExactly(3.0, 1.5, 1.5, 4.0);
		assertThat(correlation(List.of(1.0, 2.0, 3.0), List.of(3.0, 2.0, 1.0))).isCloseTo(-1, offset(1e-12));
		assertThat(correlation(List.of(1.0, 1.0, 1.0), List.of(3.0, 2.0, 1.0))).isNaN();
	}

	@Test void equalKnockoutFieldsHaveEqualTitleChances() {
		var field = new ArrayList<Entrant>();
		for (int i = 1; i <= 8; i++) field.add(new Entrant(i, "Player " + i, null, 2000.0));
		assertThat(probabilities(field, 400).values()).allSatisfy(p -> assertThat(p).isCloseTo(.125, offset(1e-12)));
	}

	@Test void bracketProbabilitiesIntegratePotentialFinalOpponents() {
		var a = new Entrant(1, "A", 1, 2400.0);
		var b = new Entrant(2, "B", null, 2000.0);
		var c = new Entrant(3, "C", 2, 2200.0);
		var d = new Entrant(4, "D", null, 1800.0);
		var result = probabilities(List.of(a, b, c, d), 400);
		double expected = winProbability(2400, 2000, 400) * (winProbability(2200, 1800, 400) * winProbability(2400, 2200, 400)
			+ winProbability(1800, 2200, 400) * winProbability(2400, 1800, 400));
		assertThat(result.get(1)).isCloseTo(expected, offset(1e-12));
		assertThat(result.values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1, offset(1e-12));
		assertThat(probabilities(Arrays.asList(a, null, c, null), 400).get(1)).isCloseTo(winProbability(2400, 2200, 400), offset(1e-12));
	}

	@Test void redrawsKeepSeedTiersApartAndGiveByesToLeadingSeeds() {
		var field = new ArrayList<Entrant>();
		for (int i = 1; i <= 96; i++) field.add(new Entrant(i, "P" + i, i <= 32 ? i : null, 2400.0 - i));
		var random = new Random(1973);
		for (int trial = 0; trial < 20; trial++) {
			var slots = seededDraw(field, 128, random);
			assertThat(slots.stream().filter(Objects::nonNull).count()).isEqualTo(96);
			for (int i = 0; i < 128; i++) if (slots.get(i) != null && slots.get(i).seed != null) assertThat(slots.get(i ^ 1)).isNull();
			for (int tier : List.of(2, 4, 8, 16, 32)) {
				var sections = new HashSet<Integer>();
				for (int i = 0; i < slots.size(); i++) if (slots.get(i) != null && slots.get(i).seed != null && slots.get(i).seed <= tier) sections.add(i / (128 / tier));
				assertThat(sections).hasSize(tier);
			}
			assertThat(probabilities(slots, 400).values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1, offset(1e-10));
		}
	}

	@Test void missingRatingsAndDuplicateEntrantsCannotProduceProbabilities() {
		var a = new Entrant(1, "A", null, 2000.0);
		assertThatThrownBy(() -> probabilities(List.of(a, a), 400)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> probabilities(List.of(a, new Entrant(2, "B", null, null)), 400)).isInstanceOf(IllegalArgumentException.class);
		assertThat(referenceTitleChance(List.of(2000.0), 2000, 400)).isNull();
	}
}
