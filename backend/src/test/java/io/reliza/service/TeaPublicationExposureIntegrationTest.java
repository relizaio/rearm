/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.UserData;
import io.reliza.service.TeaPublicationService.TeaConcealReason;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationExposure;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;

/**
 * TEA-5 round 2, case 74 (T-5), against the test database: a published release whose lifecycle
 * falls below ASSEMBLED is concealed live, with no row written and no hide, and is served again
 * once it is back.
 */
public class TeaPublicationExposureIntegrationTest extends TeaPublicationIntegrationTestBase {

	private static final String PUBLICATION = "query($r: ID!) { releaseTeaPublicationView(release: $r) { "
			+ "publication { state latestVersion exposure concealedBecause cascadeOfLabel } } }";

	@SuppressWarnings("unchecked")
	private Map<String, Object> viewed(UserData user, World w) {
		Map<String, Object> view = data(asUser(user, PUBLICATION, vars("r", w.release().toString())),
				"releaseTeaPublicationView");
		return (Map<String, Object>) view.get("publication");
	}

	@Test
	void aCancelledReleaseIsConcealedWithoutAnActAndServedAgainOnceAssembled() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		publish(w.release(), TeaPublishMode.PUBLISH);
		int events = events(w.release(), ReleaseUpdateScope.TEA_PUBLICATION).size();
		int revision = publicationRepository.findByRelease(w.release()).orElseThrow().getRevision();

		ossReleaseService.updateReleaseLifecycle(w.release(), ReleaseLifecycle.CANCELLED, WU);

		TeaPublicationData head = teaPublicationService.getPublicationOfRelease(w.release()).orElseThrow();
		TeaPublicationExposure e = teaPublicationService.exposureOf(head);
		assertEquals(TeaExposureState.CONCEALED, e.state());
		assertEquals(TeaConcealReason.LIFECYCLE_BELOW_ASSEMBLED, e.concealedBecause());
		assertEquals(TeaPublicationState.PUBLISHED, head.getState());
		assertEquals(revision, publicationRepository.findByRelease(w.release()).orElseThrow().getRevision());
		assertEquals(events, events(w.release(), ReleaseUpdateScope.TEA_PUBLICATION).size());
		UserData admin = admin();
		Map<String, Object> pub = viewed(admin, w);
		assertEquals("PUBLISHED", pub.get("state"));
		assertEquals("CONCEALED", pub.get("exposure"));
		assertEquals("LIFECYCLE_BELOW_ASSEMBLED", pub.get("concealedBecause"));
		assertNull(pub.get("cascadeOfLabel"));

		ossReleaseService.updateReleaseLifecycle(w.release(), ReleaseLifecycle.ASSEMBLED, WU);

		pub = viewed(admin, w);
		assertEquals("PRIVATE", pub.get("exposure"));
		assertNull(pub.get("concealedBecause"));
		assertEquals(events, events(w.release(), ReleaseUpdateScope.TEA_PUBLICATION).size());
	}
}
