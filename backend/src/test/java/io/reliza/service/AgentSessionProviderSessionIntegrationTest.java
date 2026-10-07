/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentSessionData.ProviderSession;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentSessionService.ProviderSessionInput;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Recording the agent tool's own session id on a ReARM session, and finding sessions by it.
 *
 * <p>The ids are the shapes Claude Code actually uses: a uuid for the local session its
 * transcript is filed under, and {@code session_<base58>} for the hosted bridge session.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentSessionProviderSessionIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private static String localId() {
		return UUID.randomUUID().toString();
	}

	private static String remoteId() {
		return "session_01" + UUID.randomUUID().toString().replace("-", "").substring(0, 22);
	}

	private AgentSessionData open(Organization org, ProviderSessionInput ps) throws RelizaException {
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"provider-agent-" + UUID.randomUUID(), null, null, null, WU);
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				"provider-" + UUID.randomUUID(), "provider session test", null, null, ps, WU);
	}

	@Test
	public void initRecordsTheProviderSessionLowerCasingTheTool() throws RelizaException {
		String local = localId();
		String remote = remoteId();
		AgentSessionData s = open(testInitializer.obtainOrganization(),
				new ProviderSessionInput("Claude-Code", local, remote));
		assertEquals(1, s.getProviderSessions().size());
		ProviderSession ps = s.getProviderSessions().get(0);
		assertEquals("claude-code", ps.provider());
		assertEquals(local, ps.id());
		assertEquals(remote, ps.remoteId());
		assertNotNull(ps.reportedAt());
	}

	@Test
	public void reportingNothingRecordsNothing() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		assertTrue(open(org, null).getProviderSessions().isEmpty());
		// A client that sends the object with every value blank has reported nothing either.
		assertTrue(open(org, new ProviderSessionInput(" ", "", null)).getProviderSessions().isEmpty());
	}

	@Test
	public void aResumedConversationAppendsAndARepeatIsANoOp() throws RelizaException {
		String first = localId();
		AgentSessionData s = open(testInitializer.obtainOrganization(),
				new ProviderSessionInput("claude-code", first, null));
		String second = localId();
		agentSessionService.updateMeta(s.getUuid(), null, null,
				new ProviderSessionInput("claude-code", first, null), WU);
		AgentSessionData after = agentSessionService.updateMeta(s.getUuid(), null, null,
				new ProviderSessionInput("claude-code", second, null), WU);
		assertEquals(List.of(first, second),
				after.getProviderSessions().stream().map(ProviderSession::id).toList());
	}

	@Test
	public void aLaterReportFillsInTheRemoteIdAndKeepsWhenItWasFirstSeen() throws RelizaException {
		String local = localId();
		AgentSessionData s = open(testInitializer.obtainOrganization(),
				new ProviderSessionInput("claude-code", local, null));
		// Re-read rather than taken from initialize's return, which still carries nanoseconds the
		// stored row keeps only to the microsecond.
		ProviderSession before = agentSessionService.getSessionData(s.getUuid()).orElseThrow()
				.getProviderSessions().get(0);
		String remote = remoteId();
		AgentSessionData after = agentSessionService.updateMeta(s.getUuid(), null, null,
				new ProviderSessionInput("claude-code", local, remote), WU);
		assertEquals(1, after.getProviderSessions().size());
		assertEquals(remote, after.getProviderSessions().get(0).remoteId());
		assertEquals(before.reportedAt().toInstant(), after.getProviderSessions().get(0).reportedAt().toInstant());
	}

	@Test
	public void aSecondRemoteIdForTheSameLocalSessionIsRefused() throws RelizaException {
		String local = localId();
		String remote = remoteId();
		AgentSessionData s = open(testInitializer.obtainOrganization(),
				new ProviderSessionInput("claude-code", local, remote));
		RelizaException e = assertThrows(RelizaException.class, () -> agentSessionService.updateMeta(
				s.getUuid(), null, null, new ProviderSessionInput("claude-code", local, remoteId()), WU));
		assertTrue(e.getMessage().contains(remote), e.getMessage());
		assertEquals(remote, agentSessionService.getSessionData(s.getUuid()).orElseThrow()
				.getProviderSessions().get(0).remoteId());
	}

	@Test
	public void halfAReportOrAnUnsafeIdIsRefused() {
		Organization org = testInitializer.obtainOrganization();
		assertThrows(RelizaException.class, () -> open(org, new ProviderSessionInput("claude-code", null, remoteId())));
		assertThrows(RelizaException.class, () -> open(org, new ProviderSessionInput(null, localId(), null)));
		assertThrows(RelizaException.class, () -> open(org, new ProviderSessionInput("claude code", localId(), null)));
		assertThrows(RelizaException.class, () -> open(org, new ProviderSessionInput("claude-code", "has space", null)));
		assertThrows(RelizaException.class, () -> open(org, new ProviderSessionInput("claude-code", "x".repeat(257), null)));
		assertThrows(RelizaException.class, () -> open(org, new ProviderSessionInput("claude-code", localId(), "café")));
	}

	@Test
	public void theListIsCapped() throws RelizaException {
		AgentSessionData s = open(testInitializer.obtainOrganization(), null);
		for (int i = 0; i < AgentSessionService.MAX_PROVIDER_SESSIONS; i++) {
			agentSessionService.updateMeta(s.getUuid(), null, null,
					new ProviderSessionInput("claude-code", localId(), null), WU);
		}
		assertThrows(RelizaException.class, () -> agentSessionService.updateMeta(s.getUuid(), null, null,
				new ProviderSessionInput("claude-code", localId(), null), WU));
	}

	@Test
	public void sessionsAreFoundByEitherIdAndOnlyInTheirOwnOrg() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization other = testInitializer.obtainOrganization();
		String local = localId();
		String remote = remoteId();
		// One conversation opening two ReARM sessions -- the ordinary case, not an edge.
		AgentSessionData a = open(org, new ProviderSessionInput("claude-code", local, remote));
		AgentSessionData b = open(org, new ProviderSessionInput("claude-code", local, remote));
		AgentSessionData elsewhere = open(other, new ProviderSessionInput("claude-code", local, remote));
		open(org, new ProviderSessionInput("claude-code", localId(), null));

		List<UUID> byLocal = agentSessionService.listByProviderSession(org.getUuid(), local).stream()
				.map(AgentSessionData::getUuid).toList();
		List<UUID> byRemote = agentSessionService.listByProviderSession(org.getUuid(), remote).stream()
				.map(AgentSessionData::getUuid).toList();
		assertEquals(2, byLocal.size());
		assertTrue(byLocal.containsAll(List.of(a.getUuid(), b.getUuid())));
		assertEquals(byLocal.stream().sorted().toList(), byRemote.stream().sorted().toList());
		assertTrue(!byLocal.contains(elsewhere.getUuid()));
		assertTrue(agentSessionService.listByProviderSession(org.getUuid(), "session_unknown").isEmpty());
	}

	@Test
	public void aRowWrittenBeforeTheFieldReadsAsEmpty() {
		AgentSessionData legacy = Utils.OM.convertValue(Map.of("status", "OPEN"), AgentSessionData.class);
		assertNotNull(legacy.getProviderSessions());
		assertTrue(legacy.getProviderSessions().isEmpty());
	}
}
