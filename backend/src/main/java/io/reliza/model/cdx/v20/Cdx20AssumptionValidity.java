/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Validity of a blueprint assumption. An unknown value reads as null rather than failing the document. */
public enum Cdx20AssumptionValidity {
	INVALID("invalid"),
	UNKNOWN("unknown"),
	UNVERIFIED("unverified"),
	VERIFIED("verified");

	private final String value;

	Cdx20AssumptionValidity(String value) {
		this.value = value;
	}

	@JsonValue
	public String value() {
		return value;
	}

	@JsonCreator
	public static Cdx20AssumptionValidity fromValue(String value) {
		if (value == null) return null;
		for (Cdx20AssumptionValidity v : values()) {
			if (v.value.equals(value)) return v;
		}
		return null;
	}
}
