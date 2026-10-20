/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.CdxType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData.BelongsToOrganization;
import io.reliza.model.DeliverableData.SoftwareDeliverableMetadata;
import io.reliza.model.Organization;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.DeliverableDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.tea.Rebom.BomHash;
import io.reliza.model.tea.Rebom.MissingSbomComponent;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.tea.Rebom.ReleaseNodeFacts;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.service.BranchService;
import io.reliza.service.DeliverableService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.RebomService.BomMediaType;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.ReleaseRebomService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.VariantService;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-30 (T-15, ADR-2, ADR-3, ADR-7, ADR-8, ADR-9) against the test database with rebom stubbed:
 * a release's facts, read from the deliverables it ships (its base variant's outbound ones), its
 * component and its organization as stored, reach the merge request; a deliverable it consumes
 * gives it no hash; an EXTERNAL component's release names no supplier; a placeholder carries its
 * release's digests; the stored rows carry neither; and the default JSON export serves the root
 * rebom filled through the strip and the injection as it came.
 */
class ReleaseServiceReleaseNodeFactsIntegrationTest extends SbomScoreGraphqlTestBase {

	private static final AtomicInteger VERSION = new AtomicInteger();
	private static final String DIGEST = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
	private static final String BARE_DIGEST = "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";
	private static final String SHA_1 = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3";

	@Autowired private DeliverableService deliverableService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ReleaseRebomService releaseRebomService;
	@Autowired private BranchService branches;
	@Autowired private VariantService variantService;

	private Component component(Organization org, ComponentType type) throws RelizaException {
		return componentService.createComponent("score30-" + UUID.randomUUID(), org.getUuid(), type, "semver",
				"Branch.Micro", null, WU);
	}

	/** A component the organization records as third-party software. */
	private Component externalComponent(Organization org) throws RelizaException {
		return componentService.createComponent(CreateComponentDto.builder().name("score30-ext-" + UUID.randomUUID())
				.organization(org.getUuid()).type(ComponentType.COMPONENT).versionSchema("semver")
				.featureBranchVersioning("Branch.Micro").isInternal(BelongsToOrganization.EXTERNAL).build(), WU);
	}

	/** {@code release} ships {@code deliverable}: an outbound deliverable of its base variant. */
	private void ships(UUID release, UUID deliverable) {
		UUID baseVariant = variantService.findBaseVariantForRelease(release).orElseThrow().getUuid();
		variantService.addOutboundDeliverables(List.of(deliverable), baseVariant, WU);
	}

	/** A deliverable on the component's base branch with the given digest records. */
	private UUID deliverable(Component c, DigestRecord... records) throws RelizaException {
		var branch = branches.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		return deliverableService.createDeliverable(DeliverableDto.builder()
				.displayIdentifier("score30-" + UUID.randomUUID()).branch(branch.getUuid()).type(CdxType.CONTAINER)
				.softwareMetadata(SoftwareDeliverableMetadata.builder()
						.digestRecords(new LinkedHashSet<>(List.of(records))).build())
				.build(), WU).getUuid();
	}

