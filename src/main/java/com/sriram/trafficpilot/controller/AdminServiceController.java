package com.sriram.trafficpilot.controller;

import com.sriram.trafficpilot.dto.CreateServiceRequest;
import com.sriram.trafficpilot.dto.ServiceResponse;
import com.sriram.trafficpilot.model.RegisteredService;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

@RestController
@RequestMapping("/admin/services")
public class AdminServiceController {

	private final RegisteredServiceRepository serviceRepository;

	public AdminServiceController(RegisteredServiceRepository serviceRepository) {
		this.serviceRepository = serviceRepository;
	}

	@GetMapping
	public Flux<ServiceResponse> listServices() {
		return serviceRepository.findAll().map(ServiceResponse::from);
	}

	@GetMapping("/{name}")
	public Mono<ServiceResponse> getServiceByName(@PathVariable String name) {
		return serviceRepository.findByName(name).map(ServiceResponse::from);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Mono<ServiceResponse> createService(@Valid @RequestBody CreateServiceRequest request) {
		return serviceRepository.existsByName(request.getName())
				.flatMap(exists -> {
					if (exists) {
						return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Service name already exists"));
					}
					return serviceRepository.save(toService(request)).map(ServiceResponse::from);
				});
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> deleteService(@PathVariable Long id) {
		return serviceRepository.deleteById(id);
	}

	private RegisteredService toService(CreateServiceRequest request) {
		RegisteredService service = new RegisteredService();
		service.setName(request.getName());
		service.setBaseUrl(request.getBaseUrl());
		service.setHealthCheckPath(request.getHealthCheckPath());
		service.setOwnerTeam(request.getOwnerTeam());
		service.setStatus(request.getStatus());
		service.setCreatedAt(Instant.now());
		return service;
	}
}