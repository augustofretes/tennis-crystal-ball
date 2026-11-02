package org.strangeforest.tcb.stats.controller;

import java.util.*;
import javax.servlet.http.*;

import org.springframework.web.bind.annotation.*;
import org.strangeforest.tcb.stats.util.*;
import org.strangeforest.tcb.util.*;

import eu.bitwalker.useragentutils.*;

public abstract class BaseController {

	public static final Map<String, String> VERSIONS = Map.of(
		"jquery", "2.2.4",
		"jquery-ui", "1.12.1",
		"bootstrap", "3.4.0",
		"bootgrid", "1.3.1",
		"font-awesome", "4.7.0"
	);

	@ModelAttribute("versions")
	public Map<String, String> getVersions() {
		return VERSIONS;
	}

	protected static void rejectAgents(HttpServletRequest httpRequest, EnumSet<BrowserType> agentTypes) {
		if (agentTypes.contains(UserAgentUtil.getAgentType(httpRequest)))
			throw new ForbiddenException("Not accessible");
	}
}
