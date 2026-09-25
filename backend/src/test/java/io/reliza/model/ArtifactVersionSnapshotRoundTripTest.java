/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.ArtifactVersionSnapshot;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.InventoryType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.tea.Link;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.util.OciRepositoryUtil;

/**
 * Pins the version-history serialization boundary.
 *
 * ArtifactVersionSnapshot is a POSITIONAL record persisted inside the artifact's record_data
 * JSONB, and three places have to agree about its shape: the record header, fromArtifactData
 * and fromSnapshot. Nothing checked that they do. Two ways that goes wrong, both silent:
 * a component added to the record but not threaded through fromSnapshot simply reads back
 * null, and two same-typed adjacent components swapped in the constructor call compile
 * cleanly and pass every other test.
 *
 * The second is not hypothetical here. ociRepositoryName and rawOciRepositoryName are both
 * String, sit next to each other, and mean opposite things -- rebom's PROCESSED copy against
 * the RAW upload -- so transposing them aims the raw download at the wrong repository while
 * the bytes themselves are perfectly intact. Every value below is therefore distinct, so an
 * assertion fails on a crossover rather than on a coincidence.
 */
class ArtifactVersionSnapshotRoundTripTest {

	private static final UUID ORIGINAL_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID ORG_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID INTERNAL_BOM_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID SIBLING_ARTIFACT_UUID = UUID.fromString("44444444-4444-4444-4444-444444444444");

	private static final String PROCESSED_REPO = "rebom-artifacts-2026-08";
	private static final String RAW_REPO = "downloadable-artifacts-2026-09";

	/** Every field the snapshot carries, each with a value unlike every other. */
	private static ArtifactData fullyPopulated() {
		ArtifactData ad = new ArtifactData();
		// uuid's setter is deliberately private on ArtifactData; fromSnapshot reaches it as a
		// nested class, and a test has to go the same way round.
		ReflectionTestUtils.setField(ad, "uuid", ORIGINAL_UUID);
		ad.setVersion("7");
		ad.setType(ArtifactType.BOM);
		ad.setDisplayIdentifier("sbom.json");
		ad.setDownloadLinks(List.of(Link.builder()
			.uri("https://example.invalid/sbom.json")
			.content(Link.ContentEnum.PLAIN_JSON)
			.build()));
		ad.setInventoryTypes(List.of(InventoryType.SOFTWARE, InventoryType.CRYPTOGRAPHY));
		ad.setBomFormat(BomFormat.SPDX);
		ad.setStoredIn(StoredIn.REARM);
		ad.setInternalBom(new InternalBom(INTERNAL_BOM_UUID, ArtifactBelongsTo.RELEASE));
		Set<DigestRecord> digests = new LinkedHashSet<>(List.of(
			new DigestRecord(TeaChecksumType.SHA_256, "declared-or-rebom-derived", DigestScope.ORIGINAL_FILE),
			new DigestRecord(TeaChecksumType.SHA_256, "hashed-by-us-over-the-upload", DigestScope.AS_UPLOADED),
			new DigestRecord(TeaChecksumType.SHA_256, "manifest-of-the-retained-upload", DigestScope.RAW_OCI_STORAGE)));
		ad.setDigestRecords(digests);
		ad.setTags(List.of(new TagRecord(CommonVariables.MEDIA_TYPE_FIELD, "application/spdx+json", Removable.NO)));
		ad.setStatus(StatusEnum.ACTIVE);
		ad.setArtifacts(List.of(SIBLING_ARTIFACT_UUID));
		ad.setOrg(ORG_UUID);
		ad.setOciRepositoryName(PROCESSED_REPO);
		ad.setRawOciRepositoryName(RAW_REPO);
		return ad;
	}

