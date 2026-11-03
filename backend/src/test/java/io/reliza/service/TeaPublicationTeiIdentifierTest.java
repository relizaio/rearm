/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.service.TeaPublicationService.TeaPublishMode;

/**
 * TEI stamping onto the release: with the profile's tei UUID the release carries its publication's TEI as a TEI
 * identifier from the publish on; the stamped identifier follows the TEI (replaced on a teiDomain change, removed
 * when stamping is off), a release published before stamping gains it on a re-publish with no new collection
 * version, and a TEI identifier entered by hand is never touched.
 */
class TeaPublicationTeiIdentifierTest extends TeaPublicationServiceTestBase {

	private static final String HAND_ENTERED = "tei://mine.example.com/uuid/0f8fad5b-d9cb-469f-a165-70867728950e";

	private List<String> teiIdentifiers() {
		return ReleaseData.dataFromRecord(releaseRows.get(release.getUuid())).getIdentifiers().stream()
				.filter(i -> RearmIdentifierType.TEI == i.getIdType()).map(RearmIdentifier::getIdValue).toList();
	}

	private String stamped(String domain) {
		return "tei://" + domain + "/uuid/" + head().getUuid();
	}

	private void stampWith(String domain) {
		profile.setTei(TeaTei.UUID);
		profile.setTeiDomain(domain);
	}

	private void stampOff() {
		profile.setTei(TeaTei.DISABLED);
		profile.setTeiDomain(null);
	}

	private void enterByHand(String tei) {
		Release r = releaseRows.get(release.getUuid());
		ReleaseData d = ReleaseData.dataFromRecord(r);
		List<RearmIdentifier> ids = new ArrayList<>(d.getIdentifiers());
		ids.add(new RearmIdentifier(RearmIdentifierType.TEI, tei));
		d.setIdentifiers(ids);
		r.setRecordData(Utils.dataToRecord(d));
	}

	@Test
	void thePublishStampsTheTeiOnTheRelease() throws Exception {
		stampWith("products.example.com");
		publish(TeaPublishMode.PUBLISH);
		assertEquals(List.of(stamped("products.example.com")), teiIdentifiers());
		assertEquals(head().getTei(), teiIdentifiers().get(0), "the release identifier is the publication's TEI");
	}

	@Test
	void theStampedIdentifierFollowsTheTeiAndGoesWhenStampingIsOff() throws Exception {
		stampWith("products.example.com");
		publish(TeaPublishMode.PUBLISH);

		stampWith("other.example.com");
		publish(TeaPublishMode.REPUBLISH);
		assertEquals(List.of(stamped("other.example.com")), teiIdentifiers(), "replaced, never accumulated");

		publish(TeaPublishMode.REPUBLISH);
		assertEquals(List.of(stamped("other.example.com")), teiIdentifiers(), "a re-publish adds no second copy");

		stampOff();
		publish(TeaPublishMode.REPUBLISH);
		assertEquals(List.of(), teiIdentifiers());
	}

	@Test
	void aReleasePublishedBeforeStampingGainsItOnARepublishWithNoNewVersion() throws Exception {
		stampOff();
		publish(TeaPublishMode.PUBLISH);
		assertEquals(List.of(), teiIdentifiers());

		stampWith("products.example.com");
		publish(TeaPublishMode.REPUBLISH);

		assertEquals(List.of(stamped("products.example.com")), teiIdentifiers());
		verify(collections, times(1)).save(any());
	}

	@Test
	void aTeiEnteredByHandIsNeverTouched() throws Exception {
		enterByHand(HAND_ENTERED);
		stampWith("products.example.com");
		publish(TeaPublishMode.PUBLISH);
		assertEquals(List.of(HAND_ENTERED, stamped("products.example.com")), teiIdentifiers());

		stampOff();
		publish(TeaPublishMode.REPUBLISH);
		assertEquals(List.of(HAND_ENTERED), teiIdentifiers());
	}

	@Test
	void stageTeiIdentifierReportsAChangeOnlyWhenTheListMoved() {
		ReleaseData rd = new ReleaseData();
		assertFalse(TeaPublicationService.stageTeiIdentifier(rd, null, null));
		assertTrue(TeaPublicationService.stageTeiIdentifier(rd, null, "tei://a.example.com/uuid/x"));
		assertFalse(TeaPublicationService.stageTeiIdentifier(rd, "tei://a.example.com/uuid/x", "tei://a.example.com/uuid/x"));
		assertTrue(TeaPublicationService.stageTeiIdentifier(rd, "tei://a.example.com/uuid/x", "tei://b.example.com/uuid/x"));
		assertEquals(List.of("tei://b.example.com/uuid/x"),
				rd.getIdentifiers().stream().map(RearmIdentifier::getIdValue).toList());
		assertTrue(TeaPublicationService.stageTeiIdentifier(rd, "tei://b.example.com/uuid/x", null));
		assertEquals(List.of(), rd.getIdentifiers());
	}
}
