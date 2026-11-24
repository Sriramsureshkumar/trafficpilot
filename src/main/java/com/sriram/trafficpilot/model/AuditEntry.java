package com.sriram.trafficpilot.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("audit_entries")
public record AuditEntry(
		@Id Long id,
		Long actorId,
		String actorUsername,
		Long teamId,
		Long routePk,
		String routeId,
		String action,
		String details,
		Instant occurredAt
) {
}
