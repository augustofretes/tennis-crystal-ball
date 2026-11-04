package org.strangeforest.tcb.stats.controller;

import java.time.*;
import java.util.*;
import java.util.stream.*;

import org.springframework.http.*;
import org.springframework.stereotype.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.*;
import org.strangeforest.tcb.stats.analytics.*;
import org.strangeforest.tcb.stats.model.core.*;

@Controller
public class AnalyticsController extends PageController {

	private final AnalyticsService analytics;
	private final AnalyticsDrawService draws;
	private final AnalyticsCalibrationService calibration;

	public AnalyticsController(AnalyticsService analytics, AnalyticsDrawService draws, AnalyticsCalibrationService calibration) {
		this.analytics = analytics; this.draws = draws; this.calibration = calibration;
	}

	@GetMapping({"/analytics", "/eraExplorer"})
	public ModelAndView page() {
		var seasons = dataService.getSeasons();
		int latest = seasons.isEmpty() ? Year.now().getValue() : seasons.get(0);
		return new ModelAndView("analytics", Map.of("surfaces", Surface.values(), "levels", TournamentLevel.MAIN_TOURNAMENT_LEVELS,
			"analyticsDefaults", Map.of("latest", latest, "first", seasons.isEmpty() ? 1968 : seasons.get(seasons.size() - 1))));
	}

	@GetMapping("/api/analytics/{view}") @ResponseBody
	public Object data(@PathVariable String view, @RequestParam Map<String, String> params) {
		var filter = filter(params);
		int playerId = bounded(params, "playerId", 0, 0, Integer.MAX_VALUE);
		int scale = bounded(params, "scale", 400, 200, 800);
		int reference = bounded(params, "reference", 2000, 1200, 2800);
		switch (view) {
			case "coverage": return analytics.coverage(filter, playerId);
			case "era": return analytics.era(filter, bounded(params, "distance", 200, 50, 400), scale, reference);
			case "contributors": return analytics.eraPlayers(filter, bounded(params, "position", 1, 1, 200));
			case "performance": return analytics.performance(filter, playerId);
			case "stats": return analytics.relativeStats(filter, playerId);
			case "surfaces": return analytics.surfaces(filter);
			case "peaks": {
				int years = bounded(params, "windowYears", 1, 1, 5);
				if (years == 2 || years == 4) throw new IllegalArgumentException("Peak windows are 1, 3 or 5 years.");
				return analytics.peaks(filter, playerId, years);
			}
			case "calendar": {
				var weights = List.of(bounded(params, "hardWeight", 50, 0, 100), bounded(params, "clayWeight", 30, 0, 100),
					bounded(params, "grassWeight", 20, 0, 100), bounded(params, "carpetWeight", 0, 0, 100));
				if (weights.stream().mapToInt(Integer::intValue).sum() == 0) throw new IllegalArgumentException("Give at least one surface a positive calendar weight.");
				return analytics.calendar(filter, playerId, weights, reference, scale);
			}
			case "matchups": {
				var ids = new ArrayList<Integer>();
				if (!params.getOrDefault("players", "").isBlank()) {
					for (var value : params.get("players").split(",", -1)) {
						int id = Integer.parseInt(value.trim());
						if (id <= 0) throw new IllegalArgumentException("Player IDs must be positive.");
						if (!ids.contains(id)) ids.add(id);
					}
					if (ids.size() > 12) throw new IllegalArgumentException("Choose at most twelve players for the matrix.");
				}
				return analytics.matchups(filter, ids);
			}
			case "matches": {
				if (playerId == 0) throw new IllegalArgumentException("Choose a player to inspect matches.");
				return analytics.matchDetails(filter, playerId);
			}
			case "events": return analytics.events(filter);
			case "draw": return draws.draw(filter, bounded(params, "eventId", 0, 1, Integer.MAX_VALUE), bounded(params, "iterations", 200, 50, 1000), scale);
			case "calibration": {
				var model = params.getOrDefault("model", "elo");
				if (!Set.of("elo", "current").contains(model)) throw new IllegalArgumentException("Unknown prediction model.");
				return calibration.calibration(filter, model);
			}
			default: throw new IllegalArgumentException("Unknown analytics view.");
		}
	}

	private AnalyticsFilter filter(Map<String, String> params) {
		int latest = Optional.ofNullable(dataService.getLastSeason()).orElse(Year.now().getValue());
		return new AnalyticsFilter(bounded(params, "fromSeason", Math.max(1968, latest - 5), 1968, Year.now().getValue() + 1),
			bounded(params, "toSeason", latest, 1968, Year.now().getValue() + 1), params.getOrDefault("surface", ""),
			params.getOrDefault("indoor", ""), params.getOrDefault("level", ""), bounded(params, "minMatches", 10, 1, 200));
	}

	private static int bounded(Map<String, String> params, String key, int fallback, int min, int max) {
		int value;
		try { value = params.containsKey(key) ? Integer.parseInt(params.get(key)) : fallback; }
		catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer."); }
		if (value < min || value > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ".");
		return value;
	}

	@ExceptionHandler(IllegalArgumentException.class) @ResponseBody
	public ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException error) {
		return ResponseEntity.badRequest().body(Map.of("error", error.getMessage() == null ? "Invalid request." : error.getMessage()));
	}
}
