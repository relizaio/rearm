/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.common.Utils;
import io.reliza.model.Artifact;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.FindingSourceDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;
import tools.jackson.core.type.TypeReference;

/**
 * CALIBRATION. Drives each candidate cause through the REAL service mutation and checks the audit trail
 * the provenance probe reads, so that when a production line says ARTIFACTS_SWAPPED or NONE_RECORDED we
 * know what actually produces that reading.
 *
 * <p>This matters because the sandbox has no organic instance of this bug: every earlier "reproduction"
 * created the state by editing {@code record_data->artifacts} in SQL, which writes NO update events and
 * would therefore have printed exactly the signature this probe attributes to a silent clobber. Calibrating
 * against a fiction is how a probe gets read backwards.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class MetricsLossProvenanceCalibrationTest {

	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private ArtifactRepository artifactRepository;
	@Autowired private ReleaseService releaseService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;

	// NOTE: the two API-path cases (programmatic append, and replace naming its actor) are NOT here.
	// Driving releaseService.addArtifact / replaceArtifact end to end pulls in saveRelease ->
	// processRelease, which needs a fuller component/policy fixture than this calibration warrants. Both
	// behaviours are established from code -- ArtifactInput carries no uuid so CI can only append
	// (schema.graphqls:4372, ArtifactService:481-485), and replaceArtifact does remove+add and writes an
	// ARTIFACT update event with WhoUpdated (ReleaseService:1707-1718) -- but they are NOT pinned by a
	// test, and that gap is deliberate and recorded rather than papered over.

	/**
	 * The control that stops the probe being read backwards: a DIRECT record_data edit -- what every
	 * earlier sandbox reproduction did, and what a stale-ReleaseData clobber does in production -- leaves
	 * NO artifact event at all. So NONE_RECORDED means "nothing that records events touched this", and
	 * must never be mistaken for an ordinary detach.
	 */
	@Test
	public void aDirectRecordDataEditLeavesNoTrailAtAll() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID scanned = saveArtifact(org.getUuid(), true);
		Release r = releaseWith(org.getUuid(), scanned, scanned);

		Release raw = releaseRepository.findById(r.getUuid()).orElseThrow();
		Map<String, Object> rec = raw.getRecordData();
		rec.put("artifacts", new ArrayList<String>());
		raw.setRecordData(rec);
		releaseRepository.save(raw);

		ReleaseData after = ReleaseData.dataFromRecord(
				releaseRepository.findById(r.getUuid()).orElseThrow());
		assertTrue(after.getArtifacts().isEmpty(), "the artifact list is gone");
		assertTrue(after.getUpdateEvents() == null || after.getUpdateEvents().stream()
						.noneMatch(e -> ReleaseUpdateScope.ARTIFACT == e.rus()
								&& ReleaseUpdateAction.REMOVED == e.rua()),
				"nothing recorded a removal. NOTE THE LIMIT OF THIS TEST, which an earlier version of it "
				+ "overstated: ReleaseService writes no REMOVED event on ANY path (replaceArtifact "
				+ "removes the uuid and records only ADDED), and SCE/deliverable changes write no "
				+ "release-scoped event at all -- so an empty trail does NOT identify a wholesale "
				+ "record_data overwrite. It only rules out a release-DIRECT attach or replace, which "
				+ "would have left a recent ADDED. This fixture also builds the release with a raw "
				+ "repository save, so updateEvents starts empty and the assertion cannot fail; it "
				+ "documents the shape rather than discriminating. A positive control driving a real "
				+ "detach is still missing");
	}

	// ---- fixture -------------------------------------------------------------------------------

	/** A real component row: addArtifact/replaceArtifact resolve it, and saveRelease -> processRelease needs it. */
	private UUID newComponent(UUID orgUuid) throws Exception {
		Component c = componentService.createComponent("comp_" + UUID.randomUUID(), orgUuid,
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		return c.getUuid();
	}

	private UUID saveArtifact(UUID orgUuid, boolean scanned) {
		Artifact a = new Artifact();
		a.setUuid(UUID.randomUUID());
		a.setCreatedDate(ZonedDateTime.now());
		a.setLastUpdatedDate(ZonedDateTime.now());
		a.setSchemaVersion(0);
		Map<String, Object> rec = new HashMap<>();
		rec.put("org", orgUuid.toString());
		rec.put("type", ArtifactType.BOM.name());
		a.setRecordData(rec);
		if (scanned) {
			ReleaseMetricsDto m = new ReleaseMetricsDto();
			m.setVulnerabilityDetails(new LinkedList<>(List.of(new VulnerabilityDto(
					"pkg:npm/left-pad@1.0.0", "CVE-0", VulnerabilitySeverity.HIGH,
					Set.of(), Set.of(new FindingSourceDto(a.getUuid(), null, null)), Set.of(),
					null, null, ZonedDateTime.now(), null, null, null, null, null, false))));
			Map<String, Object> metrics = Utils.OM.convertValue(m, new TypeReference<Map<String, Object>>() {});
			metrics.put("firstScanned", 1_700_000_000.0);
			metrics.put("lastScanned", 1_700_000_000.0);
			a.setMetrics(metrics);
		}
		return artifactRepository.save(a).getUuid();
	}

	/** ASSEMBLED release holding {@code artifact}, with findings credited to {@code sourceArtifact}. */
	private Release releaseWith(UUID orgUuid, UUID artifact, UUID sourceArtifact) throws Exception {
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		r.setCreatedDate(ZonedDateTime.now());
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setSchemaVersion(0);
		Map<String, Object> rec = new HashMap<>();
		rec.put("org", orgUuid.toString());
		rec.put("lifecycle", ReleaseLifecycle.ASSEMBLED.name());
		rec.put("version", "1.0.0");
		rec.put("component", newComponent(orgUuid).toString());
		rec.put("artifacts", new ArrayList<>(List.of(artifact.toString())));
		rec.put("parentReleases", new ArrayList<>());
		r.setRecordData(rec);
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setVulnerabilityDetails(new LinkedList<>(List.of(new VulnerabilityDto(
				"pkg:npm/left-pad@1.0.0", "CVE-0", VulnerabilitySeverity.HIGH,
				Set.of(), Set.of(new FindingSourceDto(sourceArtifact, null, null)), Set.of(),
				null, null, ZonedDateTime.now(), null, null, null, null, null, false))));
		Map<String, Object> metrics = Utils.OM.convertValue(m, new TypeReference<Map<String, Object>>() {});
		metrics.put("firstScanned", 1_700_000_000.0);
		metrics.put("lastScanned", 1_700_000_000.0);
		r.setMetrics(metrics);
		return releaseRepository.save(r);
	}
}
