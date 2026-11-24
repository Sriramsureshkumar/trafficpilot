package com.sriram.trafficpilot.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthPrincipalTest {

	private final AuthPrincipal platformAdmin = new AuthPrincipal(1L, "root", Role.PLATFORM_ADMIN, null, null);
	private final AuthPrincipal teamAdmin = new AuthPrincipal(2L, "alice", Role.TEAM_ADMIN, 10L, "payments");
	private final AuthPrincipal viewer = new AuthPrincipal(3L, "bob", Role.VIEWER, 10L, "payments");

	@Test
	void platformAdminReachesEveryTeam() {
		assertTrue(platformAdmin.canAccessTeam(10L));
		assertTrue(platformAdmin.canAccessTeam(99L));
		assertTrue(platformAdmin.canAccessTeam(null));
	}

	@Test
	void teamMembersReachOnlyTheirOwnTeam() {
		assertTrue(teamAdmin.canAccessTeam(10L));
		assertFalse(teamAdmin.canAccessTeam(11L));
		assertFalse(teamAdmin.canAccessTeam(null));
		assertTrue(viewer.canAccessTeam(10L));
		assertFalse(viewer.canAccessTeam(11L));
	}

	@Test
	void viewersCannotWrite() {
		assertTrue(platformAdmin.canWrite());
		assertTrue(teamAdmin.canWrite());
		assertFalse(viewer.canWrite());
	}
}
