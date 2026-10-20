/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import graphql.GraphQLError;
import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.ApiKey;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.dto.DeliverableDto;
import io.reliza.service.DeliverableService;
import io.reliza.service.VariantService;

/**
 * SCORE-52 T-19 (ADR-4, carried from SCORE-47 T-18), the refusals with a record: a rule B refusal of
 * an upload to an existing release rolls back what its transaction wrote and leaves one GUARD
 * CHANGED event on the release, written in a transaction of its own, naming the uploading component,
 * the serialNumber as sent, the 3.4 text and the principal, never a holder.
 */
class SbomUpdateGuardProgrammaticRefusalTest extends SbomUpdateGuardProgrammaticTestBase {

	@Autowired private DeliverableDataFetcher deliverableDataFetcher;
	@Autowired private DeliverableService deliverableService;
	@Autowired private VariantService variantService;

	private void assertOneRefusalEvent(World w, ReleaseUpdateEvent e, UUID principal) {
		assertEquals(ReleaseUpdateAction.CHANGED, e.rua());
		assertEquals(w.b().getUuid(), e.objectId());
		assertEquals("urn:uuid:" + w.serial(), e.newValue());
		assertEquals(SbomUpdateNotPermittedException.messageFor("urn:uuid:" + w.serial()), e.message());
		assertEquals(principal, e.wu().getLastUpdatedBy());
		assertTrue(!e.message().contains(w.a().getUuid().toString()), e.message());
	}

	@Test
	void aRefusedProgrammaticUploadLeavesOneGuardEventAndTouchesNothingOfTheHolder() throws Exception {
		World w = world();
		onlySerialIsRefused(w);
		ApiKey keyB = componentKey(w, w.b());

		assertThrows(SbomUpdateNotPermittedException.class, () -> releaseDatafetcher.addArtifactProg(env(keyB,
				"artifactInput", artifactInput(w.releaseB(), "releaseArtifacts", entries(superseding(w))))));

		List<ReleaseUpdateEvent> events = guardEvents(w.releaseB());
		assertEquals(1, events.size(), events.toString());
		assertOneRefusalEvent(w, events.get(0), keyB.getUuid());
		assertEquals(ProgrammaticType.API, events.get(0).wu().getCreatedType());
		assertEquals(List.of(w.x()), artifactsOf(w.releaseA()));
		assertEquals(List.of(), guardEvents(w.releaseA()));
		assertEquals(List.of(w.y()), artifactsOf(w.releaseB()));

		assertThrows(SbomUpdateNotPermittedException.class, () -> releaseDatafetcher.addArtifactProg(env(keyB,
				"artifactInput", artifactInput(w.releaseB(), "releaseArtifacts", entries(superseding(w))))));
		assertEquals(2, guardEvents(w.releaseB()).size(), "a second refusal adds a second event");
	}

	@Test
	void theRefusedPartRollsBackItsGoodUploadAndTheEarlierPartStays() throws Exception {
		World w = world();
		onlySerialIsRefused(w);
		ApiKey keyB = componentKey(w, w.b());

		assertThrows(SbomUpdateNotPermittedException.class, () -> releaseDatafetcher.addArtifactProg(env(keyB,
				"artifactInput", artifactInput(w.releaseB(), "releaseArtifacts", entries(fresh(), superseding(w))))));
		assertEquals(List.of(w.y()), artifactsOf(w.releaseB()), "the good upload of the refused part is not attached");
		assertEquals(0, artifactRows(accepted));
		assertEquals(1, guardEvents(w.releaseB()).size());

		UUID deliverable = deliverableService.createDeliverable(DeliverableDto.builder()
				.displayIdentifier("score52-" + UUID.randomUUID())
				.branch(branchService.getBaseBranchOfComponent(w.b().getUuid()).orElseThrow().getUuid())
				.type(CdxType.CONTAINER).build(), WU).getUuid();
		variantService.addOutboundDeliverables(List.of(deliverable),
				variantService.getBaseVariantForRelease(w.releaseB()).getUuid(), WU);
		Map<String, Object> target = map("deliverable", deliverable.toString(), "artifacts", entries(superseding(w)));
		assertThrows(SbomUpdateNotPermittedException.class, () -> releaseDatafetcher.addArtifactProg(env(keyB,
				"artifactInput", artifactInput(w.releaseB(), "releaseArtifacts", entries(fresh()),
						"deliverableArtifacts", new ArrayList<>(List.of(target))))));
		UUID kept = accepted.get(accepted.size() - 1);
		assertEquals(List.of(w.y(), kept), artifactsOf(w.releaseB()), "the release part committed before the refusal");
		assertEquals(2, guardEvents(w.releaseB()).size());
	}

	@Test
	void addArtifactManualRecordsTheEventInATransactionOfItsOwn() throws Exception {
		World w = world();
		var writerB = writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid());

		// The refusal is unchecked, so the resolver's own transaction rolls back: the event is there
		// only because the record is written in REQUIRES_NEW.
		GraphQLError error = onlyError(manualUpload(writerB, w.releaseB(), superseding(w)));

		assertEquals(SbomUpdateNotPermittedException.ERROR_CODE, error.getExtensions().get("code"));
		List<ReleaseUpdateEvent> events = guardEvents(w.releaseB());
		assertEquals(1, events.size(), events.toString());
		assertOneRefusalEvent(w, events.get(0), writerB.getUuid());
		assertEquals(List.of(w.y()), artifactsOf(w.releaseB()));
	}

	@Test
	void aRefusedOutboundDeliverablesCallLeavesNoDeliverableAndRecordsOnTheRelease() throws Exception {
		World w = world();
		onlySerialIsRefused(w);
		ApiKey keyB = componentKey(w, w.b());
		UUID variant = variantService.getBaseVariantForRelease(w.releaseB()).getUuid();
		Set<UUID> before = variantService.getVariantData(variant).orElseThrow().getOutboundDeliverables();
		String good = "score52-good-" + UUID.randomUUID();
		String bad = "score52-bad-" + UUID.randomUUID();
		Map<String, Object> input = map(CommonVariables.RELEASE_FIELD, w.releaseB().toString(), "deliverables",
				new ArrayList<>(List.of(
						map("displayIdentifier", good, "type", CdxType.CONTAINER.name(), "artifacts", entries(fresh())),
						map("displayIdentifier", bad, "type", CdxType.CONTAINER.name(),
								"artifacts", entries(superseding(w))))));

		assertThrows(SbomUpdateNotPermittedException.class,
				() -> deliverableDataFetcher.addOutboundDeliverablesProgrammatic(env(keyB, "deliverables", input)));

		assertEquals(before, variantService.getVariantData(variant).orElseThrow().getOutboundDeliverables());
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.deliverables"
				+ " WHERE record_data->>'displayIdentifier' IN (?, ?)", Long.class, good, bad));
		assertEquals(1, guardEvents(w.releaseB()).size());
	}
}
