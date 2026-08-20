package com.sriram.trafficpilot.repository;

import com.sriram.trafficpilot.model.RegisteredService;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface RegisteredServiceRepository extends ReactiveCrudRepository<RegisteredService, Long> {

	Mono<RegisteredService> findByName(String name);

	Mono<Boolean> existsByName(String name);
}