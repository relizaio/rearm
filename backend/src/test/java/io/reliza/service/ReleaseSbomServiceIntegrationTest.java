/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ReleaseSbomRef;
import io.reliza.model.ReleaseSbomVersionData;
import io.reliza.model.ReleaseSbomWriter;
import io.reliza.model.SbomProfileData;
import io.reliza.service.ReleaseSbomService.EnsureOutcome;
import io.reliza.service.ReleaseSbomService.VersionWrite;
import tools.jackson.databind.JsonNode;

/**
 * FDAW-7 row 3: {@link ReleaseSbomService#ensureVersion} over the test database: one head and one version row
 * per content change, reuse on equal content, an in-place revision of the same artifact row whose earlier
 * version stays readable, needsBind from the release row, and rows that commit on their own.
 */
class ReleaseSbomServiceIntegrationTest extends ReleaseSbomIntegrationTestBase {

	@Autowired PlatformTransactionManager transactionManager;

	@Test
	void oneHeadAndAVersionPerContentChangeOnOneArtifactRow() throws Exception {
		World w = componentRelease("pkg:generic/pump@1.0.0");
		SbomProfileData recipe = SbomProfileData.builtInDefault(org.getUuid());
		recipe.setUuid(UUID.randomUUID());
		recipe.setRevision(5);
		JsonNode d1 = doc("pkg:generic/pump@1.0.0");

		EnsureOutcome first = ensure(w.release(), d1, ReleaseSbomWriter.pkg(recipe));
		ReleaseSbomRef p1 = first.pin();
		assertTrue(first.created());
		List<Map<String, Object>> heads = headRows(w.release());
		assertEquals(1, heads.size());
		assertEquals(p1.serial(), heads.get(0).get("uuid"), "the serial is the head uuid");
		assertEquals(PROFILE, heads.get(0).get("profile_name"));
		assertEquals(1, heads.get(0).get("latest_version"));
		List<Map<String, Object>> rows = versionRows(p1.serial());
		assertEquals(1, rows.size());
		ArtifactData stored = artifactService.getArtifactData(p1.artifact()).orElseThrow();
		String sha1 = TeaPublicationService.digestOf(stored, DigestScope.AS_UPLOADED).orElseThrow();
		assertEquals(sha1, rows.get(0).get("sha256"));
		assertEquals(sha1, p1.sha256());
		assertEquals(TeaCollectionAssembler.contentDigest(d1), rows.get(0).get("content_digest"));
		assertEquals(p1.artifact(), rows.get(0).get("artifact"));
		String record = (String) rows.get(0).get("record");
		assertTrue(record.contains("\"writer\": \"PACKAGE\"") || record.contains("\"writer\":\"PACKAGE\""), record);
		assertTrue(record.contains(recipe.getUuid().toString()), record);
		assertTrue(record.contains("\"recipeRevision\": 5") || record.contains("\"recipeRevision\":5"), record);
		assertEquals("urn:uuid:" + p1.serial(), "urn:uuid:" + stored.getInternalBom().id());

		// needsBind follows the release row's synthetic list
		assertTrue(first.needsBind());
		EnsureOutcome same = ensure(w.release(), doc("pkg:generic/pump@1.0.0"), ReleaseSbomWriter.pkg(recipe));
		assertFalse(same.created());
		assertEquals(p1, same.pin());
		assertTrue(same.needsBind(), "not bound yet");
		bind(w.release(), p1.artifact());
		assertFalse(ensure(w.release(), d1, ReleaseSbomWriter.pkg(recipe)).needsBind(), "bound by the caller");
		assertEquals(1, versionRows(p1.serial()).size(), "equal content wrote nothing");

		// changed content: version 2 on the same row, the head moves, version 1 stays readable
		UUID publication = UUID.randomUUID();
		EnsureOutcome bumped = ensure(w.release(), doc("pkg:generic/pump@1.0.0", "pkg:generic/seal@2"),
				ReleaseSbomWriter.tea(publication, recipe));
		ReleaseSbomRef p2 = bumped.pin();
		assertEquals(p1.serial(), p2.serial());
		assertEquals(2, p2.version());
		assertEquals(p1.artifact(), p2.artifact());
		assertFalse(bumped.needsBind(), "the same bound row");
		assertEquals(2, headRows(w.release()).get(0).get("latest_version"));
		assertEquals(2, versionRows(p1.serial()).size());
		assertTrue(((String) versionRows(p1.serial()).get(1).get("record")).contains("TEA"));
		ArtifactData v1 = artifactService.getArtifactDataByVersion(p1.artifact(), 1).orElseThrow();
		assertEquals(sha1, TeaPublicationService.digestOf(v1, DigestScope.AS_UPLOADED).orElseThrow(),
				"the version 1 snapshot keeps version 1's digest");
		assertEquals(TeaCollectionAssembler.contentDigest(d1),
				TeaPublicationService.systemTagValue(v1, CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY).orElseThrow(),
				"and version 1's tags");
		assertFalse(TeaPublicationService.systemTagValue(v1, CommonVariables.TEA_PUBLICATION_TAG_KEY).isPresent());
		ArtifactData v2 = artifactService.getArtifactData(p1.artifact()).orElseThrow();
		assertEquals(p2.sha256(), TeaPublicationService.digestOf(v2, DigestScope.AS_UPLOADED).orElseThrow());
		assertEquals(publication.toString(),
				TeaPublicationService.systemTagValue(v2, CommonVariables.TEA_PUBLICATION_TAG_KEY).orElseThrow());
		assertEquals(p1, releaseSbomService.pinOf(p1.serial(), 1).orElseThrow());
		assertEquals(p2, releaseSbomService.latestPin(w.release(), PROFILE).orElseThrow());
	}

