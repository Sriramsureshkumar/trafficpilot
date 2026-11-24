package com.sriram.trafficpilot.dto;

import com.sriram.trafficpilot.model.Team;

import java.time.Instant;

public record TeamResponse(Long id, String name, Instant createdAt) {

	public static TeamResponse from(Team team) {
		return new TeamResponse(team.getId(), team.getName(), team.getCreatedAt());
	}
}
