/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.tea.TeaChecksum;
import io.reliza.model.tea.TeaChecksumType;

/**
 * Pins which digest TEA publishes, which is the only one consumers ever see.
 *
 * AS_UPLOADED is a digest taken over the bytes the publisher sent; ORIGINAL_FILE is one that
 * was declared to us, or on a rebom-stored BOM derived from rebom's own copy of the document.
 * A TEA format entry has no way to label which is which, so publishing both would hand a
 * verifier two sha256 values for one file and no way to choose between them. Exactly one is
 * published, and it is the stronger claim when it exists.
 */
class TeaChecksumScopePreferenceTest {

	private final TeaTransformerService service = new TeaTransformerService();

	private List<TeaChecksum> publish(Set<DigestRecord> records) {
		return ReflectionTestUtils.invokeMethod(service, "transformDigestRecordToTeaChecksum", records);
	}

	private static Set<DigestRecord> records(DigestRecord... drs) {
		return new LinkedHashSet<>(List.of(drs));
	}

	@Test
	void publishesAsUploadedWhenPresent() {
		List<TeaChecksum> out = publish(records(
			new DigestRecord(TeaChecksumType.SHA_256, "original-file-value", DigestScope.ORIGINAL_FILE),
			new DigestRecord(TeaChecksumType.SHA_256, "as-uploaded-value", DigestScope.AS_UPLOADED)));

		assertEquals(1, out.size(), "exactly one checksum, never a choice between two");
		assertEquals("as-uploaded-value", out.get(0).getAlgValue());
	}

	/**
	 * Insertion order must not decide this. ORIGINAL_FILE is written first on a real artifact,
	 * so a naive findFirst over the set would publish the weaker claim.
	 */
	@Test
	void preferenceDoesNotDependOnRecordOrder() {
		List<TeaChecksum> out = publish(records(
			new DigestRecord(TeaChecksumType.SHA_256, "as-uploaded-value", DigestScope.AS_UPLOADED),
			new DigestRecord(TeaChecksumType.SHA_256, "original-file-value", DigestScope.ORIGINAL_FILE)));

		assertEquals(1, out.size());
		assertEquals("as-uploaded-value", out.get(0).getAlgValue());
	}

	/**
	 * Artifacts uploaded before retention, external artifacts and deliverables have no
	 * AS_UPLOADED record and must be published exactly as they were.
	 */
	@Test
	void fallsBackToOriginalFileWhenThereIsNoRetainedUpload() {
		List<TeaChecksum> out = publish(records(
			new DigestRecord(TeaChecksumType.SHA_256, "original-file-value", DigestScope.ORIGINAL_FILE),
			new DigestRecord(TeaChecksumType.SHA_256, "oci-value", DigestScope.OCI_STORAGE)));

		assertEquals(1, out.size());
		assertEquals("original-file-value", out.get(0).getAlgValue());
	}

	@Test
	void neverPublishesStorageOrSemanticDigests() {
		List<TeaChecksum> out = publish(records(
			new DigestRecord(TeaChecksumType.SHA_256, "oci-value", DigestScope.OCI_STORAGE),
			new DigestRecord(TeaChecksumType.SHA_256, "rearm-value", DigestScope.REARM),
			new DigestRecord(TeaChecksumType.SHA_256, "raw-oci-value", DigestScope.RAW_OCI_STORAGE)));

		assertTrue(out.isEmpty(),
			"internal storage pointers and the semantic digest are not file checksums");
	}
}
