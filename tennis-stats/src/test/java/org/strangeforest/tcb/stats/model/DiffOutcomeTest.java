package org.strangeforest.tcb.stats.model;

import org.junit.jupiter.api.*;
import org.strangeforest.tcb.stats.model.prediction.*;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.data.Offset.*;

class DiffOutcomeTest {

	@Test void scoreEvaluationHasQuadraticWorkAndCanReusePreviousResults() {
		var outcome = new CountingOutcome();
		assertThat(outcome.pWin()).isCloseTo(.5, offset(1e-12));
		// A first-to-ten outcome must not enumerate exponentially many point paths.
		assertThat(outcome.evaluations).isLessThanOrEqualTo(4 * 11 * 11);
		int firstCalculation = outcome.evaluations;
		assertThat(outcome.pWin()).isCloseTo(.5, offset(1e-12));
		assertThat(outcome.evaluations).isEqualTo(firstCalculation);
	}

	@Test void extendedDeuceStatesRetainTheAnalyticalSolution() {
		var outcome = new GameOutcome(.75);
		assertThat(outcome.pWin(40, 40)).isCloseTo(.9, offset(1e-12));
		assertThat(outcome.pWin(41, 40)).isCloseTo(.975, offset(1e-12));
		assertThat(outcome.pWin(40, 41)).isCloseTo(.675, offset(1e-12));
	}

	private static final class CountingOutcome extends DiffOutcome {
		int evaluations;
		CountingOutcome() { super(10, 2); }
		@Override protected double pItemWin(int item) { evaluations++; return .5; }
	}
}
