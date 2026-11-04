package org.strangeforest.tcb.stats.api;

import java.util.*;
import java.util.stream.*;

public final class ApiCsv {
	private ApiCsv() { }

	public static String render(List<Map<String, Object>> rows) {
		var columns = rows.stream().flatMap(row -> row.keySet().stream()).distinct().collect(Collectors.toList());
		StringBuilder csv = new StringBuilder(columns.stream().map(ApiCsv::cell).collect(Collectors.joining(","))).append("\r\n");
		for (var row : rows) csv.append(columns.stream().map(key -> cell(row.get(key))).collect(Collectors.joining(","))).append("\r\n");
		return csv.toString();
	}

	private static String cell(Object value) {
		String text = value == null ? "" : value.toString();
		if (value instanceof String && text.matches("(?s)^[\\s]*[=+@-].*") || value instanceof String && text.matches("(?s)^[\\t\\r\\n].*")) text = "'" + text;
		return "\"" + text.replace("\"", "\"\"") + "\"";
	}
}
