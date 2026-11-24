package com.sriram.trafficpilot.dto;

import com.sriram.trafficpilot.security.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
		@NotBlank @Size(max = 120) @Pattern(regexp = "[A-Za-z0-9._@-]+") String username,
		@NotBlank @Size(min = 10, max = 72) String password,
		@NotNull Role role,
		String teamName
) {
}
