package com.sriram.trafficpilot.dto;

import com.sriram.trafficpilot.security.AuthPrincipal;

public record UserInfo(Long id, String username, String role, Long teamId, String teamName) {

	public static UserInfo from(AuthPrincipal principal) {
		return new UserInfo(principal.userId(), principal.username(), principal.role().name(),
				principal.teamId(), principal.teamName());
	}
}
