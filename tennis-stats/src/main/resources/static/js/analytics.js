(function () {
	"use strict";
	const $id = id => document.getElementById(id);
	const content = $id("analytics-content");
	const form = $id("analytics-filters");
	const colors = ["#255d68", "#a2553b", "#69804b", "#8b6b94", "#a78336", "#4b6c96"];
	const surfaceNames = {H: "Hard", C: "Clay", G: "Grass", P: "Carpet"};
	const views = ["era", "performance", "stats", "draw", "surfaces", "matchups", "peaks", "calendar", "calibration"];
	const defaults = window.analyticsDefaults;
	let view = "era", generation = 0, request, exported = [], detailRows = [], lastDraw;
	let playerNames = {}, brushTarget = "a", lastFocus;
	const esc = value => String(value == null ? "" : value).replace(/[&<>"']/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c]));
	const valid = value => value !== null && value !== undefined && Number.isFinite(Number(value));
	const num = (value, digits = 1) => valid(value) ? Number(value).toLocaleString("en-US", {maximumFractionDigits: digits, minimumFractionDigits: digits}) : "—";
	const pct = value => valid(value) ? (value > 0 && value < .001 ? "<0.1%" : num(value * 100, 1) + "%") : "—";
	const pp = value => valid(value) ? (value > 0 ? "+" : "") + num(value * 100, 1) + " pp" : "—";
	const signed = value => valid(value) ? (value > 0 ? "+" : "") + num(value) : "—";
	const mean = values => { const good = values.filter(valid).map(Number); return good.length ? good.reduce((a, b) => a + b, 0) / good.length : null; };
	const difference = value => '<span class="analytics-difference ' + (value >= 0 ? "positive" : "negative") + '">' + esc(signed(value)) + "</span>";
	const playerLink = r => '<a href="/playerProfile?playerId=' + encodeURIComponent(r.player_id) + '">' + esc(r.name) + "</a>";
	const inspect = r => playerLink(r) + ' <button type="button" class="analytics-data-link" data-inspect="' + Number(r.player_id) + '" data-season="' + (r.season || "") + '">Matches</button>';
	const section = (title, description, body) => '<section class="analytics-section"><h2>' + esc(title) + "</h2><p>" + esc(description) + "</p>" + body + "</section>";
	const empty = message => '<div class="analytics-empty"><h2>No comparable data in this scope</h2><p>' + esc(message || "Try a wider period, a lower minimum sample, or another surface.") + "</p></div>";
	const method = text => '<details class="analytics-method"><summary>How this is calculated</summary><p>' + esc(text) + "</p></details>";
	const cards = items => '<div class="analytics-cards">' + items.map(item => '<div class="analytics-card"><span class="analytics-card-label">' + esc(item[0]) + '</span><strong class="analytics-card-number">' + item[1] + "</strong><small>" + esc(item[2] || "") + "</small></div>").join("") + "</div>";

	function values() {
		const params = {};
		for (const element of form.elements) if (element.name) params[element.name] = element.type === "checkbox" ? String(element.checked) : element.value;
		return params;
	}

	function scope(extra) {
		const all = values();
		return Object.assign({fromSeason: all.fromSeason, toSeason: all.toSeason, surface: all.surface, indoor: all.indoor,
			level: all.level, minMatches: all.minMatches, playerId: ["performance", "stats", "peaks", "calendar"].includes(view) ? all.playerId : 0, distance: all.distance, scale: all.scale,
			reference: all.reference, windowYears: all.windowYears, model: all.model, iterations: all.iterations,
			players: all.players, hardWeight: all.hardWeight, clayWeight: all.clayWeight, grassWeight: all.grassWeight, carpetWeight: all.carpetWeight}, extra || {});
	}

	async function api(name, params, signal) {
		const response = await fetch("/api/analytics/" + name + "?" + new URLSearchParams(params || scope()), {signal: signal || (request && request.signal), headers: {Accept: "application/json"}});
		if (!response.ok) {
			let message = "Unable to load this comparison (" + response.status + ").";
			try { const body = await response.json(); if (body.error && response.status === 400) message = body.error; } catch (ignored) { /* Keep the HTTP error. */ }
			throw new Error(message);
		}
		return response.json();
	}

	function remember(push) {
		const params = new URLSearchParams(values());
		const eventId = new URLSearchParams(location.search).get("eventId");
		if (view === "draw" && eventId) params.set("eventId", eventId);
		params.set("tab", view);
		if ($id("analytics-player").value) params.set("playerName", $id("analytics-player").value);
		const url = "/analytics?" + params;
		if (push) history.pushState(null, "", url); else history.replaceState(null, "", url);
	}

	function restore() {
		const params = new URLSearchParams(location.search);
		form.reset();
		$id("fromSeason").value = Math.max(1968, defaults.latest - 5);
		$id("toSeason").value = defaults.latest;
		$id("bFrom").value = Math.max(1968, defaults.latest - 5);
		$id("bTo").value = defaults.latest;
		for (const element of form.elements) if (element.name && params.has(element.name)) {
			if (element.type === "checkbox") element.checked = params.get(element.name) === "true";
			else element.value = params.get(element.name);
		}
		for (const input of form.querySelectorAll('input[type="number"][min="1968"]')) input.max = new Date().getFullYear() + 1;
		$id("analytics-player").value = params.get("playerName") || "";
		view = views.includes(params.get("tab")) ? params.get("tab") : "era";
		visibility();
	}

	function visibility() {
		for (const element of form.querySelectorAll("[data-only-on], [data-hide-on]")) {
			element.hidden = element.dataset.onlyOn ? !element.dataset.onlyOn.split(",").includes(view) : element.dataset.hideOn.split(",").includes(view);
			for (const control of element.querySelectorAll("input,select")) control.disabled = element.hidden;
		}
		for (const link of document.querySelectorAll("[data-view]")) {
			if (link.dataset.view === view) link.setAttribute("aria-current", "page"); else link.removeAttribute("aria-current");
		}
	}

	function table(rows, columns, caption) {
		if (!rows.length) return empty();
		return '<div class="analytics-table-scroll" tabindex="0" role="region" aria-label="' + esc(caption || "Comparison results") + '"><table class="table table-condensed table-hover analytics-table">' +
			(caption ? "<caption>" + esc(caption) + "</caption>" : "") + "<thead><tr>" + columns.map(c => '<th scope="col">' + esc(c.title) + "</th>").join("") + "</tr></thead><tbody>" +
			rows.map(r => "<tr>" + columns.map(c => "<td>" + (c.render ? c.render(r) : esc(c.format ? c.format(r[c.key]) : (r[c.key] == null ? "—" : r[c.key]))) + "</td>").join("") + "</tr>").join("") + "</tbody></table></div>";
	}

	function tableNote(result) {
		return result.total_rows > result.rows.length ? '<p class="text-muted">Showing ' + result.rows.length + " of " + num(result.total_rows, 0) + " rows. Narrow the filters to inspect a smaller group.</p>" : "";
	}

	function coverage(data, label) {
		return '<details class="analytics-coverage"><summary>' + esc(label || "Data coverage") + ": " + num(data.rated_matches, 0) + " / " + num(data.completed_matches, 0) + " completed matches have both Elo ratings · latest match " + esc(data.latest_match || "unavailable") + "</summary><dl>" +
			[["Recorded matches", num(data.recorded_matches, 0)], ["Completed matches used", num(data.completed_matches, 0)], ["With detailed point stats", num(data.stats_matches, 0)],
				["First detailed stats in scope", data.first_stats || "Unavailable"], ["Latest Elo snapshot in database", data.latest_elo || "Unavailable"], ["Latest recorded match in database", data.latest_recorded_match || "Unavailable"]].map(pair => "<div><dt>" + esc(pair[0]) + "</dt><dd>" + esc(pair[1]) + "</dd></div>").join("") +
			"</dl><p>Retirements, walkovers, defaults and abandoned matches are excluded from completed-match comparisons. Tournament-title and recorded-route views identify their own outcome handling. Recorded dates indicate dataset coverage, not the time of ingestion.</p></details>";
	}

	/** Local SVG rendering keeps comparisons usable without a third-party chart loader. */
	function chart(series, options) {
		options = options || {};
		const all = series.flatMap(s => s.points).filter(p => valid(p.x) && valid(p.y));
		if (!all.length) return empty();
		const width = 960, height = options.small ? 310 : 370;
		const margin = {left: 65, right: 24, top: 20, bottom: 48};
		let xmin = Math.min(...all.map(p => Number(p.x))), xmax = Math.max(...all.map(p => Number(p.x)));
		let ymin = options.minY === undefined ? Math.min(...all.map(p => Number(options.variation && valid(p.low) ? p.low : p.y))) : options.minY;
		let ymax = options.maxY === undefined ? Math.max(...all.map(p => Number(options.variation && valid(p.high) ? p.high : p.y))) : options.maxY;
		if (xmin === xmax) { xmin -= 1; xmax += 1; }
		if (ymin === ymax) { ymin -= 1; ymax += 1; }
		if (options.minY === undefined) ymin -= (ymax - ymin) * .06;
		if (options.maxY === undefined) ymax += (ymax - ymin) * .06;
		const x = n => margin.left + (n - xmin) / (xmax - xmin) * (width - margin.left - margin.right);
		const y = n => height - margin.bottom - (n - ymin) / (ymax - ymin) * (height - margin.top - margin.bottom);
		const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
		svg.setAttribute("viewBox", "0 0 " + width + " " + height);
		svg.setAttribute("role", options.select ? "group" : "img"); svg.setAttribute("aria-label", options.title || "Comparison chart");
		function node(tag, attributes, text, parent) {
			const element = document.createElementNS(svg.namespaceURI, tag);
			for (const key of Object.keys(attributes || {})) element.setAttribute(key, attributes[key]);
			if (text !== undefined) element.textContent = text;
			(parent || svg).appendChild(element); return element;
		}
		node("title", {}, options.title || "Comparison chart");
		for (let i = 0; i <= 4; i++) {
			const value = ymin + i * (ymax - ymin) / 4;
			node("line", {x1: margin.left, x2: width - margin.right, y1: y(value), y2: y(value), stroke: "#d7d5c8", "stroke-width": 1});
			node("text", {x: margin.left - 10, y: y(value) + 4, "text-anchor": "end"}, options.percentage ? pct(value) : num(value, 0));
		}
		const tickCount = Math.min(6, Math.max(1, Math.floor(xmax - xmin)));
		for (let i = 0; i <= tickCount; i++) {
			const value = Math.round(xmin + i * (xmax - xmin) / tickCount);
			node("text", {x: x(value), y: height - margin.bottom + 22, "text-anchor": "middle"}, options.xLabel && options.xLabel !== "Season" ? num(value, 0) : String(value));
		}
		node("text", {x: (width + margin.left) / 2, y: height - 6, "text-anchor": "middle"}, options.xLabel || "Season");
		const wrapper = document.createElement("div"); wrapper.className = "analytics-chart";
		const legend = document.createElement("div"); legend.className = "analytics-chart-legend";
		series.forEach((s, index) => {
			const points = s.points.filter(p => valid(p.y)).sort((a, b) => a.x - b.x);
			if (!points.length) return;
			const color = s.color || colors[index % colors.length];
			const group = node("g");
			if (options.variation && points.every(p => valid(p.low) && valid(p.high))) {
				const boundary = points.map(p => x(p.x) + "," + y(p.high)).concat(points.slice().reverse().map(p => x(p.x) + "," + y(p.low)));
				node("polygon", {points: boundary.join(" "), fill: color, opacity: .1}, undefined, group);
			}
			node("polyline", {points: points.map(p => x(p.x) + "," + y(p.y)).join(" "), fill: "none", stroke: color, "stroke-width": 2.5}, undefined, group);
			points.forEach(p => {
				const circle = node("circle", {cx: x(p.x), cy: y(p.y), r: points.length > 60 ? 2 : 4, fill: color, class: "analytics-chart-point", tabindex: 0}, undefined, group);
				const label = s.name + " · " + (p.label || num(p.x, 0)) + ": " + (options.percentage ? pct(p.y) : num(p.y));
				node("title", {}, label, circle); circle.setAttribute("aria-label", label);
				if (options.select) {
					circle.setAttribute("role", "button");
					circle.addEventListener("click", () => options.select(p, s));
					circle.addEventListener("keydown", event => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); options.select(p, s); } });
				}
			});
			const toggle = document.createElement("button"); toggle.type = "button"; toggle.setAttribute("aria-pressed", "true");
			const swatch = document.createElement("i"); swatch.style.background = color; toggle.appendChild(swatch); toggle.appendChild(document.createTextNode(s.name));
			toggle.addEventListener("click", () => { const shown = toggle.getAttribute("aria-pressed") !== "true"; toggle.setAttribute("aria-pressed", shown); group.style.display = shown ? "" : "none"; });
			legend.appendChild(toggle);
		});
		wrapper.appendChild(svg); wrapper.appendChild(legend); return wrapper;
	}

	function appendChart(target, series, options) {
		const result = chart(series, options); const parent = typeof target === "string" ? $id(target) : target;
		if (typeof result === "string") parent.innerHTML = result; else parent.appendChild(result);
	}

	async function showEra(token) {
		const a = scope({fromSeason: $id("aFrom").value, toSeason: $id("aTo").value});
		const b = scope({fromSeason: $id("bFrom").value, toSeason: $id("bTo").value});
		const [eraA, eraB, coverageA, coverageB] = await Promise.all([api("era", a), api("era", b), api("coverage", a), api("coverage", b)]);
		if (token !== generation) return;
		$id("analytics-coverage").innerHTML = coverage(coverageA, "Era A coverage") + coverage(coverageB, "Era B coverage");
		const labelA = a.fromSeason + "–" + a.toSeason, labelB = b.fromSeason + "–" + b.toSeason;
		const metrics = [["Elite · top three", "top3"], ["Elite · top eight", "top8"], ["Depth · positions 4–10", "depth4_10"], ["Depth · positions 11–32", "depth11_32"], ["Depth · positions 33–100", "depth33_100"], ["Contenders near leader", "contenders"]];
		const metricCards = metrics.map(([title, key]) => {
			const av = mean(eraA.seasons.map(s => s[key])), bv = mean(eraB.seasons.map(s => s[key]));
			return [title, '<span class="era-a">' + num(av) + '</span> <span aria-hidden="true">/</span> <span class="era-b">' + num(bv) + "</span>", valid(av) && valid(bv) ? "A minus B: " + signed(av - bv) + (key === "contenders" ? " players" : " Elo") : "Complete band data unavailable"];
		});
		const omitted = [];
		if (eraA.missing_seasons.length) omitted.push("A: " + eraA.missing_seasons.join(", "));
		if (eraB.missing_seasons.length) omitted.push("B: " + eraB.missing_seasons.join(", "));
		// Build sections separately to keep chart containers stable during interaction.
		content.innerHTML = section("Two eras, across the field", "Era A " + labelA + " · Era B " + labelB + ". Each year has equal weight. Colors stay consistent throughout the comparison.",
			(omitted.length ? '<p class="alert alert-warning">No ranking observations for ' + esc(omitted.join("; ")) + ". Missing years are excluded, never treated as zero.</p>" : "") + cards(metricCards) +
			'<div id="era-curve"></div><p class="text-muted">Click or focus a position to see the players behind it. Shading shows the range of yearly averages.</p><button id="compare-surfaces" class="btn btn-default" type="button">Compare all four surfaces</button><div id="era-surface-grid" class="analytics-mini-grid"></div><div id="era-brush"></div><div id="era-season-chart"></div>');
		const excluded = Number($id("excludeTop").value);
		appendChart("era-curve", [{name: "Era A · " + labelA, color: colors[0], period: a, points: eraA.curve.filter(p => p.position > excluded).map(p => Object.assign({x: p.position, y: p.elo}, p))},
			{name: "Era B · " + labelB, color: colors[1], period: b, points: eraB.curve.filter(p => p.position > excluded).map(p => Object.assign({x: p.position, y: p.elo}, p))}],
			{title: "Average contemporaneous Elo by field position", xLabel: "Position in the contemporaneous field", variation: $id("variation").checked, select: (p, s) => showContributors(p.position, s.period, s.name)});
		const seasons = eraA.seasons.map(s => Object.assign({era: "A"}, s)).concat(eraB.seasons.map(s => Object.assign({era: "B"}, s)));
		exported = seasons;
		content.insertAdjacentHTML("beforeend", section("Strength and balance, year by year", "Higher title concentration means fewer players shared the titles. It is a measure of balance, not proof of a weaker field.", table(seasons, [
			{key: "era", title: "Era"}, {key: "season", title: "Season", render: r => '<a href="/season?season=' + r.season + '">' + r.season + "</a>"},
			{key: "leader", title: "No. 1 Elo", format: num}, {key: "top3", title: "Top 3 Elo", format: num}, {key: "depth4_10", title: "4–10 Elo", format: num},
			{key: "depth11_32", title: "11–32 Elo", format: num}, {key: "depth33_100", title: "33–100 Elo", format: num},
			{key: "contenders", title: "Contenders", format: num}, {key: "title_concentration", title: "Title concentration", format: v => num(v, 3)},
			{key: "upset_rate", title: "Elo upset rate", format: pct}, {key: "reference_title_probability", title: "Reference title chance", format: pct},
			{key: "snapshots", title: "Ranking dates", format: v => num(v, 0)}], "Yearly observations · missing measures remain blank") + method(eraA.method)));
		appendChart("era-season-chart", [{name: "Era A · top three", points: eraA.seasons.map(s => ({x: s.season, y: s.top3}))}, {name: "Era B · top three", points: eraB.seasons.map(s => ({x: s.season, y: s.top3}))}], {title: "Elite strength through each period", select: (p, s) => showContributors(1, Object.assign({}, s.name.startsWith("Era A") ? a : b, {fromSeason: p.x, toSeason: p.x}), "Season " + p.x)});
		brush();
		$id("compare-surfaces").addEventListener("click", async event => {
			event.currentTarget.disabled = true;
			const grid = $id("era-surface-grid"); grid.textContent = "Loading surface comparisons…";
			try {
				const results = await Promise.all(Object.keys(surfaceNames).map(async surface => ({surface, a: await api("era", Object.assign({}, a, {surface})), b: await api("era", Object.assign({}, b, {surface}))})));
				if (token !== generation) return;
				grid.textContent = "";
				for (const result of results) {
					const panel = document.createElement("section"); const heading = document.createElement("h3"); heading.textContent = surfaceNames[result.surface]; panel.appendChild(heading); grid.appendChild(panel);
					appendChart(panel, [{name: "Era A", points: result.a.curve.filter(p => p.position > excluded).map(p => ({x: p.position, y: p.elo}))}, {name: "Era B", points: result.b.curve.filter(p => p.position > excluded).map(p => ({x: p.position, y: p.elo}))}], {small: true, xLabel: "Field position", title: surfaceNames[result.surface] + " era comparison"});
				}
			} catch (error) { if (error.name !== "AbortError") grid.textContent = error.message; }
		});
	}

	function brush() {
		const first = Math.min(defaults.first, Number($id("aFrom").value)), last = Math.max(defaults.latest, Number($id("bTo").value));
		const width = 960, height = 65, pad = 12;
		const yearX = year => pad + (year - first) / (last - first + 1) * (width - 2 * pad);
		$id("era-brush").innerHTML = '<div class="analytics-brush"><div class="analytics-brush-controls"><strong>Choose a period on the timeline</strong><label><input name="brush-target" type="radio" value="a" ' + (brushTarget === "a" ? "checked" : "") + '/> Era A</label><label><input name="brush-target" type="radio" value="b" ' + (brushTarget === "b" ? "checked" : "") + '/> Era B</label></div><svg id="era-timeline" viewBox="0 0 960 65" role="img" aria-label="Drag across years to select a period; keyboard range controls follow"></svg><div class="analytics-range-controls">' +
			["a", "b"].flatMap(period => ["From", "To"].map(end => '<label>Era ' + period.toUpperCase() + " " + end.toLowerCase() + ' <output id="' + period + end + '-output">' + esc($id(period + end).value) + '</output><input type="range" data-period-range="' + period + end + '" min="' + first + '" max="' + last + '" value="' + esc($id(period + end).value) + '"/></label>')).join("") + "</div></div>";
		const timeline = $id("era-timeline");
		let markup = '<rect x="12" y="10" width="936" height="24" fill="#eeede3"/>';
		for (let year = first; year <= last; year++) if (year % 5 === 0) markup += '<text x="' + yearX(year) + '" y="58" font-size="11" fill="#6a6c60">' + year + "</text>";
		for (const period of ["a", "b"]) markup += '<rect x="' + yearX(Number($id(period + "From").value)) + '" y="' + (period === "a" ? 10 : 22) + '" width="' + Math.max(1, yearX(Number($id(period + "To").value) + 1) - yearX(Number($id(period + "From").value))) + '" height="12" fill="' + (period === "a" ? colors[0] : colors[1]) + '" opacity=".8"/>';
		timeline.innerHTML = markup;
		for (const input of $id("era-brush").querySelectorAll('[name="brush-target"]')) input.addEventListener("change", () => { brushTarget = input.value; });
		let start;
		const position = event => Math.max(first, Math.min(last, Math.floor(first + ((event.clientX - timeline.getBoundingClientRect().left) / timeline.getBoundingClientRect().width * width - pad) / (width - 2 * pad) * (last - first + 1))));
		timeline.addEventListener("pointerdown", event => { start = position(event); timeline.setPointerCapture(event.pointerId); });
		timeline.addEventListener("pointerup", event => {
			if (start === undefined) return;
			const end = position(event);
			$id(brushTarget + "From").value = Math.min(start, end); $id(brushTarget + "To").value = Math.max(start, end); start = undefined;
			equalize(brushTarget); load();
		});
		timeline.addEventListener("pointercancel", () => { start = undefined; });
		for (const input of $id("era-brush").querySelectorAll("[data-period-range]")) {
			input.addEventListener("input", () => { $id(input.dataset.periodRange + "-output").textContent = input.value; });
			input.addEventListener("change", () => {
				const id = input.dataset.periodRange, period = id[0]; $id(id).value = input.value;
				if (Number($id(period + "From").value) > Number($id(period + "To").value)) $id(period + (id.endsWith("From") ? "To" : "From")).value = input.value;
				equalize(period); load();
			});
		}
	}

	function equalize(changed) {
		if (!$id("equalLength").checked) return;
		const other = changed === "a" ? "b" : "a", length = Number($id(changed + "To").value) - Number($id(changed + "From").value);
		if (length < 0) return;
		let from = Number($id(other + "From").value), to = from + length;
		if (to > new Date().getFullYear() + 1) { to = new Date().getFullYear() + 1; from = to - length; }
		$id(other + "From").value = from; $id(other + "To").value = to;
	}

	async function showContributors(position, period, title) {
		openDetails(title + " · position " + position, "Loading the underlying ranking observations…");
		try {
			const rows = await api("contributors", Object.assign({}, period, {position}));
			$id("analytics-details-content").innerHTML = table(rows, [{title: "Player", render: playerLink}, {key: "elo", title: "Average Elo at this position", format: num}, {key: "snapshots", title: "Ranking dates", format: v => num(v, 0)}], "Players contributing to this position in the selected period");
		} catch (error) { if (error.name !== "AbortError") $id("analytics-details-content").textContent = error.message; }
	}

	async function showStandard(token) {
		const params = scope();
		const [data, covered] = await Promise.all([api(view, params), api("coverage", params)]);
		if (token !== generation) return;
		$id("analytics-coverage").innerHTML = coverage(covered);
		exported = data.rows || [];
		if (view === "performance") showPerformance(data);
		if (view === "stats") showStats(data);
		if (view === "surfaces") showSurfaces(data);
		if (view === "peaks") showPeaks(data);
		if (view === "calendar") showCalendar(data);
		if (view === "matchups") showMatchups(data);
		if (view === "calibration") showCalibration(data);
	}

	function seasonSeries(rows, key, nameKey) {
		const groups = {};
		for (const r of rows) { const name = nameKey ? nameKey(r) : r.name; (groups[name] = groups[name] || []).push({x: r.season, y: r[key], label: r.season}); }
		return Object.keys(groups).slice(0, 6).map(name => ({name, points: groups[name]}));
	}

	function showPerformance(data) {
		content.innerHTML = section("Results, relative to the opposition", "A win counts differently when the player was expected to win 95% of the time. Compare season results with pre-match Elo expectations, then inspect the matches behind them.", '<div id="performance-chart"></div>' + table(data.rows, [
			{title: "Player", render: inspect}, {key: "season", title: "Season"}, {key: "matches", title: "Matches", format: v => num(v, 0)}, {key: "wins", title: "Wins", format: v => num(v, 0)},
			{key: "rated_matches", title: "Rated matches", format: v => num(v, 0)}, {key: "rated_wins", title: "Wins in rated matches", format: v => num(v, 0)}, {key: "expected_wins", title: "Expected wins", format: num},
			{title: "Excess wins", render: r => difference(r.excess_wins)}, {title: "95% expected residual range", render: r => num(r.excess_low) + " to " + num(r.excess_high)},
			{key: "shrunk_excess_rate", title: "Shrunk excess per match", format: pp}, {key: "opponent_elo", title: "Average opponent Elo", format: num},
			{key: "top10_matches", title: "Top-ten encounters", format: v => num(v, 0)}, {key: "top10_opponent_elo", title: "Top-ten opponent Elo", format: num}], "Player seasons · ordered by wins above expectation") + tableNote(data) + method(data.method));
		const metric = $id("performanceMetric").value || "excess_wins";
		const title = {excess_wins: "Season wins above Elo expectation", opponent_elo: "Average opponent Elo at the time of each match", top10_matches: "Season encounters against ATP top-ten opponents"}[metric];
		appendChart("performance-chart", seasonSeries(data.rows, metric), {title: title + " · up to six players"});
	}

	function showStats(data) {
		content.innerHTML = section("A serve in the context of its era", "Compare raw rates with the same season and surface. Above-baseline values are percentage-point differences, with small samples pulled toward the baseline.", '<div id="relative-stats-chart"></div>' + table(data.rows, [
			{title: "Player", render: inspect}, {key: "season", title: "Season"}, {key: "surface", title: "Surface", format: s => surfaceNames[s]}, {key: "matches", title: "Matches", format: v => num(v, 0)},
			{key: "serve_rate", title: "Serve points won", format: pct}, {key: "serve_baseline", title: "Era baseline", format: pct}, {key: "serve_above", title: "Serve above baseline", format: pp},
			{title: "95% raw serve interval", render: r => pct(r.serve_low) + " to " + pct(r.serve_high)},
			{key: "return_rate", title: "Return points won", format: pct}, {key: "return_baseline", title: "Era return baseline", format: pct}, {key: "return_above", title: "Return above baseline", format: pp},
			{key: "ace_rate", title: "Aces / service point", format: pct}, {key: "ace_baseline", title: "Era ace baseline", format: pct}, {key: "hold_rate", title: "Hold rate", format: pct}, {key: "hold_baseline", title: "Era hold baseline", format: pct}], "Season-and-surface statistics · valid detailed point samples") + tableNote(data) + method(data.method));
		appendChart("relative-stats-chart", seasonSeries(data.rows, "serve_above", r => r.name + " · " + surfaceNames[r.surface]), {percentage: true, title: "Serve points won above the season-and-surface baseline"});
	}

	function showSurfaces(data) {
		content.innerHTML = section("Are the surfaces rewarding the same players?", "Follow shared top-ten players, correlations between surface rankings, and titles won by surface specialists. Choose All surfaces for comparisons between surfaces.", '<div id="surface-overlap-chart"></div>' + table(data.rows, [
			{key: "season", title: "Season"}, {title: "Surface pair", render: r => esc(surfaceNames[r.surface1] + " / " + surfaceNames[r.surface2])}, {key: "top10_overlap", title: "Top-ten overlap", format: pct},
			{key: "shared_top10", title: "Shared top-ten players", format: num}, {key: "rank_correlation", title: "Rank correlation", format: v => num(v, 3)}, {key: "common_players", title: "Common top-100 players", format: num},
			{key: "snapshots", title: "Ranking dates", format: v => num(v, 0)}, {key: "correlation_snapshots", title: "Dates with correlation", format: v => num(v, 0)}], "Contemporaneous surface comparisons") + table(data.specialists, [
			{key: "season", title: "Season"}, {key: "surface", title: "Surface", format: s => surfaceNames[s]}, {key: "classified_titles", title: "Titles with sufficient ratings", format: v => num(v, 0)},
			{key: "specialist_titles", title: "Specialist titles", format: v => num(v, 0)}, {title: "Specialist share", render: r => pct(r.specialist_titles / r.classified_titles)}], "Surface-specialist title success") + method(data.method));
		appendChart("surface-overlap-chart", seasonSeries(data.rows, "top10_overlap", r => surfaceNames[r.surface1] + " / " + surfaceNames[r.surface2]), {title: "Top-ten Jaccard overlap by surface pair", percentage: true, minY: 0, maxY: 1});
	}

	function showPeaks(data) {
		content.innerHTML = section("How long did the peak last?", "Compare sustained playing strength over a full year, three years, or five years. Windows need sufficient activity and ranking coverage inside the selected period.", table(data.rows, [
			{title: "Player", render: playerLink}, {key: "elo", title: "Mean Elo over the window", format: num}, {key: "from_date", title: "First snapshot"}, {key: "to_date", title: "Last snapshot"},
			{key: "snapshots", title: "Ranking dates", format: v => num(v, 0)}, {key: "matches", title: "Completed matches", format: v => num(v, 0)}], "Best qualifying window per player") + method(data.method));
	}

	function showCalendar(data) {
		const total = data.weights.reduce((a, b) => a + b, 0);
		const mix = Object.keys(surfaceNames).map((s, i) => surfaceNames[s] + " " + pct(data.weights[i] / total)).join(" · ");
		content.innerHTML = section("A common calendar, two different questions", mix + ". Observed reweighting retains historical opposition. The separate model asks how each player would fare against the reference opponent on every surface.", table(data.rows, [
			{title: "Player", render: playerLink}, {key: "matches", title: "Qualifying surface matches", format: v => num(v, 0)}, {key: "observed_win_rate", title: "Observed win rate", format: pct},
			{key: "standardized_win_rate", title: "Common-calendar win rate", format: pct}, {title: "Approx. 95% interval", render: r => pct(r.standardized_low) + " to " + pct(r.standardized_high)},
			{key: "modeled_wins_per100", title: "Model · expected wins / 100", format: num}, {title: "Unavailable required surfaces", render: r => esc(r.missing_surfaces.concat(r.missing_rating_surfaces).map(s => surfaceNames[s]).filter((s, i, all) => all.indexOf(s) === i).join(", ") || "None")},
			{title: "Surface samples", render: r => esc(r.surfaces.map(s => surfaceNames[s.surface] + ": " + s.matches + " matches, " + pct(s.win_rate)).join(" · "))}], "Common-calendar comparisons · positive-weight surfaces require the minimum sample") + tableNote(data) + method(data.method));
	}

	function showMatchups(data) {
		const lookup = {};
		for (const r of data.rows) lookup[r.player_id + ":" + r.opponent_id] = r;
		if ($id("players").value) {
			for (const p of data.players) playerNames[p.player_id] = p.name;
			chips();
		}
		const matrix = '<div class="analytics-table-scroll" tabindex="0" role="region" aria-label="Matchup residual matrix"><table class="table table-condensed analytics-table analytics-matrix"><caption>Row player against column player · excess wins / rated matches</caption><thead><tr><th scope="col">Player</th>' + data.players.map(p => '<th scope="col">' + esc(p.name) + "</th>").join("") + "</tr></thead><tbody>" + data.players.map(p => '<tr><th scope="row">' + esc(p.name) + "</th>" + data.players.map(opponent => {
			const r = lookup[p.player_id + ":" + opponent.player_id];
			if (!r) return "<td>—</td>";
			const opacity = Math.min(.35, Math.abs(r.shrunk_excess_rate) * 2), bg = r.enough_matches ? (r.excess_wins >= 0 ? "rgba(37,93,104," : "rgba(162,85,59,") + opacity + ")" : "transparent";
			return '<td style="background:' + bg + '" title="' + esc("Wins " + r.wins + "; expected " + num(r.expected_wins) + "; predictive margin ±" + num(r.margin) + (r.enough_matches ? "" : "; below minimum sample")) + '">' + signed(r.excess_wins) + ' <small>/ ' + r.matches + "</small></td>";
		}).join("") + "</tr>").join("") + "</tbody></table></div>";
		content.innerHTML = section("Who causes more trouble than expected?", "Blue cells favor the row player; clay cells favor the opponent. Hover or focus on the detailed table to inspect the sample. Sparse matrix cells stay uncolored.", data.rows.length ? matrix + table(data.rows, [
			{title: "Player", render: playerLink}, {key: "opponent", title: "Opponent"}, {key: "matches", title: "Rated matches", format: v => num(v, 0)}, {key: "wins", title: "Wins", format: v => num(v, 0)},
			{key: "expected_wins", title: "Expected wins", format: num}, {title: "Excess wins", render: r => difference(r.excess_wins)}, {key: "shrunk_excess_rate", title: "Shrunk excess per match", format: pp},
			{title: "95% expected residual range", render: r => num(Math.max(0, r.expected_wins - r.margin) - r.expected_wins) + " to " + num(Math.min(r.matches, r.expected_wins + r.margin) - r.expected_wins)}, {title: "Sample", render: r => r.enough_matches ? "Meets minimum" : "Below minimum"}], "Matchup calculations and sample sizes") + method(data.method) : empty("No rated encounters among these players in the selected scope.") + method(data.method));
	}

	function showCalibration(data) {
		const s = data.summary;
		content.innerHTML = section("When the model says 70%, does it mean 70%?", "Compare predicted favorite probabilities with the results. Lower Brier score and log loss indicate better probabilistic predictions; calibration measures whether the probabilities match observed frequencies.",
			cards([["Evaluated matches", num(s.matches, 0), data.sampled_matches + " sampled of " + data.available_matches + " eligible; " + data.skipped_matches + " skipped"], ["Brier score", num(s.brier, 3), "Lower is better · 0.25 for a constant 50% prediction"],
				["Log loss", num(s.log_loss, 3), "Lower is better"], ["Calibration gap", pct(data.calibration_error), "Weighted absolute gap across favorite bins"]]) + '<div id="calibration-chart"></div>' + table(data.rows, [
			{key: "dimension", title: "Breakdown"}, {key: "label", title: "Group", format: v => surfaceNames[v] || v}, {key: "matches", title: "Matches", format: v => num(v, 0)}, {key: "predicted", title: "Predicted favorite wins", format: pct},
			{key: "observed", title: "Observed favorite wins", format: pct}, {title: "95% observed interval", render: r => pct(r.low) + " to " + pct(r.high)}, {key: "brier", title: "Brier", format: v => num(v, 3)}, {key: "log_loss", title: "Log loss", format: v => num(v, 3)}], "Probability calibration, surface and season breakdowns") + method(data.method));
		const bins = data.rows.filter(r => r.dimension === "Probability");
		appendChart("calibration-chart", [{name: "Perfect calibration", color: "#8e9687", points: [{x: 50, y: .5}, {x: 100, y: 1}]}, {name: "Observed favorite wins", points: bins.map(r => ({x: r.predicted * 100, y: r.observed, low: r.low, high: r.high, label: r.label + " · " + r.matches + " matches"}))}], {title: "Predicted versus observed favorite win rates", xLabel: "Mean predicted favorite win probability (%)", percentage: true, variation: true, minY: 0, maxY: 1});
	}

	async function showDraw(token) {
		const [events, covered] = await Promise.all([api("events"), api("coverage")]);
		if (token !== generation) return;
		$id("analytics-coverage").innerHTML = coverage(covered, "Event-list coverage");
		const saved = new URLSearchParams(location.search).get("eventId");
		const eventId = saved || (events.length ? events[0].tournament_event_id : null);
		content.innerHTML = section("The field, the draw, and the route", "Compare the original bracket with seeded redraws, then inspect the opponents actually faced. Select an event to see which part of its difficulty came from the draw.",
			'<label class="analytics-event-select" for="analytics-event">Tournament event<select id="analytics-event" class="form-control">' + (!events.length ? '<option value="">No events in this scope</option>' : "") +
			events.map(e => '<option value="' + e.tournament_event_id + '"' + (String(e.tournament_event_id) === String(eventId) ? " selected" : "") + ">" + esc(e.season + " · " + e.name + " · " + (surfaceNames[e.surface] || "Unknown surface")) + "</option>").join("") + '</select></label><div id="draw-results"></div>');
		const select = $id("analytics-event");
		if (saved && !events.some(e => String(e.tournament_event_id) === saved)) { const option = document.createElement("option"); option.value = saved; option.textContent = "Linked event"; select.appendChild(option); select.value = saved; }
		select.addEventListener("change", () => loadDrawEvent(select.value, token));
		if (eventId) await loadDrawEvent(eventId, token); else $id("draw-results").innerHTML = empty("There are no tournament events in this period and scope.");
	}

	let drawGeneration = 0;
	async function loadDrawEvent(eventId, token) {
		const drawToken = ++drawGeneration;
		const target = $id("draw-results"); target.textContent = "Reconstructing the bracket and simulating redraws…";
		const url = new URL(location.href); url.searchParams.set("eventId", eventId); history.replaceState(null, "", url);
		try {
			const data = await api("draw", scope({eventId}));
			if (token !== generation || drawToken !== drawGeneration) return;
			lastDraw = data; exported = data.rows.map(r => { const copy = Object.assign({}, r); delete copy.path; return copy; });
			const ratings = data.rows.map(r => r.entry_elo).filter(valid).sort((a, b) => b - a);
			target.innerHTML = "<h3>" + esc(data.season + " · " + data.name) + "</h3>" + cards([["Recorded field", num(data.entrants, 0), "Players appearing in recorded matches"], ["Entry-rating coverage", num(data.rated_entrants, 0) + " / " + num(data.entrants, 0), "First recorded pre-match Elo in the event"], ["Mean field Elo", num(mean(ratings)), "Rated entrants only"], ["Top-eight mean Elo", ratings.length >= 8 ? num(mean(ratings.slice(0, 8))) : "—", "Rated entrants only"]]) +
				(!data.simulated ? '<p class="alert alert-warning">' + esc(data.reason) + "</p>" : '<p class="text-muted">' + num(data.iterations, 0) + " seeded redraws · " + data.draw_size + " draw slots. Error margins cover simulation sampling only.</p>") + table(data.rows, [
					{title: "Player", render: playerLink}, {key: "seed", title: "Seed"}, {key: "entry_elo", title: "Entry Elo", format: num},
					{title: "Original bracket · title chance", render: r => probabilityBar(r.actual_title_probability)}, {title: "Seeded redraw · title chance", render: r => probabilityBar(r.redraw_title_probability)},
					{key: "draw_bonus", title: "Draw bonus", format: pp}, {key: "simulation_error", title: "95% redraw margin", format: pp},
					{key: "path_probability", title: "Win each observed matchup", format: pct}, {title: "Opponents faced", render: r => '<button class="analytics-data-link" type="button" data-path="' + r.player_id + '">Inspect route</button>'}], "Entry field and draw comparison") + method(data.method);
		} catch (error) { if (error.name !== "AbortError" && token === generation) target.textContent = error.message; }
	}

	function probabilityBar(value) { return pct(value) + (valid(value) ? '<div class="analytics-probability-bar"><span style="width:' + Math.max(0, Math.min(100, value * 100)) + '%"></span></div>' : ""); }

	function openDetails(title, body) {
		lastFocus = document.activeElement;
		$id("analytics-details-title").textContent = title;
		$id("analytics-details-content").textContent = body;
		if (!$id("analytics-details").open) $id("analytics-details").showModal();
	}

	async function showMatches(playerId, season) {
		openDetails("Matches behind the comparison", "Loading recorded match results…");
		try {
			const extra = {playerId}; if (season) { extra.fromSeason = season; extra.toSeason = season; }
			const data = await api("matches", scope(extra)); detailRows = data.rows;
			$id("analytics-details-content").innerHTML = table(data.rows, [
				{key: "date", title: "Date"}, {title: "Tournament", render: r => '<a href="/tournamentEvent?tournamentEventId=' + r.tournament_event_id + '">' + esc(r.event_name) + "</a>"},
				{key: "round", title: "Round"}, {key: "surface", title: "Surface", format: s => surfaceNames[s]}, {key: "winner", title: "Winner"}, {key: "loser", title: "Loser"}, {key: "score", title: "Score"},
				{key: "winner_rank", title: "Winner ATP rank"}, {key: "loser_rank", title: "Loser ATP rank"}, {key: "winner_elo", title: "Winner Elo", format: num},
				{key: "loser_elo", title: "Loser Elo", format: num}, {key: "expected", title: "Selected player's win chance", format: pct}, {key: "residual", title: "Result minus expectation", format: v => signed(v)}], "Underlying completed matches · newest first") + tableNote(data) + method(data.method);
		} catch (error) { if (error.name !== "AbortError") $id("analytics-details-content").textContent = error.message; }
	}

	function chips() {
		const ids = $id("players").value.split(",").filter(Boolean);
		$id("matrix-players").innerHTML = ids.map(id => '<span class="analytics-chip">' + esc(playerNames[id] || "Selected player") + '<button type="button" data-remove-player="' + Number(id) + '" aria-label="Remove ' + esc(playerNames[id] || "player") + '">×</button></span>').join("");
	}

	async function load(push) {
		if (!form.reportValidity()) return;
		if (request) request.abort(); request = new AbortController();
		const token = ++generation;
		remember(push);
		content.setAttribute("aria-busy", "true"); $id("analytics-status").textContent = "Calculating comparison…";
		$id("run-analytics").disabled = true; $id("export-analytics").disabled = true;
		try {
			if (view === "era") await showEra(token);
			else if (view === "draw") await showDraw(token);
			else await showStandard(token);
			if (token !== generation) return;
			$id("analytics-status").textContent = "Comparison ready. Filters are saved in the link.";
		} catch (error) {
			if (error.name === "AbortError" || token !== generation) return;
			content.innerHTML = '<div class="alert alert-warning"><strong>This comparison could not be loaded.</strong><p>' + esc(error.message) + "</p><p>Adjust the filters and explore again.</p></div>";
			$id("analytics-status").textContent = "Comparison unavailable."; exported = [];
		} finally {
			if (token === generation) { content.setAttribute("aria-busy", "false"); $id("run-analytics").disabled = false; $id("export-analytics").disabled = !exported.length; }
		}
	}

	form.addEventListener("submit", event => { event.preventDefault(); if (view === "era") equalize("a"); load(); });
	form.addEventListener("change", event => {
		if (view === "era" && /^[ab](From|To)$/.test(event.target.id)) equalize(event.target.id[0]);
		if (["surface", "indoor", "level", "distance", "excludeTop", "variation", "windowYears", "performanceMetric", "model", "scale"].includes(event.target.id)) load();
	});
	for (const link of document.querySelectorAll("[data-view]")) link.addEventListener("click", event => { event.preventDefault(); view = link.dataset.view; visibility(); load(true); });
	window.addEventListener("popstate", () => { restore(); load(); });
	$id("share-analytics").addEventListener("click", async () => {
		try { await navigator.clipboard.writeText(location.href); $id("analytics-status").textContent = "Comparison link copied."; }
		catch (ignored) { openDetails("Share this comparison", location.href); }
	});
	$id("export-analytics").addEventListener("click", () => {
		const keys = Array.from(new Set(exported.flatMap(r => Object.keys(r).filter(k => r[k] === null || typeof r[k] !== "object"))));
		const cell = value => { let text = value == null ? "" : String(value); if (typeof value === "string" && /^[=+\-@\t\r]/.test(text)) text = "'" + text; return '"' + text.replace(/"/g, '""') + '"'; };
		const csv = [keys.map(cell).join(",")].concat(exported.map(r => keys.map(k => cell(r[k])).join(","))).join("\r\n");
		const url = URL.createObjectURL(new Blob([csv], {type: "text/csv;charset=utf-8"})); const link = document.createElement("a"); link.href = url; link.download = "tennis-" + view + ".csv"; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
	});
	$id("close-analytics-details").addEventListener("click", () => $id("analytics-details").close());
	$id("analytics-details").addEventListener("close", () => { if (lastFocus && lastFocus.isConnected) lastFocus.focus(); });
	document.addEventListener("click", event => {
		const inspectButton = event.target.closest("[data-inspect]"); if (inspectButton) showMatches(Number(inspectButton.dataset.inspect), inspectButton.dataset.season);
		const routeButton = event.target.closest("[data-path]"); if (routeButton && lastDraw) {
			const player = lastDraw.rows.find(r => r.player_id === Number(routeButton.dataset.path)); openDetails(player.name + " · recorded route", "");
			$id("analytics-details-content").innerHTML = table(player.path, [{key: "round", title: "Round"}, {key: "opponent", title: "Opponent"}, {key: "opponent_elo", title: "Entry Elo", format: num},
				{title: "Result", render: r => r.won ? "Won" : "Lost"}, {key: "score", title: "Score"}, {key: "outcome", title: "Special outcome"}, {key: "probability", title: "Entry-rating win chance", format: pct}], "Opponents actually faced · entry ratings held fixed");
		}
		const remove = event.target.closest("[data-remove-player]"); if (remove) { $id("players").value = $id("players").value.split(",").filter(id => id !== remove.dataset.removePlayer).join(","); chips(); load(); }
	});
	if (window.jQuery) {
		window.jQuery("#analytics-player").autocomplete({source: "/autocompletePlayer", minLength: 2, select: function (event, ui) { event.preventDefault(); $id("playerId").value = ui.item.id; this.value = ui.item.value; load(); }});
		$id("analytics-player").addEventListener("input", () => { $id("playerId").value = "0"; });
		window.jQuery("#matrix-player").autocomplete({source: "/autocompletePlayer", minLength: 2, select: function (event, ui) {
			event.preventDefault(); const ids = $id("players").value.split(",").filter(Boolean);
			if (!ids.includes(String(ui.item.id)) && ids.length < 12) ids.push(String(ui.item.id));
			playerNames[ui.item.id] = ui.item.value; $id("players").value = ids.join(","); this.value = ""; chips(); load();
		}});
	}
	restore(); chips(); load();
})();
