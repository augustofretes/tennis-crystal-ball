package org.strangeforest.tcb.stats.controller;

import org.springframework.stereotype.*;
import org.springframework.web.bind.annotation.*;

@Controller
public class PublicPlatformController extends PageController {
	@GetMapping({"/data", "/api"}) public String data() { return "dataExplorer"; }
	@GetMapping("/lab") public String lab() { return "researchLab"; }
}
