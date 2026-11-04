(function () {
	"use strict";
	const byId = id => document.getElementById(id), rules = byId("rules-form"), tournament = byId("tournament-form");
	const esc = value => String(value).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
	const pct = value => (value * 100).toFixed(2) + "%";
	const requests = {}, generations = {rules:0, tournament:0};
	let field = [];
	function syncField() {
		field = Array.from(byId("tournament-field").children).map(row => {
			const player = {id:Number(row.dataset.id), name:row.querySelector('[data-key="name"]').value, elo:Number(row.querySelector('[data-key="elo"]').value)};
			const seed = row.querySelector('[data-key="seed"]').value;
			if (seed !== "") player.seed = Number(seed);
			return player;
		});
		tournament.elements.players.value = JSON.stringify(field, null, 2);
	}
	function renderField() {
		const container = byId("tournament-field"); container.replaceChildren();
		field.forEach((player, index) => {
			const row = document.createElement("div"); row.className = "research-entrant"; row.dataset.id = player.id == null ? index + 1 : player.id;
			for (const [key, text, type] of [["name", "Player " + (index + 1), "text"], ["elo", "Assumed Elo", "number"], ["seed", "Seed (optional)", "number"]]) {
				const label = document.createElement("label"), input = document.createElement("input");
				label.textContent = text; label.className = "entrant-" + key; input.className = "form-control"; input.type = type; input.dataset.key = key;
				input.value = player[key] == null ? "" : player[key];
				if (key === "name") { input.maxLength = 100; input.required = true; }
				if (key === "elo") { input.min = "500"; input.max = "3500"; input.step = "any"; input.required = true; }
				if (key === "seed") { input.min = "1"; input.max = "32"; input.step = "1"; }
				label.appendChild(input); row.appendChild(label);
			}
			const remove = document.createElement("button"); remove.className = "btn btn-default"; remove.type = "button"; remove.textContent = "Remove"; remove.setAttribute("aria-label", "Remove player " + (index + 1)); remove.disabled = field.length <= 2;
			remove.addEventListener("click", () => { syncField(); field.splice(index, 1); renderField(); syncField(); byId("add-tournament-player").focus(); });
			row.appendChild(remove); container.appendChild(row);
		});
		byId("add-tournament-player").disabled = field.length >= 64;
	}
	function importField() {
		const imported = JSON.parse(tournament.elements.players.value);
		if (!Array.isArray(imported) || imported.length < 2 || imported.length > 64 || imported.some(player => !player || typeof player !== "object" || Array.isArray(player))) throw new Error("Import an array of 2–64 player objects.");
		field = imported.map((player, index) => Object.assign({}, player, {name:player.name == null ? "Player " + (index + 1) : player.name})); renderField(); syncField();
	}
	byId("tournament-field").addEventListener("input", syncField);
	byId("add-tournament-player").addEventListener("click", () => { syncField(); field.push({id:Math.max(0, ...field.map(player => player.id)) + 1, name:"Player " + (field.length + 1), elo:2000}); renderField(); syncField(); byId("tournament-field").lastElementChild.querySelector("input").focus(); });
	byId("import-tournament-field").addEventListener("click", () => { try { importField(); byId("tournament-status").textContent = "Field imported. Review the entrants and run the experiment."; } catch (error) { byId("tournament-status").textContent = error.message; } });
	function table(headers, rows) { return '<div class="research-table-scroll" tabindex="0" role="region" aria-label="Experiment results"><table class="research-table"><thead><tr>' + headers.map(header => '<th scope="col">' + esc(header) + "</th>").join("") + "</tr></thead><tbody>" + rows.map(row => "<tr>" + row.map(value => "<td>" + esc(value) + "</td>").join("") + "</tr>").join("") + "</tbody></table></div>"; }
	function state(kind) { if (kind === "tournament") syncField(); const params = new URLSearchParams(new FormData(kind === "rules" ? rules : tournament)); params.set("experiment", kind); return params; }
	async function run(kind) {
		const form = kind === "rules" ? rules : tournament;
		if (!form.reportValidity()) return;
		if (requests[kind]) requests[kind].abort(); requests[kind] = new AbortController(); const token = ++generations[kind];
		byId(kind + "-status").textContent = "Calculating…"; byId(kind + "-results").setAttribute("aria-busy", "true");
		form.querySelector('[type="submit"]').disabled = true;
		try {
			let url, options = {signal:requests[kind].signal, headers:{Accept:"application/json"}};
			if (kind === "rules") {
				url = "/api/v1/lab/rules?" + new URLSearchParams(new FormData(form));
				byId("rules-json").href = url; byId("rules-request").textContent = "curl --fail '" + new URL(url, location.origin).href + "'";
			} else {
				syncField();
				const body = {players:JSON.parse(form.elements.players.value), draw:form.elements.draw.value, seed:Number(form.elements.seed.value), iterations:Number(form.elements.iterations.value), scale:Number(form.elements.scale.value)};
				url = "/api/v1/simulations"; options.method = "POST"; options.headers["Content-Type"] = "application/json"; options.body = JSON.stringify(body);
				byId("tournament-request").textContent = "curl --fail '" + location.origin + url + "' \\\n  -H 'Content-Type: application/json' \\\n  --data-binary @- <<'TENNIS_SCENARIO'\n" + JSON.stringify(body, null, 2) + "\nTENNIS_SCENARIO";
			}
			const response = await fetch(url, options), result = await response.json();
			if (!response.ok) throw new Error(result.error && (result.error.message || result.error) || "Experiment unavailable.");
			if (token !== generations[kind]) return;
			if (kind === "rules") byId("rules-results").innerHTML = table(["Best of", "Scoring", "Deciding set", "Player 1 win chance", "Player 2 win chance"], result.data.map(row => [row.best_of, row.no_ad ? "No-ad" : "Advantage", row.deciding_set, pct(row.player1_probability), pct(row.player2_probability)]));
			else {
				const rounds = result.data[0].round_probabilities.length;
				const headers = ["Player", "Assumed Elo"].concat(Array.from({length:rounds}, (_, i) => i === rounds - 1 ? "Win title" : "Pass round " + (i + 1)));
				byId("tournament-results").innerHTML = table(headers, result.data.slice().sort((a, b) => b.title_probability - a.title_probability).map(row => [row.name, row.elo].concat(row.round_probabilities.map(pct)))) + '<p class="research-note">' + result.draw_size + " bracket slots · " + result.iterations + " bracket(s) · seed " + result.seed + " · " + esc(result.model_version) + "</p>";
			}
			byId(kind + "-status").textContent = "Experiment ready.";
			history.replaceState(null, "", "/lab?" + state(kind) + "#" + kind);
		} catch (error) { if (error.name !== "AbortError" && token === generations[kind]) { byId(kind + "-results").textContent = error.message; byId(kind + "-status").textContent = "Check your inputs and try again."; } }
		finally { if (token === generations[kind]) { byId(kind + "-results").setAttribute("aria-busy", "false"); form.querySelector('[type="submit"]').disabled = false; } }
	}
	for (const [kind, form] of [["rules", rules], ["tournament", tournament]]) form.addEventListener("submit", event => { event.preventDefault(); run(kind); });
	function drawMode() { const fixed = tournament.elements.draw.value === "fixed"; tournament.elements.iterations.readOnly = fixed; if (fixed) tournament.elements.iterations.value = "1"; }
	 tournament.elements.draw.addEventListener("change", drawMode);
	for (const button of document.querySelectorAll("[data-share]")) button.addEventListener("click", async () => { const kind = button.dataset.share, url = location.origin + "/lab?" + state(kind) + "#" + kind; try { await navigator.clipboard.writeText(url); byId(kind + "-status").textContent = "Experiment link copied."; } catch (ignored) { history.replaceState(null, "", url); byId(kind + "-status").textContent = "Copy the address in your browser to share this experiment."; } });
	const saved = new URLSearchParams(location.search), kind = saved.get("experiment") === "tournament" ? "tournament" : "rules", form = kind === "rules" ? rules : tournament;
	for (const input of form.elements) if (input.name && saved.has(input.name)) input.value = saved.get(input.name);
	drawMode();
	try { importField(); run(kind); } catch (error) { byId("tournament-status").textContent = error.message; }
})();