	@Test
	void everyFieldSurvivesTheRoundTripThroughASnapshot() {
		ArtifactData before = fullyPopulated();

		ArtifactData after = ArtifactVersionSnapshot.fromSnapshot(
			ArtifactVersionSnapshot.fromArtifactData(before));

		assertEquals(before.getUuid(), after.getUuid(), "originalUuid carries the identity of the replaced artifact");
		assertEquals(before.getVersion(), after.getVersion());
		assertEquals(before.getType(), after.getType());
		assertEquals(before.getDisplayIdentifier(), after.getDisplayIdentifier());
		assertEquals(before.getDownloadLinks(), after.getDownloadLinks());
		assertEquals(before.getInventoryTypes(), after.getInventoryTypes());
		assertEquals(before.getBomFormat(), after.getBomFormat());
		assertEquals(before.getStoredIn(), after.getStoredIn());
		assertEquals(before.getInternalBom(), after.getInternalBom());
		assertEquals(before.getDigestRecords(), after.getDigestRecords());
		assertEquals(before.getTags(), after.getTags());
		assertEquals(before.getStatus(), after.getStatus());
		assertEquals(before.getArtifacts(), after.getArtifacts());
		assertEquals(before.getOrg(), after.getOrg());
		assertEquals(before.getOciRepositoryName(), after.getOciRepositoryName());
		assertEquals(before.getRawOciRepositoryName(), after.getRawOciRepositoryName());
	}

	/**
	 * The transposition guard, stated on its own so a failure names the problem. These two are
	 * the only same-typed adjacent pair in the record, and they address different registries.
	 */
	@Test
	void theProcessedAndRawRepositoryPointersDoNotCrossOver() {
		ArtifactVersionSnapshot snapshot = ArtifactVersionSnapshot.fromArtifactData(fullyPopulated());

		assertEquals(PROCESSED_REPO, snapshot.ociRepositoryName(),
			"ociRepositoryName means rebom's processed copy");
		assertEquals(RAW_REPO, snapshot.rawOciRepositoryName(),
			"rawOciRepositoryName means the retained upload, which lives in its own repository");

		ArtifactData restored = ArtifactVersionSnapshot.fromSnapshot(snapshot);
		assertEquals(PROCESSED_REPO, restored.getOciRepositoryName());
		assertEquals(RAW_REPO, restored.getRawOciRepositoryName());
	}

	/**
	 * The path an artifact actually takes: written to record_data by the Jackson 2 mapper
	 * Hibernate's JsonBinaryType uses, read back by the Jackson 3 mapper dataFromRecord binds
	 * with. Mirrored here rather than round-tripped through one mapper, because that asymmetry
	 * is real and a field can bind under one and not the other.
	 */
	@Test
	void theSnapshotSurvivesTheJsonbWriteAndRead() throws Exception {
		ArtifactData withHistory = new ArtifactData();
		withHistory.addVersionSnapshot(ArtifactVersionSnapshot.fromArtifactData(fullyPopulated()));

		String persisted = Utils.JSONB_OM.writeValueAsString(withHistory);
		@SuppressWarnings("unchecked")
		Map<String, Object> recordData = Utils.OM.readValue(persisted, Map.class);
		ArtifactData read = Utils.OM.convertValue(recordData, ArtifactData.class);

		assertEquals(1, read.getPreviousVersions().size());
		ArtifactVersionSnapshot snapshot = read.getPreviousVersions().get(0);
		assertEquals(ORIGINAL_UUID, snapshot.originalUuid());
		assertEquals("7", snapshot.version());
		assertEquals(ArtifactType.BOM, snapshot.type());
		assertEquals(BomFormat.SPDX, snapshot.bomFormat());
		assertEquals(ORG_UUID, snapshot.org());
		assertEquals(PROCESSED_REPO, snapshot.ociRepositoryName());
		assertEquals(RAW_REPO, snapshot.rawOciRepositoryName());
		assertNotNull(snapshot.versionDate());
		assertEquals(3, snapshot.digestRecords().size());
		assertTrue(snapshot.digestRecords().stream().anyMatch(d -> d.scope() == DigestScope.AS_UPLOADED),
			"the digest the raw download validates against must survive into version history");
	}

