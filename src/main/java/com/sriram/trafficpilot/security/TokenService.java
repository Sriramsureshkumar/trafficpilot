package com.sriram.trafficpilot.security;

import com.sriram.trafficpilot.model.AppUser;
import com.sriram.trafficpilot.model.AuthToken;
import com.sriram.trafficpilot.repository.AppUserRepository;
import com.sriram.trafficpilot.repository.AuthTokenRepository;
import com.sriram.trafficpilot.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/** Issues and validates opaque login tokens; only their SHA-256 hash is stored. */
@Service
public class TokenService {

	public record IssuedToken(String token, Instant expiresAt) {
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	private final AuthTokenRepository tokenRepository;
	private final AppUserRepository userRepository;
	private final TeamRepository teamRepository;
	private final Duration ttl;

	public TokenService(
			AuthTokenRepository tokenRepository,
			AppUserRepository userRepository,
			TeamRepository teamRepository,
			@Value("${trafficpilot.security.token-ttl-hours:8}") long ttlHours
	) {
		this.tokenRepository = tokenRepository;
		this.userRepository = userRepository;
		this.teamRepository = teamRepository;
		this.ttl = Duration.ofHours(ttlHours);
	}

	public Mono<IssuedToken> issue(AppUser user) {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		AuthToken token = new AuthToken();
		token.setTokenHash(hash(rawToken));
		token.setUserId(user.getId());
		token.setExpiresAt(Instant.now().plus(ttl));
		return tokenRepository.deleteByExpiresAtBefore(Instant.now())
				.then(tokenRepository.save(token))
				.map(saved -> new IssuedToken(rawToken, saved.getExpiresAt()));
	}

	public Mono<AuthPrincipal> authenticate(String rawToken) {
		return tokenRepository.findByTokenHash(hash(rawToken))
				.filter(token -> token.getExpiresAt().isAfter(Instant.now()))
				.flatMap(token -> userRepository.findById(token.getUserId()))
				.filter(user -> Boolean.TRUE.equals(user.getEnabled()))
				.flatMap(this::toPrincipal);
	}

	public Mono<Void> revoke(String rawToken) {
		return tokenRepository.deleteByTokenHash(hash(rawToken));
	}

	public Mono<Void> revokeAllFor(Long userId) {
		return tokenRepository.deleteByUserId(userId);
	}

	public Mono<AuthPrincipal> toPrincipal(AppUser user) {
		Role role = Role.valueOf(user.getRole());
		if (user.getTeamId() == null) {
			return Mono.just(new AuthPrincipal(user.getId(), user.getUsername(), role, null, null));
		}
		return teamRepository.findById(user.getTeamId())
				.map(team -> new AuthPrincipal(user.getId(), user.getUsername(), role, team.getId(), team.getName()));
	}

	private static String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
