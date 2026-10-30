/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.Utils.RootComponentMergeMode;
import io.reliza.exceptions.MergedSbomUnavailableException.ChildBomStatus;
import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.dto.ExportMetadataOptions;
import io.reliza.model.Rebom.MissingSbomComponent;
import io.reliza.model.Rebom.MissingSbomMode;
import io.reliza.model.Rebom.MissingSbomReason;
import io.reliza.service.RebomService.BomMediaType;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaChildPublishResult;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import tools.jackson.databind.JsonNode;

/**
 * TEA-15 (architecture 3.3, 3.4, 4.5 cases 23 to 26) against the test database: the TEA aggregate of a
 * product leaves a component release without an SBOM out, the cascade refuses that child as NO_SBOM, and
 * the internal export of the same product keeps its placeholder, each from its own release_reboms row.
 */
public class TeaPublicationMissingSbomIntegrationTest extends TeaPublicationIntegrationTestBase {

	@Autowired private ReleaseRebomService releaseRebomService;

	private static TeaChildPublishResult childOf(TeaPublishResult r, UUID release) {
		return r.children().stream().filter(c -> release.equals(c.release())).findFirst().orElseThrow();
	}

	private List<ReleaseBom> rowsOf(UUID release) {
		return releaseRebomService.getReleaseBoms(stored(release));
	}

	private ReleaseBom rowIn(UUID release, MissingSbomMode mode) {
		List<ReleaseBom> of = rowsOf(release).stream()
				.filter(rb -> rb.rebomMergeOptions().effectiveMissingSbomMode() == mode).toList();
		assertEquals(1, of.size(), rowsOf(release).toString());
		return of.get(0);
	}

	/** The aggregate documents the publication uploaded for {@code release}: the root is named after it. */
	private List<JsonNode> aggregatesOf(UUID release) {
		return uploaded.values().stream()
				.filter(d -> ("release-" + release).equals(d.path("metadata").path("component").path("name").asText()))
				.toList();
	}

	private record Rig(World a, World bare, UUID product) {}

