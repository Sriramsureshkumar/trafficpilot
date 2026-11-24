package com.sriram.trafficpilot.security;

import com.sriram.trafficpilot.model.AppUser;
import com.sriram.trafficpilot.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * On startup: turns the legacy free-text owner_team labels into real teams, and creates
 * the first platform admin if none exists.
 */
@Component
public class SecurityBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SecurityBootstrap.class);

	private final DatabaseClient databaseClient;
	private final AppUserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final String adminUsername;
	private final String adminPassword;

	public SecurityBootstrap(
			DatabaseClient databaseClient,
			AppUserRepository userRepository,
			PasswordEncoder passwordEncoder,
			@Value("${trafficpilot.security.bootstrap-admin-username:admin}") String adminUsername,
			@Value("${trafficpilot.security.bootstrap-admin-password:}") String adminPassword
	) {
		this.databaseClient = databaseClient;
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.adminUsername = adminUsername;
		this.adminPassword = adminPassword;
	}

	@Override
	public void run(ApplicationArguments args) {
		backfillTeams().block(Duration.ofSeconds(30));
		createFirstAdmin().block(Duration.ofSeconds(30));
	}

	private reactor.core.publisher.Mono<Void> backfillTeams() {
		return databaseClient.sql("""
						INSERT INTO "teams" (name, created_at)
						SELECT DISTINCT owner_team, CURRENT_TIMESTAMP FROM "registered_services" WHERE team_id IS NULL
						ON CONFLICT (name) DO NOTHING
						""").fetch().rowsUpdated()
				.then(databaseClient.sql("""
						UPDATE "registered_services" s SET team_id = t.id
						FROM "teams" t WHERE s.team_id IS NULL AND t.name = s.owner_team
						""").fetch().rowsUpdated())
				.then();
	}

	private reactor.core.publisher.Mono<Void> createFirstAdmin() {
		String username = adminUsername.trim().toLowerCase();
		return userRepository.existsByUsername(username)
				.flatMap(exists -> {
					if (exists) {
						return reactor.core.publisher.Mono.<Void>empty();
					}
					if (adminPassword.length() < 10) {
						return reactor.core.publisher.Mono.error(new IllegalStateException(
								"Bootstrap admin '" + username + "' does not exist. Set TRAFFICPILOT_ADMIN_PASSWORD (at least 10 characters) to create it."));
					}
					AppUser admin = new AppUser();
					admin.setUsername(username);
					admin.setPasswordHash(passwordEncoder.encode(adminPassword));
					admin.setRole(Role.PLATFORM_ADMIN.name());
					admin.setEnabled(true);
					admin.setCreatedAt(Instant.now());
					return userRepository.save(admin)
							.doOnSuccess(saved -> log.info("Created platform admin '{}'", saved.getUsername()))
							.then();
				});
	}
}
