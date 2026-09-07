/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Provenance of a per-component support/EOS assertion. Set SERVER-SIDE per write
 * path, never read from client input or from an ingested BOM: MANUAL in the
 * setSbomComponentSupport mutation, SUPPLIER at BOM reconcile (later slice),
 * ENRICHED is reserved for the future BEAR integration (t20260830-164619-4069). Precedence for the merge
 * is MANUAL greater-than SUPPLIER greater-than ENRICHED.
 */
public enum SupportSource {
	MANUAL, SUPPLIER, ENRICHED
}
