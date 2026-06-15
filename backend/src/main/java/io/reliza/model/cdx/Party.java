/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * An organizational party on a hardware deliverable / HBOM component, aligned
 * 1:1 with the CycloneDX 2.0 generic parties model (spec PR #930): an entity
 * with one or more roles, address, and contacts. Round-trips to/from a CDX
 * entity fragment. {@code origin} carries this party's country-of-origin /
 * multi-source split (a ReARM/HBOM extension).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Party {
	private List<PartyRole> roles;
	private String bomRef;
	private Integer priority;
	private String name;
	private Address address;
	private List<Contact> contacts;
	private String url;
	private Origin origin;
}
