/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.reliza.common.Utils;
import io.reliza.common.Utils.RootComponentMergeMode;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.tea.Rebom.MissingSbomComponent;
import io.reliza.model.tea.Rebom.MissingSbomReason;
import io.reliza.model.tea.Rebom.RebomOptions;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-15 (T-6; SCORE-9 T-5): a product of two component releases, one of them without an SBOM,
 * against the test database with rebom mocked. The product merge request names the bare release;
 * the merged document rebom answers keeps its placeholder marker, its count and its unknown
 * composition through the default JSON export and through the dialog's internal-metadata strip.
 */
class ProductMissingSbomExportIntegrationTest extends SbomScoreGraphqlTestBase {

	private static final AtomicInteger VERSION = new AtomicInteger();
	private static final String MARKER = "reliza:sbom:missing";
	private static final String COUNT = "reliza:export:componentsWithoutSbom";
	/** A ReARM-internal marker, which includeInternalMetadata=false does remove. */
	private static final String INTERNAL_MARKER = "reliza:containerSafeVersion";
	/** What the egress writes for an organization that does not disclose support data; not this task's. */
	private static final String DISCLOSURE = "reliza:support:disclosure=provenance-stripped-no-disclosure";
	/** SCORE-33: the record every merged JSON export of an org without a signing key ends with. */
	private static final String SIGNED_FALSE = "reliza:export:signed=false";
	private static final String NO_KEY = "reliza:export:unsignedReason=NO_KEY";

	/** The product merge as rebom stores it, for the bare release {@code bare}. */
	private static String productDoc(UUID bare) {
		String ref = "urn:rearm:release:" + bare;
		return "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
				+ "\"metadata\":{\"component\":{\"type\":\"application\",\"name\":\"product\",\"bom-ref\":\"root\"},"
				+ "\"properties\":[{\"name\":\"" + COUNT + "\",\"value\":\"1\"},"
				+ "{\"name\":\"" + INTERNAL_MARKER + "\",\"value\":\"1.2.3\"}]},"
				+ "\"components\":[{\"type\":\"library\",\"name\":\"lib\",\"version\":\"1.0\",\"bom-ref\":\"lib\"},"
				+ "{\"type\":\"library\",\"name\":\"bare\",\"version\":\"1.0\",\"bom-ref\":\"" + ref + "\","
				+ "\"properties\":[{\"name\":\"" + MARKER + "\",\"value\":\"NO_SBOM_ARTIFACT\"}]}],"
				+ "\"dependencies\":[{\"ref\":\"root\",\"dependsOn\":[\"lib\",\"" + ref + "\"]},"
				+ "{\"ref\":\"" + ref + "\",\"dependsOn\":[]}],"
				+ "\"compositions\":[{\"aggregate\":\"unknown\",\"dependencies\":[\"" + ref + "\"]}]}";
	}

	private record Product(Fixture f, UUID release, UUID bare) {}

	private UUID productMerge;
	private UUID bareRelease;

	@BeforeEach
	void rebomServesTheProductMerge() throws Exception {
		productMerge = UUID.randomUUID();
		when(rebomService.mergeAndStoreBoms(anyList(), any(), any())).thenAnswer(inv -> {
			RebomOptions options = inv.getArgument(1);
			return RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT == options.rootComponentMergeMode()
					? productMerge : UUID.randomUUID();
		});
		when(rebomService.findBomByIdJson(any(), any())).thenAnswer(inv -> Utils.OM.readTree(
				productMerge.equals(inv.getArgument(0)) ? productDoc(bareRelease) : DOC));
	}

