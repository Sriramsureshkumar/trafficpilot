package com.sriram.trafficpilot.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/** The authenticated caller of an /admin/** request. */
public record AuthPrincipal(Long userId, String username, Role role, Long teamId, String teamName) {

	static final String ATTRIBUTE = AuthPrincipal.class.getName();

	public static AuthPrincipal of(ServerWebExchange exchange) {
		AuthPrincipal principal = exchange.getAttribute(ATTRIBUTE);
		if (principal == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return principal;
	}

	public boolean isPlatformAdmin() {
		return role == Role.PLATFORM_ADMIN;
	}

	public boolean canWrite() {
		return role != Role.VIEWER;
	}

	/** Platform admins reach every team; everyone else only their own. */
	public boolean canAccessTeam(Long resourceTeamId) {
		return isPlatformAdmin() || (teamId != null && teamId.equals(resourceTeamId));
	}
}
