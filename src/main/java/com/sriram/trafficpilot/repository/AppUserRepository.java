package com.sriram.trafficpilot.repository;

import com.sriram.trafficpilot.model.AppUser;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface AppUserRepository extends ReactiveCrudRepository<AppUser, Long> {

	Mono<AppUser> findByUsername(String username);

	Mono<Boolean> existsByUsername(String username);
}