	@Test
	void theRowsSurviveACallerTransactionThatFailsAfterwards() throws Exception {
		World w = componentRelease("pkg:generic/pump@1.0.0");
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		UUID[] serial = new UUID[1];
		assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
			try {
				serial[0] = ensure(w.release(), doc("pkg:generic/pump@1.0.0"), ReleaseSbomWriter.pkg(null)).pin()
						.serial();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			throw new IllegalStateException("the caller's act fails");
		}));
		assertEquals(1, headRows(w.release()).size(), "the head committed in its own transaction");
		assertEquals(1, versionRows(serial[0]).size(), "and the version row");
	}

	/**
	 * Security F-6: a bump's {@code recordVersion} never advances a head whose stored serial is not the
	 * version row's own serial. This is defense-in-depth against a bug that bypasses the release's lease
	 * (the lease itself already serializes every normal writer): exercised directly against
	 * {@code recordVersion}, since nothing reachable through {@link #ensure} can present it a stale head.
	 */
	@Test
	void recordVersionNeverAdvancesAHeadOfAnotherSerial() throws Exception {
		World w = componentRelease("pkg:generic/pump@1.0.0");
		EnsureOutcome first = ensure(w.release(), doc("pkg:generic/pump@1.0.0"), ReleaseSbomWriter.pkg(null));
		ReleaseSbomRef p1 = first.pin();

		ReleaseSbomVersionData v = new ReleaseSbomVersionData();
		v.setUuid(UUID.randomUUID()); // not the stored head's serial
		v.setVersion(2);
		v.setOrg(org.getUuid());
		v.setRelease(w.release());
		v.setProfileName(PROFILE);
		v.setArtifact(p1.artifact());
		v.setSha256("4d".repeat(32));
		v.setContentDigest("mismatch");

		RelizaException e = assertThrows(RelizaException.class,
				() -> releaseSbomService.recordVersion(new VersionWrite(v, false)));
		assertTrue(e.getMessage().contains("the head of profile " + PROFILE + " is not this serial"), e.getMessage());
		assertEquals(1, headRows(w.release()).get(0).get("latest_version"), "the real head is untouched");
		assertEquals(p1.serial(), headRows(w.release()).get(0).get("uuid"));
	}
}
