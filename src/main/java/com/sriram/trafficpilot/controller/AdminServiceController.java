package com.sriram.trafficpilot.controller;

import com.sriram.trafficpilot.dto.CreateServiceRequest;
import com.sriram.trafficpilot.dto.ServiceResponse;
import com.sriram.trafficpilot.model.RegisteredService;
import com.sriram.trafficpilot.model.Team;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;
import com.sriram.trafficpilot.repository.TeamRepository;
import com.sriram.trafficpilot.security.AuthPrincipal;
import com.sriram.trafficpilot.security.ResourceAccess;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

import java.time.Instant;

@RestController
@RequestMapping("/admin/services")
public class AdminServiceController {

	private final RegisteredServiceRepository serviceRepository;
	private final TeamRepository teamRepository;
	private final ResourceAccess access;

	public AdminServiceController(
			RegisteredServiceRepository serviceRepository,
			TeamRepository teamRepository,
			ResourceAccess access
	) {
		this.serviceRepository = serviceRepository;
		this.teamRepository = teamRepository;
		this.access = access;
	}

	@GetMapping
	public Flux<ServiceResponse> listServices(ServerWebExchange exchange) {
		AuthPrincipal principal = AuthPrincipal.of(exchange);
		Flux<RegisteredService> services = principal.isPlatformAdmin()
				? serviceRepository.findAll()
				: serviceRepository.findByTeamId(principal.teamId());
		return services.map(ServiceResponse::from);
	}

	@GetMapping("/{name}")
	public Mono<ServiceResponse> getServiceByName(@PathVariable String name, ServerWebExchange exchange) {
		return access.serviceByName(name, AuthPrincipal.of(exchange)).map(ServiceResponse::from);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Mono<ServiceResponse> createService(
			@Valid @RequestBody CreateServiceRequest request,
			ServerWebExchange exchange
	) {
		AuthPrincipal principal = AuthPrincipal.of(exchange);
		return resolveOwningTeam(request, principal).flatMap(team ->
				serviceRepository.existsByName(request.getName())
						.flatMap(exists -> {
							if (exists) {
								return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Service name already exists"));
							}
							return serviceRepository.save(toService(request, team)).map(ServiceResponse::from);
						}));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> deleteService(@PathVariable Long id, ServerWebExchange exchange) {
		return access.service(id, AuthPrincipal.of(exchange)).flatMap(serviceRepository::delete);
	}

	// Team admins always create services for their own team; platform admins name the team.
	private Mono<Team> resolveOwningTeam(CreateServiceRequest request, AuthPrincipal principal) {
		if (!principal.isPlatformAdmin()) {
			return teamRepository.findById(principal.teamId());
		}
		if (request.getOwnerTeam() == null || request.getOwnerTeam().isBlank()) {
			return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "ownerTeam is required"));
		}
		return teamRepository.findByName(request.getOwnerTeam().trim())
				.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Unknown team: " + request.getOwnerTeam())));
	}

	private RegisteredService toService(CreateServiceRequest request, Team team) {
		RegisteredService service = new RegisteredService();
		service.setName(request.getName());
		service.setBaseUrl(request.getBaseUrl());
		service.setHealthCheckPath(request.getHealthCheckPath());
		service.setOwnerTeam(team.getName());
		service.setTeamId(team.getId());
		service.setStatus(request.getStatus());
		service.setCreatedAt(Instant.now());
		return service;
	}
}
