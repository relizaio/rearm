/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.ProgrammaticAuthenticationFilter;
import io.reliza.ws.ProgrammaticGraphQlConfig;
import io.reliza.ws.oss.TestInitializer;

/**
 * getlatestrelease for a COMPONENT with no branch given. The fetcher defaulted an omitted branch
 * to the base feature set for products only; a component kept a null name, findBranchByName passed
 * it to Utils.cleanBranch, and the NullPointerException reached the caller as "Internal server
 * error". The fetcher now defaults a component to its base branch, and the lookup itself no longer
 * throws on a missing name.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class LatestReleaseOmittedBranchTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private LicenseStatus licenseStatus;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private static final String LATEST = "query($r: GetLatestReleaseInput!) {"
			+ " getLatestReleaseProgrammatic(release: $r) { uuid version branch } }";

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	/** The programmatic endpoint as the CLI's getlatestrelease reaches it, past the filter that verified the key. */
	private ExecutionResult as(ApiKey key, String query, Map<String, Object> vars) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.88"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return dgsQueryExecutor.execute(query, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	/**
	 * The fetcher path itself: getLatestReleaseProgrammatic for a component with the branch omitted returns the
	 * latest release on the component's base branch, not a newer one on a feature branch and not nothing. Before
	 * the fix the fetcher kept a null branch for a component; with only the lookup guard that would come back empty.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void anOmittedBranchGetsTheComponentsBaseBranchLatestThroughTheFetcher() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component c = componentService.createComponent("omitted-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		Branch base = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		UUID onBase = ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(base.getUuid()).version("1.0.0")
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU).getUuid();
		Branch feature = branchService.createBranch("feature-" + UUID.randomUUID().toString().substring(0, 8),
				c.getUuid(), BranchType.FEATURE, WU);
		UUID onFeature = ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(feature.getUuid()).version("2.0.0")
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU).getUuid();
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.ORGANIZATION, org.getUuid(),
				UUID.randomUUID().toString(), "latest-release reader", WU);

		ExecutionResult r = as(key, LATEST, Map.of("r", Map.of("component", c.getUuid().toString())));
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		Map<String, Object> latest = (Map<String, Object>) ((Map<String, Object>) r.getData())
				.get("getLatestReleaseProgrammatic");
		assertNotNull(latest, "an omitted branch resolves the component's base branch, so a release comes back");
		assertEquals(onBase.toString(), latest.get("uuid"), "the base branch's latest, not the newer feature release "
				+ onFeature);
		assertEquals(base.getUuid().toString(), latest.get("branch"));
	}

	@Test
	public void aMissingBranchNameFindsNoBranchInsteadOfThrowing() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component c = componentService.createComponent("omitted-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		assertTrue(branchService.findBranchByName(c.getUuid(), null).isEmpty());
		assertTrue(branchService.findBranchByName(c.getUuid(), "  ").isEmpty());
	}

	/** What the fetcher now resolves an omitted component branch to: the base branch's latest release. */
	@Test
	public void theBaseBranchNameFindsTheComponentsLatestRelease() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component c = componentService.createComponent("omitted-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		Branch base = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(base.getUuid()).version("1.0.0")
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU).getUuid();
		String baseName = BranchData.branchDataFromDbRecord(base).getName();
		// The overload without the approved-environment filter: CE's service has it too.
		Optional<ReleaseData> latest = ossReleaseService.getReleasePerProductComponent(org.getUuid(), c.getUuid(),
				null, baseName, null, null);
		assertEquals(release, latest.orElseThrow().getUuid());
	}
}
