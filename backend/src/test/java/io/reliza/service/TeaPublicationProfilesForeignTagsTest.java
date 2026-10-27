/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaPublication;
import io.reliza.model.TeaPublishedCollection;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishResult;

/**
 * Task TEA-28, design 4.3, the F-5 cases (threat-model mitigation M1): the engine trusts system tags only
 * (D15), so a row of another writer that a user tagged with the four TEA keys is never adopted and its
 * forged publication uuid never asked about; and a publication uuid recovered from a system tag is used
 * only when it is free or this release's own (D16), else a fresh uuid with one WARN.
 */
class TeaPublicationProfilesForeignTagsTest extends TeaPublicationServiceTestBase {

	/** Another release's PUBLISHED head and its version 1, the public uuid a forger would name. */
	private UUID otherHead() {
		ComponentData other = component("valve", ComponentType.COMPONENT);
		return seedHead(other, null, TeaPublicationState.PUBLISHED);
	}

	/** A CycloneDX row of another writer with its own serial, bound to the release's synthetic list. */
	private ArtifactData foreignRow(TagRecord... tags) {
		UUID uuid = artifact(ArtifactType.BOM, "other-writer.cdx.json", "3", StoredIn.REARM, false, "ee55");
		ArtifactData ad = artifacts.get(uuid);
		ad.setInternalBom(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.SYNTHETIC));
		List<TagRecord> all = new ArrayList<>(List.of(new TagRecord("otherWriter", "true", Removable.NO)));
		all.addAll(List.of(tags));
		ad.setTags(all);
		Release r = releaseRows.get(release.getUuid());
		ReleaseData d = ReleaseData.dataFromRecord(r);
		d.addSyntheticArtifact(uuid);
		r.setRecordData(Utils.dataToRecord(d));
		return ad;
	}

	/** The digest the act's merge of the release will produce. */
	private String digestOfTheMerge() {
		return TeaCollectionAssembler.contentDigest(TeaCollectionAssembler.stripRebomProducerLink(
				documents.get(release.getUuid()).deepCopy()));
	}

	private TeaPublication headSnapshot(UUID uuid) {
		return copyOf(heads.get(uuid));
	}

	private static void assertSameHead(TeaPublication a, TeaPublication b) {
		assertEquals(a.getRecordData(), b.getRecordData());
		assertEquals(a.getRevision(), b.getRevision());
		assertEquals(a.getRelease(), b.getRelease());
		assertEquals(a.getOrg(), b.getOrg());
	}

	@Test
	void aRowOfAnotherWriterCarryingTheFourKeysAsUserTagsIsNeverAdopted() throws Exception {
		captureServiceWarnings();
		UUID held = otherHead();
		TeaPublication heldBefore = headSnapshot(held);
		ArtifactData foreign = foreignRow(new TagRecord(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true"),
				new TagRecord(CommonVariables.TEA_PROFILE_NAME_TAG_KEY, "default"),
				new TagRecord(CommonVariables.TEA_PUBLICATION_TAG_KEY, held.toString()),
				new TagRecord(CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY, digestOfTheMerge()));
		UUID foreignSerial = foreign.getInternalBom().id();

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		assertEquals(1, writtenDtos.size(), "a new row is uploaded");
		ArtifactDto dto = writtenDtos.get(0);
		assertEquals(null, dto.getUuid(), "never the foreign row's uuid");
		String serial = Utils.OM.readTree(writtenBytes.get(0)).get("serialNumber").asString();
		assertNotEquals("urn:uuid:" + foreignSerial, serial, "never the foreign row's serial");
		UUID pub = head().getUuid();
		assertNotEquals(held, pub, "a random publication uuid, not the forged one");
		verify(publications, never()).findById(held);
		verify(collections, never()).countForeign(eq(held), any(), any());
		verify(publications, never()).save(argThat(p -> held.equals(p.getUuid())));
		verify(collections, never()).save(argThat((TeaPublishedCollection c) -> !pub.equals(c.getUuid())));
		assertSameHead(heldBefore, heads.get(held));
		assertNotEquals(foreign.getUuid(), TeaAggregates.defaultAggregate(head()));
		assertTrue(capturedWarnings().isEmpty(), "nothing to warn about: the tags were never read " + capturedWarnings());
	}

	@Test
	void aSystemPublicationTagNamingAnotherReleasesHeadYieldsAFreshUuidWithOneWarning() throws Exception {
		captureServiceWarnings();
		profile.setTei(TeaTei.UUID);
		profile.setTeiDomain("products.example.com");
		UUID held = otherHead();
		TeaPublication heldBefore = headSnapshot(held);
		// a state only a database edit reaches: the engine's keys as system tags on a row it did not write
		ArtifactData row = foreignRow(new TagRecord(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true", Removable.NO),
				new TagRecord(CommonVariables.TEA_PUBLICATION_TAG_KEY, held.toString(), Removable.NO));

		publish(TeaPublishMode.PUBLISH);

		UUID pub = head().getUuid();
		assertNotEquals(held, pub);
		List<String> warnings = capturedWarnings();
		assertEquals(1, warnings.size(), warnings.toString());
		String w = warnings.get(0);
		assertTrue(w.contains(release.getUuid().toString()) && w.contains(row.getUuid().toString())
				&& w.contains(held.toString()), w);
		for (ArtifactDto dto : writtenDtos) {
			assertEquals(pub.toString(), dto.getTags().stream().filter(t -> CommonVariables.TEA_PUBLICATION_TAG_KEY
					.equals(t.key())).findFirst().orElseThrow().value());
		}
		assertEquals("tei://products.example.com/uuid/" + pub, head().getTei());
		verify(publications, never()).save(argThat(p -> held.equals(p.getUuid())));
		assertSameHead(heldBefore, heads.get(held));
	}

	@Test
	void aRecoveredUuidWithAVersionOfAnotherReleaseYieldsAFreshUuidWithOneWarning() throws Exception {
		captureServiceWarnings();
		UUID taken = UUID.randomUUID();
		TeaPublishedCollection foreignVersion = new TeaPublishedCollection();
		foreignVersion.setUuid(taken);
		foreignVersion.setVersion(1);
		foreignVersion.setOrg(ORG);
		foreignVersion.setRelease(UUID.randomUUID());
		foreignVersion.setRecordData(new TeaPublishedCollectionData().toRecordData());
		collectionRows.put(taken + ":1", foreignVersion);
		foreignRow(new TagRecord(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true", Removable.NO),
				new TagRecord(CommonVariables.TEA_PUBLICATION_TAG_KEY, taken.toString(), Removable.NO));

		publish(TeaPublishMode.PUBLISH);

		assertNotEquals(taken, head().getUuid());
		assertEquals(1, capturedWarnings().size(), capturedWarnings().toString());
		assertEquals(1, collectionRows.keySet().stream().filter(k -> k.startsWith(taken.toString())).count(),
				"nothing written under the taken uuid");
	}

	@Test
	void aMissingHeadOverThisReleasesOwnVersionsKeepsItsUuidAndContinuesTheNumbering() throws Exception {
		captureServiceWarnings();
		publish(TeaPublishMode.PUBLISH);
		UUID pub = head().getUuid();
		heads.remove(pub);
		// a save that is no content change; the aggregate row still carries the system publication tag
		ReflectionTestUtils.setField(artifacts.get(inventoryBom), "updatedDate", ZonedDateTime.now());

		TeaPublishResult unchanged = publish(TeaPublishMode.PUBLISH);

		assertEquals(pub, unchanged.publication().getUuid(), "the heal of a missing head keeps the uuid");
		assertEquals(1, unchanged.publication().getLatestVersion(), "nothing new: the head is written at version 1");
		assertTrue(capturedWarnings().isEmpty(), capturedWarnings().toString());

		heads.remove(pub);
		documents.put(release.getUuid(), bom("pkg:generic/a@2"));
		TeaPublishResult changed = publish(TeaPublishMode.PUBLISH);
		assertEquals(pub, changed.publication().getUuid());
		assertEquals(2, changed.collection().getVersion(), "the numbering continues from the stored version");
		assertTrue(capturedWarnings().isEmpty(), capturedWarnings().toString());
	}
}
