package com.sriram.trafficpilot.security;

import com.sriram.trafficpilot.model.AppUser;
import com.sriram.trafficpilot.model.Team;
import com.sriram.trafficpilot.repository.AppUserRepository;
import com.sriram.trafficpilot.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(properties = {
		"trafficpilot.health.enabled=false",
		"trafficpilot.security.bootstrap-admin-username=test-bootstrap-admin",
		"trafficpilot.security.bootstrap-admin-password=test-only-password-123"
})
class TokenServiceTests {

	@Autowired
	private TokenService tokenService;
	@Autowired
	private TeamRepository teamRepository;
	@Autowired
	private AppUserRepository userRepository;
	@Autowired
	private ReactiveTransactionManager transactionManager;

	@Test
	void tokenIdentifiesUserAndTeamUntilRevoked() {
		AuthPrincipal[] results = inRolledBackTransaction(createTeamAndUser(Role.TEAM_ADMIN, true).flatMap(user ->
				tokenService.issue(user).flatMap(issued ->
						tokenService.authenticate(issued.token()).flatMap(before ->
								tokenService.revoke(issued.token())
										.then(tokenService.authenticate(issued.token()).map(p -> new AuthPrincipal[]{before, p})
												.defaultIfEmpty(new AuthPrincipal[]{before, null}))))));

		AuthPrincipal before = results[0];
		assertNotNull(before.userId());
		assertEquals(Role.TEAM_ADMIN, before.role());
		assertNotNull(before.teamId());
		assertNull(results[1]);
	}

	@Test
	void disabledUserTokenIsRejected() {
		Boolean accepted = inRolledBackTransaction(createTeamAndUser(Role.VIEWER, false).flatMap(user ->
				tokenService.issue(user).flatMap(issued ->
						tokenService.authenticate(issued.token()).map(p -> true).defaultIfEmpty(false))));

		assertEquals(false, accepted);
	}

	@Test
	void platformAdminHasNoTeam() {
		AuthPrincipal principal = inRolledBackTransaction(
				createUser(Role.PLATFORM_ADMIN, null, true).flatMap(tokenService::toPrincipal));

		assertEquals(Role.PLATFORM_ADMIN, principal.role());
		assertNull(principal.teamId());
	}

	private Mono<AppUser> createTeamAndUser(Role role, boolean enabled) {
		Team team = new Team();
		team.setName("team-" + UUID.randomUUID());
		team.setCreatedAt(Instant.now());
		return teamRepository.save(team).flatMap(saved -> createUser(role, saved.getId(), enabled));
	}

	private Mono<AppUser> createUser(Role role, Long teamId, boolean enabled) {
		AppUser user = new AppUser();
		user.setUsername("user-" + UUID.randomUUID());
		user.setPasswordHash("not-a-real-hash");
		user.setRole(role.name());
		user.setTeamId(teamId);
		user.setEnabled(enabled);
		user.setCreatedAt(Instant.now());
		return userRepository.save(user);
	}

	private <T> T inRolledBackTransaction(Mono<T> work) {
		return TransactionalOperator.create(transactionManager).execute(transaction -> {
			transaction.setRollbackOnly();
			return work;
		}).blockLast(Duration.ofSeconds(30));
	}
}
