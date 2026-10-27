/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaChildPublishResult;
import io.reliza.service.TeaPublicationService.TeaConcealReason;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationExposure;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishResult;

/**
 * TEA-5 round 2 against the test database: the cascade's organization boundary (case 70, T-1),
 * the child's own profile as gate and cap (case 72, T-4), and one owner per shared child (case
 * 73, T-3).
 */
public class TeaPublicationCascadeIntegrationTest extends TeaPublicationIntegrationTestBase {

	@org.springframework.beans.factory.annotation.Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

	@Autowired private GetComponentService getComponentService;

	private static TeaChildPublishResult childOf(TeaPublishResult r, UUID release) {
		return r.children().stream().filter(c -> release.equals(c.release())).findFirst().orElseThrow();
	}

	private List<TeaArtifactOrigin> latestOrigins(UUID release) {
		TeaPublicationData head = teaPublicationService.getPublicationOfRelease(release).orElseThrow();
		return teaPublicationService.getLatestCollection(head.getUuid()).orElseThrow().getEntries().stream()
				.map(e -> e.origin()).toList();
	}

	// ---------------------------------------------------------------- 70

	@Test
	void aProductNeverPublishesOrTouchesAReleaseOfAnotherOrganization() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		Organization other = testInitializer.obtainOrganization();
		World foreign = componentReleaseIn(other, "pkg:generic/secret@1.0.0");
		World own = componentRelease("pkg:generic/a@1.0.0");
		// main refuses a foreign parent release at createRelease (TPF); the row is written as one stored before
		// that check, so the cascade's own organization boundary is what is tested
		UUID product = product(List.of(own.release()));
		jdbcTemplate.update("UPDATE rearm.releases SET record_data = jsonb_set(record_data, '{parentReleases}', "
				+ "COALESCE(record_data->'parentReleases', '[]'::jsonb) || jsonb_build_array(jsonb_build_object("
				+ "'release', CAST(? AS text), 'deliverables', '[]'::jsonb))) WHERE uuid = CAST(? AS uuid)",
				foreign.release().toString(), product.toString());

		TeaPublishResult r = publish(product, TeaPublishMode.PUBLISH);

		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, own.release()).outcome());
		TeaChildPublishResult refused = childOf(r, foreign.release());
		assertEquals(TeaChildOutcome.REFUSED, refused.outcome());
		assertEquals(TeaPublishRefusal.RELEASE_NOT_FOUND, refused.refusal());
		assertEquals("not found", refused.detail());
		assertNull(refused.componentName());
		assertNull(refused.version());
		assertTrue(publicationRepository.findByRelease(foreign.release()).isEmpty());
		assertTrue(publicationRepository.listByOrgAndState(other.getUuid(), TeaPublicationState.PUBLISHED).isEmpty());
		assertTrue(stored(foreign.release()).getSyntheticArtifacts().isEmpty());
		assertTrue(events(foreign.release(), ReleaseUpdateScope.TEA_PUBLICATION).isEmpty());
		assertTrue(events(foreign.release(), ReleaseUpdateScope.SYNTHETIC_ARTIFACT).isEmpty());
		assertNull(getComponentService.getComponentData(foreign.component()).orElseThrow().getTeaUuid());
	}

	// ---------------------------------------------------------------- 72

	// CE (SCORE-72 3.6): aChildsOwnProfileGatesAndCapsTheCascade is not here. It saves a two-profile TEA row,
	// which CE refuses (one SBOM profile per TEA publishing settings); Pro's copy of this test keeps it.

	// ---------------------------------------------------------------- 73

	@Test
	void aSharedChildHasOneOwnerUntilThatOwnerLetsGo() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World shared = componentRelease("pkg:generic/shared@1.0.0");
		UUID left = product(List.of(shared.release()));
		UUID right = product(List.of(shared.release()));

		UUID leftPub = publish(left, TeaPublishMode.PUBLISH).publication().getUuid();
		TeaPublicationData child = teaPublicationService.getPublicationOfRelease(shared.release()).orElseThrow();
		assertEquals(leftPub, child.getCascadeOf());
		int eventsBefore = events(shared.release(), ReleaseUpdateScope.TEA_PUBLICATION).size();

		TeaPublishResult r = publish(right, TeaPublishMode.PUBLISH);
		assertEquals(TeaChildOutcome.SKIPPED_OTHER_PRODUCT, childOf(r, shared.release()).outcome());
		TeaPublicationData unchanged = teaPublicationService.getPublicationOfRelease(shared.release()).orElseThrow();
		assertEquals(leftPub, unchanged.getCascadeOf());
		assertEquals(child.getLatestVersion(), unchanged.getLatestVersion());
		assertEquals(eventsBefore, events(shared.release(), ReleaseUpdateScope.TEA_PUBLICATION).size());

		teaPublicationService.hide(left, null, WU);
		TeaPublicationExposure concealed = teaPublicationService.exposureOf(unchanged);
		assertEquals(TeaExposureState.CONCEALED, concealed.state());
		assertEquals(TeaConcealReason.PRODUCT_CONCEALED, concealed.concealedBecause());

		TeaPublishResult takeover = publish(right, TeaPublishMode.REPUBLISH);
		assertEquals(TeaChildOutcome.UNCHANGED, childOf(takeover, shared.release()).outcome());
		TeaPublicationData taken = teaPublicationService.getPublicationOfRelease(shared.release()).orElseThrow();
		assertEquals(takeover.publication().getUuid(), taken.getCascadeOf());
		assertEquals(child.getLatestVersion(), taken.getLatestVersion(), "a takeover with unchanged content writes no version");
		List<ReleaseUpdateEvent> events = events(shared.release(), ReleaseUpdateScope.TEA_PUBLICATION);
		assertEquals(eventsBefore + 1, events.size());
		ReleaseUpdateEvent handOver = events.get(events.size() - 1);
		assertEquals(ReleaseUpdateAction.CHANGED, handOver.rua());
		assertTrue(handOver.oldValue().startsWith("with tea-5-product-"), handOver.oldValue());
		assertTrue(handOver.newValue().startsWith("with tea-5-product-"), handOver.newValue());
		assertNotEquals(handOver.oldValue(), handOver.newValue());

		TeaPublishResult back = publish(left, TeaPublishMode.REPUBLISH);
		assertEquals(TeaChildOutcome.SKIPPED_OTHER_PRODUCT, childOf(back, shared.release()).outcome());
		assertEquals(takeover.publication().getUuid(),
				teaPublicationService.getPublicationOfRelease(shared.release()).orElseThrow().getCascadeOf());
	}
}
