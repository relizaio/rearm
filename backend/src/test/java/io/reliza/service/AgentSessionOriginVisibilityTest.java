/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentSessionData.AuthMethod;
import io.reliza.model.AgentSessionData.OwnerSource;
import io.reliza.model.AgentSessionData.SessionDevice;
import io.reliza.model.AgentSessionData.SessionFederation;
import io.reliza.model.AgentSessionData.SessionOrigin;
import io.reliza.service.AgentSessionOriginService.DeviceReport;
import io.reliza.service.AgentSessionOriginService.SessionOriginView;
import io.reliza.service.AgentSessionOriginService.Viewer;

/**
 * Who may see which part of a session's origin, and what the rest of the readers get. No Spring:
 * the rule is a pure function of the reader and the session, and this is where it is pinned.
 */
public class AgentSessionOriginVisibilityTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID KEY = UUID.randomUUID();
	private static final UUID OWNER = UUID.randomUUID();

	private static SessionOrigin origin(UUID owner) {
		return new SessionOrigin(AuthMethod.CLI_LOGIN, UUID.randomUUID(), owner, OwnerSource.CLI_LOGIN,
				new SessionDevice("pavels-macbook", "darwin/arm64", "UTC-04:00 EDT", "rearm-cli/26.09.4", "203.0.113.7"),
				new SessionDevice("pavels-macbook", "darwin/arm64", "UTC-04:00 EDT", "rearm-cli/26.09.4", null),
				"203.0.113.9",
				new SessionFederation(List.of(), "github", "iss", "relizaio", "relizaio/rearm", null, "refs/heads/main",
						"abc", "wf", null, "push", "some-github-login", "123"),
				ZonedDateTime.now());
	}

	/** A web user; {@code admin} is whether they administer the session's org. */
	private static Viewer user(UUID uuid, boolean admin) {
		return new Viewer(uuid, org -> admin && ORG.equals(org), null, null);
	}

	@Test
	public void anOrgAdminSeesEverything() {
		assertTrue(AgentSessionOriginService.canSeePersonal(user(UUID.randomUUID(), true),
				ORG, KEY, origin(OWNER)));
	}

	@Test
	public void theOwnerSeesTheirOwnSessionWithoutBeingAnAdmin() {
		assertTrue(AgentSessionOriginService.canSeePersonal(user(OWNER, false),
				ORG, KEY, origin(OWNER)));
	}

	@Test
	public void anotherReaderDoesNot() {
		assertFalse(AgentSessionOriginService.canSeePersonal(user(UUID.randomUUID(), false),
				ORG, KEY, origin(OWNER)));
	}

	@Test
	public void withNoOwnerOnlyAdminsSee() {
		assertFalse(AgentSessionOriginService.canSeePersonal(user(UUID.randomUUID(), false),
				ORG, KEY, origin(null)));
		assertTrue(AgentSessionOriginService.canSeePersonal(user(UUID.randomUUID(), true),
				ORG, KEY, origin(null)));
	}

	@Test
	public void anAdminOfAnotherOrgIsJustAnotherReader() {
		UUID elsewhere = UUID.randomUUID();
		Viewer v = new Viewer(UUID.randomUUID(), elsewhere::equals, null, null);
		assertFalse(AgentSessionOriginService.canSeePersonal(v, ORG, KEY, origin(OWNER)));
	}

	@Test
	public void theKeyThatOpenedTheSessionReadsBackWhatItReported() {
		assertTrue(AgentSessionOriginService.canSeePersonal(Viewer.ofKey(KEY, null), ORG, KEY, origin(OWNER)));
	}

	@Test
	public void aKeyWithOrgWideAgentAccessIsNotAPerson() {
		assertFalse(AgentSessionOriginService.canSeePersonal(Viewer.ofKey(UUID.randomUUID(), null), ORG, KEY, origin(OWNER)));
	}

	@Test
	public void theOwnerThroughAnotherLoginStillSees() {
		assertTrue(AgentSessionOriginService.canSeePersonal(Viewer.ofKey(UUID.randomUUID(), OWNER), ORG, KEY, origin(OWNER)));
	}

	@Test
	public void nobodySeesNothingPersonal() {
		assertFalse(AgentSessionOriginService.canSeePersonal(Viewer.nobody(), ORG, KEY, origin(OWNER)));
		assertFalse(AgentSessionOriginService.canSeePersonal(null, ORG, KEY, origin(OWNER)));
	}

	@Test
	public void theRestrictedViewKeepsWhatIdentifiesNoOne() {
		SessionOriginView v = AgentSessionOriginService.view(origin(OWNER), false);
		assertTrue(v.restricted());
		assertNull(v.observedIp());
		for (SessionDevice d : List.of(v.loginDevice(), v.reportedDevice())) {
			assertNull(d.hostname());
			assertNull(d.observedIp());
			assertEquals("darwin/arm64", d.os());
			assertEquals("UTC-04:00 EDT", d.timeZone());
			assertEquals("rearm-cli/26.09.4", d.client());
		}
		assertNull(v.federation().actor());
		assertEquals("relizaio/rearm", v.federation().repository());
		assertEquals(OWNER, v.ownerUser());
		assertEquals(OwnerSource.CLI_LOGIN, v.ownerSource());
		assertEquals(AuthMethod.CLI_LOGIN, v.authMethod());
	}

	@Test
	public void theFullViewIsTheRecord() {
		SessionOrigin o = origin(OWNER);
		SessionOriginView v = AgentSessionOriginService.view(o, true);
		assertFalse(v.restricted());
		assertEquals(o.loginDevice(), v.loginDevice());
		assertEquals(o.reportedDevice(), v.reportedDevice());
		assertEquals(o.observedIp(), v.observedIp());
		assertEquals(o.federation(), v.federation());
	}

	@Test
	public void aReportIsTrimmedClippedAndEmptyMeansNone() {
		SessionDevice d = AgentSessionOriginService.reportedDevice(new DeviceReport("  host  ", "x".repeat(500), null, ""));
		assertEquals("host", d.hostname());
		assertEquals(AgentSessionOriginService.MAX_REPORTED, d.os().length());
		assertNull(d.timeZone());
		assertNull(d.client());
		assertNull(AgentSessionOriginService.reportedDevice(new DeviceReport(" ", null, "", null)));
	}
}
