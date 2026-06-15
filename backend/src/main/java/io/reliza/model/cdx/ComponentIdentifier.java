/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.RearmIdentifier;
import lombok.Data;

/**
 * Identity claims of an HBOM component grouped by the asserting party, per
 * the CycloneDX 2.0 identity model (spec PR #936). {@code party} is the
 * bom-ref of the {@link Party} making the assertion; each claim pairs a
 * scheme ({@link io.reliza.model.RearmIdentifierType} — MPN, PART_NUMBER,
 * SERIAL, UDI_DI, ...) with the asserted value. Custom (non-taxonomy) schemes
 * are dropped at ingest.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComponentIdentifier {
	private String bomRef;
	private String party;
	private List<RearmIdentifier> identities;
}
