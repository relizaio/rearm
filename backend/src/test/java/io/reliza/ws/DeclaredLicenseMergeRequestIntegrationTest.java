/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData.BelongsToOrganization;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.Rebom.MissingSbomComponent;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.Rebom.ReleaseNodeFacts;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.RebomService.BomMediaType;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.ReleaseRebomService;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedReleaseService;

/**
 * SCORE-39 T-17 (ADR-3, ADR-4, ADR-5), the merge half, against the test database with rebom stubbed:
 * what each level declares, and the component supplier, reach the merge request on the release node
 * and on a product's placeholder, the product root carries its own declaration, and a stored row
 * keyed by the facts serves only while they hold: an override change merges again and leaves one row
 * for the request, an unchanged export is served.
 */
class DeclaredLicenseMergeRequestIntegrationTest extends SbomScoreGraphqlTestBase {

	private static final AtomicInteger VERSION = new AtomicInteger();
	private static final String ACME_URL = "https://acme.example/licence";
	private static final String ORG_URL = "https://org.example/licence";

	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private OrganizationService organizationService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ReleaseRebomService releaseRebomService;

	private void orgDefault(Organization org, String expression, String url) throws RelizaException {
		OrganizationData.Settings patch = new OrganizationData.Settings();
		patch.setDefaultLicenseExpression(expression);
		patch.setDefaultLicenseUrl(url);
		organizationService.updateSettings(org.getUuid(), patch, WU);
	}

	private Component component(Organization org, ComponentType type, BelongsToOrganization isInternal,
			String expression, String url, String supplier) throws RelizaException {
		return componentService.createComponent(CreateComponentDto.builder().name("score39-" + UUID.randomUUID())
				.organization(org.getUuid()).type(type).versionSchema("semver").featureBranchVersioning("Branch.Micro")
				.isInternal(isInternal).licenseExpression(expression).licenseUrl(url).supplierName(supplier).build(), WU);
	}

