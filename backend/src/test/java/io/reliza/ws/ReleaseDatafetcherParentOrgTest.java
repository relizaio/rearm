/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.VersionAssignmentService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.oss.TestInitializer;

/**
 * A product release's parents must be releases of its own organization, pinned at the doors a user
 * hits (TPF-5, TEA out-of-scope entry #19): addReleaseManual and updateRelease driven through the
 * datafetcher, so the input conversion, the branch and organization resolution, the authorization and
 * the refusal are the ones a caller meets. The rule itself (OssReleaseService.validateReleaseReferences,
 * rearm-saas#756) is pinned at the service in ReleaseReferenceOrgValidationTest, and the organization
 * and branch check of createRelease in ReleaseCreateOrgBranchMismatchTest.
 *
 * <p>Org A holds a product with its base feature set and a component with two ASSEMBLED releases
 * (the children); org B holds a component with one ASSEMBLED release (the secret). The cross-org
 * cases are the merge sentinel for the 2026-10-tea branch, whose base predates the guard.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ReleaseDatafetcherParentOrgTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private VersionAssignmentService versionAssignmentService;
	@Autowired private AuthorizationService authorizationService;
	@Autowired private UserService realUserService;
	@Autowired private LicenseStatus licenseStatus;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private boolean wasSealed;
	private boolean wasLicensed;

	/** Org A's product feature set and children, org B's secret, and a writer of A. */
	private record Fixture(UUID orgA, UUID orgB, UUID featureSet, UUID childA, UUID childA2, UUID secret,
			UserData writerA) {}

	@BeforeEach
	void operational() {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
		SecurityContext ctx = mock(SecurityContext.class);
		when(ctx.getAuthentication()).thenReturn(mock(JwtAuthenticationToken.class));
		SecurityContextHolder.setContext(ctx);
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	private Fixture fixture() throws RelizaException {
		UUID orgA = testInitializer.obtainOrganization().getUuid();
		UUID orgB = testInitializer.obtainOrganization().getUuid();
		UUID featureSet = baseBranch(component(orgA, ComponentType.PRODUCT));
		Component childComponent = component(orgA, ComponentType.COMPONENT);
		UUID childA = assembled(orgA, childComponent, "1.0.0");
		UUID childA2 = assembled(orgA, childComponent, "1.0.1");
		UUID secret = assembled(orgB, component(orgB, ComponentType.COMPONENT), "1.0.0");
		return new Fixture(orgA, orgB, featureSet, childA, childA2, secret, writer(orgA));
	}

	private Component component(UUID org, ComponentType type) throws RelizaException {
		return componentService.createComponent("parentorg_" + UUID.randomUUID(), org, type, "semver",
				"Branch.Micro", null, WU);
	}

	private UUID baseBranch(Component c) {
		return branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow().getUuid();
	}

	private UUID assembled(UUID org, Component c, String version) throws RelizaException {
		return ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid()).org(org)
				.branch(baseBranch(c)).version(version).status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU).getUuid();
	}

	/** A tenant user of {@code org} with organization-wide READ_WRITE, as BoardPermissionIntegrationTest.member. */
	private UserData writer(UUID org) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = realUserService.createUser("Writer " + tag, "writer-" + tag + "@parentorg.io", true, List.of(org),
				"writer-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		realUserService.setUserPermission(u.getUuid(), org, PermissionScope.ORGANIZATION, org,
				PermissionType.READ_WRITE, List.of(), null, WU);
		return realUserService.getUserData(u.getUuid()).orElseThrow();
	}

	/** The datafetcher as DGS would call it for {@code user}, over the real services. */
	private ReleaseDatafetcher fetcherAs(UserData user, AuthorizationService authz) throws Exception {
		UserService userService = mock(UserService.class);
		when(userService.getUserDataByAuth(any(JwtAuthenticationToken.class))).thenReturn(Optional.of(user));
		ReleaseDatafetcher fetcher = new ReleaseDatafetcher();
		inject(fetcher, "ossReleaseService", ossReleaseService);
		inject(fetcher, "sharedReleaseService", sharedReleaseService);
		inject(fetcher, "branchService", branchService);
		inject(fetcher, "getOrganizationService", getOrganizationService);
		inject(fetcher, "authorizationService", authz);
		inject(fetcher, "userService", userService);
		return fetcher;
	}

	private static void inject(ReleaseDatafetcher fetcher, String field, Object value) throws Exception {
		Field f = ReleaseDatafetcher.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(fetcher, value);
	}

	/** The {@code release} argument as DGS hands it over: a map of strings, converted by Utils.OM. */
	private static DgsDataFetchingEnvironment dfe(Map<String, Object> release) {
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.getArgument("release")).thenReturn(release);
		return dfe;
	}

	private static List<Map<String, Object>> parents(UUID... releases) {
		return java.util.Arrays.stream(releases).map(r -> Map.<String, Object>of("release", r.toString())).toList();
	}

	private static Map<String, Object> createInput(UUID org, UUID branch, String version, ReleaseLifecycle lifecycle,
			UUID... parentReleases) {
		Map<String, Object> in = new LinkedHashMap<>();
		in.put("org", org.toString());
		in.put("branch", branch.toString());
		in.put("version", version);
		if (null != lifecycle) in.put("lifecycle", lifecycle.name());
		in.put("parentReleases", parents(parentReleases));
		return in;
	}

	private static Map<String, Object> updateInput(UUID release, UUID... parentReleases) {
		Map<String, Object> in = new LinkedHashMap<>();
		in.put("uuid", release.toString());
		in.put("parentReleases", parents(parentReleases));
		return in;
	}

	private ReleaseData stored(UUID release) {
		return sharedReleaseService.getReleaseData(release).orElseThrow();
	}

	private static List<UUID> parentUuids(ReleaseData rd) {
		return rd.getParentReleases().stream().map(ParentRelease::getRelease).toList();
	}

	private static String notFound(UUID... refused) {
		return "Parent release not found in this organization: " + List.of(refused);
	}

	/** Nothing written on the feature set, and the other organization's release untouched. */
	private void assertNothingWritten(Fixture f, int secretEventsBefore) {
		assertTrue(sharedReleaseService.listReleaseDataOfBranch(f.featureSet()).isEmpty(),
				"no product release may be created");
		assertTrue(versionAssignmentService.getLatestVersionAssignmentOfBranch(f.featureSet(), 10).isEmpty(),
				"no version may be assigned on the feature set");
		assertSecretUnchanged(f, secretEventsBefore);
	}

	private void assertSecretUnchanged(Fixture f, int secretEventsBefore) {
		ReleaseData secret = stored(f.secret());
		assertEquals(secretEventsBefore, secret.getUpdateEvents().size(), "org B's release gets no update event");
		assertTrue(secret.getParentReleases().isEmpty());
	}

	// ---------------- create: addReleaseManual ----------------

	@Test
	public void sameOrgParentsAreAcceptedOnCreate() throws Exception {
		Fixture f = fixture();
		int childEvents = stored(f.childA()).getUpdateEvents().size();
		int child2Events = stored(f.childA2()).getUpdateEvents().size();
		ReleaseData created = fetcherAs(f.writerA(), authorizationService).addRelease(dfe(
				createInput(f.orgA(), f.featureSet(), "1.0.0", null, f.childA(), f.childA2())));
		ReleaseData rd = stored(created.getUuid());
		assertEquals(f.orgA(), rd.getOrg());
		assertEquals(f.featureSet(), rd.getBranch());
		assertEquals(List.of(f.childA(), f.childA2()), parentUuids(rd));
		assertEquals(childEvents, stored(f.childA()).getUpdateEvents().size());
		assertEquals(child2Events, stored(f.childA2()).getUpdateEvents().size());
	}

	@Test
	public void aCrossOrgParentIsRefusedOnCreate() throws Exception {
		Fixture f = fixture();
		int secretEvents = stored(f.secret()).getUpdateEvents().size();
		RelizaException re = assertThrows(RelizaException.class, () -> fetcherAs(f.writerA(), authorizationService)
				.addRelease(dfe(createInput(f.orgA(), f.featureSet(), "1.0.0", null, f.secret()))));
		assertEquals(notFound(f.secret()), re.getMessage());
		assertNothingWritten(f, secretEvents);
	}

	@Test
	public void theTea5MixedListIsRefusedAsAWhole() throws Exception {
		// The TEA-5 run 1 recipe: an own child next to the other organization's release.
		Fixture f = fixture();
		int secretEvents = stored(f.secret()).getUpdateEvents().size();
		int childEvents = stored(f.childA()).getUpdateEvents().size();
		RelizaException re = assertThrows(RelizaException.class, () -> fetcherAs(f.writerA(), authorizationService)
				.addRelease(dfe(createInput(f.orgA(), f.featureSet(), "1.0.0", null, f.childA(), f.secret()))));
		assertEquals(notFound(f.secret()), re.getMessage(), "only the foreign parent is named");
		assertNothingWritten(f, secretEvents);
		assertEquals(childEvents, stored(f.childA()).getUpdateEvents().size(), "A's child is linked to nothing");
	}

	@Test
	public void aMissingAndAForeignParentAreRefusedAlikeInTheOrderSent() throws Exception {
		Fixture f = fixture();
		int secretEvents = stored(f.secret()).getUpdateEvents().size();
		UUID missing = UUID.randomUUID();
		RelizaException re = assertThrows(RelizaException.class, () -> fetcherAs(f.writerA(), authorizationService)
				.addRelease(dfe(createInput(f.orgA(), f.featureSet(), "1.0.0", null, missing, f.secret()))));
		assertEquals(notFound(missing, f.secret()), re.getMessage());
		assertNothingWritten(f, secretEvents);
	}

	@Test
	public void anOrganizationAndBranchMismatchIsRefusedEvenWhenAuthorizationAllows() throws Exception {
		// A global admin clears authorization before the single-organization rule is asked, so the
		// organization in the input can be another one than the branch's. createRelease refuses it
		// before validating the parents against that organization; without the check this created a
		// release of organization B on A's feature set, with B's release as its parent.
		Fixture f = fixture();
		int secretEvents = stored(f.secret()).getUpdateEvents().size();
		AuthorizationService allowAll = mock(AuthorizationService.class);
		RelizaException re = assertThrows(RelizaException.class, () -> fetcherAs(f.writerA(), allowAll)
				.addRelease(dfe(createInput(f.orgB(), f.featureSet(), "1.0.0", null, f.secret()))));
		assertEquals("Organization and branch mismatch for org = " + f.orgB() + " and branch = " + f.featureSet(),
				re.getMessage());
		assertNothingWritten(f, secretEvents);
	}

	// ---------------- update: updateRelease ----------------

	@Test
	public void anUpdateAddingACrossOrgParentIsRefusedAndSameOrgOnesAreStored() throws Exception {
		Fixture f = fixture();
		ReleaseDatafetcher fetcher = fetcherAs(f.writerA(), authorizationService);
		// DRAFT, since updateRelease runs at DRAFT_ONLY strength.
		UUID product = fetcher.addRelease(dfe(createInput(f.orgA(), f.featureSet(), "1.0.0",
				ReleaseLifecycle.DRAFT, f.childA()))).getUuid();
		assertEquals(List.of(f.childA()), parentUuids(stored(product)));
		int productEvents = stored(product).getUpdateEvents().size();
		int secretEvents = stored(f.secret()).getUpdateEvents().size();

		RelizaException re = assertThrows(RelizaException.class,
				() -> fetcher.updateRelease(dfe(updateInput(product, f.childA(), f.secret()))));
		assertEquals(notFound(f.secret()), re.getMessage());
		assertEquals(List.of(f.childA()), parentUuids(stored(product)), "the stored parents are unchanged");
		assertEquals(productEvents, stored(product).getUpdateEvents().size(), "no update event is recorded");
		assertSecretUnchanged(f, secretEvents);

		fetcher.updateRelease(dfe(updateInput(product, f.childA(), f.childA2())));
		assertEquals(List.of(f.childA(), f.childA2()), parentUuids(stored(product)));
	}

	// ---------------- the programmatic lane ----------------

	@Test
	public void theProgrammaticReleaseInputCarriesNoParents() throws IOException {
		// addReleaseProgrammatic and addReleasesProgrammatic go through the same createRelease door,
		// which validates parents (pinned by ReleaseReferenceOrgValidationTest.createRefusesAnotherOrgsCommitAndParent),
		// and their input has no field to carry one. Adding parentReleases to ReleaseInputProg must
		// route it through createRelease and extend this class with the programmatic cases.
		String schema;
		try (InputStream in = getClass().getResourceAsStream("/schema/programmatic.graphqls")) {
			schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		int start = schema.indexOf("input ReleaseInputProg {");
		assertTrue(start >= 0, "ReleaseInputProg is declared in programmatic.graphqls");
		String block = schema.substring(start, schema.indexOf("\n}", start));
		assertFalse(block.contains("parentReleases"), "ReleaseInputProg must not carry parentReleases");
		assertFalse(schema.contains("ParentReleaseInput"), "no programmatic input may carry a parent release");
	}
}
