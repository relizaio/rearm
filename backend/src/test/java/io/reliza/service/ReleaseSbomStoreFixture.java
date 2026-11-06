/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ReleaseSbom;
import io.reliza.model.ReleaseSbomVersion;
import io.reliza.model.ReleaseSbomVersionId;
import io.reliza.repositories.ReleaseSbomRepository;
import io.reliza.repositories.ReleaseSbomVersionRepository;

/**
 * FDAW-7: the two release SBOM tables kept in memory behind mocked repositories, with the two keys
 * the schema enforces (a version row per (uuid, version), a head per (release, profile name)) raising
 * {@link DataIntegrityViolationException} as the database would; and a real {@link ReleaseSbomService}
 * wired over them whose {@code self} is itself (no proxy: its own transaction is not emulated).
 */
final class ReleaseSbomStoreFixture {

	final ReleaseSbomRepository heads = mock(ReleaseSbomRepository.class);
	final ReleaseSbomVersionRepository versions = mock(ReleaseSbomVersionRepository.class);
	/** The head rows by serial. */
	final Map<UUID, ReleaseSbom> headRows = new LinkedHashMap<>();
	/** The version rows by "serial:version", in insert order. */
	final Map<String, ReleaseSbomVersion> versionRows = new LinkedHashMap<>();

	ReleaseSbomStoreFixture() {
		when(heads.findByReleaseAndProfileName(any(UUID.class), anyString())).thenAnswer(inv -> headRows.values()
				.stream().filter(h -> inv.getArgument(0).equals(h.getRelease())
						&& inv.getArgument(1).equals(h.getProfileName()))
				.findFirst());
		when(heads.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(headRows.get(inv.<UUID>getArgument(0))));
		when(heads.listByRelease(any(UUID.class))).thenAnswer(inv -> headRows.values().stream()
				.filter(h -> inv.getArgument(0).equals(h.getRelease()))
				.sorted(Comparator.comparing(ReleaseSbom::getProfileName)).toList());
		when(heads.save(any(ReleaseSbom.class))).thenAnswer(inv -> {
			ReleaseSbom h = inv.getArgument(0);
			if (h.isNew()) {
				if (headRows.containsKey(h.getUuid())) {
					throw new DataIntegrityViolationException("duplicate key release_sboms_pkey " + h.getUuid());
				}
				boolean taken = headRows.values().stream().anyMatch(x -> x.getRelease().equals(h.getRelease())
						&& x.getProfileName().equals(h.getProfileName()));
				if (taken) throw new DataIntegrityViolationException("duplicate key release_sboms_release_profile");
				ReflectionTestUtils.setField(h, "fresh", false);
			}
			headRows.put(h.getUuid(), h);
			return h;
		});
		when(versions.save(any(ReleaseSbomVersion.class))).thenAnswer(inv -> {
			ReleaseSbomVersion v = inv.getArgument(0);
			String key = v.getUuid() + ":" + v.getVersion();
			if (versionRows.containsKey(key)) {
				throw new DataIntegrityViolationException("duplicate key release_sbom_versions_pk " + key);
			}
			versionRows.put(key, v);
			return v;
		});
		when(versions.findById(any(ReleaseSbomVersionId.class))).thenAnswer(inv -> {
			ReleaseSbomVersionId id = inv.getArgument(0);
			return Optional.ofNullable(versionRows.get(id.getUuid() + ":" + id.getVersion()));
		});
		when(versions.findVersion(any(UUID.class), anyInt()))
				.thenAnswer(inv -> Optional.ofNullable(versionRows.get(inv.getArgument(0) + ":" + inv.getArgument(1))));
		when(versions.latest(any(UUID.class))).thenAnswer(inv -> versionRows.values().stream()
				.filter(v -> inv.getArgument(0).equals(v.getUuid()))
				.max(Comparator.comparingInt(ReleaseSbomVersion::getVersion)));
		when(versions.maxVersion(any(UUID.class))).thenAnswer(inv -> versionRows.values().stream()
				.filter(v -> inv.getArgument(0).equals(v.getUuid())).mapToInt(ReleaseSbomVersion::getVersion).max()
				.orElse(0));
	}

	/** A real service over these tables and the given collaborators. */
	ReleaseSbomService service(SyntheticArtifactService synthetic, ArtifactService artifactService,
			GetOrganizationService getOrganizationService, GetComponentService getComponentService,
			RebomService rebomService) {
		ReleaseSbomService s = new ReleaseSbomService();
		ReflectionTestUtils.setField(s, "releaseSbomRepository", heads);
		ReflectionTestUtils.setField(s, "releaseSbomVersionRepository", versions);
		ReflectionTestUtils.setField(s, "syntheticArtifactService", synthetic);
		ReflectionTestUtils.setField(s, "artifactService", artifactService);
		ReflectionTestUtils.setField(s, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(s, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(s, "rebomService", rebomService);
		ReflectionTestUtils.setField(s, "self", s);
		return s;
	}
}
