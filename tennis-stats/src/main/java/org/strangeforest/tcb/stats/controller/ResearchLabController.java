package org.strangeforest.tcb.stats.controller;

import java.util.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.type.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.*;
import org.strangeforest.tcb.stats.api.*;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*", methods = {RequestMethod.GET, RequestMethod.POST})
public class ResearchLabController {

	private final ResearchLab lab;
	// Preserve decimal tokens until exact integer validation; a double could round
	// 1.0000000000000000001 to 1 before the service ever sees the value.
	private static final ObjectReader SIMULATION_INPUT = new ObjectMapper()
		.readerFor(new TypeReference<Map<String, Object>>() { })
		.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
		.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	public ResearchLabController(ResearchLab lab) { this.lab = lab; }

	@GetMapping("/lab/rules")
	public Map<String, Object> rules(@RequestParam MultiValueMap<String, String> params) {
		var values = PublicApiController.single(params);
		for (var key : values.keySet()) if (!Set.of("pServe", "pReturn").contains(key)) throw new IllegalArgumentException("Unknown parameter: " + key);
		try { return lab.rules(Double.parseDouble(values.getOrDefault("pServe", "0.65")), Double.parseDouble(values.getOrDefault("pReturn", "0.38"))); }
		catch (NumberFormatException e) { throw new IllegalArgumentException("Point probabilities must be numbers."); }
	}

	@PostMapping("/predictions/match")
	public Map<String, Object> prediction(@RequestBody Map<String, Object> input) { return lab.prediction(input); }

	@PostMapping(value = "/simulations", consumes = "application/json")
	public Map<String, Object> tournament(@RequestBody String json) {
		try {
			Map<String, Object> input = SIMULATION_INPUT.readValue(json);
			if (input == null) throw new IllegalArgumentException("Supply a valid JSON object.");
			return lab.tournament(input);
		}
		catch (JsonProcessingException error) { throw new IllegalArgumentException("Supply a valid JSON object."); }
	}

	@ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
	public org.springframework.http.ResponseEntity<?> invalid(Exception error) {
		return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", Map.of("code", "invalid_request",
			"message", error instanceof IllegalArgumentException ? Objects.toString(error.getMessage(), "Invalid request.") : "Supply a valid JSON object.")));
	}
}
