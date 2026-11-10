/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The record of an overlay row (FDAW-15 3.2). {@code supplier} is written into the node's
 * {@code supplier.name}, {@code licence} is in {@code SpdxLicenseExpressions.canonical} form, and
 * {@code description} is written into the node's {@code description}; {@code origin} stays in ReARM and is
 * never exported; {@code name} and {@code version} are a vendored node's, as sent. {@code assertedBy} and
 * {@code assertedAt} (RFC-3339 UTC) are the last write's. {@code supplierBulkAct} is the
 * {@code support_bulk_acts.uuid} when a sweep wrote the supplier.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SbomComponentOverlayData(String supplier, String licence, String description, String origin,
		String name, String version, UUID assertedBy, String assertedAt, UUID supplierBulkAct)
		implements Serializable {

	public static SbomComponentOverlayData empty() {
		return new SbomComponentOverlayData(null, null, null, null, null, null, null, null, null);
	}
}
