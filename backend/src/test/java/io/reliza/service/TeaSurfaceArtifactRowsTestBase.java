/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.Link;
import io.reliza.model.Organization;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.Rebom.InternalBom;

/**
 * Task TEA-10: the artifact rows the artifact world is built from (split out of
 * {@link TeaSurfaceArtifactIntegrationTestBase} in round 5 so each file stays under 500 lines): a BOM with
 * retained bytes, a hosted file with declared digests, a signature child, a row stored EXTERNALLY at a link.
 */
abstract class TeaSurfaceArtifactRowsTestBase extends TeaSurfaceIntegrationTestBase {

	static final String LIC_MEDIA_TYPE = "text/plain";
	static final String NOSIG_MEDIA_TYPE = "text/markdown";
	static final String S512_MEDIA_TYPE = "application/pdf";
	static final String SIG_MEDIA_TYPE = "application/octet-stream";
	static final String EXT_MEDIA_TYPE = "text/markdown";

	// ---------------------------------------------------------------- rows

	/** A seedBom row with retained bytes: CycloneDX, an AS_UPLOADED digest of {@code bytes} and a RAW_OCI_STORAGE pointer. */
	UUID seedBomWithBytes(Organization o, String name, byte[] bytes) throws Exception {
		Artifact a = artifactService.persistArtifact(ArtifactDto.builder().uuid(UUID.randomUUID()).org(o.getUuid())
				.type(ArtifactType.BOM).displayIdentifier(name).storedIn(StoredIn.REARM).version("1").build(), WU);
		ArtifactData ad = ArtifactData.dataFromRecord(sharedArtifactService.getArtifact(a.getUuid()).orElseThrow());
		ad.setInternalBom(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.RELEASE));
		ad.setBomFormat(BomFormat.CYCLONEDX);
		ad.setTags(new ArrayList<>(List.of(new TagRecord(CommonVariables.MEDIA_TYPE_FIELD, "application/json", Removable.YES),
				new TagRecord(CommonVariables.FILE_NAME_FIELD, name, Removable.YES))));
		ad.setDigestRecords(new LinkedHashSet<>(List.of(
				new DigestRecord(DigestAlgorithm.SHA_256, Utils.bytesToHexSha256(bytes), DigestScope.AS_UPLOADED),
				new DigestRecord(DigestAlgorithm.SHA_256, "ab".repeat(32), DigestScope.RAW_OCI_STORAGE))));
		ad.setRawOciRepositoryName("downloadable-artifacts-test");
		sharedArtifactService.saveArtifact(sharedArtifactService.getArtifact(a.getUuid()).orElseThrow(), ad, WU);
		retained.put(Utils.bytesToHexSha256(bytes), bytes);
		return a.getUuid();
	}

	/** A direct upload stored in ReARM: the download tags, declared ORIGINAL_FILE digests, an OCI_STORAGE sha256. */
	UUID seedFile(Organization o, String name, byte[] bytes, ArtifactType type, String mediaType, Set<DigestAlgorithm> declared)
			throws Exception {
		Artifact a = artifactService.persistArtifact(ArtifactDto.builder().uuid(UUID.randomUUID()).org(o.getUuid())
				.type(type).displayIdentifier(name).storedIn(StoredIn.REARM).version("1").build(), WU);
		ArtifactData ad = ArtifactData.dataFromRecord(sharedArtifactService.getArtifact(a.getUuid()).orElseThrow());
		ad.setTags(new ArrayList<>(List.of(new TagRecord(CommonVariables.DOWNLOADABLE_ARTIFACT, "true", Removable.YES),
				new TagRecord(CommonVariables.TAG_FIELD, name, Removable.YES),
				new TagRecord(CommonVariables.MEDIA_TYPE_FIELD, mediaType, Removable.YES),
				new TagRecord(CommonVariables.FILE_NAME_FIELD, name, Removable.YES))));
		Set<DigestRecord> digests = new LinkedHashSet<>();
		for (DigestAlgorithm alg : declared) digests.add(new DigestRecord(alg, digest(alg, bytes), DigestScope.ORIGINAL_FILE));
		digests.add(new DigestRecord(DigestAlgorithm.SHA_256, Utils.bytesToHexSha256(bytes), DigestScope.OCI_STORAGE));
		ad.setDigestRecords(digests);
		ad.setOciRepositoryName("downloadable-artifacts-test");
		sharedArtifactService.saveArtifact(sharedArtifactService.getArtifact(a.getUuid()).orElseThrow(), ad, WU);
		retained.put(Utils.bytesToHexSha256(bytes), bytes);
		return a.getUuid();
	}

	static String digest(DigestAlgorithm alg, byte[] bytes) throws Exception {
		String name = DigestAlgorithm.SHA_512 == alg ? "SHA-512" : "SHA-256";
		return HexFormat.of().formatHex(java.security.MessageDigest.getInstance(name).digest(bytes));
	}

	/** A SIGNATURE-typed file attached to {@code signed} as its child artifact. */
	UUID seedSignature(Organization o, UUID signed, byte[] bytes) throws Exception {
		UUID sig = seedFile(o, "lic-" + tag() + ".sig", bytes, ArtifactType.SIGNATURE, SIG_MEDIA_TYPE, Set.of(DigestAlgorithm.SHA_256));
		attachChild(signed, sig);
		return sig;
	}

	void attachChild(UUID parent, UUID child) throws Exception {
		Artifact a = sharedArtifactService.getArtifact(parent).orElseThrow();
		ArtifactData ad = ArtifactData.dataFromRecord(a);
		List<UUID> children = new ArrayList<>(null == ad.getArtifacts() ? List.of() : ad.getArtifacts());
		children.add(child);
		ad.setArtifacts(children);
		sharedArtifactService.saveArtifact(a, ad, WU);
	}

	/**
	 * Round 2: a row stored EXTERNALLY at {@code url}, the download tags as seedFile sets them, one
	 * declared ORIGINAL_FILE SHA-256 and no OCI fields; nothing is retained for it, so a fetch fails loudly.
	 */
	UUID seedExternal(Organization o, String name, String url, ArtifactType type, String mediaType, String sha256Hex)
			throws Exception {
		Artifact a = artifactService.persistArtifact(ArtifactDto.builder().uuid(UUID.randomUUID()).org(o.getUuid())
				.type(type).displayIdentifier(name).storedIn(StoredIn.EXTERNALLY).version("1")
				.downloadLinks(List.of(new Link(url, Link.ContentEnum.OCTET_STREAM))).build(), WU);
		ArtifactData ad = ArtifactData.dataFromRecord(sharedArtifactService.getArtifact(a.getUuid()).orElseThrow());
		ad.setTags(new ArrayList<>(List.of(new TagRecord(CommonVariables.DOWNLOADABLE_ARTIFACT, "true", Removable.YES),
				new TagRecord(CommonVariables.TAG_FIELD, name, Removable.YES),
				new TagRecord(CommonVariables.MEDIA_TYPE_FIELD, mediaType, Removable.YES),
				new TagRecord(CommonVariables.FILE_NAME_FIELD, name, Removable.YES))));
		ad.setStoredIn(StoredIn.EXTERNALLY);
		ad.setDownloadLinks(new ArrayList<>(List.of(new Link(url, Link.ContentEnum.OCTET_STREAM))));
		ad.setDigestRecords(new LinkedHashSet<>(List.of(new DigestRecord(DigestAlgorithm.SHA_256, sha256Hex, DigestScope.ORIGINAL_FILE))));
		sharedArtifactService.saveArtifact(sharedArtifactService.getArtifact(a.getUuid()).orElseThrow(), ad, WU);
		return a.getUuid();
	}

	/** Round 2: replaces the download links of a row in place (no version change). */
	void setDownloadLinks(UUID row, List<Link> links) throws Exception {
		Artifact a = sharedArtifactService.getArtifact(row).orElseThrow();
		ArtifactData ad = ArtifactData.dataFromRecord(a);
		ad.setDownloadLinks(new ArrayList<>(links));
		sharedArtifactService.saveArtifact(a, ad, WU);
	}
}
