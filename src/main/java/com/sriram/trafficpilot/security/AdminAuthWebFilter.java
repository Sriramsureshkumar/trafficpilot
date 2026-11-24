package com.sriram.trafficpilot.security;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.Set;

/** Authenticates /admin/** with a bearer token and enforces role rules. */
@Component
public class AdminAuthWebFilter implements WebFilter {

	private static final String BEARER = "Bearer ";
	private static final Set<String> SELF_SERVICE_PATHS = Set.of("/admin/logout", "/admin/me/password");

	private final TokenService tokenService;

	public AdminAuthWebFilter(TokenService tokenService) {
		this.tokenService = tokenService;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String path = exchange.getRequest().getPath().value();
		if (!path.startsWith("/admin/")) {
			return chain.filter(exchange);
		}

		String token = bearerToken(exchange);
		if (token == null) {
			return reject(exchange, HttpStatus.UNAUTHORIZED);
		}

		// Optional avoids switchIfEmpty re-running the chain, which completes empty.
		return tokenService.authenticate(token)
				.map(Optional::of)
				.defaultIfEmpty(Optional.empty())
				.flatMap(principal -> {
					if (principal.isEmpty()) {
						return reject(exchange, HttpStatus.UNAUTHORIZED);
					}
					if (!isAllowed(path, exchange.getRequest().getMethod(), principal.get())) {
						return reject(exchange, HttpStatus.FORBIDDEN);
					}
					exchange.getAttributes().put(AuthPrincipal.ATTRIBUTE, principal.get());
					return chain.filter(exchange);
				});
	}

	private boolean isAllowed(String path, HttpMethod method, AuthPrincipal principal) {
		if (isUnder(path, "/admin/teams") || isUnder(path, "/admin/users")) {
			return principal.isPlatformAdmin();
		}
		if (SELF_SERVICE_PATHS.contains(path)) {
			return true;
		}
		boolean readOnly = HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
		return readOnly || principal.canWrite();
	}

	private boolean isUnder(String path, String prefix) {
		return path.equals(prefix) || path.startsWith(prefix + "/");
	}

	private String bearerToken(ServerWebExchange exchange) {
		String header = exchange.getRequest().getHeaders().getFirst("Authorization");
		if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
			return null;
		}
		String token = header.substring(BEARER.length()).trim();
		return token.isEmpty() ? null : token;
	}

	private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
		exchange.getResponse().setStatusCode(status);
		return exchange.getResponse().setComplete();
	}
}
