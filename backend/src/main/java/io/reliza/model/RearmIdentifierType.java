/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * The single identifier-type enum for ReARM-owned data — a superset of
 * {@link io.reliza.model.tea.TeaIdentifierType} (which is OpenAPI-generated
 * from the TEA spec and must not be edited). Components, releases, and
 * deliverables store one {@code identifiers} list typed with this enum; at
 * the TEA boundary only the entries whose type name exists in the TEA enum
 * are exported (see {@code TeaTransformerService.toTeaIdentifiers}).
 *
 * <p>UDI and its DI/PI components are FDA device-identity concepts ReARM
 * owns internally:
 * <ul>
 *   <li>{@code UDI} — the literal labeled/scanned carrier string. Authoritative
 *       for search. Stored verbatim rather than synthesized from DI+PI, because
 *       synthesis is issuing-agency-specific (GS1 AIDC vs HIBCC vs ICCBBA).</li>
 *   <li>{@code UDI_DI} — the static Device Identifier (identifies version/model,
 *       keys the GUDID record).</li>
 *   <li>{@code UDI_PI} — the Production Identifier (lot, serial, dates, software
 *       version). Per-shipment PI values live on the ShippedProduct record;
 *       the release carries the software-version element.</li>
 * </ul>
 */
public enum RearmIdentifierType {
	// TEA-exportable types — names must match io.reliza.model.tea.TeaIdentifierType
	CPE,
	TEI,
	PURL,
	COMPLIANCE_DOCUMENT,
	// ReARM-internal types, never exported to TEA
	UDI,
	UDI_DI,
	UDI_PI,
	/** Per-unit serial number (a device-level production identifier). CDX scheme {@code serial-number}. */
	SERIAL,
	/** Batch / lot number (a shipment-level production identifier). */
	LOT,
	// Remaining CDX 2.0 identity schemes (spec PR #936). CDX tokens are the
	// lowercase-hyphenated enum names (PART_NUMBER <-> part-number) except
	// serial-number <-> SERIAL; the rebom extractor owns that normalization.
	SWID,
	SWHID,
	OMNIBORID,
	GTIN,
	GMN,
	/** Manufacturer Part Number, assigned by the original manufacturer. */
	MPN,
	/** Generic part number assigned by a distributor, integrator, or operator. */
	PART_NUMBER,
	MODEL_NUMBER,
	SKU,
	ASSET_TAG,
	FCC_ID,
	IMEI,
	MAC_ADDRESS;
}
