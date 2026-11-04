package org.strangeforest.tcb.stats.api;

import java.util.*;

import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.*;
import org.strangeforest.tcb.stats.controller.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublicApiTest {
	private PublicDataRepository repository;
	private MockMvc mvc;

	@BeforeEach void setup() {
		repository = mock(PublicDataRepository.class);
		when(repository.one(anyString(), anyLong())).thenReturn(null);
		when(repository.coverage()).thenReturn(Map.of("recorded_matches", 10, "matches_through", "2010-01-10"));
		mvc = MockMvcBuilders.standaloneSetup(new PublicApiController(repository), new ResearchLabController(new ResearchLab())).build();
	}

	@Test void paginationPreservesFiltersAndDoesNotLeakTheLookaheadRow() throws Exception {
		when(repository.list(eq("players"), any(), isNull())).thenReturn(List.of(Map.of("player_id", 1, "name", "Roger"), Map.of("player_id", 2, "name", "Rafael")));
		mvc.perform(get("/api/v1/players").queryParam("q", "Ro").queryParam("limit", "1"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.pagination.has_more").value(true))
			.andExpect(jsonPath("$.links.next").value("/api/v1/players?q=Ro&limit=1&offset=1"))
			.andExpect(jsonPath("$.meta.coverage.matches_through").value("2010-01-10"));
	}

	@Test void badParametersAreRejectedBeforeQuerying() throws Exception {
		for (var params : List.of(Map.of("limit", "201"), Map.of("limit", "0"), Map.of("offset", "-1"), Map.of("country", "US"), Map.of("q", "R"), Map.of("sort", "name; DROP TABLE player"))) {
			var request = get("/api/v1/players"); params.forEach(request::param);
			mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_request"));
		}
		mvc.perform(get("/api/v1/players").param("q", "Roger", "Rafael")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/matches").param("fromSeason", "2011").param("toSeason", "2010")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/rankings").param("date", "2010-02-30")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/players/1/ratings").param("fromDate", "2011-01-01").param("toDate", "2010-01-01")).andExpect(status().isBadRequest());
		verify(repository, never()).list(anyString(), any(), any());
	}

	@Test void recordsAndMissingPlayersHaveConsistentStatusCodes() throws Exception {
		mvc.perform(get("/api/v1/players/99")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("not_found"));
		mvc.perform(get("/api/v1/players/99/ratings")).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/matches/-1")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/players/not-a-number")).andExpect(status().isBadRequest());
	}

	@Test void csvPreservesNumbersAndEscapesSpreadsheetFormulas() throws Exception {
		var row = new LinkedHashMap<String, Object>(); row.put("name", "  =HYPERLINK(\"bad\")"); row.put("delta", -5);
		when(repository.list(eq("players"), any(), isNull())).thenReturn(List.of(row));
		mvc.perform(get("/api/v1/players").param("format", "csv")).andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("text/csv"))
			.andExpect(header().string("Content-Disposition", "attachment; filename=\"tennis-players.csv\""));
		assertThat(ApiCsv.render(List.of(row))).contains("\"'  =HYPERLINK(\"\"bad\"\")\"").contains("\"-5\"");
	}

	@Test void openApiAndCrossOriginAccessAreAvailableWithoutAuthentication() throws Exception {
		mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andExpect(jsonPath("$.openapi").value("3.0.3"));
		mvc.perform(get("/api/v1").header("Origin", "https://example.org"))
			.andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "*"));
	}

	@Test void simulationInputsAreBoundedAndErrorsAreJson() throws Exception {
		mvc.perform(post("/api/v1/predictions/match").contentType("application/json").content("{\"player1Elo\":2000,\"player2Elo\":2000}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.player1_probability").value(.5));
		mvc.perform(post("/api/v1/predictions/match").contentType("application/json").content("{\"player1Elo\":2000,\"player2Elo\":2000,\"unknown\":1}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_request"));
		mvc.perform(post("/api/v1/simulations").contentType("application/json").content("{bad json"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_request"));
		mvc.perform(get("/api/v1/lab/rules").param("pServe", "NaN")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/lab/rules").param("pReturn", "Infinity")).andExpect(status().isBadRequest());
	}

	@Test void simulationJsonPreservesPrecisionUntilIntegerValidation() throws Exception {
		String players = "\"players\":[{\"id\":1,\"elo\":2000},{\"id\":2,\"elo\":2000}]";
		for (var body : List.of("{" + players + ",\"seed\":2147483647.0000000001}", "{" + players + ",\"seed\":-1e-400}",
			"{" + players + ",\"iterations\":1.0000000000000000001}", "{" + players + ",\"iterations\":500.00000000000001}",
			"{\"players\":[{\"id\":1.00000000000000001,\"elo\":2000},{\"id\":2,\"elo\":2000}]}",
			"{\"players\":[{\"seed\":1.00000000000000001,\"elo\":2000},{\"elo\":2000}]}",
			"{" + players + ",\"seed\":9223372036854775808}", "null", "[]", "{" + players + "} {}"))
			mvc.perform(post("/api/v1/simulations").contentType("application/json").content(body))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_request"));
		mvc.perform(post("/api/v1/simulations").contentType("application/json").content("{" + players + ",\"seed\":2147483647,\"draw\":\"fixed\",\"iterations\":1.0}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.seed").value(Integer.MAX_VALUE)).andExpect(jsonPath("$.iterations").value(1));
	}
}
