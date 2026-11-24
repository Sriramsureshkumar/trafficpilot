package com.sriram.trafficpilot.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyProperties {

	private final String gatewayApiKey;

	public ApiKeyProperties(@Value("${trafficpilot.security.gateway-api-key}") String gatewayApiKey) {
		this.gatewayApiKey = gatewayApiKey;
	}

	public String getGatewayApiKey() {
		return gatewayApiKey;
	}
}
