package com.sriram.trafficpilot.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class AdminApiKeyWebFilter implements WebFilter {

	private static final String ADMIN_KEY_HEADER = "X-Admin-Key";

	private final ApiKeyProperties apiKeyProperties;

	public AdminApiKeyWebFilter(ApiKeyProperties apiKeyProperties) {
		this.apiKeyProperties = apiKeyProperties;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String path = exchange.getRequest().getPath().value();
		if (!path.startsWith("/admin/")) {
			return chain.filter(exchange);
		}

		String providedKey = exchange.getRequest().getHeaders().getFirst(ADMIN_KEY_HEADER);
		if (apiKeyProperties.getAdminApiKey().equals(providedKey)) {
			return chain.filter(exchange);
		}

		exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
		return exchange.getResponse().setComplete();
	}
}