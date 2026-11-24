package com.sriram.trafficpilot.controller;

import com.sriram.trafficpilot.dto.CreateUserRequest;
import com.sriram.trafficpilot.dto.UserResponse;
import com.sriram.trafficpilot.model.AppUser;
import com.sriram.trafficpilot.model.Team;
import com.sriram.trafficpilot.repository.AppUserRepository;
import com.sriram.trafficpilot.repository.TeamRepository;
import com.sriram.trafficpilot.security.AuthPrincipal;
import com.sriram.trafficpilot.security.Role;
import com.sriram.trafficpilot.security.TokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/** Platform admin only (enforced by AdminAuthWebFilter). */
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

	private final AppUserRepository userRepository;
	private final TeamRepository teamRepository;
	private final TokenService tokenService;
	private final PasswordEncoder passwordEncoder;

	public AdminUserController(
			AppUserRepository userRepository,
			TeamRepository teamRepository,
			TokenService tokenService,
			PasswordEncoder passwordEncoder
	) {
		this.userRepository = userRepository;
		this.teamRepository = teamRepository;
		this.tokenService = tokenService;
		this.passwordEncoder = passwordEncoder;
	}

	@GetMapping
	public Flux<UserResponse> listUsers() {
		return userRepository.findAll().concatMap(this::toResponse);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Mono<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
		String username = request.username().trim().toLowerCase(Locale.ROOT);
		boolean needsTeam = request.role() != Role.PLATFORM_ADMIN;
		boolean hasTeam = request.teamName() != null && !request.teamName().isBlank();
		if (needsTeam != hasTeam) {
			return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, needsTeam
					? "teamName is required for " + request.role()
					: "PLATFORM_ADMIN must not belong to a team"));
		}

		return userRepository.existsByUsername(username).flatMap(exists -> {
			if (exists) {
				return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists"));
			}
			return resolveTeam(hasTeam ? request.teamName().trim() : null)
					.flatMap(team -> Mono.fromCallable(() -> passwordEncoder.encode(request.password()))
							.subscribeOn(Schedulers.boundedElastic())
							.flatMap(hash -> {
								AppUser user = new AppUser();
								user.setUsername(username);
								user.setPasswordHash(hash);
								user.setRole(request.role().name());
								user.setTeamId(team.map(Team::getId).orElse(null));
								user.setEnabled(true);
								user.setCreatedAt(Instant.now());
								return userRepository.save(user);
							})
							.map(saved -> UserResponse.from(saved, team.map(Team::getName).orElse(null))));
		});
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> deleteUser(@PathVariable Long id, ServerWebExchange exchange) {
		if (AuthPrincipal.of(exchange).userId().equals(id)) {
			return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot delete your own account"));
		}
		return userRepository.findById(id)
				.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
				.flatMap(user -> tokenService.revokeAllFor(id).then(userRepository.delete(user)));
	}

	private Mono<Optional<Team>> resolveTeam(String teamName) {
		if (teamName == null) {
			return Mono.just(Optional.empty());
		}
		return teamRepository.findByName(teamName)
				.map(Optional::of)
				.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown team: " + teamName)));
	}

	private Mono<UserResponse> toResponse(AppUser user) {
		if (user.getTeamId() == null) {
			return Mono.just(UserResponse.from(user, null));
		}
		return teamRepository.findById(user.getTeamId())
				.map(team -> UserResponse.from(user, team.getName()))
				.defaultIfEmpty(UserResponse.from(user, null));
	}
}
