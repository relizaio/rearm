/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.ReleaseData;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ArtifactGatherService {

	@Autowired
    private GetSourceCodeEntryService getSourceCodeEntryService;
	
	@Autowired
    private VariantService variantService;
	
	@Autowired
    private GetDeliverableService getDeliverableService;

	/** A release's reference to a source code entry that does not exist. */
	private record DanglingSce(UUID release, UUID sourceCodeEntry) {}

	/**
	 * Dangling references already reported, per process: the metrics compute
	 * and every release save gather again, so without this one bad reference
	 * would log on each of them. Cleared when it reaches
	 * {@link #MAX_REPORTED_DANGLING_SCE}, so a mass deletion cannot grow it
	 * without bound; the references are then reported once more.
	 */
	private final Set<DanglingSce> reportedDanglingSce = ConcurrentHashMap.newKeySet();

	private static final int MAX_REPORTED_DANGLING_SCE = 10_000;

	/**
	 * The artifacts of a release: its own, those of its source code entry
	 * (the commit-scoped ones, and those tagged with the release's
	 * component), and those of its variants' outbound deliverables. A
	 * reference to a source code entry or a deliverable that does not exist
	 * contributes nothing and is logged, rather than failing every caller
	 * (the release metrics compute, the release save's artifact collection).
	 */
	public Set<UUID> gatherReleaseArtifacts (ReleaseData rd) {
		Set<UUID> artifactIds = new HashSet<>(rd.getArtifacts());
		if (null != rd.getSourceCodeEntry()) {
			var sceData = getSourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry());
			if (sceData.isPresent()) {
				// null component tag = commit-scoped artifact (signature / signed
				// payload) -- belongs to every release referencing this SCE.
				List<UUID> sceArtIds = sceData.get().getArtifacts().stream()
						.filter(scea -> scea.componentUuid() == null || rd.getComponent().equals(scea.componentUuid()))
						.map(scea -> scea.artifactUuid()).toList();
				artifactIds.addAll(sceArtIds);
			} else {
				reportDanglingSce(rd);
			}
		}
		// skip inbound deliverables
//		if (null != rd.getInboundDeliverables() && !rd.getInboundDeliverables().isEmpty()) {
//			rd.getInboundDeliverables().forEach(inbd -> {
//				var arts = deliverableService.getDeliverableData(inbd).get().getArtifacts();
//				if (null != arts && !arts.isEmpty()) artifactIds.addAll(arts);
//			});		
//		}
		variantService.getVariantsOfRelease(rd.getUuid()).forEach(rvd -> {
			if (null != rvd.getOutboundDeliverables() && !rvd.getOutboundDeliverables().isEmpty()) {
				rvd.getOutboundDeliverables().forEach(outbd -> {
					var deliverableData = getDeliverableService.getDeliverableData(outbd);
					if (deliverableData.isPresent()) {
						var arts = deliverableData.get().getArtifacts();
						if (null != arts && !arts.isEmpty()) {
							artifactIds.addAll(arts);
						}
					} else {
						log.warn("SBOM_CHANGELOG: Deliverable {} not found", outbd);
					}
				});
			}
		});
		return artifactIds;
	}

	/**
	 * ERROR, not WARN: the instances where this matters retain ERROR only
	 * (see the [ARTIFACT-REF-MISSING] probe of the metrics compute, which
	 * cannot see this case: the entry's artifacts are never gathered). Once
	 * per reference per process keeps the volume bounded.
	 */
	private void reportDanglingSce(ReleaseData rd) {
		if (reportedDanglingSce.size() >= MAX_REPORTED_DANGLING_SCE) reportedDanglingSce.clear();
		if (reportedDanglingSce.add(new DanglingSce(rd.getUuid(), rd.getSourceCodeEntry()))) {
			log.error("[SCE-REF-MISSING] Release {} references a missing source code entry {};"
					+ " its artifacts are left out of the release", rd.getUuid(), rd.getSourceCodeEntry());
		}
	}
}
