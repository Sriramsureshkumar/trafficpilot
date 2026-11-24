package com.sriram.trafficpilot.dto;

import com.sriram.trafficpilot.model.AppUser;

import java.time.Instant;

public record UserResponse(Long id, String username, String role, Long teamId, String teamName, Boolean enabled,
						   Instant createdAt) {

	public static UserResponse from(AppUser user, String teamName) {
		return new UserResponse(user.getId(), user.getUsername(), user.getRole(), user.getTeamId(), teamName,
				user.getEnabled(), user.getCreatedAt());
	}
}
