/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

/**
 * Provenance of a VCS repository's resolved PR-validation trigger. See
 * {@code OrgValidationTriggerService.PrValidationTriggerResolution} for the resolver. Top-
 * level so DGS can map the GraphQL {@code PrValidationTriggerSource}
 * enum onto this Java type by simple-name lookup.
 */
public enum PrValidationTriggerSource {
	/** Per-repo trigger configured directly on this VCS row. */
	PER_VCS,
	/** Inherited from an org-wide GlobalPrValidationTriggerRule. */
	ORG_RULE,
	/** Neither per-repo nor any matching org rule — no verdict will be dispatched. */
	NONE
}
