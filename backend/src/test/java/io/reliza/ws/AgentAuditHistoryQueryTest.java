/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.OrganizationData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.service.AgentAuditReadService;
import io.reliza.service.AgentAuditReadService.AgentBoardRevision;
import io.reliza.service.AgentAuditReadService.AgentRoleConfigRevision;
import io.reliza.service.AgentAuditReadService.AgentTaskRevision;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentTaskService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.UserService;

/**
 * The history reads (22ddc644) are org ADMIN, as the board's settings are (D11): a revision
 * carries the prompts, notes and holds the entity had then. The gate is the live row's org, and
 * a refused or unknown read never reaches the audit table.
 */
class AgentAuditHistoryQueryTest {

	private AuthorizationService authorizationService;
	private AgentTaskService agentTaskService;
	private AgentBoardService agentBoardService;
	private AgentAuditReadService agentAuditReadService;
	private GetOrganizationService getOrganizationService;
	private AgentTaskDataFetcher fetcher;

	@BeforeEach
	void wire() throws Exception {
		authorizationService = mock(AuthorizationService.class);
		agentTaskService = mock(AgentTaskService.class);
		agentBoardService = mock(AgentBoardService.class);
		agentAuditReadService = mock(AgentAuditReadService.class);
		getOrganizationService = mock(GetOrganizationService.class);
		UserService userService = mock(UserService.class);
		UserData user = mock(UserData.class);
		when(user.getUuid()).thenReturn(UUID.randomUUID());
		when(userService.getUserDataByAuth(any(JwtAuthenticationToken.class))).thenReturn(Optional.of(user));
		fetcher = new AgentTaskDataFetcher();
		inject("authorizationService", authorizationService);
		inject("agentTaskService", agentTaskService);
		inject("agentBoardService", agentBoardService);
		inject("agentAuditReadService", agentAuditReadService);
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

	private void org(UUID org) {
		OrganizationData od = mock(OrganizationData.class);
		when(od.getUuid()).thenReturn(org);
		when(getOrganizationService.getOrganizationData(org)).thenReturn(Optional.of(od));
	}

	/** The histories read with the board (task d8e7bd7e): BOARD_READ on it, at READ. */
	private void readable() throws RelizaException {
		when(authorizationService.userOnBoard(any(), any(), any(), any(), any()))
				.thenReturn(io.reliza.model.WhoUpdated.getTestWhoUpdated());
	}

	private UUID task(UUID org, UUID board) {
		AgentTaskData td = mock(AgentTaskData.class);
		UUID uuid = UUID.randomUUID();
		when(td.getOrg()).thenReturn(org);
		when(td.getBoard()).thenReturn(board);
		when(agentTaskService.getTaskData(uuid)).thenReturn(Optional.of(td));
		org(org);
		return uuid;
	}

	private UUID board(UUID org) {
		AgentBoardData bd = mock(AgentBoardData.class);
		UUID uuid = UUID.randomUUID();
		when(bd.getOrg()).thenReturn(org);
		when(bd.getUuid()).thenReturn(uuid);
		when(agentBoardService.getBoardData(uuid)).thenReturn(Optional.of(bd));
		org(org);
		return uuid;
	}

	/** A board's role; with no board, one of the organization's presets. */
	private UUID roleConfig(UUID org, UUID board) {
		AgentTaskRoleConfigData rcd = mock(AgentTaskRoleConfigData.class);
		UUID uuid = UUID.randomUUID();
		when(rcd.getOrg()).thenReturn(org);
		when(rcd.getBoard()).thenReturn(board);
		when(agentBoardService.getRoleConfigData(uuid)).thenReturn(Optional.of(rcd));
		org(org);
		return uuid;
	}

	@Test
	void aReaderOfTheBoardReadsEachHistory() throws Exception {
		UUID org = UUID.randomUUID();
		UUID b = board(org);
		readable();
		UUID t = task(org, b);
		List<AgentTaskRevision> tasks = List.of();
		when(agentAuditReadService.taskHistory(t, 5, 10)).thenReturn(tasks);
		assertSame(tasks, fetcher.agentTaskHistory(t, 5, 10));

		List<AgentBoardRevision> boards = List.of();
		when(agentAuditReadService.boardHistory(b, null, null)).thenReturn(boards);
		assertSame(boards, fetcher.agentBoardHistory(b, null, null));

		UUID rc = roleConfig(org, b);
		List<AgentRoleConfigRevision> roles = List.of();
		when(agentAuditReadService.roleConfigHistory(rc, 1, 0)).thenReturn(roles);
		assertSame(roles, fetcher.agentRoleConfigHistory(rc, 1, 0));

		// Each read checked BOARD_READ on the entity's board, and no org tier.
		verify(authorizationService, times(3)).userOnBoard(any(), eq(org), eq(b),
				eq(List.of(PermissionFunction.BOARD_READ)), eq(CallType.READ));
		verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(any(), any(), any(), any(), any(), any());
	}

	@Test
	void aPresetsHistoryStaysTheOrgAdmins() throws Exception {
		UUID org = UUID.randomUUID();
		fetcher.agentRoleConfigHistory(roleConfig(org, null), null, null);
		verify(authorizationService).isUserAuthorizedForObjectGraphQL(any(), eq(PermissionFunction.RESOURCE),
				eq(PermissionScope.ORGANIZATION), eq(org),
				argThat(c -> c.size() == 1 && org.equals(c.iterator().next().getUuid())), eq(CallType.ADMIN));
		verify(authorizationService, never()).userOnBoard(any(), any(), any(), any(), any());
	}

	@Test
	void withoutBoardReadIsRefusedAndReadsNothing() throws Exception {
		UUID org = UUID.randomUUID();
		UUID b = board(org);
		// The resolver finds no BOARD_READ: null, and each read refuses.
		when(authorizationService.userOnBoard(any(), any(), any(), any(), any())).thenReturn(null);
		UUID t = task(org, b);
		UUID rc = roleConfig(org, b);
		assertThrows(AccessDeniedException.class, () -> fetcher.agentTaskHistory(t, null, null));
		assertThrows(AccessDeniedException.class, () -> fetcher.agentBoardHistory(b, null, null));
		assertThrows(AccessDeniedException.class, () -> fetcher.agentRoleConfigHistory(rc, null, null));
		verifyNoInteractions(agentAuditReadService);
	}

	@Test
	void anUnknownEntityIsNotFoundAndChecksNothing() throws Exception {
		UUID missing = UUID.randomUUID();
		when(agentTaskService.getTaskData(missing)).thenReturn(Optional.empty());
		when(agentBoardService.getBoardData(missing)).thenReturn(Optional.empty());
		when(agentBoardService.getRoleConfigData(missing)).thenReturn(Optional.empty());
		assertTrue(assertThrows(RelizaException.class, () -> fetcher.agentTaskHistory(missing, null, null))
				.getMessage().contains("Task not found"));
		assertTrue(assertThrows(RelizaException.class, () -> fetcher.agentBoardHistory(missing, null, null))
				.getMessage().contains("Board not found"));
		assertTrue(assertThrows(RelizaException.class, () -> fetcher.agentRoleConfigHistory(missing, null, null))
				.getMessage().contains("Role config not found"));
		verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(any(), any(), any(), any(), any(), any());
		verify(authorizationService, never()).userOnBoard(any(), any(), any(), any(), any());
		verifyNoInteractions(agentAuditReadService);
	}
}
