/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationCapability;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.TriggerIntegrationInputDto;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Prevent-recurrence guard for the P1 misconfig: a GITHUB integration could be
 * saved with the PR_VALIDATE capability but an EMPTY GitHub App key, producing
 * a silently-broken PR-validation channel (getGithubKey returns null on every
 * attempt). {@code IntegrationService} now rejects that at the create/update
 * boundary with a {@link RelizaException}.
 *
 * Covers: create with PR_VALIDATE + empty/absent key is rejected; create with a
 * key succeeds; non-PR_VALIDATE GitHub integrations (e.g. WEBHOOK-only) with an
 * empty key are unaffected; and updateCapabilities adding PR_VALIDATE enforces
 * the same rule against the (encrypted) stored secret.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class IntegrationServiceGithubPrValidateValidationTest {

	@Autowired private IntegrationService integrationService;
	@Autowired private TestInitializer testInitializer;

	private static final String TEST_APP_ID = "123456";

	private TriggerIntegrationInputDto githubTii(UUID orgUuid, String secret,
			List<IntegrationCapability> caps) {
		// A PR_VALIDATE GitHub integration now requires both a key and an App ID
		// (schedule); set a valid App ID by default so the key-focused cases are
		// not rejected for a missing App ID.
		return githubTii(orgUuid, secret, caps, TEST_APP_ID);
	}

	private TriggerIntegrationInputDto githubTii(UUID orgUuid, String secret,
			List<IntegrationCapability> caps, String appId) {
		TriggerIntegrationInputDto tii = new TriggerIntegrationInputDto();
		tii.setOrg(orgUuid);
		tii.setType(IntegrationType.GITHUB);
		tii.setSecret(secret);
		tii.setCapabilities(caps);
		tii.setSchedule(appId);
		return tii;
	}

	@Test
	public void create_prValidateWithEmptyKey_isRejected() {
		Organization org = testInitializer.obtainOrganization();
		RelizaException ex = assertThrows(RelizaException.class, () ->
				integrationService.createIntegration(
						githubTii(org.getUuid(), "", List.of(IntegrationCapability.PR_VALIDATE)),
						WhoUpdated.getTestWhoUpdated()));
		assertTrue(ex.getMessage().contains("PR_VALIDATE"),
				"rejection message must name the offending capability; got: " + ex.getMessage());
	}

	@Test
	public void create_prValidateWithNullKey_isRejected() {
		Organization org = testInitializer.obtainOrganization();
		assertThrows(RelizaException.class, () ->
				integrationService.createIntegration(
						githubTii(org.getUuid(), null, List.of(IntegrationCapability.PR_VALIDATE)),
						WhoUpdated.getTestWhoUpdated()));
	}

	@Test
	public void create_prValidateWithKey_succeeds() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Integration created = integrationService.createIntegration(
				githubTii(org.getUuid(), "github-app-private-key", List.of(IntegrationCapability.PR_VALIDATE)),
				WhoUpdated.getTestWhoUpdated());
		assertNotNull(created.getUuid());
	}

	@Test
	public void create_prValidateWithKeyButNoAppId_isRejected() {
		// Same silent-failure class as a missing key: getGithubJWT uses the App ID
		// (schedule) as the JWT issuer, so PR_VALIDATE with a key but no App ID
		// would still fail every token mint. Must be rejected at the boundary.
		Organization org = testInitializer.obtainOrganization();
		RelizaException ex = assertThrows(RelizaException.class, () ->
				integrationService.createIntegration(
						githubTii(org.getUuid(), "github-app-private-key",
								List.of(IntegrationCapability.PR_VALIDATE), ""),
						WhoUpdated.getTestWhoUpdated()));
		assertTrue(ex.getMessage().contains("App ID"),
				"rejection message must name the missing App ID; got: " + ex.getMessage());
	}

	@Test
	public void create_nonPrValidateWithEmptyKey_isUnaffected() throws Exception {
		// WEBHOOK uses a per-webhook HMAC secret, not the App key, so an empty
		// App key on a WEBHOOK-only GitHub integration must remain allowed.
		Organization org = testInitializer.obtainOrganization();
		Integration created = integrationService.createIntegration(
				githubTii(org.getUuid(), "", List.of(IntegrationCapability.WEBHOOK)),
				WhoUpdated.getTestWhoUpdated());
		assertNotNull(created.getUuid());
	}

	@Test
	public void updateCapabilities_addingPrValidateToKeyedIntegration_succeeds() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Integration created = integrationService.createIntegration(
				githubTii(org.getUuid(), "github-app-private-key", List.of(IntegrationCapability.WEBHOOK)),
				WhoUpdated.getTestWhoUpdated());
		Integration updated = integrationService.updateCapabilities(created.getUuid(),
				List.of(IntegrationCapability.WEBHOOK, IntegrationCapability.PR_VALIDATE),
				WhoUpdated.getTestWhoUpdated());
		assertEquals(created.getUuid(), updated.getUuid());
	}

	@Test
	public void create_nonGithubWithEmptyKey_isUnaffected() throws Exception {
		// The guard is GITHUB-scoped: PR_VALIDATE is GITHUB-only today, so a
		// non-GITHUB type must never trip it even with an empty key. This pins
		// the type-scoping contract so widening PR_VALIDATE later can't silently
		// bypass the check.
		Organization org = testInitializer.obtainOrganization();
		TriggerIntegrationInputDto tii = new TriggerIntegrationInputDto();
		tii.setOrg(org.getUuid());
		tii.setType(IntegrationType.JENKINS);
		tii.setSecret("");
		tii.setCapabilities(List.of(IntegrationCapability.PR_VALIDATE));
		Integration created = integrationService.createIntegration(tii, WhoUpdated.getTestWhoUpdated());
		assertNotNull(created.getUuid());
	}

	@Test
	public void updateCapabilities_addingPrValidateToKeylessIntegration_isRejected() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Integration created = integrationService.createIntegration(
				githubTii(org.getUuid(), "", List.of(IntegrationCapability.WEBHOOK)),
				WhoUpdated.getTestWhoUpdated());
		assertThrows(RelizaException.class, () ->
				integrationService.updateCapabilities(created.getUuid(),
						List.of(IntegrationCapability.WEBHOOK, IntegrationCapability.PR_VALIDATE),
						WhoUpdated.getTestWhoUpdated()));
	}

	@Test
	public void updateGithubAppCredentials_replacesKey_succeeds() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Integration created = integrationService.createIntegration(
				githubTii(org.getUuid(), "old-key", List.of(IntegrationCapability.PR_VALIDATE)),
				WhoUpdated.getTestWhoUpdated());
		Integration updated = integrationService.updateGithubAppCredentials(
				created.getUuid(), "new-github-app-private-key", "987654", WhoUpdated.getTestWhoUpdated());
		assertEquals(created.getUuid(), updated.getUuid());
	}

	@Test
	public void updateGithubAppCredentials_blankAppId_keepsStoredAppId() throws Exception {
		// A blank appId rotates the key alone and must leave the stored App ID intact.
		Organization org = testInitializer.obtainOrganization();
		TriggerIntegrationInputDto tii = githubTii(org.getUuid(), "old-key",
				List.of(IntegrationCapability.PR_VALIDATE));
		tii.setSchedule("555111");
		Integration created = integrationService.createIntegration(tii, WhoUpdated.getTestWhoUpdated());
		integrationService.updateGithubAppCredentials(
				created.getUuid(), "rotated-github-app-private-key", "", WhoUpdated.getTestWhoUpdated());
		IntegrationData after = integrationService.getIntegrationData(created.getUuid())
				.orElseThrow(() -> new AssertionError("integration vanished"));
		assertEquals("555111", after.getSchedule(),
				"blank appId must leave the stored App ID unchanged");
	}

	@Test
	public void updateGithubAppCredentials_blankKeyWithPrValidate_isRejected() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Integration created = integrationService.createIntegration(
				githubTii(org.getUuid(), "old-key", List.of(IntegrationCapability.PR_VALIDATE)),
				WhoUpdated.getTestWhoUpdated());
		assertThrows(RelizaException.class, () ->
				integrationService.updateGithubAppCredentials(
						created.getUuid(), "", "987654", WhoUpdated.getTestWhoUpdated()));
	}

	@Test
	public void updateGithubAppCredentials_nonGithub_isRejected() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		TriggerIntegrationInputDto tii = new TriggerIntegrationInputDto();
		tii.setOrg(org.getUuid());
		tii.setType(IntegrationType.JENKINS);
		tii.setSecret("jenkins-token");
		tii.setCapabilities(List.of());
		Integration created = integrationService.createIntegration(tii, WhoUpdated.getTestWhoUpdated());
		RelizaException ex = assertThrows(RelizaException.class, () ->
				integrationService.updateGithubAppCredentials(
						created.getUuid(), "some-key", "1", WhoUpdated.getTestWhoUpdated()));
		assertTrue(ex.getMessage().contains("GITHUB"),
				"message should explain the GITHUB-only restriction; got: " + ex.getMessage());
	}

	@Test
	public void updateCapabilities_addingPrValidateToKeyedIntegrationWithNoAppId_isRejected() throws Exception {
		// Update path reads the App ID (schedule) from the stored record, a
		// different code path than create. A keyed WEBHOOK-only integration with no
		// App ID must be rejected when PR_VALIDATE is added.
		Organization org = testInitializer.obtainOrganization();
		Integration created = integrationService.createIntegration(
				githubTii(org.getUuid(), "github-app-private-key", List.of(IntegrationCapability.WEBHOOK), ""),
				WhoUpdated.getTestWhoUpdated());
		RelizaException ex = assertThrows(RelizaException.class, () ->
				integrationService.updateCapabilities(created.getUuid(),
						List.of(IntegrationCapability.WEBHOOK, IntegrationCapability.PR_VALIDATE),
						WhoUpdated.getTestWhoUpdated()));
		assertTrue(ex.getMessage().contains("App ID"),
				"rejection message must name the missing App ID; got: " + ex.getMessage());
	}
}
