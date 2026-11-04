package org.strangeforest.tcb.stats.controller;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.servlet.http.*;

import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.http.converter.*;
import org.springframework.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.*;
import org.springframework.web.util.*;
import org.strangeforest.tcb.stats.api.*;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*", methods = {RequestMethod.GET, RequestMethod.POST}, exposedHeaders = {"Link", "X-API-Version"})
public class PublicApiController {

	private final PublicDataRepository data;

	public PublicApiController(PublicDataRepository data) { this.data = data; }

	@GetMapping({"", "/"})
	public Map<String, Object> index() {
		return Map.of("api_version", "1", "scope", "Men's main-tour singles history", "authentication", "No API key required",
			"links", Map.of("documentation", "/data", "openapi", "/api/v1/openapi.json", "coverage", "/api/v1/coverage", "players", "/api/v1/players", "matches", "/api/v1/matches", "events", "/api/v1/events", "rankings", "/api/v1/rankings", "lab", "/lab"));
	}

	@GetMapping(value = "/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
	public Resource specification() { return new ClassPathResource("public-api/openapi.json"); }

	@GetMapping("/coverage")
	public Map<String, Object> coverage() { return Map.of("data", data.coverage(), "meta", metadata()); }

	@GetMapping("/{resource:players|matches|events|rankings}")
	public ResponseEntity<?> list(@PathVariable String resource, @RequestParam MultiValueMap<String, String> params, HttpServletRequest request) {
		return page(resource, new ApiQuery(resource, single(params)), null, request);
	}

	@GetMapping("/{resource:players|matches|events}/{id}")
	public Map<String, Object> one(@PathVariable String resource, @PathVariable long id) {
		if (id < 1) throw new IllegalArgumentException("IDs must be positive.");
		var row = data.one(resource, id);
		if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such " + resource + " record.");
		return Map.of("data", row, "meta", metadata());
	}

	@GetMapping("/players/{id}/ratings")
	public ResponseEntity<?> ratings(@PathVariable int id, @RequestParam MultiValueMap<String, String> params, HttpServletRequest request) {
		if (id < 1) throw new IllegalArgumentException("IDs must be positive.");
		var query = new ApiQuery("ratings", single(params));
		if (data.one("players", id) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such player.");
		return page("ratings", query, id, request);
	}

	private ResponseEntity<?> page(String resource, ApiQuery query, Integer playerId, HttpServletRequest request) {
		var fetched = data.list(resource, query, playerId);
		boolean hasMore = fetched.size() > query.limit;
		var rows = fetched.subList(0, Math.min(fetched.size(), query.limit));
		var links = new LinkedHashMap<String, String>();
		links.put("self", link(request, query.offset));
		if (query.offset > 0) links.put("previous", link(request, Math.max(0, query.offset - query.limit)));
		if (hasMore && query.offset + query.limit <= 100000) links.put("next", link(request, query.offset + query.limit));
		var response = ResponseEntity.ok().cacheControl(CacheControl.maxAge(1, TimeUnit.MINUTES).cachePublic()).header("X-API-Version", "1");
		if (links.containsKey("next")) response.header("Link", "<" + links.get("next") + ">; rel=\"next\"");
		if (query.format.equals("csv")) return response.contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
			.header("Content-Disposition", "attachment; filename=\"tennis-" + resource + ".csv\"")
			.body(ApiCsv.render(rows));
		var page = Map.of("limit", query.limit, "offset", query.offset, "returned", rows.size(), "has_more", hasMore,
			"offset_limit_reached", hasMore && !links.containsKey("next"));
		return response.body(Map.of("data", rows, "pagination", page, "links", links, "meta", metadata()));
	}

	private Map<String, Object> metadata() {
		return Map.of("api_version", "1", "scope", "Men's main-tour singles; player and ranking registries may include players without main-tour results",
			"coverage", data.coverage(), "coverage_cache_seconds", 300,
			"notes", List.of("Coverage dates describe recorded data, not a versioned historical snapshot. Corrections may change existing records.",
				"Match dates can represent a tournament date when the exact day is unavailable. This API does not yet expose per-match date precision.",
				"Missing statistics and ratings are not zero. CSV exports contain this page only; follow pagination for more data.",
				"Offset pagination is ordered but is not a snapshot across ingestion updates. Narrow filters if the offset limit is reached."),
			"attribution", "Historical data: Jeff Sackmann / Tennis Abstract and the sources documented in this repository; current results: ESPN and Tennis Abstract.",
			"source_documentation", "https://github.com/augustofretes/tennis-crystal-ball/blob/master/scripts/WEEKLY-RESULTS.md");
	}

	private static String link(HttpServletRequest request, int offset) {
		return UriComponentsBuilder.fromPath(request.getRequestURI()).query(request.getQueryString()).replaceQueryParam("offset", offset).build(true).toUriString();
	}

	static Map<String, String> single(MultiValueMap<String, String> params) {
		for (var entry : params.entrySet()) if (entry.getValue().size() != 1) throw new IllegalArgumentException("Supply " + entry.getKey() + " only once.");
		return params.toSingleValueMap();
	}

	@ExceptionHandler({IllegalArgumentException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
	public ResponseEntity<?> invalid(Exception error) {
		String message = error instanceof IllegalArgumentException ? error.getMessage() : "Invalid request. Check the documented types and JSON body.";
		return ResponseEntity.badRequest().body(Map.of("error", Map.of("code", "invalid_request", "message", message == null ? "Invalid request." : message)));
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<?> missing(ResponseStatusException error) {
		return ResponseEntity.status(error.getStatus()).body(Map.of("error", Map.of("code", "not_found", "message", Objects.toString(error.getReason(), "Record not found."))));
	}
}
