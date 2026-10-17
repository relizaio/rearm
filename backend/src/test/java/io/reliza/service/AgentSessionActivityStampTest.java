/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSession;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentSessionData.SessionStatus;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentSessionRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * A keep-alive is an activity stamp, not a revision (task RD3-1). {@code rearm agent wait} touches its
 * session every poll; each touch used to go through the session's save, which bumps the revision and
 * copies the whole row into the audit table -- about 1,400 audit rows a day per waiting agent.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentSessionActivityStampTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentSessionRepository repository;
	@Autowired private AuditService auditService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private AgentSessionData session() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"stamp-" + UUID.randomUUID(), null, null, null, WU);
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), UUID.randomUUID(),
				"stamp-" + UUID.randomUUID(), "stamp test", null, null, WU);
	}

	private AgentSession row(AgentSessionData s) {
		return repository.findById(s.getUuid()).orElseThrow();
	}

	private AgentSessionData reload(AgentSessionData s) {
		return AgentSessionData.dataFromRecord(row(s));
	}

	private int auditRows(AgentSessionData s) {
		return auditService.getAuditForEntity(s.getUuid(), TableName.AGENT_SESSIONS, 1000, 0).size();
	}

	private static long millis(ZonedDateTime t) {
		return t.toInstant().toEpochMilli();
	}

	@Test
	public void touchesMoveTheStampWithoutARevisionOrAnAuditRow() throws RelizaException {
		AgentSessionData s = session();
		ZonedDateTime t0 = s.getLastActivityAt();
		int revision = row(s).getRevision();
		ZonedDateTime updated = row(s).getLastUpdatedDate();
		int audit = auditRows(s);

		// A touch a minute for an hour, as rearm agent wait sends them.
		int writes = 0;
		ZonedDateTime stamp = t0;
		for (int minute = 1; minute <= 60; minute++) {
			AgentSessionData after = agentSessionService.touch(s.getUuid(), t0.plusMinutes(minute), WU);
			if (millis(after.getLastActivityAt()) != millis(stamp)) {
				writes++;
				stamp = after.getLastActivityAt();
				assertEquals(millis(t0.plusMinutes(minute)), millis(stamp), "the stamp is the touch's time");
			}
		}
		assertEquals(12, writes, "one stamp per five minutes; the touches in between write nothing");
		assertEquals(millis(t0.plusMinutes(60)), millis(reload(s).getLastActivityAt()));
		assertEquals(revision, row(s).getRevision(), "no revision");
		assertEquals(millis(updated), millis(row(s).getLastUpdatedDate()), "lastUpdatedDate untouched");
		assertEquals(audit, auditRows(s), "no audit row");
	}

	@Test
	public void aPendingWarningIsClearedByATouchInsideTheThrottle() throws RelizaException {
		AgentSessionData s = session();
		ZonedDateTime t0 = s.getLastActivityAt();
		int audit = auditRows(s);
		agentSessionService.touch(s.getUuid(), t0.plusMinutes(10), WU);
		assertEquals(1, repository.stampIdleWarning(s.getUuid(), AgentSessionService.epochSecondsOf(t0.plusMinutes(11))));
		assertNotNull(reload(s).getIdleWarnedAt(), "warned");
		assertEquals(0, repository.stampIdleWarning(s.getUuid(), AgentSessionService.epochSecondsOf(t0.plusMinutes(12))),
				"warned once");

		// One minute after the last stamp: inside the throttle, but the warning is pending.
		AgentSessionData touched = agentSessionService.touch(s.getUuid(), t0.plusMinutes(12), WU);
		assertNull(touched.getIdleWarnedAt(), "any call with its id clears the warning");
		assertEquals(millis(t0.plusMinutes(12)), millis(touched.getLastActivityAt()));
		assertEquals(audit, auditRows(s), "neither the warning nor the touch is an audit row");
	}

	@Test
	public void aCloseAfterTouchesIsOneRevisionCarryingTheLastStamp() throws RelizaException {
		AgentSessionData s = session();
		ZonedDateTime t0 = s.getLastActivityAt();
		int revision = row(s).getRevision();
		int audit = auditRows(s);
		for (int minute = 5; minute <= 30; minute += 5) agentSessionService.touch(s.getUuid(), t0.plusMinutes(minute), WU);
		ZonedDateTime lastTouch = reload(s).getLastActivityAt();

		ZonedDateTime closing = ZonedDateTime.now();
		AgentSessionData closed = agentSessionService.close(s.getUuid(), WU);
		assertEquals(SessionStatus.CLOSED, closed.getStatus());
		assertEquals(revision + 1, row(s).getRevision(), "the close is the one revision");
		assertEquals(audit + 1, auditRows(s), "one audit row: the session as it was before the close");
		AgentSessionData before = Utils.OM.convertValue(auditService.getAuditForEntity(s.getUuid(),
				TableName.AGENT_SESSIONS, 1000, 0).get(0).getRevisionRecordData(), AgentSessionData.class);
		assertEquals(SessionStatus.OPEN, before.getStatus());
		assertEquals(millis(lastTouch), millis(before.getLastActivityAt()), "the audit copy carries the last stamp");
		// The close stamps its own time in its save, as before; the touches above ran on a test clock ahead of it.
		assertTrue(Math.abs(millis(reload(s).getLastActivityAt()) - millis(closing)) < 60_000,
				"the close's revision carries the close time");

		AgentSessionData afterClose = agentSessionService.touch(s.getUuid(), t0.plusHours(1), WU);
		assertEquals(SessionStatus.CLOSED, afterClose.getStatus(), "a touch on a closed session is ignored");
		assertEquals(revision + 1, row(s).getRevision());
	}

	@Test
	public void recordActivityStampsOncePerThrottleAndOnlyForTheSessionsOwnKey() throws RelizaException {
		AgentSessionData s = session();
		ZonedDateTime t0 = s.getLastActivityAt();
		int revision = row(s).getRevision();
		int audit = auditRows(s);

		assertFalse(agentSessionService.recordActivity(s.getUuid(), UUID::randomUUID, t0.plusMinutes(6)),
				"another key's call is not this session working");
		assertEquals(millis(t0), millis(reload(s).getLastActivityAt()));

		int writes = 0;
		for (int second = 0; second < 240; second += 20) {
			if (agentSessionService.recordActivity(s.getUuid(), s::getApiKey, t0.plusMinutes(6).plusSeconds(second))) writes++;
		}
		assertEquals(1, writes, "calls inside the throttle stamp once");
		assertEquals(millis(t0.plusMinutes(6)), millis(reload(s).getLastActivityAt()));
		assertEquals(revision, row(s).getRevision(), "no revision");
		assertEquals(audit, auditRows(s), "no audit row");
	}

	@Test
	public void anOlderStampNeverOverwritesANewerOne() throws RelizaException {
		AgentSessionData s = session();
		ZonedDateTime t0 = s.getLastActivityAt();
		agentSessionService.touch(s.getUuid(), t0.plusMinutes(20), WU);
		assertEquals(0, repository.stampActivity(s.getUuid(), AgentSessionService.epochSecondsOf(t0.plusMinutes(10))),
				"a late, older stamp writes nothing");
		assertEquals(millis(t0.plusMinutes(20)), millis(reload(s).getLastActivityAt()));
		agentSessionService.close(s.getUuid(), WU);
		assertEquals(0, repository.stampActivity(s.getUuid(), AgentSessionService.epochSecondsOf(t0.plusHours(2))),
				"a closed session is not stamped");
		assertTrue(reload(s).getLastActivityAt().isBefore(t0.plusHours(1)));
	}
}
