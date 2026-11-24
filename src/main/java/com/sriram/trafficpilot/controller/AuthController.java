package com.sriram.trafficpilot.controller;

import com.sriram.trafficpilot.dto.ChangePasswordRequest;
import com.sriram.trafficpilot.dto.LoginRequest;
import com.sriram.trafficpilot.dto.LoginResponse;
import com.sriram.trafficpilot.dto.UserInfo;
import com.sriram.trafficpilot.model.AppUser;
import com.sriram.trafficpilot.repository.AppUserRepository;
import com.sriram.trafficpilot.security.AuthPrincipal;
import com.sriram.trafficpilot.security.LoginThrottle;
import com.sriram.trafficpilot.security.TokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Locale;
import java.util.Optional;

@RestController
public class AuthController {

	private final AppUserRepository userRepository;
	private final TokenService tokenService;
	private final PasswordEncoder passwordEncoder;
	private final LoginThrottle loginThrottle;
	private final String dummyHash;

	public AuthController(
			AppUserRepository userRepository,
			TokenService tokenService,
			PasswordEncoder passwordEncoder,
			LoginThrottle loginThrottle
	) {
		this.userRepository = userRepository;
		this.tokenService = tokenService;
		this.passwordEncoder = passwordEncoder;
		this.loginThrottle = loginThrottle;
		// Checked when the username is unknown so response time does not reveal valid usernames.
		this.dummyHash = passwordEncoder.encode("trafficpilot-dummy-password");
	}

	@PostMapping("/auth/login")
	public Mono<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		String username = request.username().trim().toLowerCase(Locale.ROOT);
		return loginThrottle.isBlocked(username).flatMap(blocked -> {
			if (blocked) {
				return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
						"Too many failed logins. Try again later."));
			}
			return userRepository.findByUsername(username)
					.map(Optional::of)
					.defaultIfEmpty(Optional.empty())
					.flatMap(user -> verify(user, request.password())
							.flatMap(valid -> valid
									? loginThrottle.clear(username).then(issueToken(user.get()))
									: loginThrottle.recordFailure(username).then(Mono.error(invalidCredentials()))));
		});
	}

	@GetMapping("/admin/me")
	public UserInfo me(ServerWebExchange exchange) {
		return UserInfo.from(AuthPrincipal.of(exchange));
	}

	@PostMapping("/admin/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> logout(ServerWebExchange exchange) {
		String header = exchange.getRequest().getHeaders().getFirst("Authorization");
		return tokenService.revoke(header.substring("Bearer ".length()).trim());
	}

	@PostMapping("/admin/me/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> changePassword(ServerWebExchange exchange, @Valid @RequestBody ChangePasswordRequest request) {
		AuthPrincipal principal = AuthPrincipal.of(exchange);
		return userRepository.findById(principal.userId())
				.flatMap(user -> verify(Optional.of(user), request.currentPassword())
						.flatMap(valid -> {
							if (!valid) {
								return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
										"Current password is incorrect"));
							}
							return encode(request.newPassword()).flatMap(hash -> {
								user.setPasswordHash(hash);
								return userRepository.save(user);
							});
						}))
				// Ends every session of this user, including the current one.
				.then(tokenService.revokeAllFor(principal.userId()));
	}

	private Mono<Boolean> verify(Optional<AppUser> user, String password) {
		String hash = user.map(AppUser::getPasswordHash).orElse(dummyHash);
		boolean usable = user.map(found -> Boolean.TRUE.equals(found.getEnabled())).orElse(false);
		return Mono.fromCallable(() -> passwordEncoder.matches(password, hash) && usable)
				.subscribeOn(Schedulers.boundedElastic());
	}

	private Mono<String> encode(String password) {
		return Mono.fromCallable(() -> passwordEncoder.encode(password)).subscribeOn(Schedulers.boundedElastic());
	}

	private Mono<LoginResponse> issueToken(AppUser user) {
		return tokenService.toPrincipal(user)
				.flatMap(principal -> tokenService.issue(user)
						.map(issued -> new LoginResponse(issued.token(), issued.expiresAt(), UserInfo.from(principal))));
	}

	private ResponseStatusException invalidCredentials() {
		return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
	}
}
