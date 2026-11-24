package com.sriram.trafficpilot.repository;

import com.sriram.trafficpilot.model.AuthToken;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface AuthTokenRepository extends ReactiveCrudRepository<AuthToken, Long> {

	Mono<AuthToken> findByTokenHash(String tokenHash);

	Mono<Void> deleteByTokenHash(String tokenHash);

	Mono<Void> deleteByUserId(Long userId);

	Mono<Void> deleteByExpiresAtBefore(Instant cutoff);
}
