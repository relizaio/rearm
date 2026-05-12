/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

/**
 * Provenance of a Component's resolved approval policy. See
 * {@code OrgApprovalPolicyService.Snapshot}. Top-level so DGS can map
 * the GraphQL {@code ApprovalPolicySource} enum onto this Java type by
 * simple-name lookup.
 */
public enum ApprovalPolicySource {
	/** Per-component approvalPolicy is set and the referenced policy still exists. */
	PER_COMPONENT,
	/** Inherited from an org-wide GlobalApprovalPolicyRule. */
	ORG_RULE,
	/** Neither per-component nor any matching org rule — no policy in effect. */
	NONE
}
