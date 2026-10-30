/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.CdxType;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.DeliverableDto;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.service.DeliverableService;
import io.reliza.service.VariantService;

/**
 * SCORE-52 T-27 (design 3.8 table), one case per family of container: S on an inbound deliverable
 * of A's release, on a deliverable of a product's feature set (held by the product), on an external
 * deliverable reached through a variant of A's release, and nested in a VDR artifact on A's release.
 * In each, B's (or a component's) key is refused the next version and the holder's key passes it.
 */
class SbomUpdateGuardFamilyTest extends SbomUpdateGuardProgrammaticTestBase {

	@Autowired private DeliverableService deliverableService;
	@Autowired private VariantService variantService;

	private static String urn(UUID serial) {
		return "urn:uuid:" + serial;
	}

	private UUID serialOf(UUID row) {
		return artifactService.getArtifactData(row).orElseThrow().getInternalBom().id();
	}

	private void bRefusedAndAPasses(World w, UUID serial) throws Exception {
		int stored = rebom.versionsStored(serial);
		assertRefusedUpload(() -> progUpload(componentKey(w, w.b()), w.releaseB(), cdx(urn(serial), 5)));
		progUpload(componentKey(w, w.a()), w.releaseA(), cdx(urn(serial), 5));
		assertEquals(stored + 1, rebom.versionsStored(serial));
	}

	@Test
	void anInboundDeliverableOfAsRelease() throws Exception {
		World w = world();
		UUID s = UUID.randomUUID();
		Map<String, Object> input = releaseInputOf(w.a(), "52.27.in");
		input.put("inboundDeliverables", new ArrayList<>(List.of(map("displayIdentifier",
				"score52-idel-" + UUID.randomUUID(), "type", CdxType.CONTAINER.name(), "artifacts",
				entries(cdx(urn(s), 1))))));
		progReleases(componentKey(w, w.a()), input);
		assertEquals(Set.of(w.a().getUuid()),
				guard.holdersOfArtifact(w.org().getUuid(), rowsOf(w.org().getUuid(), s).get(0)).components());
		bRefusedAndAPasses(w, s);
	}

	@Test
	void aDeliverableOfAProductsFeatureSetIsHeldByTheProduct() throws Exception {
		World w = world();
		Component product = componentService.createComponent("score52-product-" + UUID.randomUUID(),
				w.org().getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		UUID row = bomArtifact(w.org().getUuid(), UUID.randomUUID(), "1");
		deliverableService.createDeliverable(DeliverableDto.builder().org(w.org().getUuid())
				.displayIdentifier("score52-pdel-" + UUID.randomUUID()).type(CdxType.CONTAINER)
				.branch(branchService.getBaseBranchOfComponent(product.getUuid()).orElseThrow().getUuid())
				.artifacts(new ArrayList<>(List.of(row))).build(), WU);
		assertEquals(Set.of(product.getUuid()), guard.holdersOfArtifact(w.org().getUuid(), row).components());
		UUID productRelease = release(w.org(), product, List.of());

		assertRefusedUpload(() -> progUpload(componentKey(w, w.a()), w.releaseA(), cdx(urn(serialOf(row)), 2)));
		progUpload(componentKey(w, product), productRelease, cdx(urn(serialOf(row)), 2));
		assertEquals(1, rebom.versionsStored(serialOf(row)));
	}

	@Test
	void anExternalDeliverableReachedThroughAVariantOfAsRelease() throws Exception {
		World w = world();
		UUID row = bomArtifact(w.org().getUuid(), UUID.randomUUID(), "1");
		UUID deliverable = deliverableService.createDeliverable(DeliverableDto.builder().org(w.org().getUuid())
				.displayIdentifier("score52-xdel-" + UUID.randomUUID()).type(CdxType.CONTAINER)
				.branch(branchService.getBaseBranchOfComponent(w.b().getUuid()).orElseThrow().getUuid())
				.artifacts(new ArrayList<>(List.of(row))).build(), WU).getUuid();
		// branchless, as an external deliverable is: the API needs a branch to create one
		jdbcTemplate.update("UPDATE rearm.deliverables SET record_data = record_data - 'branch' WHERE uuid = ?",
				deliverable);
		variantService.addOutboundDeliverables(List.of(deliverable),
				variantService.getBaseVariantForRelease(w.releaseA()).getUuid(), WU);
		assertEquals(Set.of(w.a().getUuid()), guard.holdersOfArtifact(w.org().getUuid(), row).components());
		bRefusedAndAPasses(w, serialOf(row));
	}

	@Test
	void sNestedInAVdrArtifactOnAsRelease() throws Exception {
		World w = world();
		UUID row = bomArtifact(w.org().getUuid(), UUID.randomUUID(), "1");
		UUID vdr = artifactService.createArtifact(ArtifactDto.builder().org(w.org().getUuid()).type(ArtifactType.VDR)
				.displayIdentifier("score52-vdr-" + UUID.randomUUID())
				.internalBom(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.RELEASE))
				.artifacts(new ArrayList<>(List.of(row))).build(), WU).getUuid();
		listDirectly(w.releaseA(), vdr);
		assertEquals(Set.of(w.a().getUuid()), guard.holdersOfArtifact(w.org().getUuid(), row).components());
		bRefusedAndAPasses(w, serialOf(row));
	}
}
