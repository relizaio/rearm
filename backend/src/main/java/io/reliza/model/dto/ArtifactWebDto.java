/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.reliza.model.ArtifactData;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EqualsAndHashCode(callSuper=true)
public class ArtifactWebDto extends ArtifactData {
	private UUID componentUuid;
	
	private ArtifactWebDto() {}
	
	public static ArtifactWebDto fromData (ArtifactData ad, UUID component) {
		ArtifactWebDto awd = new ArtifactWebDto();
		awd.setUuid(ad.getUuid());
		awd.setOrg(ad.getOrg());
		awd.setType(ad.getType());
		awd.setDisplayIdentifier(ad.getDisplayIdentifier());
		awd.setIdentities(new LinkedList<>(ad.getIdentities()));
		awd.setDownloadLinks(new LinkedList<>(ad.getDownloadLinks()));
		awd.setInventoryTypes(new LinkedList<>(ad.getInventoryTypes()));
		awd.setBomFormat(ad.getBomFormat());
		awd.setDate(ad.getDate());
		awd.setStoredIn(ad.getStoredIn());
		awd.setInternalBom(ad.getInternalBom());
		awd.setDigestRecords(new LinkedHashSet<>(ad.getDigestRecords()));
		awd.setTags(new LinkedList<>(ad.getTags()));
		awd.setStatus(ad.getStatus());
		awd.setVersion(ad.getVersion());
		awd.setMetrics(ad.getMetrics());
		awd.setComponentUuid(component);
		awd.setArtifacts(ad.getArtifacts());
		return awd;
	}
}
