/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/** What an overlay row describes (FDAW-15 3.2). Fixed when the row is created. Stored by name. */
public enum OverlayKind {
	/** A component the uploaded document identifies: the key is an {@code sbom_components.canonical_purl}. */
	COMPONENT,
	/** A keyless node: the key is minted by {@code VendoredIdentifier}. */
	VENDORED
}
