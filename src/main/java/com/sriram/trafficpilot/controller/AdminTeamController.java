package com.sriram.trafficpilot.controller;

import com.sriram.trafficpilot.dto.CreateTeamRequest;
import com.sriram.trafficpilot.dto.TeamResponse;
import com.sriram.trafficpilot.model.Team;
import com.sriram.trafficpilot.repository.TeamRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/** Platform admin only (enforced by AdminAuthWebFilter). */
@RestController
@RequestMapping("/admin/teams")
public class AdminTeamController {

	private final TeamRepository teamRepository;

	public AdminTeamController(TeamRepository teamRepository) {
		this.teamRepository = teamRepository;
	}

	@GetMapping
	public Flux<TeamResponse> listTeams() {
		return teamRepository.findAll().map(TeamResponse::from);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Mono<TeamResponse> createTeam(@Valid @RequestBody CreateTeamRequest request) {
		String name = request.name().trim();
		return teamRepository.existsByName(name).flatMap(exists -> {
			if (exists) {
				return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Team already exists"));
			}
			Team team = new Team();
			team.setName(name);
			team.setCreatedAt(Instant.now());
			return teamRepository.save(team).map(TeamResponse::from);
		});
	}
}
