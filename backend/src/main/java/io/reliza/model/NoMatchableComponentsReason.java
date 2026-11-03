/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Why a mapped BOM's canonical has no gate-matchable component (TPF-121 architecture 4.6, the vocabulary
 * TPF-125/TPF-126 reuse). Derived from SyntheticSbomService.isGateMatchable in this precedence. Exact simple
 * name and values of the schema enum (GraphQlSchemaEnumSyncTest).
 */
public enum NoMatchableComponentsReason {
	/** The canonical has no component rows at all. */
	NO_COMPONENTS,
	/** Every component row is the BOM's own root component. */
	ONLY_ROOT,
	/** Every non-root component row is enrichment-terminal. */
	ALL_TERMINAL,
	/** Non-root, non-terminal rows exist, but none carries a pkg: or cpe: purl. */
	NO_PURL_OR_CPE
}
