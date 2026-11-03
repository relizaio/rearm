/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables;
import io.reliza.model.Release;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.ws.App;

/**
 * The org-scoped lookups check only the caller's own organization: a row of the zero organization
 * ({@link CommonVariables#EXTERNAL_PROJ_ORG_UUID}, formerly admitted next to every org as the shared
 * external-components org) is not returned to another organization, and
 * {@link SharedReleaseService#isReferenceableFromOrg} no longer accepts it.
 *
 * <p>The release rows are written straight through the repository with a random own org and
 * removed after each test, so nothing is left in the zero org (which the run-end org cleanup never
 * touches).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ZeroOrgLookupScopeTest {

	private static final UUID ZERO_ORG = CommonVariables.EXTERNAL_PROJ_ORG_UUID;

	@Autowired private ReleaseRepository releaseRepository;

	private final List<UUID> created = new ArrayList<>();

	@AfterEach
	void removeRows() {
		created.forEach(releaseRepository::deleteById);
		created.clear();
	}

	@Test
	public void aZeroOrgReleaseIsNotFoundByIdInAnotherOrg() {
		UUID ownOrg = UUID.randomUUID();
		UUID zeroRelease = saveRelease(ZERO_ORG, List.of()).getUuid();
		UUID ownRelease = saveRelease(ownOrg, List.of()).getUuid();

		assertTrue(releaseRepository.findReleaseByIdAndOrg(zeroRelease, ownOrg.toString()).isEmpty(),
				"a zero-org release is not visible from another org");
		assertTrue(releaseRepository.findReleaseByIdAndOrg(zeroRelease, ZERO_ORG.toString()).isPresent(),
				"fixture: the zero-org row exists and is found in its own org");
		assertTrue(releaseRepository.findReleaseByIdAndOrg(ownRelease, ownOrg.toString()).isPresent(),
				"an own-org release is found as before");
	}

	@Test
	public void aZeroOrgReleaseIsNotFoundByArtifactInAnotherOrg() {
		UUID ownOrg = UUID.randomUUID();
		String artifact = UUID.randomUUID().toString();
		saveRelease(ZERO_ORG, List.of(artifact));
		UUID ownRelease = saveRelease(ownOrg, List.of(artifact)).getUuid();

		assertEquals(Set.of(ownRelease), uuidsOf(releaseRepository.findReleasesByReleaseArtifact(artifact,
				ownOrg.toString())), "only the own-org release carrying the artifact");
		assertEquals(Set.of(ownRelease), new HashSet<>(releaseRepository.findReleasesByReleaseArtifacts(
				List.of(artifact), ownOrg.toString())), "the batched lookup agrees");
	}

	@Test
	public void theZeroOrgIsNotReferenceableFromAnotherOrg() {
		UUID ownOrg = UUID.randomUUID();
		assertFalse(SharedReleaseService.isReferenceableFromOrg(ZERO_ORG, ownOrg));
		assertTrue(SharedReleaseService.isReferenceableFromOrg(ownOrg, ownOrg));
		assertFalse(SharedReleaseService.isReferenceableFromOrg(UUID.randomUUID(), ownOrg));
		assertFalse(SharedReleaseService.isReferenceableFromOrg(null, ownOrg));
	}

	private Release saveRelease(UUID org, List<String> artifacts) {
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		r.setCreatedDate(ZonedDateTime.now());
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", org.toString());
		recordData.put("component", UUID.randomUUID().toString());
		recordData.put("artifacts", artifacts);
		r.setRecordData(recordData);
		Release saved = releaseRepository.save(r);
		created.add(saved.getUuid());
		return saved;
	}

	private static Set<UUID> uuidsOf(List<Release> releases) {
		return releases.stream().map(Release::getUuid).collect(Collectors.toSet());
	}
}