	/**
	 * Rows written before raw retention carry no rawOciRepositoryName key at all. Jackson binds
	 * records by name, so the value reads back null rather than shifting every later component
	 * along by one -- and null is the documented signal for "this version's bytes were never
	 * retained", which sends the download to the default repository.
	 */
	@Test
	void aVersionWrittenBeforeRawRetentionReadsBackAsNull() {
		String legacyRecordData = """
			{
			  "previousVersions": [
			    {
			      "originalUuid": "11111111-1111-1111-1111-111111111111",
			      "versionDate": "2026-03-01T00:00:00Z",
			      "version": "3",
			      "type": "BOM",
			      "displayIdentifier": "sbom.json",
			      "bomFormat": "CYCLONEDX",
			      "storedIn": "REARM",
			      "org": "22222222-2222-2222-2222-222222222222",
			      "ociRepositoryName": "rebom-artifacts-2026-03"
			    }
			  ]
			}
			""";

		ArtifactData read = Utils.OM.readValue(legacyRecordData, ArtifactData.class);
		ArtifactVersionSnapshot snapshot = read.getPreviousVersions().get(0);

		assertNull(snapshot.rawOciRepositoryName(),
			"absent key means the bytes were never retained, not a shifted component");
		assertEquals("rebom-artifacts-2026-03", snapshot.ociRepositoryName(),
			"the pre-existing pointer must not be disturbed by the new neighbour");
		assertEquals(ORIGINAL_UUID, snapshot.originalUuid());
		assertEquals("3", snapshot.version());
		assertEquals(BomFormat.CYCLONEDX, snapshot.bomFormat());
	}

	/**
	 * What that null means downstream, asserted here so the constant and the fallback cannot
	 * drift apart: a version with no retained bytes resolves to the unrotated default.
	 */
	@Test
	void aNullRawRepositoryMeansTheDefaultRepository() {
		ArtifactData ad = fullyPopulated();
		ad.setRawOciRepositoryName(null);

		ArtifactData restored = ArtifactVersionSnapshot.fromSnapshot(
			ArtifactVersionSnapshot.fromArtifactData(ad));

		assertNull(restored.getRawOciRepositoryName());
		assertEquals("downloadable-artifacts", OciRepositoryUtil.DEFAULT_REPOSITORY_NAME,
			"the download's fallback target");
	}

	/**
	 * A snapshot must not drag the whole version chain along with it, or each rebuild nests the
	 * previous history one level deeper and record_data grows without bound.
	 */
	@Test
	void aRestoredVersionCarriesNoNestedHistory() {
		ArtifactData withHistory = fullyPopulated();
		withHistory.addVersionSnapshot(ArtifactVersionSnapshot.fromArtifactData(fullyPopulated()));

		ArtifactData restored = ArtifactVersionSnapshot.fromSnapshot(
			ArtifactVersionSnapshot.fromArtifactData(withHistory));

		assertTrue(restored.getPreviousVersions() == null || restored.getPreviousVersions().isEmpty(),
			"previousVersions is deliberately not carried into a snapshot");
	}

	/**
	 * A reflective backstop for the next component added to the record: if the header grows and
	 * fromArtifactData or fromSnapshot is not updated with it, the count moves and this fails
	 * with a pointer to the three places that have to agree.
	 */
	@Test
	void theRecordShapeIsPinnedSoAnAddedComponentCannotBeForgotten() {
		assertEquals(17, ArtifactVersionSnapshot.class.getRecordComponents().length,
			"ArtifactVersionSnapshot changed shape -- update the record header, fromArtifactData, "
			+ "fromSnapshot and this test's field-by-field round trip together");
		assertEquals("rawOciRepositoryName",
			ArtifactVersionSnapshot.class.getRecordComponents()[16].getName(),
			"components are positional; appending keeps existing JSONB rows readable");
	}
}
