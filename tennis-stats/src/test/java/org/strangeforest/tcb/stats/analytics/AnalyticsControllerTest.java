package org.strangeforest.tcb.stats.analytics;

import org.junit.jupiter.api.*;
import org.springframework.test.util.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.*;
import org.strangeforest.tcb.stats.controller.*;
import org.strangeforest.tcb.stats.service.*;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalyticsControllerTest {

	private MockMvc mvc;

	@BeforeEach void setup() {
		var controller = new AnalyticsController(mock(AnalyticsService.class), mock(AnalyticsDrawService.class), mock(AnalyticsCalibrationService.class));
		var data = mock(DataService.class); when(data.getLastSeason()).thenReturn(2010);
		ReflectionTestUtils.setField(controller, "dataService", data);
		mvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test void invalidFiltersReturnUsefulClientErrors() throws Exception {
		mvc.perform(get("/api/analytics/era").param("fromSeason", "2011").param("toSeason", "2010")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
		mvc.perform(get("/api/analytics/era").param("surface", "H OR TRUE")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/analytics/peaks").param("windowYears", "2")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/analytics/calendar").param("hardWeight", "0").param("clayWeight", "0").param("grassWeight", "0").param("carpetWeight", "0")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/analytics/calibration").param("model", "unknown")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/analytics/draw").param("iterations", "100000")).andExpect(status().isBadRequest());
	}
}
