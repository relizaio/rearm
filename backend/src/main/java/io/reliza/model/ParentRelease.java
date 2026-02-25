/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class ParentRelease {

	@JsonProperty(CommonVariables.RELEASE_FIELD)
	private UUID release;
	@JsonProperty
	private List<UUID> deliverables = new LinkedList<>();
	
	@Override
	public int hashCode() {
		String releasePart = this.release.toString();
		String deliverablePart = "";
		if (null != this.deliverables) {
			var sortedDeliverables = new LinkedList<>(this.deliverables);
			Collections.sort(sortedDeliverables);
			deliverablePart = sortedDeliverables.toString();
		}
		
		String allForHash = releasePart + deliverablePart;
		
		return allForHash.hashCode();
	}
	
	@Override
	public boolean equals (Object other) {
		boolean equals = true;
		if (!(other instanceof ParentRelease)) {
			equals = false;
		}
		if (equals) {
			equals = (this.hashCode() == other.hashCode());
		}
		return equals;
	}
	
	public static ParentRelease minimalParentReleaseFactory(UUID releaseUuid, UUID... deliverableUuid) {
		ParentRelease pr = new ParentRelease();
		pr.setRelease(releaseUuid);
		List<UUID> deliverableList = (null != deliverableUuid) ? Arrays.asList(deliverableUuid): List.of(); 
		pr.setDeliverables(deliverableList);
		return pr;
	}
}