package com.sriram.trafficpilot.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Blocks a username after repeated failed logins. Fails open if Redis is unavailable. */
@Component
public class LoginThrottle {

	private final ReactiveStringRedisTemplate redisTemplate;
	private final int maxFailures;
	private final Duration lockout;

	public LoginThrottle(
			ReactiveStringRedisTemplate redisTemplate,
			@Value("${trafficpilot.security.login-max-failures:5}") int maxFailures,
			@Value("${trafficpilot.security.login-lockout-minutes:15}") long lockoutMinutes
	) {
		this.redisTemplate = redisTemplate;
		this.maxFailures = maxFailures;
		this.lockout = Duration.ofMinutes(lockoutMinutes);
	}

	public Mono<Boolean> isBlocked(String username) {
		return redisTemplate.opsForValue().get(key(username))
				.map(count -> Long.parseLong(count) >= maxFailures)
				.defaultIfEmpty(false)
				.onErrorReturn(false);
	}

	public Mono<Void> recordFailure(String username) {
		String key = key(username);
		return redisTemplate.opsForValue().increment(key)
				.flatMap(count -> count == 1L ? redisTemplate.expire(key, lockout) : Mono.just(true))
				.onErrorReturn(false)
				.then();
	}

	public Mono<Void> clear(String username) {
		return redisTemplate.delete(key(username)).onErrorReturn(0L).then();
	}

	private String key(String username) {
		return "login-fail:" + username;
	}
}
