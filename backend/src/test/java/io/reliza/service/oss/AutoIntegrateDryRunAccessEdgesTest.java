/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.ActionRefusedException;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.Component;
import io.reliza.model.ComponentLock.AttestationRequirement;
import io.reliza.model.ComponentLock.UnlockLevel;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateDependencyRow;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateEvaluation;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateOutcome;
import io.reliza.service.ComponentLockService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.App;
import io.reliza.ws.BranchDataFetcher;
import io.reliza.ws.BranchDataFetcher.AutoIntegrateResultView;

/**
 * TPF-119 mutation round: the fetchers' redaction and error behaviour that AutoIntegrateDryRunAccessTest
 * leaves open. A row of another organization's component keeps the evaluator's own note for a caller who
 * cannot read it; a row of a component that is gone is redacted; the explained mutation redacts for a caller
 * who may write the feature set but not read its dependencies; the old mutation still answers a lock with
 * an error, and the explained one with CREATE_FAILED.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class, TestAsyncConfig.class})
public class AutoIntegrateDryRunAccessEdgesTest extends AutoIntegrateDryRunTestBase {

	private static final String NOT_READABLE = "release not shown: you have no read permission on this component";
	private static final String FOREIGN_NOTE = "release belongs to another organization and cannot be a parent";

	@Autowired private UserService userService;
	@Autowired private BranchDataFetcher branchDataFetcher;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private ComponentLockService lockService;

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
		SecurityContextHolder.clearContext();
	}

	/** A member of the organization whose only grant is the given one, acting now. */
	private void actAs(Organization org, PermissionScope scope, UUID object, PermissionType type) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		String oauthId = "e-" + UUID.randomUUID();
		User u = userService.createUser("E " + tag, "e-" + tag + "@tpf119.io", true, List.of(org.getUuid()), oauthId,
				OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), scope, object, type, null, null, WU);
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(oauthId).build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
	}

	@Test
	public void aRowOfAnotherOrganizationsComponentKeepsItsOwnNoteAndOneOfAGoneComponentIsRedacted() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Organization otherOrg = testInitializer.obtainOrganization();
		Comp own = component(org, "accEdgeOwn");
		assembled(org, own, "1.0.0");
		Comp foreign = component(otherOrg, "accEdgeForeign");
		assembled(otherOrg, foreign, "7.7.7");
		UUID ghost = UUID.randomUUID();
		UUID fsUuid = featureSet(product(org), "fs");
		configure(fsUuid, AutoIntegrateState.ENABLED, dep(own, StatusEnum.REQUIRED), dep(foreign, StatusEnum.TRANSIENT),
				ChildComponent.builder().uuid(ghost).branch(UUID.randomUUID()).status(StatusEnum.TRANSIENT).build());
		actAs(org, PermissionScope.BRANCH, fsUuid, PermissionType.READ_ONLY);

		AutoIntegrateEvaluation ev = branchDataFetcher.autoIntegrateFeatureSetPreview(fsUuid);

		assertEquals(AutoIntegrateOutcome.CREATE_FAILED, ev.outcome(), "decided on the full data");
		assertEquals(NOT_READABLE, row(ev, own).note(), "a same-organization component the caller cannot read");
		AutoIntegrateDependencyRow foreignRow = row(ev, foreign);
		assertEquals(FOREIGN_NOTE, foreignRow.note(), "the evaluator already redacted it; the fetcher leaves it as it is");
		assertNull(foreignRow.release());
		AutoIntegrateDependencyRow ghostRow = ev.dependencies().stream().filter(r -> r.component().uuid().equals(ghost))
				.findFirst().orElseThrow();
		assertEquals(NOT_READABLE, ghostRow.note(), "a component that is gone is not readable");
		assertNull(ghostRow.release());
	}

	@Test
	public void theExplainedMutationRedactsForACallerWhoMayWriteTheFeatureSetButNotReadItsDependencies() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "accEdgeC1");
		assembled(org, c1, "4.4.4");
		UUID fsUuid = featureSet(product(org), "fs");
		configure(fsUuid, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED));
		actAs(org, PermissionScope.BRANCH, fsUuid, PermissionType.READ_WRITE);

		AutoIntegrateResultView view = branchDataFetcher.autoIntegrateFeatureSetExplained(fsUuid);

		assertNotNull(view.release(), "the product release is created, and is the caller's to see");
		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, view.evaluation().outcome());
		assertNotNull(view.evaluation().projectedVersion());
		AutoIntegrateDependencyRow r = row(view.evaluation(), c1);
		assertNull(r.release());
		assertEquals(NOT_READABLE, r.note());
		assertEquals(1, count(fsUuid));
	}

	@Test
	public void aLockedProductIsAnErrorOnTheOldMutationAndCreateFailedOnTheExplainedOne() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "accEdgeLocked");
		assembled(org, c1, "1.0.0");
		Component product = product(org);
		UUID fsUuid = featureSet(product, "fs");
		configure(fsUuid, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED));
		lockService.lockComponent(product.getUuid(), "frozen", UnlockLevel.ADMIN, AttestationRequirement.NONE, WU);
		actAs(org, PermissionScope.BRANCH, fsUuid, PermissionType.READ_WRITE);

		ActionRefusedException refused = assertThrows(ActionRefusedException.class,
				() -> branchDataFetcher.autoIntegrateFeatureSet(fsUuid));
		assertTrue(refused.getMessage().contains("is locked") && refused.getMessage().contains("frozen"), refused.getMessage());

		AutoIntegrateResultView view = branchDataFetcher.autoIntegrateFeatureSetExplained(fsUuid);
		assertNull(view.release());
		assertEquals(AutoIntegrateOutcome.CREATE_FAILED, view.evaluation().outcome());
		assertEquals(refused.getMessage(), view.evaluation().note());
		assertEquals(0, count(fsUuid));
	}
}
