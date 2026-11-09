/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

/**
 * The predefined safety integrity level schemes of the pinned CycloneDX 2.0 snapshot. Not read by
 * Jackson directly: a document's scheme is read as {@link Cdx20Scheme} and mapped with
 * {@link #fromValue}, so a custom or unknown scheme never fails the document.
 */
public enum Cdx20SafetyScheme {
	ISO_26262("ISO-26262"),
	IEC_61508("IEC-61508"),
	DO_178C("DO-178C"),
	IEC_62304("IEC-62304"),
	EN_50128("EN-50128");

	private final String value;

	Cdx20SafetyScheme(String value) {
		this.value = value;
	}

	/** The scheme as the document writes it. */
	public String value() {
		return value;
	}

	/** The scheme written as {@code value}, or null when it is not predefined. */
	public static Cdx20SafetyScheme fromValue(String value) {
		if (value == null) return null;
		for (Cdx20SafetyScheme s : values()) {
			if (s.value.equals(value)) return s;
		}
		return null;
	}
}
