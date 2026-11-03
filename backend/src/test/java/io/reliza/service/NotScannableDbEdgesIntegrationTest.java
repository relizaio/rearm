/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.Artifact;
import io.reliza.model.SbomComponent;
import io.reliza.ws.App;

/**
 * TPF-126 mutation round, against the real database: the stall sampler's universe is the counter's
 * (matchable pkg:/cpe: rows of the same canonical and organization, not root, not terminal), and the carry-forward
 * seed write loses to both a scan and a not-scannable verdict.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class NotScannableDbEdgesIntegrationTest extends FanOutCandidateTestBase {

	private List<UUID> sampled(UUID org) {
		return artifactCanonicalMapRepository.findNeverScannedMappedArtifactCanonicalsOlderThan(org,
				ZonedDateTime.now().plusHours(1), 50);
	}

	// ---------------- the sampler ----------------

	@Test
	public void theSamplerTakesACanonicalWithAPkgOrACpeComponentAndNoOther() {
		UUID org = newOrg();
		Mapped pkg = mapBom(org, null, null, component(org, uniquePurl("pkg"), 0));
		Mapped cpe = mapBom(org, null, null, component(org, "cpe:2.3:a:vendor:product:" + UUID.randomUUID(), 0));
		Mapped neither = mapBom(org, null, null, component(org, "generic:thing-" + UUID.randomUUID(), 0));
		Mapped empty = mapBom(org, null, null);

		List<UUID> found = sampled(org);

		assertTrue(found.contains(pkg.canonical()), "a pkg: component is matchable");
		assertTrue(found.contains(cpe.canonical()), "so is a cpe: one");
		assertFalse(found.contains(neither.canonical()));
		assertFalse(found.contains(empty.canonical()));
	}

	@Test
	public void theSamplerLeavesOutACanonicalWhoseOnlyMatchableRowsAreTerminal() {
		UUID org = newOrg();
		SbomComponent terminal = component(org, uniquePurl("terminal"), 0);
		Mapped allTerminal = mapBom(org, null, null, terminal);
		SbomComponent live = component(org, uniquePurl("live"), 0);
		SbomComponent dead = component(org, uniquePurl("dead"), 0);
		Mapped mixed = mapBom(org, null, null, live, dead);
		assertEquals(1, sbomComponentRepository.markEnrichmentTerminal(terminal.getUuid(), "OWN_BOM_PULLED_UNMATCHED"));
		assertEquals(1, sbomComponentRepository.markEnrichmentTerminal(dead.getUuid(), "OWN_BOM_PULLED_UNMATCHED"));

		List<UUID> found = sampled(org);

		assertFalse(found.contains(allTerminal.canonical()), "every matchable row is terminal: nothing to scan");
		assertTrue(found.contains(mixed.canonical()), "one live matchable row is enough");
	}

	@Test
	public void theSamplerLooksAtTheCanonicalsOwnRowsAndNotAtOtherCanonicalsOfTheOrg() {
		UUID org = newOrg();
		Mapped matchable = mapBom(org, null, null, component(org, uniquePurl("has-one"), 0));
		Mapped bare = mapBom(org, null, null, component(org, "generic:bare-" + UUID.randomUUID(), 0));

		List<UUID> found = sampled(org);

		assertTrue(found.contains(matchable.canonical()));
		assertFalse(found.contains(bare.canonical()), "another canonical's matchable row is no reason to sample this one");
	}

	@Test
	public void theSamplerIgnoresRowsOfAnotherOrganizationHoldingTheSameCanonicalUuid() {
		UUID org1 = newOrg();
		UUID org2 = newOrg();
		UUID canonical = UUID.randomUUID();
		Artifact a1 = saveArtifactWithLastScanned(org1, null);
		mapRow(org1, a1.getUuid(), canonical, null);
		holdComponent(org1, canonical, component(org1, "generic:org1-only-" + UUID.randomUUID(), 0));
		holdComponent(org2, canonical, component(org2, uniquePurl("org2-matchable"), 0));

		assertFalse(sampled(org1).contains(canonical),
				"org1's canonical holds nothing matchable of its own; org2's row under the same uuid does not count");
	}

	// ---------------- the seed write ----------------

	@Test
	public void theSeedWriteLandsOnlyOnAnArtifactNoScanAndNoVerdictHasReached() {
		UUID org = newOrg();
		Artifact plain = saveArtifactWithLastScanned(org, null);
		Artifact scanned = saveArtifactWithLastScanned(org, STALE_EPOCH);
		Artifact marked = saveArtifactWithLastScanned(org, null);
		assertEquals(1, artifactRepository.stampNotScannable(marked.getUuid(), "ONLY_ROOT", 1, 1, 0, 0, STALE_EPOCH));
		String seed = "{\"projectName\":\"seeded\"}";

		assertEquals(1, artifactRepository.updateMetricsIfStillUnscanned(plain.getUuid(), seed), "nothing reached it");
		assertEquals(0, artifactRepository.updateMetricsIfStillUnscanned(scanned.getUuid(), seed), "a scan got there first");
		assertEquals(0, artifactRepository.updateMetricsIfStillUnscanned(marked.getUuid(), seed),
				"a not-scannable verdict got there first");
		Map<String, Object> after = artifactRepository.findById(marked.getUuid()).orElseThrow().getMetrics();
		assertTrue(after.containsKey("notScannable"), "the verdict stands");
		assertEquals("seeded", artifactRepository.findById(plain.getUuid()).orElseThrow().getMetrics().get("projectName"));
		assertEquals("keep-me", artifactRepository.findById(scanned.getUuid()).orElseThrow().getMetrics().get("projectName"));
	}
}
