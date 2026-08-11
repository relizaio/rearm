/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.Organization;
import io.reliza.model.SbomComponent;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Covers the stale-canonical-qualifier sweep
 * ({@link SbomComponentService#sweepStaleCanonicalQualifiers}), which repoints
 * {@code artifact_sbom_components} rows written under the old qualifier-stripping
 * canonicalization onto the correct qualifier-bearing {@code sbom_components} row.
 *
 * <p>The repair is asserted through {@code repointCanonicalArtifactQualifiers}
 * rather than through the batch entry point wherever the assertion is about one
 * canonical's outcome: the batch picks up whatever else the shared test database
 * has left below the current form version, so anything phrased as "the sweep
 * returned exactly these" would be coupled to neighbouring suites. The one test
 * that does drive the batch asserts only the flow_control stamp on its own rows.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class CanonicalQualifierSweepTest {

	@Autowired private SbomComponentService sbomComponentService;
	@Autowired private SbomComponentRepository sbomComponentRepository;
	@Autowired private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Autowired private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Autowired private TestInitializer testInitializer;

	private static final String STRIPPED = "pkg:apk/alpine/musl@1.2.4";
	private static final String EXACT = "pkg:apk/alpine/musl@1.2.4?distro=alpine-3.18&arch=x86_64";
	private static final String CORRECTED = "pkg:apk/alpine/musl@1.2.4?distro=alpine-3.18";

	@Test
	public void repointsStaleMappingOntoQualifierBearingCanonical() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		SbomComponent stripped = saveComponent(org, STRIPPED, null, null);
		UUID canonicalArtifact = UUID.randomUUID();
		ArtifactSbomComponent mapping = saveMapping(org, canonicalArtifact, stripped.getUuid(), EXACT, null);

		int repaired = sbomComponentService.repointCanonicalArtifactQualifiers(org, canonicalArtifact);

		assertEquals(1, repaired, "the stale mapping must be counted as repaired");
		UUID nowPointsAt = artifactSbomComponentRepository.findById(mapping.getUuid())
				.orElseThrow().getSbomComponentUuid();
		assertNotEquals(stripped.getUuid(), nowPointsAt, "mapping must no longer point at the stripped canonical");
		assertEquals(CORRECTED, sbomComponentRepository.findById(nowPointsAt).orElseThrow().getCanonicalPurl(),
				"mapping must point at the canonical derived from exact_purl, keeping distro and dropping arch");
	}

	/**
	 * The corrected canonical inherits licenses and enriched_at from the row it
	 * replaces. A cold row would be withheld by submitOrg's enrichment gate until
	 * the puller happened to stamp it, which is a coverage gap plus a burst of
	 * [SYNTHETIC-STALL] noise for what is the same package.
	 */
	@Test
	public void carriesEnrichmentForwardOntoTheCorrectedCanonical() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		List<Map<String, Object>> licenses = new ArrayList<>();
		Map<String, Object> lic = new LinkedHashMap<>();
		lic.put("id", "MIT");
		licenses.add(lic);
		ZonedDateTime enrichedAt = ZonedDateTime.now().minusDays(3);
		SbomComponent stripped = saveComponent(org, STRIPPED, licenses, enrichedAt);
		UUID canonicalArtifact = UUID.randomUUID();
		saveMapping(org, canonicalArtifact, stripped.getUuid(), EXACT, null);

		sbomComponentService.repointCanonicalArtifactQualifiers(org, canonicalArtifact);

		SbomComponent corrected = sbomComponentRepository
				.findByOrgAndCanonicalPurl(org, CORRECTED).orElseThrow();
		assertNotNull(corrected.getEnrichedAt(), "corrected canonical must not start un-enriched");
		assertEquals("MIT", corrected.getLicenses().get(0).get("id"),
				"licenses must be carried over from the stripped canonical");
	}

	/** A mapping already on the correct canonical is left completely alone. */
	@Test
	public void alreadyCorrectMappingIsANoOp() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		SbomComponent correct = saveComponent(org, CORRECTED, null, null);
		UUID canonicalArtifact = UUID.randomUUID();
		ArtifactSbomComponent mapping = saveMapping(org, canonicalArtifact, correct.getUuid(), EXACT, null);

		int repaired = sbomComponentService.repointCanonicalArtifactQualifiers(org, canonicalArtifact);

		assertEquals(0, repaired, "an already-correct mapping must not be counted as repaired");
		assertEquals(correct.getUuid(), artifactSbomComponentRepository.findById(mapping.getUuid())
				.orElseThrow().getSbomComponentUuid(), "the mapping must be untouched");
	}

	/**
	 * A purl type with no identity-bearing qualifiers keeps its bare canonical.
	 * This is the case the user tolerance rests on: a qualifier-less canonical is
	 * only wrong when some exact_purl feeding it carried a preserved qualifier.
	 */
	@Test
	public void purlWithoutPreservedQualifiersIsNotRewritten() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		SbomComponent npm = saveComponent(org, "pkg:npm/lodash@4.17.21", null, null);
		UUID canonicalArtifact = UUID.randomUUID();
		ArtifactSbomComponent mapping = saveMapping(org, canonicalArtifact, npm.getUuid(),
				"pkg:npm/lodash@4.17.21?foo=bar", null);

		int repaired = sbomComponentService.repointCanonicalArtifactQualifiers(org, canonicalArtifact);

		assertEquals(0, repaired, "npm has no preserved qualifiers, so nothing is stale");
		assertEquals(npm.getUuid(), artifactSbomComponentRepository.findById(mapping.getUuid())
				.orElseThrow().getSbomComponentUuid());
	}

	/**
	 * Parent edges embed the parent's canonical uuid and purl, so they go stale by
	 * the same mechanism and must be rewritten from their own sourceExactPurl -
	 * otherwise the dependency graph keeps pointing into the stripped rows.
	 */
	@Test
	public void rewritesStaleParentEdges() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		SbomComponent strippedParent = saveComponent(org, STRIPPED, null, null);
		SbomComponent child = saveComponent(org, "pkg:npm/leftpad@1.0.0", null, null);
		UUID canonicalArtifact = UUID.randomUUID();

		Map<String, Object> parent = new LinkedHashMap<>();
		parent.put("sourceSbomComponentUuid", strippedParent.getUuid().toString());
		parent.put("sourceCanonicalPurl", STRIPPED);
		parent.put("relationshipType", "DEPENDS_ON");
		parent.put("sourceExactPurl", EXACT);
		parent.put("targetExactPurl", "pkg:npm/leftpad@1.0.0");
		List<Map<String, Object>> parents = new ArrayList<>();
		parents.add(parent);

		ArtifactSbomComponent mapping = saveMapping(org, canonicalArtifact, child.getUuid(),
				"pkg:npm/leftpad@1.0.0", parents);

		sbomComponentService.repointCanonicalArtifactQualifiers(org, canonicalArtifact);

		Map<String, Object> rewritten = artifactSbomComponentRepository.findById(mapping.getUuid())
				.orElseThrow().getParents().get(0);
		assertEquals(CORRECTED, rewritten.get("sourceCanonicalPurl"),
				"the parent edge's canonical purl must be corrected");
		assertNotEquals(strippedParent.getUuid().toString(), rewritten.get("sourceSbomComponentUuid"),
				"the parent edge must no longer reference the stripped canonical");
	}

	/**
	 * The batch entry point stamps flow_control so a verified canonical drops out
	 * of the pickup query permanently - that is what makes the sweep converge
	 * instead of re-examining the estate every tick.
	 */
	@Test
	public void sweepStampsFlowControlSoCanonicalIsNotRevisited() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		SbomComponent stripped = saveComponent(org, STRIPPED, null, null);
		UUID canonicalArtifact = UUID.randomUUID();
		saveMapping(org, canonicalArtifact, stripped.getUuid(), EXACT, null);
		ArtifactCanonicalMap map = saveCanonicalMap(org, canonicalArtifact);
		assertTrue(map.getFlowControl() == null
				|| map.getFlowControl().canonicalFormVersion() == null,
				"fixture must start unverified");

		sbomComponentService.sweepStaleCanonicalQualifiers(500);

		ArtifactCanonicalMap after = artifactCanonicalMapRepository.findById(map.getUuid()).orElseThrow();
		assertNotNull(after.getFlowControl(), "sweep must write flow_control");
		assertEquals(SbomComponentService.CANONICAL_FORM_VERSION,
				after.getFlowControl().canonicalFormVersion(),
				"the canonical must be stamped at the current canonical form version");
		assertFalse(artifactCanonicalMapRepository
				.findPendingCanonicalForm(SbomComponentService.CANONICAL_FORM_VERSION, 5000)
				.stream().anyMatch(p -> canonicalArtifact.equals(p.getCanonicalArtifactUuid())),
				"a stamped canonical must no longer appear in the pickup query");
	}

	/**
	 * THE live-incident regression: a mapping already pointing at rebom's
	 * byte-form canonical (epoch colon raw) must be a no-op. v1 of the sweep
	 * compared against the Java PackageURL round-trip (%3A) and "repaired" every
	 * such row into an encoding-variant duplicate.
	 */
	@Test
	public void rebomEncodedCanonicalWithEpochColonIsANoOp() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		SbomComponent rebomForm = saveComponent(org, "pkg:deb/debian/attr@1:2.5.2-3?distro=debian-13", null, null);
		UUID canonicalArtifact = UUID.randomUUID();
		ArtifactSbomComponent mapping = saveMapping(org, canonicalArtifact, rebomForm.getUuid(),
				"pkg:deb/debian/attr@1:2.5.2-3?arch=amd64&distro=debian-13&distro_name=trixie&epoch=1", null);

		int repaired = sbomComponentService.repointCanonicalArtifactQualifiers(org, canonicalArtifact);

		assertEquals(0, repaired,
				"encoding variants are the same identity; repairing them mints duplicate canonicals");
		assertEquals(rebomForm.getUuid(), artifactSbomComponentRepository.findById(mapping.getUuid())
				.orElseThrow().getSbomComponentUuid());
	}

	/**
	 * A mapping stranded on an encoding-variant duplicate (the v1-incident state)
	 * is deliberately NOT repaired: the drifted row denotes the same identity, so
	 * the semantic guard sees no defect. This pins the design decision that the
	 * sweep repairs identity defects, not encoding preferences -- making the
	 * byte form authoritative here would mass-churn the legitimate mixed-era
	 * rows (raw + vs %2B) the guard exists to protect. Cleanup of such
	 * strandings is a one-time surgical operation, not sweep behaviour.
	 */
	@Test
	public void mappingOnEncodingVariantDuplicateIsLeftAlone() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		saveComponent(org, "pkg:deb/debian/attr@1:2.5.2-3?distro=debian-13", null, null);
		SbomComponent javaDrifted = saveComponent(org, "pkg:deb/debian/attr@1%3A2.5.2-3?distro=debian-13", null, null);
		UUID canonicalArtifact = UUID.randomUUID();
		ArtifactSbomComponent mapping = saveMapping(org, canonicalArtifact, javaDrifted.getUuid(),
				"pkg:deb/debian/attr@1:2.5.2-3?arch=amd64&distro=debian-13&distro_name=trixie&epoch=1", null);

		int repaired = sbomComponentService.repointCanonicalArtifactQualifiers(org, canonicalArtifact);

		assertEquals(0, repaired,
				"an encoding-variant stranding is the same identity -- repairing it would reintroduce churn");
		assertEquals(javaDrifted.getUuid(), artifactSbomComponentRepository.findById(mapping.getUuid())
				.orElseThrow().getSbomComponentUuid());
	}

	/**
	 * Mint-path guard for mixed-encoding data: when the byte-equal lookup misses
	 * but a row with the SAME identity exists in another percent-encoding era
	 * (raw + vs %2B), the sweep must reuse that row -- minting would split the
	 * identity between encoding variants, the same duplicate class the
	 * encoding-drift incident produced.
	 */
	@Test
	public void reusesEncodingVariantRowInsteadOfMintingDuplicate() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// The stale row the mapping points at (no distro).
		SbomComponent stripped = saveComponentNamed(org, "pkg:deb/debian/libstdc%2B%2B6@12.2.0-14+deb12u1",
				"libstdc++6", "12.2.0-14+deb12u1");
		// A correct row already exists -- in the RAW-plus era's encoding for the
		// version, while the exact_purl below uses the %2B era.
		SbomComponent otherEra = saveComponentNamed(org,
				"pkg:deb/debian/libstdc%2B%2B6@12.2.0-14+deb12u1?distro=debian-12",
				"libstdc++6", "12.2.0-14+deb12u1");
		UUID canonicalArtifact = UUID.randomUUID();
		ArtifactSbomComponent mapping = saveMapping(org, canonicalArtifact, stripped.getUuid(),
				"pkg:deb/debian/libstdc%2B%2B6@12.2.0-14%2Bdeb12u1?arch=amd64&distro=debian-12", null);

		int repaired = sbomComponentService.repointCanonicalArtifactQualifiers(org, canonicalArtifact);

		assertEquals(1, repaired);
		assertEquals(otherEra.getUuid(), artifactSbomComponentRepository.findById(mapping.getUuid())
				.orElseThrow().getSbomComponentUuid(),
				"the existing encoding-variant row must be reused, not a freshly minted duplicate");
	}

	/**
	 * Orphaned-component GC safety triple: delete ONLY unreferenced+unbucketed
	 * rows; anything a mapping references or a bucket carries must survive.
	 */
	@Test
	public void gcDeletesOnlyUnreferencedUnbucketedComponents() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// 1. pure debris: unreferenced + unbucketed -> deleted
		SbomComponent debris = saveComponent(org, "pkg:npm/debris-" + UUID.randomUUID() + "@1.0", null, null);
		// 2. referenced (mapping row points at it) -> survives
		SbomComponent referenced = saveComponent(org, "pkg:npm/referenced-" + UUID.randomUUID() + "@1.0", null, null);
		saveMapping(org, UUID.randomUUID(), referenced.getUuid(), "pkg:npm/referenced@1.0", null);
		// 3. bucketed (even if unreferenced) -> survives
		SbomComponent bucketed = saveComponent(org, "pkg:npm/bucketed-" + UUID.randomUUID() + "@1.0", null, null);
		bucketed.setSyntheticBucketIndex(7);
		bucketed = sbomComponentRepository.save(bucketed);

		sbomComponentService.gcOrphanedComponents(100000);

		assertFalse(sbomComponentRepository.findById(debris.getUuid()).isPresent(),
				"unreferenced+unbucketed debris must be GC'd");
		assertTrue(sbomComponentRepository.findById(referenced.getUuid()).isPresent(),
				"a mapping-referenced component must never be GC'd");
		assertTrue(sbomComponentRepository.findById(bucketed.getUuid()).isPresent(),
				"a bucketed component must never be GC'd -- buckets/ref_maps know it");
	}

	/**
	 * Terminal state (V75): marking a component enrichment-terminal removes it
	 * from the matchable universe -- candidate window and dirty-check -- while
	 * enriched_at stays NULL, and the jsonb maps back through the entity.
	 */
	@Test
	public void terminalComponentLeavesTheMatchableUniverse() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		SbomComponent stuck = saveComponentNamed(org, "pkg:generic/stuck-" + UUID.randomUUID() + "@1.0",
				"stuck", "1.0");
		assertTrue(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(org.toString(), 1000)
				.stream().anyMatch(c -> c.getUuid().equals(stuck.getUuid())),
				"un-enriched matchable row must be a candidate before terminal");

		int marked = sbomComponentRepository.markEnrichmentTerminal(stuck.getUuid(), "OWN_BOM_PULLED_UNMATCHED");
		assertEquals(1, marked);

		assertFalse(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(org.toString(), 1000)
				.stream().anyMatch(c -> c.getUuid().equals(stuck.getUuid())),
				"terminal row must leave the candidate window");
		SbomComponent reloaded = sbomComponentRepository.findById(stuck.getUuid()).orElseThrow();
		assertTrue(reloaded.isEnrichmentTerminal(), "flow_control jsonb must map back through the entity");
		assertEquals("OWN_BOM_PULLED_UNMATCHED", reloaded.getFlowControl().enrichmentTerminalReason());
		assertTrue(reloaded.getEnrichedAt() == null, "terminal must NOT fake enrichment");
		assertEquals(1, sbomComponentRepository.countEnrichmentTerminal(org.toString()));
	}

	// ---------------- fixtures ----------------

	private SbomComponent saveComponentNamed(UUID org, String canonicalPurl, String name, String version) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(org);
		sc.setCanonicalPurl(canonicalPurl);
		Map<String, Object> rec = new HashMap<>();
		rec.put("name", name);
		rec.put("version", version);
		rec.put("type", "library");
		sc.setRecordData(rec);
		return sbomComponentRepository.save(sc);
	}

	private SbomComponent saveComponent(UUID org, String canonicalPurl,
			List<Map<String, Object>> licenses, ZonedDateTime enrichedAt) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(org);
		sc.setCanonicalPurl(canonicalPurl);
		Map<String, Object> rec = new HashMap<>();
		rec.put("name", "musl");
		rec.put("version", "1.2.4");
		rec.put("type", "library");
		sc.setRecordData(rec);
		if (licenses != null) sc.setLicenses(licenses);
		if (enrichedAt != null) sc.setEnrichedAt(enrichedAt);
		return sbomComponentRepository.save(sc);
	}

	private ArtifactSbomComponent saveMapping(UUID org, UUID canonicalArtifactUuid,
			UUID sbomComponentUuid, String exactPurl, List<Map<String, Object>> parents) {
		ArtifactSbomComponent asc = new ArtifactSbomComponent();
		asc.setOrg(org);
		asc.setCanonicalArtifactUuid(canonicalArtifactUuid);
		asc.setSbomComponentUuid(sbomComponentUuid);
		asc.setExactPurl(exactPurl);
		asc.setParents(parents != null ? parents : new ArrayList<>());
		return artifactSbomComponentRepository.save(asc);
	}

	private ArtifactCanonicalMap saveCanonicalMap(UUID org, UUID canonicalArtifactUuid) {
		ArtifactCanonicalMap m = new ArtifactCanonicalMap();
		m.setOrg(org);
		m.setArtifactUuid(UUID.randomUUID());
		m.setCanonicalArtifactUuid(canonicalArtifactUuid);
		return artifactCanonicalMapRepository.save(m);
	}
}
