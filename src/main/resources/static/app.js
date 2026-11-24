(() => {
	"use strict";

	const tokenStorageKey = "trafficpilot.token";
	const state = { services: [], routes: [], teams: [], users: [], audit: [], auditOffset: 0, canaryRouteId: "", user: null };
	const notice = document.getElementById("notice");
	const loginDialog = document.getElementById("login-dialog");
	let noticeTimer;

	const roleLabels = { PLATFORM_ADMIN: "Platform admin", TEAM_ADMIN: "Team admin", VIEWER: "Viewer" };

	const escapeHtml = (value) => String(value ?? "").replace(/[&<>"']/g, (character) => ({
		"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
	})[character]);

	function showNotice(message, type = "error") {
		window.clearTimeout(noticeTimer);
		notice.textContent = message;
		notice.className = `notice ${type}`;
		notice.hidden = false;
		noticeTimer = window.setTimeout(() => { notice.hidden = true; }, 6500);
	}

	function setConnection(connected) {
		const indicator = document.getElementById("connection-status");
		indicator.classList.toggle("offline", !connected);
		indicator.innerHTML = `<span class="status-dot"></span>${connected ? "Connected to gateway" : "Gateway unavailable"}`;
	}

	async function api(path, options = {}) {
		const headers = new Headers(options.headers || {});
		const token = sessionStorage.getItem(tokenStorageKey);
		if (token && path.startsWith("/admin/")) {
			headers.set("Authorization", `Bearer ${token}`);
		}
		if (options.body !== undefined) {
			headers.set("Content-Type", "application/json");
		}

		let response;
		try {
			response = await fetch(path, { ...options, headers });
		} catch (error) {
			setConnection(false);
			throw new Error("Could not reach the gateway. Check that TrafficPilot is running and try again.");
		}
		setConnection(true);

		if (!response.ok) {
			if (response.status === 401 && path.startsWith("/admin/")) {
				signedOut();
			}
			const body = await response.text();
			let detail = body;
			try {
				const parsed = JSON.parse(body);
				detail = parsed.detail || parsed.message || parsed.error || body;
			} catch {
				detail = body;
			}
			throw new Error(detail || `Request failed (${response.status})`);
		}
		if (response.status === 204) return null;
		return response.json();
	}

	function renderServiceRows() {
		document.getElementById("service-count").textContent = state.services.length;
		const body = document.getElementById("services-body");
		if (!state.services.length) {
			body.innerHTML = '<tr><td class="empty-cell" colspan="6">No services registered yet. Add one to start routing traffic.</td></tr>';
		} else {
			body.innerHTML = state.services.map((service) => `
				<tr>
					<td><span class="primary-cell">${escapeHtml(service.name)}</span><span class="secondary-cell">Created ${escapeHtml(formatDate(service.createdAt))}</span></td>
					<td>${escapeHtml(service.baseUrl)}</td>
					<td>${escapeHtml(service.healthCheckPath)}</td>
					<td>${escapeHtml(service.ownerTeam)}</td>
					<td><span class="status-pill ${service.status !== "UP" ? "inactive" : ""}"><span class="status-dot"></span>${escapeHtml(service.status || "UNKNOWN")}</span><span class="secondary-cell">${service.lastCheckedAt ? `Checked ${escapeHtml(new Date(service.lastCheckedAt).toLocaleString())}` : "Not checked yet"}</span></td>
					<td><button class="action-button write-only" type="button" data-delete-service="${escapeHtml(service.id)}" aria-label="Delete ${escapeHtml(service.name)}">Delete</button></td>
				</tr>`).join("");
		}

	}

	function renderServices() {
		renderServiceRows();
		const serviceOptions = state.services.map((service) =>
			`<option value="${escapeHtml(service.name)}">${escapeHtml(service.name)} — ${escapeHtml(service.baseUrl)}</option>`).join("");
		const routeService = document.getElementById("route-service");
		const selectedService = routeService.value;
		routeService.innerHTML = `<option value="">Choose a service…</option>${serviceOptions}`;
		if (state.services.some((service) => service.name === selectedService)) routeService.value = selectedService;
		renderCanaryTargets();
	}

	function renderRoutes() {
		document.getElementById("route-count").textContent = state.routes.length;
		const body = document.getElementById("routes-body");
		if (!state.routes.length) {
			body.innerHTML = '<tr><td class="empty-cell" colspan="6">No routes configured yet. Create one after registering a service.</td></tr>';
		} else {
			body.innerHTML = state.routes.map((route) => {
				const policies = [
					route.authRequired ? '<span class="policy-pill policy-blue">API key</span>' : "",
					route.rateLimitPerMinute ? `<span class="policy-pill">${escapeHtml(route.rateLimitPerMinute)}/min</span>` : "",
					route.stripPrefix > 0 ? `<span class="policy-pill">Strip ${escapeHtml(route.stripPrefix)}</span>` : ""
				].filter(Boolean).join("");
				return `<tr>
					<td><span class="primary-cell">${escapeHtml(route.routeId)}</span><span class="secondary-cell">ID ${escapeHtml(route.id)}</span></td>
					<td>${escapeHtml(route.path)}</td>
					<td><span class="primary-cell">${escapeHtml(route.serviceName)}</span><span class="secondary-cell">${escapeHtml(route.serviceBaseUrl)}</span></td>
					<td>${policies || '<span class="secondary-cell">No extra policies</span>'}</td>
					<td><span class="status-pill ${route.enabled ? "" : "inactive"}"><span class="status-dot"></span>${route.enabled ? "Enabled" : "Disabled"}</span></td>
					<td><button class="action-button write-only" type="button" data-delete-route="${escapeHtml(route.id)}" aria-label="Delete ${escapeHtml(route.routeId)}">Delete</button></td>
				</tr>`;
			}).join("");
		}

		const selector = document.getElementById("canary-route");
		const previous = state.canaryRouteId;
		selector.innerHTML = `<option value="">Select a route…</option>${state.routes.map((route) =>
			`<option value="${escapeHtml(route.id)}">${escapeHtml(route.routeId)} — ${escapeHtml(route.path)}</option>`).join("")}`;
		if (state.routes.some((route) => String(route.id) === String(previous))) {
			selector.value = previous;
		} else {
			state.canaryRouteId = "";
			selector.value = "";
		}
		renderCanaryTargets();
	}

	function formatDate(value) {
		if (!value) return "date unavailable";
		const date = new Date(value);
		return Number.isNaN(date.getTime()) ? "date unavailable" : date.toLocaleDateString();
	}

	function renderCanaryTargets(targets = []) {
		const selector = document.getElementById("canary-route");
		const container = document.getElementById("canary-targets");
		const selectedRoute = selector.value;
		state.canaryRouteId = selectedRoute;
		container.replaceChildren();
		document.getElementById("save-canary-button").disabled = true;
		document.getElementById("remove-canary-button").disabled = true;
		document.getElementById("add-target-button").disabled = !selectedRoute || !state.services.length;

		if (!selectedRoute) {
			document.getElementById("weight-total").textContent = "Select a route to configure a split";
			document.getElementById("weight-total").className = "weight-total";
			return;
		}

		const route = state.routes.find((item) => String(item.id) === String(selectedRoute));
		if (!route) return;
		targets.forEach((target) => addTargetRow(target.serviceName, target.weight));
		const hasCanary = targets.length > 0;
		document.getElementById("remove-canary-button").disabled = !hasCanary;
		updateWeightTotal();
	}

	function addTargetRow(serviceName = "", weight = "") {
		const container = document.getElementById("canary-targets");
		const row = document.createElement("div");
		row.className = "target-row";
		const options = state.services.map((service) =>
			`<option value="${escapeHtml(service.name)}">${escapeHtml(service.name)}</option>`).join("");
		row.innerHTML = `
			<div><label class="field-label">Service</label><select class="target-service"><option value="">Choose service…</option>${options}</select></div>
			<div><label class="field-label">Traffic %</label><input class="target-weight" type="number" min="1" max="100" step="1" placeholder="0"></div>
			<button class="target-remove write-only" type="button" aria-label="Remove target">×</button>`;
		row.querySelector(".target-service").value = serviceName;
		row.querySelector(".target-weight").value = weight;
		row.querySelector(".target-service").addEventListener("change", updateWeightTotal);
		row.querySelector(".target-weight").addEventListener("input", updateWeightTotal);
		row.querySelector(".target-remove").addEventListener("click", () => {
			row.remove();
			updateWeightTotal();
		});
		container.append(row);
		updateWeightTotal();
	}

	function updateWeightTotal() {
		const rows = [...document.querySelectorAll(".target-row")];
		const total = rows.reduce((sum, row) => sum + (Number(row.querySelector(".target-weight").value) || 0), 0);
		const totalElement = document.getElementById("weight-total");
		const valid = rows.length > 0 && total === 100
			&& rows.every((row) => row.querySelector(".target-service").value
				&& Number(row.querySelector(".target-weight").value) >= 1
				&& Number(row.querySelector(".target-weight").value) <= 100);
		totalElement.textContent = `Total: ${total}%${total === 100 ? "" : " (must equal 100%)"}`;
		totalElement.className = `weight-total ${valid ? "valid" : "invalid"}`;
		const selectedServices = rows.map((row) => row.querySelector(".target-service").value).filter(Boolean);
		const noDuplicates = new Set(selectedServices).size === selectedServices.length;
		document.getElementById("save-canary-button").disabled = !valid || !noDuplicates || !state.canaryRouteId;
	}

	async function loadCanary(routeId) {
		state.canaryRouteId = routeId;
		if (!routeId) {
			renderCanaryTargets();
			return;
		}
		try {
			const targets = await api(`/admin/routes/${encodeURIComponent(routeId)}/canary`);
			const route = state.routes.find((item) => String(item.id) === String(routeId));
			if (route) route.canaryEnabled = targets.length > 0;
			document.getElementById("canary-count").textContent = state.routes.filter((item) => item.canaryEnabled).length;
			renderCanaryTargets(targets);
			document.getElementById("remove-canary-button").disabled = targets.length === 0;
		} catch (error) {
			showNotice(`Could not load canary targets: ${error.message}`);
		}
	}

	async function loadHealth() {
		const health = document.getElementById("health-value");
		const detail = document.getElementById("health-detail");
		try {
			const response = await fetch("/actuator/health");
			if (!response.ok) throw new Error(`HTTP ${response.status}`);
			const result = await response.json();
			const up = result.status === "UP";
			health.textContent = up ? "Operational" : result.status || "Unknown";
			health.style.color = up ? "var(--green)" : "var(--red)";
			detail.textContent = up ? "Actuator reports all clear" : "Check application health";
		} catch {
			health.textContent = "Unavailable";
			health.style.color = "var(--red)";
			detail.textContent = "Health endpoint could not be reached";
		}
	}

	function signedOut() {
		sessionStorage.removeItem(tokenStorageKey);
		state.user = null;
		state.services = [];
		state.routes = [];
		state.teams = [];
		state.users = [];
		state.audit = [];
		state.auditOffset = 0;
		applyUser();
		renderServices();
		renderRoutes();
		renderAccess();
		renderAudit();
		if (!loginDialog.open) loginDialog.showModal();
	}

	function applyUser() {
		const user = state.user;
		document.body.dataset.canWrite = String(Boolean(user && user.role !== "VIEWER"));
		document.body.dataset.platformAdmin = String(Boolean(user && user.role === "PLATFORM_ADMIN"));
		const chip = document.getElementById("user-chip");
		chip.hidden = !user;
		document.getElementById("logout-button").hidden = !user;
		document.getElementById("password-button").hidden = !user;
		if (user) {
			const scope = user.teamName ? ` · ${escapeHtml(user.teamName)}` : "";
			chip.innerHTML = `${escapeHtml(user.username)}<span class="role">${escapeHtml(roleLabels[user.role] || user.role)}${scope}</span>`;
		}
	}

	function renderAccess() {
		const teamsBody = document.getElementById("teams-body");
		teamsBody.innerHTML = state.teams.length
			? state.teams.map((team) => `<tr><td><span class="primary-cell">${escapeHtml(team.name)}</span></td><td>${escapeHtml(formatDate(team.createdAt))}</td></tr>`).join("")
			: '<tr><td class="empty-cell" colspan="2">No teams yet.</td></tr>';

		const usersBody = document.getElementById("users-body");
		usersBody.innerHTML = state.users.length
			? state.users.map((user) => `<tr>
				<td><span class="primary-cell">${escapeHtml(user.username)}</span></td>
				<td>${escapeHtml(roleLabels[user.role] || user.role)}</td>
				<td>${escapeHtml(user.teamName || "—")}</td>
				<td>${user.id === state.user.id ? "" : `<button class="action-button" type="button" data-delete-user="${escapeHtml(user.id)}" aria-label="Delete ${escapeHtml(user.username)}">Delete</button>`}</td>
			</tr>`).join("")
			: '<tr><td class="empty-cell" colspan="4">No users yet.</td></tr>';

		const teamOptions = state.teams.map((team) =>
			`<option value="${escapeHtml(team.name)}">${escapeHtml(team.name)}</option>`).join("");
		document.getElementById("service-owner").innerHTML = teamOptions;
		document.getElementById("user-team").innerHTML = teamOptions;
	}

	async function loadAccess() {
		const [teams, users] = await Promise.all([api("/admin/teams"), api("/admin/users")]);
		state.teams = teams;
		state.users = users;
		renderAccess();
	}

	function renderAudit() {
		document.getElementById("audit-body").innerHTML = state.audit.length
			? state.audit.map((entry) => `<tr>
				<td>${escapeHtml(new Date(entry.occurredAt).toLocaleString())}</td>
				<td>${escapeHtml(entry.actorUsername)}</td>
				<td>${escapeHtml(entry.action)}</td>
				<td>${escapeHtml(entry.routeId)}</td>
				<td>${escapeHtml(entry.details)}</td>
			</tr>`).join("")
			: '<tr><td class="empty-cell" colspan="5">No route changes recorded on this page.</td></tr>';
		document.getElementById("audit-newer").disabled = state.auditOffset === 0;
		document.getElementById("audit-older").disabled = state.audit.length < 50;
	}

	async function loadAudit(offset = state.auditOffset) {
		const token = sessionStorage.getItem(tokenStorageKey);
		const entries = await api(`/admin/audit?limit=50&offset=${offset}`);
		if (!state.user || token !== sessionStorage.getItem(tokenStorageKey)) return;
		state.audit = entries;
		state.auditOffset = offset;
		renderAudit();
	}

	for (const [id, delta] of [["audit-newer", -50], ["audit-older", 50]]) {
		document.getElementById(id).addEventListener("click", async () => {
			try {
				await loadAudit(Math.max(0, state.auditOffset + delta));
			} catch (error) {
				showNotice(`Could not load audit history: ${error.message}`);
			}
		});
	}

	async function loadData() {
		try {
			const [services, routes] = await Promise.all([
				api("/admin/services"),
				api("/admin/routes")
			]);
			state.services = services;
			state.routes = routes;
			if (state.user && state.user.role === "PLATFORM_ADMIN") await loadAccess();
			const canaryResults = await Promise.allSettled(routes.map(async (route) => {
				const targets = await api(`/admin/routes/${encodeURIComponent(route.id)}/canary`);
				route.canaryEnabled = targets.length > 0;
			}));
			renderServices();
			renderRoutes();
			const canaryFailed = canaryResults.some((result) => result.status === "rejected");
			document.getElementById("canary-count").textContent = canaryFailed
				? "—"
				: routes.filter((route) => route.canaryEnabled).length;
			if (canaryFailed) {
				const failure = canaryResults.find((result) => result.status === "rejected");
				showNotice(`Services and routes loaded, but canary status could not be retrieved: ${failure.reason.message}`);
			}
			if (state.canaryRouteId) await loadCanary(state.canaryRouteId);
			await loadAudit();
		} catch (error) {
			showNotice(`Could not load gateway data: ${error.message}`);
		}
		await loadHealth();
	}

	document.getElementById("current-year").textContent = new Date().getFullYear();
	document.getElementById("refresh-button").addEventListener("click", () => loadData());
	document.getElementById("add-service-button").addEventListener("click", () => {
		document.getElementById("service-dialog").showModal();
	});
	document.getElementById("add-route-button").addEventListener("click", () => {
		if (!state.services.length) {
			showNotice("Register a service before creating a route.", "error");
			return;
		}
		document.getElementById("route-dialog").showModal();
	});
	document.querySelectorAll("[data-close]").forEach((button) => {
		button.addEventListener("click", () => document.getElementById(button.dataset.close).close());
	});
	// The sign-in dialog cannot be dismissed with Escape.
	loginDialog.addEventListener("cancel", (event) => event.preventDefault());
	document.getElementById("login-form").addEventListener("submit", async (event) => {
		event.preventDefault();
		const username = document.getElementById("login-username").value.trim();
		const password = document.getElementById("login-password").value;
		try {
			const result = await api("/auth/login", { method: "POST", body: JSON.stringify({ username, password }) });
			sessionStorage.setItem(tokenStorageKey, result.token);
			state.user = result.user;
			applyUser();
			document.getElementById("login-password").value = "";
			loginDialog.close();
			await loadData();
		} catch (error) {
			document.getElementById("login-password").value = "";
			showNotice(`Sign in failed: ${error.message}`);
		}
	});
	document.getElementById("logout-button").addEventListener("click", async () => {
		try {
			await api("/admin/logout", { method: "POST" });
		} catch {
			// The session is dropped locally either way.
		}
		signedOut();
	});
	document.getElementById("password-button").addEventListener("click", () => {
		document.getElementById("password-dialog").showModal();
	});
	document.getElementById("password-form").addEventListener("submit", async (event) => {
		event.preventDefault();
		const formElement = event.currentTarget;
		const form = new FormData(formElement);
		try {
			await api("/admin/me/password", {
				method: "POST",
				body: JSON.stringify({ currentPassword: form.get("currentPassword"), newPassword: form.get("newPassword") })
			});
			formElement.reset();
			document.getElementById("password-dialog").close();
			signedOut();
			showNotice("Password changed. Sign in again.", "success");
		} catch (error) {
			showNotice(`Could not change password: ${error.message}`);
		}
	});
	document.getElementById("add-team-button").addEventListener("click", () => document.getElementById("team-dialog").showModal());
	document.getElementById("team-form").addEventListener("submit", async (event) => {
		event.preventDefault();
		const formElement = event.currentTarget;
		const name = new FormData(formElement).get("name").trim();
		try {
			await api("/admin/teams", { method: "POST", body: JSON.stringify({ name }) });
			formElement.reset();
			document.getElementById("team-dialog").close();
			showNotice("Team added.", "success");
			await loadData();
		} catch (error) {
			showNotice(`Could not add team: ${error.message}`);
		}
	});
	document.getElementById("add-user-button").addEventListener("click", () => {
		if (!state.teams.length) {
			showNotice("Add a team first, or create a platform admin.", "error");
		}
		document.getElementById("user-dialog").showModal();
	});
	document.getElementById("user-role").addEventListener("change", (event) => {
		document.getElementById("user-team-field").hidden = event.target.value === "PLATFORM_ADMIN";
	});
	document.getElementById("user-form").addEventListener("submit", async (event) => {
		event.preventDefault();
		const formElement = event.currentTarget;
		const form = new FormData(formElement);
		const role = form.get("role");
		const request = {
			username: form.get("username").trim(),
			password: form.get("password"),
			role,
			teamName: role === "PLATFORM_ADMIN" ? null : form.get("teamName")
		};
		try {
			await api("/admin/users", { method: "POST", body: JSON.stringify(request) });
			formElement.reset();
			document.getElementById("user-team-field").hidden = false;
			document.getElementById("user-dialog").close();
			showNotice("User added.", "success");
			await loadData();
		} catch (error) {
			showNotice(`Could not add user: ${error.message}`);
		}
	});
	document.getElementById("users-body").addEventListener("click", async (event) => {
		const button = event.target.closest("[data-delete-user]");
		if (!button || !window.confirm("Delete this user? Their sessions end immediately.")) return;
		try {
			await api(`/admin/users/${encodeURIComponent(button.dataset.deleteUser)}`, { method: "DELETE" });
			showNotice("User deleted.", "success");
			await loadData();
		} catch (error) {
			showNotice(`Could not delete user: ${error.message}`);
		}
	});
	document.getElementById("service-form").addEventListener("submit", async (event) => {
		event.preventDefault();
		const formElement = event.currentTarget;
		const form = new FormData(formElement);
		const request = {
			name: form.get("name").trim(),
			baseUrl: form.get("baseUrl").trim(),
			healthCheckPath: form.get("healthCheckPath").trim(),
			ownerTeam: (form.get("ownerTeam") || "").trim(),
			status: "UNKNOWN"
		};
		try {
			await api("/admin/services", { method: "POST", body: JSON.stringify(request) });
			document.getElementById("service-dialog").close();
			formElement.reset();
			document.getElementById("service-health").value = "/health";
			showNotice("Service registered.", "success");
			await loadData();
		} catch (error) {
			showNotice(`Could not register service: ${error.message}`);
		}
	});
	document.getElementById("route-form").addEventListener("submit", async (event) => {
		event.preventDefault();
		const formElement = event.currentTarget;
		const form = new FormData(formElement);
		const rateLimit = form.get("rateLimitPerMinute");
		const request = {
			routeId: form.get("routeId").trim() || null,
			path: form.get("path").trim(),
			serviceName: form.get("serviceName"),
			stripPrefix: Number(form.get("stripPrefix") || 0),
			authRequired: form.get("authRequired") === "on",
			rateLimitPerMinute: rateLimit ? Number(rateLimit) : null,
			enabled: true
		};
		try {
			await api("/admin/routes", { method: "POST", body: JSON.stringify(request) });
			document.getElementById("route-dialog").close();
			formElement.reset();
			document.getElementById("route-strip-prefix").value = "0";
			showNotice("Route created and activated.", "success");
			await loadData();
		} catch (error) {
			showNotice(`Could not create route: ${error.message}`);
		}
	});
	document.getElementById("services-body").addEventListener("click", async (event) => {
		const button = event.target.closest("[data-delete-service]");
		if (!button) return;
		if (!window.confirm("Delete this service? Any route or canary target that references it may prevent deletion.")) return;
		try {
			await api(`/admin/services/${encodeURIComponent(button.dataset.deleteService)}`, { method: "DELETE" });
			showNotice("Service deleted.", "success");
			await loadData();
		} catch (error) {
			showNotice(`Could not delete service: ${error.message}`);
		}
	});
	document.getElementById("routes-body").addEventListener("click", async (event) => {
		const button = event.target.closest("[data-delete-route]");
		if (!button || !window.confirm("Delete this route and its routing rule?")) return;
		try {
			await api(`/admin/routes/${encodeURIComponent(button.dataset.deleteRoute)}`, { method: "DELETE" });
			showNotice("Route deleted.", "success");
			await loadData();
		} catch (error) {
			showNotice(`Could not delete route: ${error.message}`);
		}
	});
	document.getElementById("canary-route").addEventListener("change", (event) => loadCanary(event.target.value));
	document.getElementById("add-target-button").addEventListener("click", () => addTargetRow());
	document.getElementById("save-canary-button").addEventListener("click", async () => {
		const targets = [...document.querySelectorAll(".target-row")].map((row) => ({
			serviceName: row.querySelector(".target-service").value,
			weight: Number(row.querySelector(".target-weight").value)
		}));
		if (new Set(targets.map((target) => target.serviceName)).size !== targets.length) {
			showNotice("Choose a different service for each canary target.");
			return;
		}
		try {
			await api(`/admin/routes/${encodeURIComponent(state.canaryRouteId)}/canary`, {
				method: "PUT",
				body: JSON.stringify({ targets })
			});
			showNotice("Canary traffic split saved.", "success");
			await loadCanary(state.canaryRouteId);
			await loadData();
		} catch (error) {
			showNotice(`Could not save traffic split: ${error.message}`);
		}
	});
	document.getElementById("remove-canary-button").addEventListener("click", async () => {
		if (!state.canaryRouteId || !window.confirm("Remove the canary split? Traffic will use the route's default service.")) return;
		try {
			await api(`/admin/routes/${encodeURIComponent(state.canaryRouteId)}/canary`, { method: "DELETE" });
			const route = state.routes.find((item) => String(item.id) === String(state.canaryRouteId));
			if (route) route.canaryEnabled = false;
			showNotice("Canary split removed.", "success");
			await loadCanary(state.canaryRouteId);
			await loadData();
		} catch (error) {
			showNotice(`Could not remove traffic split: ${error.message}`);
		}
	});

	async function start() {
		applyUser();
		loadHealth();
		if (!sessionStorage.getItem(tokenStorageKey)) {
			loginDialog.showModal();
			return;
		}
		try {
			state.user = await api("/admin/me");
			applyUser();
			await loadData();
		} catch (error) {
			showNotice(error.message);
		}
	}

	async function refreshServiceHealth() {
		const token = sessionStorage.getItem(tokenStorageKey);
		if (state.user && !document.hidden) {
			try {
				const services = await api("/admin/services");
				if (state.user && token === sessionStorage.getItem(tokenStorageKey)) {
					const byId = new Map(services.map((service) => [service.id, service]));
					state.services.forEach((service) => {
						const fresh = byId.get(service.id);
						if (fresh) {
							service.status = fresh.status;
							service.lastCheckedAt = fresh.lastCheckedAt;
						}
					});
					renderServiceRows();
					await loadHealth();
				}
			} catch (error) {
				showNotice(`Could not refresh service health: ${error.message}`);
			}
		}
		window.setTimeout(refreshServiceHealth, 30000);
	}

	start();
	window.setTimeout(refreshServiceHealth, 30000);
})();
