/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import io.reliza.model.tea.TeaChecksumType;

/**
 * The digest algorithms ReARM records on artifacts and deliverables (task TEA-6). The constants and
 * their JSON strings are those of the TEA 0.4.0 generated checksum enum, which these records were
 * stored with, so every stored row reads back unchanged. TEA 1.0.0 dropped MD5 from its checksum
 * vocabulary: {@link #toTea()} is the one crossing to TEA, and an MD5 digest never crosses it.
 */
public enum DigestAlgorithm {

	MD5("MD5"),
	SHA_1("SHA-1"),
	SHA_256("SHA-256"),
	SHA_384("SHA-384"),
	SHA_512("SHA-512"),
	SHA3_256("SHA3-256"),
	SHA3_384("SHA3-384"),
	SHA3_512("SHA3-512"),
	BLAKE2B_256("BLAKE2b-256"),
	BLAKE2B_384("BLAKE2b-384"),
	BLAKE2B_512("BLAKE2b-512"),
	BLAKE3("BLAKE3");

	private final String value;

	DigestAlgorithm(String value) {
		this.value = value;
	}

	@JsonValue
	public String getValue() {
		return value;
	}

	@Override
	public String toString() {
		return value;
	}

	/** Reads the stored string, or the constant name. */
	@JsonCreator
	public static DigestAlgorithm fromValue(String value) {
		for (DigestAlgorithm a : values()) {
			if (a.value.equals(value)) return a;
		}
		for (DigestAlgorithm a : values()) {
			if (a.name().equals(value)) return a;
		}
		throw new IllegalArgumentException("Unexpected value '" + value + "'");
	}

	/** The TEA 1.0.0 checksum type of the same string; empty for MD5, which TEA 1.0.0 cannot express. */
	public Optional<TeaChecksumType> toTea() {
		for (TeaChecksumType t : TeaChecksumType.values()) {
			if (t.getValue().equals(value)) return Optional.of(t);
		}
		return Optional.empty();
	}
}
