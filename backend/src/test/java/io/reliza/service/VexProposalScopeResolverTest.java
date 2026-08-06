/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisScope;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.VexStatementProposalWebDto;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Pins the scope resolution that makes VEX proposals readable in the UI: a
 * proposal stores only {@code scope} + {@code scopeUuid}, so without this the
 * inbox and the review page can only show a bare UUID.
 *
 * <p>Covers the fill rules per scope kind, the "most specific name" the UI uses
 * as link text, and the degradation contract -- a scope pointing at something
 * that no longer resolves must leave the fields null rather than fail the query.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class VexProposalScopeResolverTest {

	@Autowired private VexProposalScopeResolver resolver;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private TestInitializer testInitializer;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private record Fx(UUID org, UUID component, String componentName, UUID branch, UUID release, String version) {}

	private Fx fixture() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String name = "vex-scope-" + UUID.randomUUID().toString().substring(0, 8);
		UUID componentUuid = componentService.createComponent(CreateComponentDto.builder()
				.organization(org.getUuid())
				.name(name)
				.type(ComponentType.COMPONENT)
				.versionSchema("semver")
				.featureBranchVersioning("Branch.Micro")
				.build(), WU).getUuid();
		Branch branch = branchService.findBranchByName(componentUuid, "main", true, WU).get();
		UUID releaseUuid = ossReleaseService.createRelease(ReleaseDto.builder()
				.component(componentUuid)
				.branch(branch.getUuid())
				.org(org.getUuid())
				.status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseData.ReleaseLifecycle.ASSEMBLED)
				.version("1.4.2")
				.build(), WU).getUuid();
		return new Fx(org.getUuid(), componentUuid, name, branch.getUuid(), releaseUuid, "1.4.2");
	}

	private static VexStatementProposalWebDto proposal(UUID org, AnalysisScope scope, UUID scopeUuid) {
		VexStatementProposalWebDto dto = new VexStatementProposalWebDto();
		dto.setUuid(UUID.randomUUID());
		dto.setOrg(org);
		dto.setScope(scope);
		dto.setScopeUuid(scopeUuid);
		return dto;
	}

	@Test
	public void releaseScopeFillsAllThreeLevels() throws RelizaException {
		Fx fx = fixture();
		var dto = proposal(fx.org(), AnalysisScope.RELEASE, fx.release());
		resolver.resolveScopeNames(dto);

		assertEquals(fx.release(), dto.getScopeReleaseUuid());
		assertEquals(fx.version(), dto.getScopeReleaseVersion(),
				"release version is the most specific name -- the UI's link text");
		assertEquals(fx.branch(), dto.getScopeBranchUuid());
		assertEquals("main", dto.getScopeBranchName());
		assertEquals(fx.component(), dto.getScopeComponentUuid());
		assertEquals(fx.componentName(), dto.getScopeComponentName());
	}

	@Test
	public void branchScopeFillsBranchAndItsComponentButNoRelease() throws RelizaException {
		Fx fx = fixture();
		var dto = proposal(fx.org(), AnalysisScope.BRANCH, fx.branch());
		resolver.resolveScopeNames(dto);

		assertNull(dto.getScopeReleaseUuid(), "a BRANCH scope names no release");
		assertEquals("main", dto.getScopeBranchName());
		assertEquals(fx.component(), dto.getScopeComponentUuid(),
				"the component is reachable only via the branch on this path");
		assertEquals(fx.componentName(), dto.getScopeComponentName());
	}

	@Test
	public void componentScopeFillsComponentOnly() throws RelizaException {
		Fx fx = fixture();
		var dto = proposal(fx.org(), AnalysisScope.COMPONENT, fx.component());
		resolver.resolveScopeNames(dto);

		assertEquals(fx.componentName(), dto.getScopeComponentName());
		assertNull(dto.getScopeBranchName());
		assertNull(dto.getScopeReleaseVersion());
	}

	@Test
	public void orgScopeFillsNothing() throws RelizaException {
		Fx fx = fixture();
		var dto = proposal(fx.org(), AnalysisScope.ORG, fx.org());
		resolver.resolveScopeNames(dto);

		assertNull(dto.getScopeComponentName());
		assertNull(dto.getScopeBranchName());
		assertNull(dto.getScopeReleaseVersion());
	}

	@Test
	public void unresolvableScopeLeavesNullsAndDoesNotThrow() throws RelizaException {
		Fx fx = fixture();
		// Deleted / invisible release: display degrades to the raw uuid in the UI.
		var dto = proposal(fx.org(), AnalysisScope.RELEASE, UUID.randomUUID());
		resolver.resolveScopeNames(dto);

		assertNull(dto.getScopeReleaseVersion());
		assertNull(dto.getScopeComponentName());
	}

	@Test
	public void mixedBatchResolvesEveryScopeKindInOnePass() throws RelizaException {
		Fx a = fixture();
		Fx b = fixture();
		List<VexStatementProposalWebDto> batch = List.of(
				proposal(a.org(), AnalysisScope.RELEASE, a.release()),
				proposal(a.org(), AnalysisScope.BRANCH, a.branch()),
				proposal(b.org(), AnalysisScope.COMPONENT, b.component()),
				proposal(b.org(), AnalysisScope.ORG, b.org()),
				proposal(b.org(), AnalysisScope.RELEASE, UUID.randomUUID()));
		resolver.resolveScopeNames(batch);

		assertEquals(a.version(), batch.get(0).getScopeReleaseVersion());
		assertEquals(a.componentName(), batch.get(0).getScopeComponentName());
		assertEquals("main", batch.get(1).getScopeBranchName());
		assertEquals(b.componentName(), batch.get(2).getScopeComponentName());
		assertNull(batch.get(3).getScopeComponentName());
		assertNull(batch.get(4).getScopeReleaseVersion());
	}
}
