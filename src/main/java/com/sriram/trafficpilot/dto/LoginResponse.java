package com.sriram.trafficpilot.dto;

import java.time.Instant;

public record LoginResponse(String token, Instant expiresAt, UserInfo user) {
}
