/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.model.OrganizationData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.service.AgentTaskService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.UserService;

/**
 * {@code agentTask(uuid)}, the task page's read (9a118a2a). The page loads from a deep link with
 * nothing but the uuid, so the gate has to come from the task itself: its org, checked as
 * {@code agentBoard} checks the board's.
 */
class AgentTaskQueryTest {

	private AuthorizationService authorizationService;
	private AgentTaskService agentTaskService;
	private io.reliza.service.AgentBoardService agentBoardService;
	private GetOrganizationService getOrganizationService;
	private AgentTaskDataFetcher fetcher;

	@BeforeEach
	void wire() throws Exception {
		authorizationService = mock(AuthorizationService.class);
		agentTaskService = mock(AgentTaskService.class);
		getOrganizationService = mock(GetOrganizationService.class);
		UserService userService = mock(UserService.class);
		UserData user = mock(UserData.class);
		when(user.getUuid()).thenReturn(UUID.randomUUID());
		when(userService.getUserDataByAuth(any(JwtAuthenticationToken.class))).thenReturn(Optional.of(user));
		fetcher = new AgentTaskDataFetcher();
		agentBoardService = mock(io.reliza.service.AgentBoardService.class);
		inject("authorizationService", authorizationService);
		inject("agentTaskService", agentTaskService);
		inject("agentBoardService", agentBoardService);
		inject("getOrganizationService", getOrganizationService);
		inject("userService", userService);
		SecurityContext ctx = mock(SecurityContext.class);
		when(ctx.getAuthentication()).thenReturn(mock(JwtAuthenticationToken.class));
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

	private AgentTaskData taskIn(UUID org) {
		AgentTaskData td = mock(AgentTaskData.class);
		when(td.getUuid()).thenReturn(UUID.randomUUID());
		when(td.getOrg()).thenReturn(org);
		UUID board = UUID.randomUUID();
		when(td.getBoard()).thenReturn(board);
		io.reliza.model.AgentBoardData bd = mock(io.reliza.model.AgentBoardData.class);
		when(bd.getOrg()).thenReturn(org);
		when(bd.getUuid()).thenReturn(board);
		when(agentBoardService.getBoardData(board)).thenReturn(Optional.of(bd));
		when(agentTaskService.getTaskData(td.getUuid())).thenReturn(Optional.of(td));
		OrganizationData od = mock(OrganizationData.class);
		when(od.getUuid()).thenReturn(org);
		when(getOrganizationService.getOrganizationData(org)).thenReturn(Optional.of(od));
		return td;
	}

	@Test
	void aReaderOfTheTasksBoardReadsIt() throws Exception {
		UUID org = UUID.randomUUID();
		AgentTaskData td = taskIn(org);
		UUID board = td.getBoard();
		when(authorizationService.userOnBoard(any(), eq(org), eq(board), any(), any()))
				.thenReturn(io.reliza.model.WhoUpdated.getTestWhoUpdated());
		assertSame(td, fetcher.agentTask(td.getUuid()));
		// BOARD_READ on the TASK's board (task d8e7bd7e), not an org tier.
		verify(authorizationService).userOnBoard(any(), eq(org), eq(board),
				eq(List.of(PermissionFunction.BOARD_READ)), eq(CallType.READ));
		verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(any(), any(), any(), any(), any(), any());
	}

	@Test
	void aTaskOnABoardThePersonCannotReadIsRefused() throws Exception {
		UUID org = UUID.randomUUID();
		AgentTaskData td = taskIn(org);
		// The resolver finds no BOARD_READ: null, and the fetcher refuses.
		when(authorizationService.userOnBoard(any(), any(), any(), any(), any())).thenReturn(null);
		assertThrows(AccessDeniedException.class, () -> fetcher.agentTask(td.getUuid()));
	}

	@Test
	void anUnknownTaskIsNotFoundAndChecksNothing() throws Exception {
		UUID missing = UUID.randomUUID();
		when(agentTaskService.getTaskData(missing)).thenReturn(Optional.empty());
		RelizaException e = assertThrows(RelizaException.class, () -> fetcher.agentTask(missing));
		assertTrue(e.getMessage().contains("Task not found"), e.getMessage());
		verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(any(), any(), any(), any(), any(), any());
		verify(authorizationService, never()).userOnBoard(any(), any(), any(), any(), any());
	}
}