	private UUID release(Organization org, Component c, List<UUID> artifacts, List<UUID> children, String override)
			throws RelizaException {
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		return ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version("39.0." + VERSION.incrementAndGet())
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.artifacts(new ArrayList<>(artifacts)).licenseExpression(override)
				.parentReleases(children.stream().map(r -> ParentRelease.minimalParentReleaseFactory(r, null)).toList())
				.build(), WU).getUuid();
	}

	private UUID bom(Organization org) throws RelizaException {
		return artifact(org.getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
	}

	private String orgName(Organization org) {
		return getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow().getName();
	}

	private void export(UUID release, Organization org) throws Exception {
		releaseService.exportReleaseSbom(release, null, null, null, BomStructureType.FLAT, BomMediaType.JSON,
				org.getUuid(), WU, null);
	}

	private List<RebomOptions> mergeRequests(Organization org) throws Exception {
		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService, atLeastOnce()).mergeAndStoreBoms(anyList(), sent.capture(), eq(org.getUuid()));
		return sent.getAllValues();
	}

	private long mergeCount() {
		return mockingDetails(rebomService).getInvocations().stream()
				.filter(i -> "mergeAndStoreBoms".equals(i.getMethod().getName())).count();
	}

	private List<ReleaseBom> storedRows(UUID release) {
		ReleaseData rd = sharedReleaseService.getReleaseData(release).orElseThrow();
		return releaseRebomService.getReleaseBoms(rd);
	}

	@Test
	void theOrganizationDefaultReachesTheReleaseNode() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		orgDefault(org, "AGPL-3.0-only", ORG_URL);
		UUID release = release(org, component(org, ComponentType.COMPONENT, null, null, null, null), List.of(bom(org)),
				List.of(), null);

		export(release, org);

		assertEquals(new ReleaseNodeFacts(orgName(org), null, "AGPL-3.0-only", ORG_URL), mergeRequests(org).get(0).releaseNode());
	}

	@Test
	void theComponentLicenceAndSupplierReachTheReleaseNode() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		orgDefault(org, "AGPL-3.0-only", ORG_URL);
		Component c = component(org, ComponentType.COMPONENT, null, "LicenseRef-Acme-Commercial", ACME_URL, "Acme Corp");
		UUID release = release(org, c, List.of(bom(org)), List.of(), null);

		export(release, org);

		assertEquals(new ReleaseNodeFacts("Acme Corp", null, "LicenseRef-Acme-Commercial", ACME_URL),
				mergeRequests(org).get(0).releaseNode());
		List<ReleaseBom> rows = storedRows(release);
		assertEquals(1, rows.size());
		assertEquals(ReleaseNodeFacts.key(new ReleaseNodeFacts("Acme Corp", null, "LicenseRef-Acme-Commercial", ACME_URL)),
				rows.get(0).rebomMergeOptions().releaseNodeFactsKey());
		assertEquals(Integer.valueOf(ReleaseService.MERGE_VERSION), rows.get(0).rebomMergeOptions().mergeVersion());
	}

	@Test
	void theReleaseOverrideReachesTheReleaseNodeWithoutAUrl() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component c = component(org, ComponentType.COMPONENT, null, "LicenseRef-Acme-Commercial", ACME_URL, null);
		UUID release = release(org, c, List.of(bom(org)), List.of(), "mit");

		export(release, org);

		assertEquals(new ReleaseNodeFacts(orgName(org), null, "MIT", null), mergeRequests(org).get(0).releaseNode());
	}

	@Test
	void aProductsPlaceholderAndRootCarryTheirDeclarations() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		orgDefault(org, "AGPL-3.0-only", ORG_URL);
		UUID first = release(org, component(org, ComponentType.COMPONENT, null, null, null, null), List.of(bom(org)),
				List.of(), null);
		Component external = component(org, ComponentType.COMPONENT, BelongsToOrganization.EXTERNAL, null, null,
				"Upstream Vendor");
		UUID bare = release(org, external, List.of(), List.of(), null);
		Component productComponent = component(org, ComponentType.PRODUCT, null, "Apache-2.0", ACME_URL, null);
		UUID product = release(org, productComponent, List.of(), List.of(first, bare), null);

		export(product, org);

		List<RebomOptions> sent = mergeRequests(org);
		assertEquals(2, sent.size(), sent.toString());
		RebomOptions productMerge = sent.get(1);
		assertEquals(new ReleaseNodeFacts(orgName(org), null, "Apache-2.0", ACME_URL), productMerge.releaseNode(),
				"the product root carries the product component's declaration");
		MissingSbomComponent placeholder = productMerge.missingSbomComponents().get(0);
		assertEquals(bare.toString(), placeholder.releaseUuid());
		assertEquals("Upstream Vendor", placeholder.supplierName());
		assertEquals("AGPL-3.0-only", placeholder.licenseExpression());
		assertEquals(ORG_URL, placeholder.licenseUrl());
	}

	@Test
	void anOverrideChangeMergesAgainAndLeavesOneRowAndAnUnchangedExportIsServed() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID release = release(org, component(org, ComponentType.COMPONENT, null, null, null, null), List.of(bom(org)),
				List.of(), null);

		export(release, org);
		export(release, org);
		assertEquals(1, mergeCount(), "the second export is served from the row");

		ossReleaseService.updateRelease(ReleaseDto.builder().uuid(release).licenseExpression("MIT").build(), WU);
		// TEA-14 decision 2: the reconcile after the commit also merges the changed release itself under the new
		// facts. Whether the export below is then served from that row or merges once more depends on where the
		// reconcile ran (on a pool thread it writes its row; this context's synchronous executor runs it inside the
		// after-commit callback, where its row write does not commit), so the counts here are relative.
		long beforeExport = mergeCount();
		assertTrue(beforeExport >= 2, "the reconcile merged the release under the new facts: " + beforeExport);
		export(release, org);

		assertTrue(mergeCount() - beforeExport <= 1, "the row of the old facts no longer serves");
		List<RebomOptions> requests = mergeRequests(org);
		assertEquals(new ReleaseNodeFacts(orgName(org), null, "MIT", null), requests.get(requests.size() - 1).releaseNode());
		long served = mergeCount();
		List<ReleaseBom> rows = storedRows(release);
		assertEquals(1, rows.size(), "one row for the request: " + rows);
		assertEquals(ReleaseNodeFacts.key(new ReleaseNodeFacts(orgName(org), null, "MIT", null)),
				rows.get(0).rebomMergeOptions().releaseNodeFactsKey());

		export(release, org);
		assertEquals(served, mergeCount(), "served again once the row matches");
		assertNull(rows.get(0).rebomMergeOptions().releaseNode(), "the facts themselves are never stored");
	}
}
