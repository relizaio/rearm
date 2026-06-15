/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.model.cdx.ComponentIdentifier;
import io.reliza.model.cdx.Party;
import io.reliza.model.cdx.PartyRole;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

/** A parsed HBOM hardware node (device/firmware) attached to a release. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HbomComponentData {

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;
	private UUID release;

	private String bomRef;
	private String type; // device | component-choice
	/** CDX #929 choice operator (XOR / AND / OPTIONAL); null for plain devices. */
	private String operator;
	private String name;
	private String version;
	private String description;
	private String category;
	private String subcategory;
	/** CDX 2.0 parties (manufacturer/supplier/... with address), spec PR #930. */
	private List<Party> parties;
	/** Party-asserted identity claims (CDX 2.0 identity model, spec PR #936). */
	private List<ComponentIdentifier> identifiers;
	private String boardLocation;
	private String deviceType;
	private Integer quantity;
	private String parentRef;
	private Boolean isRoot;


	/** Derived display value: first MANUFACTURER-role party's name, else first party. */
	@JsonIgnore
	public String getManufacturer() {
		if (parties == null || parties.isEmpty()) return null;
		return parties.stream()
				.filter(p -> p.getRoles() != null && p.getRoles().contains(PartyRole.MANUFACTURER))
				.map(Party::getName)
				.filter(Objects::nonNull)
				.findFirst()
				.orElseGet(() -> parties.stream().map(Party::getName).filter(Objects::nonNull).findFirst().orElse(null));
	}

	private static final Set<RearmIdentifierType> PART_NUMBER_SCHEMES =
			Set.of(RearmIdentifierType.MPN, RearmIdentifierType.PART_NUMBER, RearmIdentifierType.MODEL_NUMBER,
					RearmIdentifierType.SKU, RearmIdentifierType.GTIN, RearmIdentifierType.GMN);

	/** Derived display value: part-number-like claims across all identifiers, deduped, insertion order. */
	@JsonIgnore
	public List<String> getPartNumbers() {
		if (identifiers == null || identifiers.isEmpty()) return List.of();
		LinkedHashSet<String> out = new LinkedHashSet<>();
		for (ComponentIdentifier ci : identifiers) {
			if (ci == null || ci.getIdentities() == null) continue;
			for (RearmIdentifier ri : ci.getIdentities()) {
				if (ri != null && ri.getIdValue() != null && PART_NUMBER_SCHEMES.contains(ri.getIdType())) {
					out.add(ri.getIdValue());
				}
			}
		}
		return List.copyOf(out);
	}

	public static HbomComponentData dataFromRecord(HbomComponent h) {
		if (h.getSchemaVersion() != 0) {
			throw new IllegalStateException("HbomComponent schema version is " + h.getSchemaVersion()
					+ ", which is not currently supported");
		}
		Map<String, Object> recordData = h.getRecordData();
		HbomComponentData hd = Utils.OM.convertValue(recordData, HbomComponentData.class);
		hd.setUuid(h.getUuid());
		return hd;
	}
}
