package org.strangeforest.tcb.stats.analytics;

import java.util.*;

public final class AnalyticsMath {

	private AnalyticsMath() {}

	public static double winProbability(double playerRating, double opponentRating, double scale) {
		return 1.0 / (1.0 + Math.pow(10.0, (opponentRating - playerRating) / scale));
	}

	/** Wilson interval for an observed binomial rate; undefined for an empty sample. */
	public static Double[] wilson(double wins, int matches) {
		return wilson(wins, matches, 1.96);
	}

	public static Double[] wilson(double wins, int matches, double z) {
		if (matches == 0) return new Double[]{null, null};
		var p = wins / matches;
		var z2 = z * z;
		var center = (p + z2 / (2 * matches)) / (1 + z2 / matches);
		var radius = z * Math.sqrt(p * (1 - p) / matches + z2 / (4.0 * matches * matches)) / (1 + z2 / matches);
		return new Double[]{Math.max(0, center - radius), Math.min(1, center + radius)};
	}

	public static Double average(Collection<Double> values) {
		return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
	}

	public static double correlation(List<Double> x, List<Double> y) {
		if (x.size() < 3 || x.size() != y.size()) return Double.NaN;
		var mx = average(x);
		var my = average(y);
		double xx = 0, yy = 0, xy = 0;
		for (int i = 0; i < x.size(); i++) {
			var dx = x.get(i) - mx;
			var dy = y.get(i) - my;
			xx += dx * dx;
			yy += dy * dy;
			xy += dx * dy;
		}
		return xx == 0 || yy == 0 ? Double.NaN : xy / Math.sqrt(xx * yy);
	}

	/** Rank values with average ranks for ties, for a Spearman correlation. */
	public static List<Double> ranks(List<Double> values) {
		var sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		var ranks = new HashMap<Double, Double>();
		for (int i = 0; i < sorted.size();) {
			int end = i + 1;
			while (end < sorted.size() && sorted.get(end).equals(sorted.get(i))) end++;
			ranks.put(sorted.get(i), (i + 1.0 + end) / 2);
			i = end;
		}
		var result = new ArrayList<Double>();
		for (var value : values) result.add(ranks.get(value));
		return result;
	}

	public static Map<String, Object> row(Object... pairs) {
		var row = new LinkedHashMap<String, Object>();
		for (int i = 0; i < pairs.length; i += 2) row.put((String)pairs[i], pairs[i + 1]);
		return row;
	}

	public static double number(Map<String, Object> row, String key) {
		return ((Number)row.get(key)).doubleValue();
	}

	public static int integer(Map<String, Object> row, String key) {
		return ((Number)row.get(key)).intValue();
	}
}
