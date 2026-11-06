/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Why a component's end-of-support date or hash cannot be given: the justification FDA's
 * premarket guidance (section V.A.4(b)) asks for when an SBOM element is missing (FDAW-1 D5).
 * ReARM writes the enum NAME as the value of the server-owned component properties
 * {@code reliza:support:justifiedUnknown:endOfSupport} and
 * {@code reliza:support:justifiedUnknown:hash} (cyclonedx-taxonomy.md), and the fda profile of
 * {@code rearm bomutils score} counts a component that lacks the fact but carries one of these
 * names, matched exactly, as justified. The names are published taxonomy: renaming or removing
 * one breaks every CLI that reads them; adding one needs the CLI's list changed too. The free
 * text of a justification stays in ReARM and is not written into the BOM.
 */
public enum JustifiedUnknownReason {
	NO_COMMITTED_END_OF_SUPPORT,
	COMMUNITY_PROJECT,
	SUPPLIER_PUBLISHES_NO_DATE,
	DISTRIBUTION_PUBLISHES_NO_HASH,
	VENDORED_SOURCE_NO_HASH,
	OTHER
}
