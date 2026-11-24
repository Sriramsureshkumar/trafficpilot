package com.sriram.trafficpilot.repository;

import com.sriram.trafficpilot.model.RegisteredService;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface RegisteredServiceRepository extends ReactiveCrudRepository<RegisteredService, Long> {

	Mono<RegisteredService> findByName(String name);

	Mono<Boolean> existsByName(String name);

	Flux<RegisteredService> findByTeamId(Long teamId);

	@Modifying
	@Query("UPDATE registered_services SET status = :status, last_checked_at = :checkedAt WHERE id = :id")
	Mono<Integer> updateHealth(Long id, String status, Instant checkedAt);
}