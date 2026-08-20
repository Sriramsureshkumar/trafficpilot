package com.sriram.trafficpilot.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyProperties {

	private final String adminApiKey;
	private final String gatewayApiKey;

	public ApiKeyProperties(
			@Value("${trafficpilot.security.admin-api-key}") String adminApiKey,
			@Value("${trafficpilot.security.gateway-api-key}") String gatewayApiKey
	) {
		this.adminApiKey = adminApiKey;
		this.gatewayApiKey = gatewayApiKey;
	}

	public String getAdminApiKey() {
		return adminApiKey;
	}

	public String getGatewayApiKey() {
		return gatewayApiKey;
	}
}