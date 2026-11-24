package com.sriram.trafficpilot.repository;

import com.sriram.trafficpilot.model.Team;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface TeamRepository extends ReactiveCrudRepository<Team, Long> {

	Mono<Team> findByName(String name);

	Mono<Boolean> existsByName(String name);
}
