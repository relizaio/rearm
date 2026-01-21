/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
	
	public Set<UUID> gatherReleaseArtifacts (ReleaseData rd) {
		Set<UUID> artifactIds = new HashSet<>(rd.getArtifacts());
		if (null != rd.getSourceCodeEntry()) {
			var sce = getSourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry()).get();
			List<UUID> sceArtIds = sce.getArtifacts().stream().filter(scea -> rd.getComponent().equals(scea.componentUuid())).map(scea -> scea.artifactUuid()).toList();
			artifactIds.addAll(sceArtIds);
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
}