	private Rig productOfAAndBare(TeaProductComponents productComponents) throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, productComponents);
		World a = componentRelease("pkg:generic/a@1.0.0");
		World bare = componentRelease(null);
		UUID product = product(List.of(a.release(), bare.release()));
		return new Rig(a, bare, product);
	}

	// 23
	@Test
	void theProductsAggregateLeavesTheBareChildOutAndTheCascadeRefusesItAsNoSbom() throws Exception {
		Rig rig = productOfAAndBare(TeaProductComponents.PUBLISH_WITH_PRODUCT);
		UUID a = rig.a().release();
		UUID bare = rig.bare().release();
		UUID product = rig.product();

		TeaPublishResult r = publish(product, TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		assertEquals(3, mergeCalls.size(), "a as the product's child, the product, a again by the cascade: " + mergeCalls);
		assertEquals(a, mergeOfRelease.get(mergeCalls.get(0).id()));
		assertEquals(product, mergeOfRelease.get(mergeCalls.get(1).id()));
		assertEquals(a, mergeOfRelease.get(mergeCalls.get(2).id()));
		assertEquals(2, mergesOf(a).size());
		assertEquals(1, mergesOf(product).size());
		assertEquals(List.of(), mergesOf(bare), "a component merge with no inputs never calls rebom");
		MergeCall productMerge = mergesOf(product).get(0);
		assertEquals(RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, productMerge.options().rootComponentMergeMode());
		assertNull(productMerge.options().missingSbomComponents(), "no placeholder is sent for the bare child");
		assertEquals(List.of(mergesOf(a).get(0).id()), productMerge.ids(), "the product consumed a's first merge");

		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, a).outcome());
		TeaChildPublishResult refused = childOf(r, bare);
		assertEquals(TeaChildOutcome.REFUSED, refused.outcome());
		assertEquals(TeaPublishRefusal.NO_SBOM_ARTIFACTS, refused.refusal());
		assertTrue(refused.detail().startsWith("No SBOMs found: release"), refused.detail());

		List<JsonNode> aggregates = aggregatesOf(product);
		assertEquals(1, aggregates.size(), uploaded.toString());
		String aggregate = aggregates.get(0).toString();
		assertFalse(aggregate.contains("reliza:sbom:missing"), aggregate);
		assertFalse(aggregate.contains("reliza:export:componentsWithoutSbom"), aggregate);
		// TEA-15 F-8: nothing identifies the left-out child on the public document
		String bareName = getComponentService.getComponentData(rig.bare().component()).orElseThrow().getName();
		assertFalse(aggregate.contains("urn:rearm:release:"), aggregate);
		assertFalse(aggregate.contains(bare.toString()), aggregate);
		assertFalse(aggregate.contains(bareName), aggregate);

		List<ReleaseBom> productRows = rowsOf(product);
		assertEquals(1, productRows.size(), productRows.toString());
		assertEquals(MissingSbomMode.OMIT, productRows.get(0).rebomMergeOptions().missingSbomMode());
		List<ReleaseBom> aRows = rowsOf(a);
		assertEquals(1, aRows.size(), aRows.toString());
		assertEquals(MissingSbomMode.OMIT, aRows.get(0).rebomMergeOptions().missingSbomMode());
		assertEquals(mergesOf(a).get(1).id(), aRows.get(0).rebomId(),
				"the cascade's merge replaced the first through sameRequest");
	}

	// 24
	@Test
	void theInternalExportOfTheSameProductCarriesThePlaceholderAndTheTwoRowsCoexist() throws Exception {
		Rig rig = productOfAAndBare(TeaProductComponents.PUBLISH_WITH_PRODUCT);
		UUID a = rig.a().release();
		UUID bare = rig.bare().release();
		UUID product = rig.product();
		publish(product, TeaPublishMode.PUBLISH);
		assertEquals(3, mergeCalls.size(), mergeCalls.toString());

		releaseService.exportReleaseSbom(product, false, false, false, null, BomStructureType.FLAT, BomMediaType.JSON,
				org.getUuid(), WU, null, ExportMetadataOptions.callerSilent());

		assertEquals(5, mergeCalls.size(), "a and then the product, each under PLACEHOLDER: " + mergeCalls);
		MergeCall aPlaceholder = mergeCalls.get(3);
		MergeCall productPlaceholder = mergeCalls.get(4);
		assertEquals(a, mergeOfRelease.get(aPlaceholder.id()));
		assertNull(aPlaceholder.options().missingSbomMode());
		assertEquals(product, mergeOfRelease.get(productPlaceholder.id()));
		List<MissingSbomComponent> missing = productPlaceholder.options().missingSbomComponents();
		assertEquals(1, missing.size(), String.valueOf(missing));
		assertEquals(bare.toString(), missing.get(0).releaseUuid());
		assertEquals(MissingSbomReason.NO_SBOM_ARTIFACT, missing.get(0).reason());
		assertEquals(List.of(aPlaceholder.id()), productPlaceholder.ids());
		for (UUID release : List.of(product, a)) {
			List<ReleaseBom> rows = rowsOf(release);
			assertEquals(2, rows.size(), rows.toString());
			rows.forEach(rb -> assertEquals(Integer.valueOf(ReleaseService.MERGE_VERSION), rb.rebomMergeOptions().mergeVersion()));
			assertEquals(MissingSbomMode.OMIT, rowIn(release, MissingSbomMode.OMIT).rebomMergeOptions().missingSbomMode());
			assertNull(rowIn(release, MissingSbomMode.PLACEHOLDER).rebomMergeOptions().missingSbomMode());
		}

		releaseService.exportReleaseSbom(product, false, false, false, null, BomStructureType.FLAT, BomMediaType.JSON,
				org.getUuid(), WU, null, ExportMetadataOptions.callerSilent());
		assertEquals(5, mergeCalls.size(), "a second export is served from the placeholder rows: " + mergeCalls);

		UUID aOmitBefore = rowIn(a, MissingSbomMode.OMIT).rebomId();
		UUID aPlaceholderRow = rowIn(a, MissingSbomMode.PLACEHOLDER).rebomId();
		UUID productPlaceholderRow = rowIn(product, MissingSbomMode.PLACEHOLDER).rebomId();

		publish(product, TeaPublishMode.REPUBLISH);

		assertEquals(7, mergeCalls.size(), "the product, then a by the cascade: " + mergeCalls);
		MergeCall productOmit = mergeCalls.get(5);
		MergeCall aCascade = mergeCalls.get(6);
		assertEquals(product, mergeOfRelease.get(productOmit.id()));
		assertEquals(List.of(aOmitBefore), productOmit.ids(), "the product consumes a's OMIT row as it stands");
		assertNull(productOmit.options().missingSbomComponents());
		assertEquals(a, mergeOfRelease.get(aCascade.id()));
		assertEquals(aPlaceholderRow, rowIn(a, MissingSbomMode.PLACEHOLDER).rebomId());
		assertEquals(productPlaceholderRow, rowIn(product, MissingSbomMode.PLACEHOLDER).rebomId());
		assertEquals(aCascade.id(), rowIn(a, MissingSbomMode.OMIT).rebomId());
		assertEquals(productOmit.id(), rowIn(product, MissingSbomMode.OMIT).rebomId());
		assertEquals(2, rowsOf(a).size());
		assertEquals(2, rowsOf(product).size());
	}

	// 25
	@Test
	void aProductOnlyPublishLogsTheOmittedChildAndListsNoOutcome() throws Exception {
		Rig rig = productOfAAndBare(TeaProductComponents.PRODUCT_ONLY);
		List<LogEvent> logged = Collections.synchronizedList(new ArrayList<>());
		Logger releaseServiceLog = (Logger) LogManager.getLogger(ReleaseService.class);
		AbstractAppender capture = new AbstractAppender("tea-15-capture-" + UUID.randomUUID(), null, null, true,
				Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				logged.add(event.toImmutable());
			}
		};
		capture.start();
		releaseServiceLog.addAppender(capture);
		Level previous = releaseServiceLog.getLevel();
		Configurator.setLevel(ReleaseService.class.getName(), Level.INFO);
		TeaPublishResult r;
		try {
			r = publish(rig.product(), TeaPublishMode.PUBLISH);
		} finally {
			Configurator.setLevel(ReleaseService.class.getName(), previous);
			releaseServiceLog.removeAppender(capture);
			capture.stop();
		}

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		assertTrue(r.children().isEmpty(), r.children().toString());
		assertEquals(2, mergeCalls.size(), "a as the product's child, then the product; no cascade: " + mergeCalls);
		List<String> lines;
		synchronized (logged) {
			lines = logged.stream().filter(e -> e.getLevel() == Level.INFO)
					.map(e -> e.getMessage().getFormattedMessage())
					.filter(m -> m.startsWith("TEA publication of release")).toList();
		}
		assertEquals(1, lines.size(), lines.toString());
		assertTrue(lines.get(0).contains(rig.bare().release().toString()), lines.get(0));
	}

	// 26
	@Test
	void aProductWithNoSbomAnywhereIsRefusedNoSbomArtifactsEndToEnd() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World bare = componentRelease(null);
		UUID product = product(List.of(bare.release()));

		TeaPublishRefusedException e = assertThrows(TeaPublishRefusedException.class,
				() -> publish(product, TeaPublishMode.PUBLISH));

		assertEquals(TeaPublishRefusal.NO_SBOM_ARTIFACTS, e.reason());
		assertEquals(1, e.children().size(), e.children().toString());
		assertEquals(bare.release(), e.children().get(0).release());
		assertEquals(ChildBomStatus.NO_SBOM, e.children().get(0).status());
		assertTrue(publicationRepository.findByRelease(product).isEmpty(), "no publication row");
		assertTrue(stored(product).getSyntheticArtifacts().isEmpty(), "no synthetic artifact");
		assertTrue(rowsOf(product).isEmpty(), "no release_reboms row for the product");
		assertEquals(List.of(), mergeCalls);
	}
}
