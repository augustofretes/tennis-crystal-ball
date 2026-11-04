(function () {
	"use strict";
	const byId = id => document.getElementById(id), form = byId("data-filters");
	const resources = ["players", "matches", "events", "rankings", "ratings"];
	const esc = value => String(value == null ? "—" : value).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
	let request, generation = 0, page;
	function visibility() {
		const resource = form.elements.resource.value;
		for (const label of form.querySelectorAll("[data-for]")) {
			label.hidden = !label.dataset.for.split(",").includes(resource);
			for (const input of label.querySelectorAll("input,select")) input.disabled = label.hidden;
		}
		form.elements.playerId.required = resource === "ratings";
	}
	function restore() {
		form.reset();
		const params = new URLSearchParams(location.search);
		for (const input of form.elements) if (input.name && params.has(input.name)) input.value = params.get(input.name);
		if (!resources.includes(form.elements.resource.value)) form.elements.resource.value = "players";
		visibility();
	}
	function parameters(includeResource) {
		const params = new URLSearchParams();
		for (const [key, value] of new FormData(form)) if (value !== "" && (includeResource || key !== "resource")) params.set(key, value);
		return params;
	}
	function endpoint() {
		const resource = form.elements.resource.value, params = parameters(false);
		let path = "/api/v1/" + resource;
		if (resource === "ratings") { path = "/api/v1/players/" + encodeURIComponent(params.get("playerId")) + "/ratings"; params.delete("playerId"); }
		return path + "?" + params;
	}
	async function json(url, signal) {
		const response = await fetch(url, {signal, headers:{Accept:"application/json"}}), body = await response.json();
		if (!response.ok) throw new Error(body.error && (body.error.message || body.error) || "Data could not be loaded (" + response.status + ").");
		return body;
	}
	function render(rows) {
		if (!rows.length) return "<p>No records match these filters. Try a wider period or another player.</p>";
		const columns = Array.from(new Set(rows.flatMap(row => Object.keys(row))));
		function cell(key, value, row) {
			if (value == null) return "—";
			if (key === "name" && row.player_id) return '<a href="/playerProfile?playerId=' + Number(row.player_id) + '">' + esc(value) + "</a>";
			if (["winner", "loser"].includes(key)) return '<a href="/playerProfile?playerId=' + Number(row[key + "_id"]) + '">' + esc(value) + "</a>";
			if ((key === "name" || key === "event") && row.event_id) return '<a href="/tournamentEvent?tournamentEventId=' + Number(row.event_id) + '">' + esc(value) + "</a>";
			if (key === "match_id") return '<a href="/api/v1/matches/' + Number(value) + '">' + esc(value) + "</a>";
			return esc(value);
		}
		return '<div class="research-table-scroll" tabindex="0" role="region" aria-label="Tennis data results"><table class="research-table"><caption>One page of results · unknown values appear as — · scroll for more columns</caption><thead><tr>' + columns.map(key => '<th scope="col">' + esc(key.replace(/_/g, " ")) + "</th>").join("") + "</tr></thead><tbody>" + rows.map(row => "<tr>" + columns.map(key => "<td>" + cell(key, row[key], row) + "</td>").join("") + "</tr>").join("") + "</tbody></table></div>";
	}
	async function load(push) {
		if (!form.reportValidity()) return;
		if (request) request.abort(); request = new AbortController(); const token = ++generation;
		const url = endpoint(), share = "/data?" + parameters(true);
		if (push) history.pushState(null, "", share); else history.replaceState(null, "", share);
		byId("data-status").textContent = "Loading records…"; byId("data-results").setAttribute("aria-busy", "true");
		byId("data-next").disabled = true; byId("data-previous").disabled = true;
		const full = new URL(url, location.origin).href;
		byId("data-json").href = url; byId("data-csv").href = url + "&format=csv";
		// JSON escaping also makes quotes and backslashes safe in the Python string literal.
		byId("data-python").textContent = "import json\nfrom urllib.request import urlopen\n\nurl = " + JSON.stringify(full) + "\npage = json.load(urlopen(url))\nprint(page['data'])\n# Follow page['links'].get('next') for more rows (relative to the API host).";
		byId("data-curl").textContent = "curl --fail '" + full.replace(/'/g, "'\\''") + "'";
		try {
			const result = await json(url, request.signal); if (token !== generation) return;
			page = result.pagination; byId("data-results").innerHTML = render(result.data);
			byId("data-page").textContent = page.returned ? "Rows " + (page.offset + 1) + "–" + (page.offset + page.returned) + (page.offset_limit_reached ? " · narrow filters to continue" : "") : "No rows";
			byId("data-next").disabled = !result.links.next; byId("data-previous").disabled = !result.links.previous;
			byId("data-status").textContent = "Ready. Downloads contain this page of results.";
		} catch (error) { if (error.name !== "AbortError" && token === generation) { page = null; byId("data-results").textContent = error.message; byId("data-status").textContent = "Unable to load records."; byId("data-page").textContent = ""; } }
		finally { if (token === generation) byId("data-results").setAttribute("aria-busy", "false"); }
	}
	form.addEventListener("submit", event => { event.preventDefault(); form.elements.offset.value = "0"; load(true); });
	form.elements.resource.addEventListener("change", () => { form.elements.offset.value = "0"; visibility(); if (form.elements.resource.value !== "ratings" || form.elements.playerId.value) load(true); });
	for (const [id, direction] of [["data-next", 1], ["data-previous", -1]]) byId(id).addEventListener("click", () => { if (page) { form.elements.offset.value = Math.max(0, page.offset + direction * page.limit); load(true); } });
	byId("data-share").addEventListener("click", async () => { try { await navigator.clipboard.writeText(location.href); byId("data-status").textContent = "Exploration link copied."; } catch (ignored) { byId("data-status").textContent = "Copy the address in your browser to share this exploration."; } });
	window.addEventListener("popstate", () => { restore(); load(false); });
	if (window.jQuery) window.jQuery("#data-player-search").autocomplete({source:"/autocompletePlayer", minLength:2, select:function (event, ui) { event.preventDefault(); this.value = ui.item.value; form.elements.playerId.value = ui.item.id; }});
	json("/api/v1/coverage").then(result => {
		const data = result.data;
		const items = [["Recorded matches", Number(data.recorded_matches).toLocaleString(), "Includes special outcomes"], ["Results through", data.matches_through || "Unavailable", "Latest recorded date; coverage can be partial"], ["Rankings through", data.rankings_through || "Unavailable", "ATP ranking snapshot"], ["Ingestion checkpoint", data.last_successful_ingestion_at ? data.last_successful_ingestion_at.slice(0, 10) : "Unavailable", "Latest successful result sync; not a coverage guarantee"]];
		byId("data-coverage").innerHTML = items.map(item => '<div class="research-card"><span>' + esc(item[0]) + "</span><strong>" + esc(item[1]) + "</strong><small>" + esc(item[2]) + "</small></div>").join("");
	}).catch(() => { byId("data-coverage").textContent = "Coverage is temporarily unavailable. You can still try an exploration below."; });
	restore(); load(false);
})();