	/** The base fixture's release (with a stored BOM), a release without any artifact, and a product of both. */
	private Product product() throws RelizaException {
		Fixture f = fixture();
		Organization org = f.org();
		Component bareComponent = componentService.createComponent("score15-bare-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		UUID bare = release(org, bareComponent, List.of());
		bareRelease = bare;
		Component productComponent = componentService.createComponent("score15-product-" + UUID.randomUUID(),
				org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		var branch = branchService.getBaseBranchOfComponent(productComponent.getUuid()).orElseThrow();
		UUID product = ossReleaseService.createRelease(ReleaseDto.builder().component(productComponent.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version("26.10." + VERSION.incrementAndGet())
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.parentReleases(new ArrayList<>(List.of(ParentRelease.minimalParentReleaseFactory(f.release(), null),
						ParentRelease.minimalParentReleaseFactory(bare, null))))
				.build(), WU).getUuid();
		return new Product(f, product, bare);
	}

	private String export(Product p, Object... pairs) {
		return data(as(p.f().downloader(), EXPORT_RELEASE, releaseVars(p.release(), pairs)), "releaseSbomExport");
	}

	private static List<String> metadataProperties(JsonNode doc) {
		List<String> out = new ArrayList<>();
		for (JsonNode p : doc.path("metadata").path("properties")) out.add(p.get("name").asString() + "=" + p.get("value").asString());
		return out;
	}

	private RebomOptions productMergeRequest() {
		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService, atLeastOnce()).mergeAndStoreBoms(anyList(), sent.capture(), any());
		List<RebomOptions> product = sent.getAllValues().stream()
				.filter(o -> RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT == o.rootComponentMergeMode()).toList();
		assertEquals(1, product.size(), sent.getAllValues().toString());
		return product.get(0);
	}

	@Test
	void theProductMergeRequestNamesTheReleaseWithoutAnSbom() throws Exception {
		Product p = product();
		export(p);

		RebomOptions sent = productMergeRequest();
		List<MissingSbomComponent> missing = sent.missingSbomComponents();
		assertEquals(1, missing.size(), String.valueOf(missing));
		assertEquals(p.bare().toString(), missing.get(0).releaseUuid());
		assertEquals(MissingSbomReason.NO_SBOM_ARTIFACT, missing.get(0).reason());
		assertEquals("library", missing.get(0).type());
		assertEquals(OrganizationData.orgDataFromDbRecord(p.f().org()).getName(), missing.get(0).supplierName());
		assertNull(missing.get(0).purl());
		verify(rebomService).mergeAndStoreBoms(anyList(), eq(sent), eq(p.f().org().getUuid()));
	}

	@Test
	void theDefaultExportCarriesTheMarkerTheCountAndTheComposition() throws Exception {
		Product p = product();
		JsonNode exported = Utils.OM.readTree(export(p));

		// SCORE-33: the org has no signing key, so the export ends in the unsigned record
		assertEquals(List.of(COUNT + "=1", INTERNAL_MARKER + "=1.2.3", DISCLOSURE, SIGNED_FALSE, NO_KEY),
				metadataProperties(exported));
		assertEquals(Utils.OM.readTree("[{\"name\":\"" + MARKER + "\",\"value\":\"NO_SBOM_ARTIFACT\"}]"),
				exported.get("components").get(1).get("properties"));
		assertEquals(Utils.OM.readTree("[{\"aggregate\":\"unknown\",\"dependencies\":[\"urn:rearm:release:" + p.bare() + "\"]}]"),
				exported.get("compositions"));
	}

	// run 1 T-1 of SCORE-11: the Export Release BOM dialog sends includeInternalMetadata=false by default
	@Test
	void theInternalMetadataStripKeepsTheMarkerTheCountAndTheComposition() throws Exception {
		Product p = product();
		JsonNode exported = Utils.OM.readTree(export(p, "im", false));

		assertEquals(List.of(COUNT + "=1", DISCLOSURE, SIGNED_FALSE, NO_KEY), metadataProperties(exported),
				"the strip ran and kept the count");
		assertEquals(Utils.OM.readTree("[{\"name\":\"" + MARKER + "\",\"value\":\"NO_SBOM_ARTIFACT\"}]"),
				exported.get("components").get(1).get("properties"));
		assertEquals(Utils.OM.readTree("[{\"aggregate\":\"unknown\",\"dependencies\":[\"urn:rearm:release:" + p.bare() + "\"]}]"),
				exported.get("compositions"));
	}
}
