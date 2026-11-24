package com.sriram.trafficpilot.audit;

import com.sriram.trafficpilot.model.AuditEntry;
import com.sriram.trafficpilot.model.GatewayRoute;
import com.sriram.trafficpilot.repository.AuditEntryRepository;
import com.sriram.trafficpilot.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Service
public class AuditService {

	private final AuditEntryRepository repository;

	public AuditService(AuditEntryRepository repository) {
		this.repository = repository;
	}

	public Mono<Void> record(AuthPrincipal actor, Long teamId, GatewayRoute route, String action, String details) {
		return repository.save(new AuditEntry(null, actor.userId(), actor.username(), teamId,
				route.getId(), route.getRouteId(), action, details, Instant.now())).then();
	}
}
