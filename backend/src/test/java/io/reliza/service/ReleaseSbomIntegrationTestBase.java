/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.model.ReleaseSbomWriter;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.service.ReleaseSbomService.EnsureOutcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * FDAW-7: the release SBOM identity over the test database, on the TEA integration fixture (its rebom
 * mock stores every upload by serial and version). A case calls {@link #ensure} as a writer would,
 * under the release's real lease.
 */
abstract class ReleaseSbomIntegrationTestBase extends TeaPublicationIntegrationTestBase {

	static final String PROFILE = "default";

	@Autowired JdbcTemplate jdbc;
	@Autowired TeaPublishLock teaPublishLock;
	@Autowired SyntheticArtifactService syntheticArtifactService;

	/** A CycloneDX document whose components are {@code purls}: its content digest changes with them. */
	static JsonNode doc(String... purls) {
		ObjectNode d = Utils.OM.createObjectNode();
		d.put("bomFormat", "CycloneDX");
		d.put("specVersion", "1.6");
		d.putObject("metadata").putObject("component").put("name", "fdaw7").put("type", "application");
		var components = d.putArray("components");
		for (String p : purls) components.addObject().put("type", "library").put("name", p).put("purl", p);
		return d;
	}

	EnsureOutcome ensure(UUID release, JsonNode document, ReleaseSbomWriter writer) throws Exception {
		try (TeaPublishLock.Lease lease = teaPublishLock.acquire(release, Duration.ofSeconds(10))) {
			return releaseSbomService.ensureVersion(stored(release), PROFILE, document, lease, writer, WU);
		}
	}

	List<Map<String, Object>> versionRows(UUID serial) {
		return jdbc.queryForList("SELECT uuid, version, org, release, profile_name, artifact, sha256, content_digest, "
				+ "created_by, record_data::text AS record FROM rearm.release_sbom_versions WHERE uuid = ? ORDER BY "
				+ "version", serial);
	}

	List<Map<String, Object>> headRows(UUID release) {
		return jdbc.queryForList("SELECT uuid, org, release, profile_name, artifact, latest_version FROM "
				+ "rearm.release_sboms WHERE release = ?", release);
	}

	/**
	 * A generated row bound to {@code release} as a TEA aggregate stored before the identity existed:
	 * {@code tags} as given, the stored serial {@code serial} at {@code version} (null: no internal BOM)
	 * and a retained-upload digest.
	 */
	UUID legacyAggregate(UUID release, List<TagRecord> tags, UUID serial, int version, String sha) throws Exception {
		return legacyAggregate(release, tags, serial, String.valueOf(version), sha);
	}

	/** As {@link #legacyAggregate(UUID, List, UUID, int, String)}, but {@code version} is the raw stored string
	 * (a non-integer tests the "unreadable version" refusal of adoption). */
	UUID legacyAggregate(UUID release, List<TagRecord> tags, UUID serial, String version, String sha) throws Exception {
		Artifact a = artifactService.persistArtifact(ArtifactDto.builder().uuid(UUID.randomUUID()).org(org.getUuid())
				.type(ArtifactType.BOM).displayIdentifier("legacy-tea-aggregate.cdx.json").storedIn(StoredIn.REARM)
				.version(version).build(), WU);
		ArtifactData ad = ArtifactData.dataFromRecord(sharedArtifactService.getArtifact(a.getUuid()).orElseThrow());
		if (null != serial) ad.setInternalBom(new InternalBom(serial, ArtifactBelongsTo.SYNTHETIC));
		ad.setDigestRecords(new LinkedHashSet<>(Set.of(new DigestRecord(DigestAlgorithm.SHA_256, sha,
				DigestScope.AS_UPLOADED))));
		ad.setTags(new ArrayList<>(tags));
		sharedArtifactService.saveArtifact(sharedArtifactService.getArtifact(a.getUuid()).orElseThrow(), ad, WU);
		bind(release, a.getUuid());
		return a.getUuid();
	}

	void bind(UUID release, UUID artifact) throws RelizaException {
		syntheticArtifactService.bind(release, artifact, WU);
	}
}
