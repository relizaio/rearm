/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea.publication;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.reliza.model.tea.TeaChecksumType;

/**
 * A TEA 1.0.0 {@code checksum} object as it stands in a frozen collection document (task TEA-5):
 * {@code algType} is the TEA checksum type, written as its spec string ({@code SHA-256}) by the
 * generated enum's {@code @JsonValue}, so a frozen document reads back and hashes exactly as before;
 * {@code algValue} is lowercase hex.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "algType", "algValue" })
public record TeaChecksumDocument(TeaChecksumType algType, String algValue) implements Serializable {
}
