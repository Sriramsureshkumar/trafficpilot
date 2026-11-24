package com.sriram.trafficpilot.controller;

import com.sriram.trafficpilot.model.AuditEntry;
import com.sriram.trafficpilot.repository.AuditEntryRepository;
import com.sriram.trafficpilot.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/admin/audit")
public class AdminAuditController {

	private final AuditEntryRepository repository;

	public AdminAuditController(AuditEntryRepository repository) {
		this.repository = repository;
	}

	@GetMapping
	public Flux<AuditEntry> list(
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(defaultValue = "0") long offset,
			ServerWebExchange exchange
	) {
		AuthPrincipal actor = AuthPrincipal.of(exchange);
		if (limit < 1 || limit > 100 || offset < 0) {
			return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"limit must be 1..100 and offset must be non-negative"));
		}
		return actor.isPlatformAdmin() ? repository.findRecent(limit, offset)
				: repository.findRecentByTeamId(actor.teamId(), limit, offset);
	}
}
