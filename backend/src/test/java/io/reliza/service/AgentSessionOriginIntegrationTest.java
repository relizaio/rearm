/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.FederatedContext;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentSessionData.AuthMethod;
import io.reliza.model.AgentSessionData.OwnerSource;
import io.reliza.model.AgentSessionData.SessionOrigin;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.CliSession;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentSessionOriginService.DeviceReport;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Resolving how a session was opened, one test per way in. Owner precedence is the part most
 * likely to be quietly wrong, so each source is pinned against a real key or login row.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentSessionOriginIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentSessionOriginService originService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private CliSessionService cliSessionService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final DeviceReport REPORTED = new DeviceReport("agent-host", "linux/amd64", "UTC+00:00 UTC",
			"rearm-cli/26.09.4");

	private ApiKey freeform(Organization org) {
		return apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "origin test", WU);
	}

	@Test
	public void aFreeFormKeyWithNoHolderNamesNoOne() {
		Organization org = testInitializer.obtainOrganization();
		ApiKey ak = freeform(org);
		SessionOrigin o = originService.resolve(AuthHeaderParse.fromVerifiedKey(ak, 1, "10.0.0.1"), ak.getUuid(),
				"203.0.113.1", REPORTED);
		assertEquals(AuthMethod.KEY_SECRET, o.authMethod());
		assertNull(o.ownerUser());
		assertNull(o.ownerSource());
		assertEquals("agent-host", o.reportedDevice().hostname());
		assertEquals("203.0.113.1", o.observedIp());
		assertNull(o.loginDevice());
	}

	@Test
	public void aFreeFormKeyWithAHolderIsAttributedToTheHolder() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey ak = freeform(org);
		UUID holder = UUID.randomUUID();
		apiKeyService.setApiKeyHolder(ak.getUuid(), holder, WU);
		SessionOrigin o = originService.resolve(AuthHeaderParse.fromVerifiedKey(ak, 1, null), ak.getUuid(), null, null);
		assertEquals(holder, o.ownerUser());
		assertEquals(OwnerSource.KEY_HOLDER, o.ownerSource());
		assertNull(o.reportedDevice());
	}

	@Test
	public void aPersonalKeyIsAttributedToItsOwner() {
		UUID user = UUID.randomUUID();
		AuthHeaderParse ahp = AuthHeaderParse.builder().type(ApiTypeEnum.USER).objUuid(user).build();
		SessionOrigin o = originService.resolve(ahp, UUID.randomUUID(), null, null);
		assertEquals(AuthMethod.KEY_SECRET, o.authMethod());
		assertEquals(user, o.ownerUser());
		assertEquals(OwnerSource.USER_KEY, o.ownerSource());
	}

	@Test
	public void aCliLoginWinsOverTheKeysHolderAndCopiesTheDevice() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey ak = freeform(org);
		apiKeyService.setApiKeyHolder(ak.getUuid(), UUID.randomUUID(), WU);
		String userCode = cliSessionService.start(new CliSessionService.DeviceInfo("pavels-macbook", "darwin/arm64",
				"UTC-04:00 EDT", "rearm-cli/26.09.4", "198.51.100.4")).userCode();
		CliSession login = cliSessionService.pending(userCode).orElseThrow();
		UUID actor = UUID.randomUUID();

		SessionOrigin o = originService.resolve(AuthHeaderParse.fromVerifiedSession(ak, actor, login.getUuid(), null),
				ak.getUuid(), "203.0.113.2", REPORTED);
		assertEquals(AuthMethod.CLI_LOGIN, o.authMethod());
		assertEquals(login.getUuid(), o.cliSession());
		assertEquals(actor, o.ownerUser());
		assertEquals(OwnerSource.CLI_LOGIN, o.ownerSource());
		assertEquals("pavels-macbook", o.loginDevice().hostname());
		assertEquals("198.51.100.4", o.loginDevice().observedIp());
		assertEquals("agent-host", o.reportedDevice().hostname());
	}

	@Test
	public void aFederatedSessionKeepsItsClaimsAndNamesNoOwner() {
		Organization org = testInitializer.obtainOrganization();
		ApiKey ak = freeform(org);
		UUID rule = UUID.randomUUID();
		FederatedContext fc = new FederatedContext(List.of(rule), "github", "https://token.actions.githubusercontent.com",
				"relizaio", "relizaio/rearm", null, "refs/heads/main", "abc123", "wf.yml@refs/heads/main", null, "push",
				"octocat", "987");
		SessionOrigin o = originService.resolve(AuthHeaderParse.fromVerifiedFederation(ak, fc, null), ak.getUuid(),
				null, null);
		assertEquals(AuthMethod.FEDERATED, o.authMethod());
		assertNull(o.ownerUser());
		assertEquals("relizaio/rearm", o.federation().repository());
		assertEquals("octocat", o.federation().actor());
		assertEquals(List.of(rule), o.federation().ruleUuids());
	}

	@Test
	public void theOriginIsStoredWithTheSessionAndReadsBack() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey ak = freeform(org);
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"origin-agent-" + UUID.randomUUID(), null, null, null, WU);
		SessionOrigin o = originService.resolve(AuthHeaderParse.fromVerifiedKey(ak, 1, null), ak.getUuid(),
				"203.0.113.3", REPORTED);
		AgentSessionData s = agentSessionService.initialize(org.getUuid(), agent.getUuid(), ak.getUuid(),
				"origin-" + UUID.randomUUID(), "origin test", null, null, null, o, WU);
		AgentSessionData read = agentSessionService.getSessionData(s.getUuid()).orElseThrow();
		assertNotNull(read.getOrigin());
		assertEquals("agent-host", read.getOrigin().reportedDevice().hostname());
		assertEquals("203.0.113.3", read.getOrigin().observedIp());
		assertEquals(AuthMethod.KEY_SECRET, read.getOrigin().authMethod());
	}

	@Test
	public void aSessionOpenedBeforeThisReadsWithNoOrigin() {
		AgentSessionData legacy = Utils.OM.convertValue(Map.of("status", "OPEN"), AgentSessionData.class);
		assertNull(legacy.getOrigin());
	}
}
