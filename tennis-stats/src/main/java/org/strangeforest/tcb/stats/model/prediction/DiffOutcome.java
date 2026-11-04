package org.strangeforest.tcb.stats.model.prediction;

public abstract class DiffOutcome {

	private final int items;
	private final int itemsDiff;
	private final Double[][] probabilities;

	protected DiffOutcome(int items, int itemsDiff) {
		if (itemsDiff > 2)
			throw new IllegalArgumentException();
		this.items = items;
		this.itemsDiff = itemsDiff;
		// Bound each instance's storage, even for custom rules with large targets.
		int size = Math.min(Math.max(items, 0), 32) + 1;
		probabilities = new Double[size][size];
	}

	public double pWin() {
		return pWin(0, 0);
	}

	public synchronized double pWin(int items1, int items2) {
		if (items1 >= 0 && items1 < probabilities.length && items2 >= 0 && items2 < probabilities.length) {
			var cached = probabilities[items1][items2];
			if (cached != null) return cached;
			double probability = calculate(items1, items2);
			probabilities[items1][items2] = probability;
			return probability;
		}
		return calculate(items1, items2);
	}

	private double calculate(int items1, int items2) {
		var nextItem = items1 + items2 + 1;
		if (items1 >= items && items1 >= items2) {
			var diff = items1 - items2;
			if (diff >= itemsDiff)
				return 1.0;
			switch (diff) {
				case 0: return pDeuce(pItemWin(nextItem), pItemWin(nextItem + 1), items1, items2);
				case 1: {
					var p = pItemWin(nextItem);
					return p + (1.0 - p) * pDeuce(pItemWin(nextItem + 1), pItemWin(nextItem + 2), items1, items2 + 1);
				}
				default: throw new IllegalStateException();
			}
		}
		if (items2 >= items) {
			var diff = items2 - items1;
			if (diff >= itemsDiff)
				return 0.0;
			switch (diff) {
				case 0: return pDeuce(pItemWin(nextItem), pItemWin(nextItem + 1), items1, items2);
				case 1: {
					var p = pItemWin(nextItem);
					return p * pDeuce(pItemWin(nextItem + 1), pItemWin(nextItem + 2), items1 + 1, items2);
				}
				default: throw new IllegalStateException();
			}
		}
		var p = pItemWin(nextItem);
		return p * pWin(items1 + 1, items2) + (1.0 - p) * pWin(items1, items2 + 1);
	}

	protected abstract double pItemWin(int item);

	protected double pDeuce(double p1, double p2, int items1, int items2) {
		if (p1 <= 0.0)
			return 0.0;
		else if (p2 <= 0.0)
			return 1.0;
		var p12 = p1 * p2;
		return p12 / (1.0 - p1 - p2 + 2.0 * p12);
	}
}
