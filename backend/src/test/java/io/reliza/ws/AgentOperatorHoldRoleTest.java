/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.OrganizationData;
import io.reliza.model.UserData;
import io.reliza.service.AgentTaskService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.UserService;

/**
 * agentTaskOperatorHold's role (task 4c566d0d): passed through on a release, refused on a hold,
 * where it would mean nothing.
 */
class AgentOperatorHoldRoleTest {

	private AgentTaskService agentTaskService;
	private AgentTaskDataFetcher fetcher;
	private UUID task;

	@BeforeEach
	void wire() throws Exception {
		agentTaskService = mock(AgentTaskService.class);
		GetOrganizationService orgs = mock(GetOrganizationService.class);
		UserService users = mock(UserService.class);
		UserData user = mock(UserData.class);
		when(user.getUuid()).thenReturn(UUID.randomUUID());
		when(users.getUserDataByAuth(any(JwtAuthenticationToken.class))).thenReturn(Optional.of(user));
		UUID org = UUID.randomUUID();
		OrganizationData od = mock(OrganizationData.class);
		when(od.getUuid()).thenReturn(org);
		when(orgs.getOrganizationData(org)).thenReturn(Optional.of(od));
		AgentTaskData td = mock(AgentTaskData.class);
		task = UUID.randomUUID();
		when(td.getOrg()).thenReturn(org);
		UUID board = UUID.randomUUID();
		when(td.getBoard()).thenReturn(board);
		when(agentTaskService.getTaskData(task)).thenReturn(Optional.of(td));
		// The operator holds BOARD_WRITE on the task's board (task d8e7bd7e).
		io.reliza.service.AgentBoardService boards = mock(io.reliza.service.AgentBoardService.class);
		io.reliza.model.AgentBoardData bd = mock(io.reliza.model.AgentBoardData.class);
		when(bd.getOrg()).thenReturn(org);
		when(bd.getUuid()).thenReturn(board);
		when(boards.getBoardData(board)).thenReturn(Optional.of(bd));
		AuthorizationService authz = mock(AuthorizationService.class);
		when(authz.userOnBoard(any(), any(), any(), any(), any())).thenReturn(io.reliza.model.WhoUpdated.getTestWhoUpdated());
		fetcher = new AgentTaskDataFetcher();
		inject("agentTaskService", agentTaskService);
		inject("agentBoardService", boards);
		inject("authorizationService", authz);
		inject("getOrganizationService", orgs);
		inject("userService", users);
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(mock(JwtAuthenticationToken.class));
		SecurityContextHolder.setContext(ctx);
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	private void inject(String field, Object value) throws Exception {
		Field f = AgentTaskDataFetcher.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(fetcher, value);
	}

	@Test
	void aReleasePassesTheRoleOn() throws RelizaException {
		fetcher.agentTaskOperatorHold(task, false, "continue", "coder", null);
		verify(agentTaskService).liftHoldOrAnswer(eq(task), eq(HoldLevel.OPERATOR), any(), eq("continue"),
				eq("coder"), any());
	}

	@Test
	void aHoldWithARoleIsRefused() throws RelizaException {
		RelizaException e = assertThrows(RelizaException.class,
				() -> fetcher.agentTaskOperatorHold(task, true, "waiting", "coder", null));
		assertTrue(e.getMessage().contains("placing a hold takes none"), e.getMessage());
		verify(agentTaskService, never()).hold(any(), any(), anyString(), any(), any());
	}
}
