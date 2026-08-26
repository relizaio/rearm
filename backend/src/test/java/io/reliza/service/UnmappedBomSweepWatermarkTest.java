/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.Artifact;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Watermark semantics of the unmapped-BOM sweep: empty windows advance the
 * persisted watermark (this is what killed the every-tick full-history walk
 * that outgrew the 120s query timeout in production), a window holding an
 * orphan pins it (the heal path may leave rows unmapped for retry — rebom is
 * unavailable in this test rig, which conveniently IS that case), and a
 * healed orphan releases it one sweep later.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class UnmappedBomSweepWatermarkTest {

	@Autowired private SbomComponentService sbomComponentService;
	@Autowired private SystemInfoService systemInfoService;
	@Autowired private ArtifactRepository artifactRepository;
	@Autowired private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Autowired private TestInitializer testInitializer;

	/**
	 * The test database is shared across suites and accumulates BOM
	 * artifacts other tests never mapped; any of them would pin the
	 * watermark and couple these assertions to neighbouring suites. Map
	 * whatever is currently unmapped so each test starts from a clean,
	 * fully-verified estate.
	 */
	@org.junit.jupiter.api.BeforeEach
	void mapForeignOrphans() {
		java.util.List<UUID> foreign = artifactRepository.findUnmappedBomArtifactUuidsInWindow(
				ZonedDateTime.now().minusYears(20), ZonedDateTime.now(), 10000);
		for (UUID artifactUuid : foreign) {
			mapArtifact(testInitializer.obtainOrganization().getUuid(), artifactUuid);
		}
	}

	private Artifact saveBomArtifact(UUID org, ZonedDateTime createdDate) {
		Artifact a = new Artifact();
		a.setUuid(UUID.randomUUID());
		Map<String, Object> rd = new LinkedHashMap<>();
		rd.put("uuid", a.getUuid().toString());
		rd.put("org", org.toString());
		rd.put("type", "BOM");
		Map<String, Object> internalBom = new LinkedHashMap<>();
		internalBom.put("id", UUID.randomUUID().toString());
		internalBom.put("belongsTo", "RELEASE");
		rd.put("internalBom", internalBom);
		a.setRecordData(rd);
		a = artifactRepository.save(a);
		a.setCreatedDate(createdDate);
		return artifactRepository.save(a);
	}

	private void mapArtifact(UUID org, UUID artifactUuid) {
		ArtifactCanonicalMap m = new ArtifactCanonicalMap();
		m.setOrg(org);
		m.setArtifactUuid(artifactUuid);
		m.setCanonicalArtifactUuid(artifactUuid);
		artifactCanonicalMapRepository.save(m);
	}

	@Test
	public void emptyAndMappedHistoryAdvancesWatermarkToCutoff() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// Mapped BOM deep in history: verified windows must skip over it.
		Artifact mapped = saveBomArtifact(org, ZonedDateTime.now().minusDays(200));
		mapArtifact(org, mapped.getUuid());
		systemInfoService.setUnmappedBomSweepWatermark(null);

		// 200 days at 30-day windows needs 7 windows; one tick allows 12.
		sbomComponentService.sweepUnmappedBomArtifacts(25);

		ZonedDateTime watermark = systemInfoService.getUnmappedBomSweepWatermark();
		assertNotNull(watermark, "watermark must be persisted after the sweep");
		// Advanced to the cutoff (now - min age), i.e. the whole verified
		// history will never be rescanned.
		assertTrue(watermark.isAfter(ZonedDateTime.now().minusMinutes(95)),
				"watermark should reach the cutoff over fully-mapped history, got " + watermark);
	}

	@Test
	public void orgLessBomRowCannotPinTheWatermark() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// A BOM row without an org is unhealable by construction; the query
		// must exclude it rather than let it pin the watermark forever.
		Artifact orgless = new Artifact();
		orgless.setUuid(UUID.randomUUID());
		Map<String, Object> rd = new LinkedHashMap<>();
		rd.put("uuid", orgless.getUuid().toString());
		rd.put("type", "BOM");
		Map<String, Object> internalBom = new LinkedHashMap<>();
		internalBom.put("id", UUID.randomUUID().toString());
		rd.put("internalBom", internalBom);
		orgless.setRecordData(rd);
		orgless = artifactRepository.save(orgless);
		orgless.setCreatedDate(ZonedDateTime.now().minusDays(150));
		artifactRepository.save(orgless);
		// A mapped sibling proves the sweep still walks the estate normally.
		Artifact mapped = saveBomArtifact(org, ZonedDateTime.now().minusDays(150));
		mapArtifact(org, mapped.getUuid());
		systemInfoService.setUnmappedBomSweepWatermark(null);

		sbomComponentService.sweepUnmappedBomArtifacts(25);

		ZonedDateTime watermark = systemInfoService.getUnmappedBomSweepWatermark();
		assertNotNull(watermark);
		assertTrue(watermark.isAfter(ZonedDateTime.now().minusDays(1)),
				"org-less rows must not pin the watermark, got " + watermark);
		// Map it so the shared test DB does not accumulate a fixture per run.
		mapArtifact(org, orgless.getUuid());
	}

	@Test
	public void tailWindowIsScannedWhileWatermarkIsPinned() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// Poison: an orphan deep in history that the heal path cannot fix
		// here (rebom is unavailable in this rig) — pins the watermark.
		Artifact poison = saveBomArtifact(org, ZonedDateTime.now().minusDays(200));
		// Fresh orphan inside the newest window — the tail guarantee must
		// still attempt it despite the pin.
		Artifact fresh = saveBomArtifact(org, ZonedDateTime.now().minusHours(2));
		systemInfoService.setUnmappedBomSweepWatermark(null);

		int attempts = sbomComponentService.sweepUnmappedBomArtifacts(25);

		assertTrue(attempts >= 2,
				"both the pinned-window poison and the tail orphan must be attempted, got " + attempts);
		ZonedDateTime pinned = systemInfoService.getUnmappedBomSweepWatermark();
		assertTrue(pinned == null || !pinned.isAfter(ZonedDateTime.now().minusDays(170)),
				"watermark must stay pinned at the poison window, got " + pinned);
		// Cleanup so this suite's poison rows don't pin neighbouring tests.
		mapArtifact(org, poison.getUuid());
		mapArtifact(org, fresh.getUuid());
	}

	@Test
	public void tailCoversFreshOrphansForMidBandPins() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// Pin age ~45d: beyond its own window's reach of the cutoff, yet
		// inside cutoff-30d — the dead band where a scannedUpTo < cutoff-30d
		// tail guard suppressed the probe and fresh orphans waited.
		Artifact poison = saveBomArtifact(org, ZonedDateTime.now().minusDays(45));
		Artifact fresh = saveBomArtifact(org, ZonedDateTime.now().minusHours(2));
		systemInfoService.setUnmappedBomSweepWatermark(null);

		int attempts = sbomComponentService.sweepUnmappedBomArtifacts(25);

		assertTrue(attempts >= 2,
				"a mid-band pin must not suppress the tail probe; fresh orphan skipped, attempts=" + attempts);
		// Cleanup so this suite's poison rows don't pin neighbouring tests.
		mapArtifact(org, poison.getUuid());
		mapArtifact(org, fresh.getUuid());
	}

	@Test
	public void orphanPinsWatermarkUntilHealedThenReleases() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		ZonedDateTime orphanCreated = ZonedDateTime.now().minusDays(100);
		Artifact orphan = saveBomArtifact(org, orphanCreated);
		systemInfoService.setUnmappedBomSweepWatermark(null);

		// Heal is attempted but rebom is unavailable here, so the artifact
		// stays unmapped — the watermark must NOT advance past its window.
		sbomComponentService.sweepUnmappedBomArtifacts(25);
		ZonedDateTime pinned = systemInfoService.getUnmappedBomSweepWatermark();
		assertTrue(pinned == null || !pinned.isAfter(orphanCreated),
				"watermark must not pass an unhealed orphan, got " + pinned);

		// Simulate the heal (map row appears) — the next sweep's window is
		// clean and the watermark moves through to the cutoff.
		mapArtifact(org, orphan.getUuid());
		sbomComponentService.sweepUnmappedBomArtifacts(25);
		ZonedDateTime released = systemInfoService.getUnmappedBomSweepWatermark();
		assertNotNull(released);
		assertFalse(released.isBefore(orphanCreated),
				"watermark must advance once the orphan is mapped, got " + released);
	}
}
