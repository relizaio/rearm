/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.FederatedContext;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ComponentData;
import io.reliza.model.FederatedGrant;
import io.reliza.model.FederatedIdentity;
import io.reliza.model.FederatedMatcher;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.FederatedTrustRuleService;

/**
 * TPF-14 D7: a FEDERATED identity that names a perspective on the three programmatic create paths
 * passes the federated create gate (its own repository, a template rule that allows creation) as
 * well as the perspective write check. The rule grants org-wide READ_WRITE, so the perspective
 * write check alone would pass and only the gate can refuse.
 */
public class PerspectiveCreatePathsFederatedIntegrationTest extends PerspectiveCreatePathsTestBase {

	private static final String GATE_REFUSAL =
			"This identity may only create components bound to its own repository, and only when a trust rule allows it";
	private static final String OTHER_REPOSITORY = "https://github.com/acme/other";
	private static final String OWN_REPOSITORY = "https://github.com/acme/app";

	@Autowired private FederatedTrustRuleService federatedTrustRuleService;

	/** The principal an exchange of a workflow token of acme/app leaves on the request, admitted in A. */
	private AuthHeaderParse federatedPrincipal(World w) throws RelizaException {
		FederatedMatcher matcher = new FederatedMatcher();
		matcher.setOwner("acme");
		FederatedGrant grant = new FederatedGrant();
		grant.setOrgPermission(PermissionType.READ_WRITE);
		FederatedTrustRule rule = federatedTrustRuleService.create(w.orgA(), "tpf14-rule", null, null, matcher, grant,
				null, WU);
		FederatedIdentity fi = new FederatedIdentity();
		fi.setProvider(FederatedTrustRule.Provider.GITHUB_ACTIONS);
		fi.setOwner("acme");
		fi.setRepository("acme/app");
		fi.setRepositoryUri("github.com/acme/app");
		ApiKey identity = apiKeyService.ensureFederatedIdentity(w.orgA(), fi, WU);
		FederatedContext ctx = new FederatedContext(List.of(rule.getUuid()), "github",
				"https://token.actions.githubusercontent.com", "acme", "acme/app", "github.com/acme/app",
				"refs/heads/main", "abc123", "wf.yml@refs/heads/main", null, "push", "octocat", "987");
		AuthHeaderParse principal = AuthHeaderParse.fromVerifiedFederation(identity, ctx, "198.51.100.14");
		assertTrue(principal.isFederatedIdentity());
		return principal;
	}

	private void assertGateRefusedAndNothingWritten(World w, Snapshot before, Exception e) {
		assertEquals(AccessDeniedException.class, e.getClass(), String.valueOf(e));
		assertEquals(GATE_REFUSAL, e.getMessage());
		assertNothingWritten(w, before);
		assertTrue(vcsRepositoryService.getVcsRepositoryDataByUri(w.orgA(), "github.com/acme/other").isEmpty(),
				"no VCS repository row for the other repository");
	}

	@Test
	public void progCreate_federatedIdentityOutsideItsRepositoryIsRefused() throws Exception {
		World w = world();
		AuthHeaderParse principal = federatedPrincipal(w);
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> progCreate(asPrincipal(principal, "component",
				componentInput("tpf14-" + tag(), null, OTHER_REPOSITORY)), w.pA()));
		assertGateRefusedAndNothingWritten(w, before, e);
	}

	@Test
	public void getNewVersion_federatedIdentityOutsideItsRepositoryIsRefused() throws Exception {
		World w = world();
		AuthHeaderParse principal = federatedPrincipal(w);
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> getNewVersion(asPrincipal(principal, "newVersionInput",
				newVersionInput(OTHER_REPOSITORY, w.pA()))));
		assertGateRefusedAndNothingWritten(w, before, e);
	}

	@Test
	public void addRelease_federatedIdentityOutsideItsRepositoryIsRefused() throws Exception {
		World w = world();
		AuthHeaderParse principal = federatedPrincipal(w);
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> addRelease(asPrincipal(principal, "release",
				releaseInput(OTHER_REPOSITORY, w.pA()))));
		assertGateRefusedAndNothingWritten(w, before, e);
	}

	@Test
	public void getNewVersion_federatedIdentityInsideItsRepositoryStillWorks() throws Exception {
		World w = world();
		AuthHeaderParse principal = federatedPrincipal(w);
		Set<UUID> before = components(w.orgA());
		getNewVersion(asPrincipal(principal, "newVersionInput", newVersionInput(OWN_REPOSITORY, w.pA())));
		ComponentData created = createdSince(w.orgA(), before);
		String uri = vcsRepositoryService.getVcsRepositoryData(created.getVcs()).orElseThrow().getUri();
		assertEquals("github.com/acme/app", Utils.cleanVcsUri(Utils.normalizeVcsUri(uri)));
		assertEquals(Set.of(w.pA()), created.getPerspectives());
	}
}