	/** A release of {@code c}; {@code consumed} are the deliverables it consumes (inbound). */
	private UUID release(Organization org, Component c, List<UUID> artifacts, List<UUID> consumed,
			List<UUID> children) throws RelizaException {
		var branch = branches.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		return ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version("30.0." + VERSION.incrementAndGet())
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.artifacts(new ArrayList<>(artifacts)).inboundDeliverables(new ArrayList<>(consumed))
				.parentReleases(children.stream().map(r -> ParentRelease.minimalParentReleaseFactory(r, null)).toList())
				.build(), WU).getUuid();
	}

	private String orgName(Organization org) {
		return getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow().getName();
	}

	private String export(UUID release, Organization org) throws Exception {
		return releaseService.exportReleaseSbom(release, null, null, null, BomStructureType.FLAT, BomMediaType.JSON,
				org.getUuid(), WU, null);
	}

	private List<RebomOptions> mergeRequests(Organization org) {
		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService, atLeastOnce()).mergeAndStoreBoms(anyList(), sent.capture(), eq(org.getUuid()));
		return sent.getAllValues();
	}

	private List<ReleaseBom> storedRows(UUID release) {
		ReleaseData rd = sharedReleaseService.getReleaseData(release).orElseThrow();
		return releaseRebomService.getReleaseBoms(rd);
	}

	@Test
	void theMergeRequestOfAReleaseShippingOneHashedDeliverableCarriesTheOrganizationAndItsDigest() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component c = component(org, ComponentType.COMPONENT);
		UUID d = deliverable(c, new DigestRecord(TeaChecksumType.SHA_256, DIGEST), new DigestRecord(TeaChecksumType.SHA_1, SHA_1));
		UUID bom = artifact(org.getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
		UUID release = release(org, c, List.of(bom), List.of(), List.of());
		ships(release, d);

		export(release, org);

		List<RebomOptions> sent = mergeRequests(org);
		assertEquals(1, sent.size());
		assertEquals(new ReleaseNodeFacts(orgName(org), List.of(new BomHash("SHA-256", DIGEST), new BomHash("SHA-1", SHA_1)), null, null),
				sent.get(0).releaseNode());
		List<ReleaseBom> rows = storedRows(release);
		assertEquals(1, rows.size());
		assertNull(rows.get(0).rebomMergeOptions().releaseNode(), "the facts are not part of the cache key");
		assertNull(rows.get(0).rebomMergeOptions().missingSbomComponents());
	}

	@Test
	void aReleaseThatOnlyConsumesAHashedDeliverableGetsNoHash() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component supplier = component(org, ComponentType.COMPONENT);
		UUID consumed = deliverable(supplier, new DigestRecord(TeaChecksumType.SHA_256, BARE_DIGEST));
		Component c = component(org, ComponentType.COMPONENT);
		UUID bom = artifact(org.getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
		UUID release = release(org, c, List.of(bom), List.of(consumed), List.of());

		export(release, org);

		List<RebomOptions> sent = mergeRequests(org);
		assertEquals(1, sent.size());
		assertEquals(new ReleaseNodeFacts(orgName(org), null, null, null), sent.get(0).releaseNode(),
				"a consumed deliverable's digest is not the release's");
	}

	@Test
	void aProductListsAnSbomLessReleaseWithItsDeliverablesDigestAndSendsItsOwnFacts() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component withBom = component(org, ComponentType.COMPONENT);
		UUID hashed = deliverable(withBom, new DigestRecord(TeaChecksumType.SHA_256, DIGEST));
		UUID bom = artifact(org.getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
		UUID first = release(org, withBom, List.of(bom), List.of(), List.of());
		ships(first, hashed);
		Component bareComponent = component(org, ComponentType.COMPONENT);
		UUID bareDeliverable = deliverable(bareComponent, new DigestRecord(TeaChecksumType.SHA_256, BARE_DIGEST));
		UUID bare = release(org, bareComponent, List.of(), List.of(), List.of());
		ships(bare, bareDeliverable);
		Component productComponent = component(org, ComponentType.PRODUCT);
		UUID product = release(org, productComponent, List.of(), List.of(), List.of(first, bare));

		export(product, org);

		List<RebomOptions> sent = mergeRequests(org);
		assertEquals(2, sent.size(), sent.toString());
		RebomOptions componentMerge = sent.get(0);
		RebomOptions productMerge = sent.get(1);
		assertEquals(new ReleaseNodeFacts(orgName(org), List.of(new BomHash("SHA-256", DIGEST)), null, null), componentMerge.releaseNode());
		assertEquals(new ReleaseNodeFacts(orgName(org), null, null, null), productMerge.releaseNode(),
				"the product release has no deliverable: its supplier alone");
		List<MissingSbomComponent> missing = productMerge.missingSbomComponents();
		assertEquals(1, missing.size());
		assertEquals(bare.toString(), missing.get(0).releaseUuid());
		assertEquals(List.of(new BomHash("SHA-256", BARE_DIGEST)), missing.get(0).hashes());
		assertEquals(orgName(org), missing.get(0).supplierName());
		for (ReleaseBom row : storedRows(product)) {
			assertNull(row.rebomMergeOptions().releaseNode());
			assertNull(row.rebomMergeOptions().missingSbomComponents());
		}
	}

	@Test
	void anExternalComponentsReleaseAndItsPlaceholderNameNoSupplier() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component withBom = externalComponent(org);
		UUID bom = artifact(org.getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
		UUID first = release(org, withBom, List.of(bom), List.of(), List.of());
		Component bareComponent = externalComponent(org);
		UUID bareDeliverable = deliverable(bareComponent, new DigestRecord(TeaChecksumType.SHA_256, BARE_DIGEST));
		UUID bare = release(org, bareComponent, List.of(), List.of(), List.of());
		ships(bare, bareDeliverable);
		Component productComponent = component(org, ComponentType.PRODUCT);
		UUID product = release(org, productComponent, List.of(), List.of(), List.of(first, bare));

		export(product, org);

		List<RebomOptions> sent = mergeRequests(org);
		assertEquals(2, sent.size(), sent.toString());
		assertNull(sent.get(0).releaseNode(), "an EXTERNAL release without a deliverable has no facts at all");
		assertEquals(new ReleaseNodeFacts(orgName(org), null, null, null), sent.get(1).releaseNode(), "the product is the org's own");
		MissingSbomComponent placeholder = sent.get(1).missingSbomComponents().get(0);
		assertNull(placeholder.supplierName());
		assertEquals(orgName(org), placeholder.group());
		assertEquals(List.of(new BomHash("SHA-256", BARE_DIGEST)), placeholder.hashes());
	}

	@Test
	void theDefaultJsonExportServesTheFilledRootAsRebomStoredIt() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component c = component(org, ComponentType.COMPONENT);
		UUID bom = artifact(org.getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
		UUID release = release(org, c, List.of(bom), List.of(), List.of());
		String filled = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
				+ "\"metadata\":{\"component\":{\"type\":\"application\",\"name\":\"api\",\"version\":\"1.0.0\","
				+ "\"purl\":\"pkg:generic/Example/api@1.0.0\",\"bom-ref\":\"pkg:generic/Example/api@1.0.0\","
				+ "\"supplier\":{\"name\":\"Example Org\"},"
				+ "\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"" + DIGEST + "\"},{\"alg\":\"SHA-1\",\"content\":\"" + SHA_1 + "\"}]}},"
				+ "\"components\":[{\"type\":\"library\",\"name\":\"a\",\"version\":\"1.0.0\",\"purl\":\"pkg:npm/a@1.0.0\","
				+ "\"supplier\":{\"name\":\"a maintainers\"},\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"" + BARE_DIGEST + "\"}]}]}";
		when(rebomService.findBomByIdJson(any(), any())).thenAnswer(inv -> Utils.OM.readTree(filled));

		JsonNode served = Utils.OM.readTree(export(release, org));

		JsonNode expected = Utils.OM.readTree(filled);
		assertEquals(expected.get("metadata").get("component").get("supplier"), served.get("metadata").get("component").get("supplier"));
		assertEquals(expected.get("metadata").get("component").get("hashes"), served.get("metadata").get("component").get("hashes"));
		assertEquals(expected.get("components").get(0).get("supplier"), served.get("components").get(0).get("supplier"));
		assertEquals(expected.get("components").get(0).get("hashes"), served.get("components").get(0).get("hashes"));
		assertFalse(served.get("metadata").get("component").has("licenses"));
	}
}
