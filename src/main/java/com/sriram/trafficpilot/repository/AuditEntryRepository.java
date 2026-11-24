package com.sriram.trafficpilot.repository;

import com.sriram.trafficpilot.model.AuditEntry;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface AuditEntryRepository extends ReactiveCrudRepository<AuditEntry, Long> {

	@Query("SELECT * FROM audit_entries ORDER BY id DESC LIMIT :limit OFFSET :offset")
	Flux<AuditEntry> findRecent(int limit, long offset);

	@Query("SELECT * FROM audit_entries WHERE team_id = :teamId ORDER BY id DESC LIMIT :limit OFFSET :offset")
	Flux<AuditEntry> findRecentByTeamId(Long teamId, int limit, long offset);
}
