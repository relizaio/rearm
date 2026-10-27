/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea.publication;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.reliza.model.tea.TeaArtifactType;

/**
 * A TEA 1.0.0 {@code artifact} object of a frozen collection document (task TEA-5): the TEA
 * artifact id, its revision, and one format. {@code createdDate} is a UTC instant ending in Z.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "uuid", "version", "name", "type", "createdDate", "formats" })
public record TeaArtifactDocument(UUID uuid, int version, String name, TeaArtifactType type,
		String createdDate, List<TeaArtifactFormatDocument> formats) implements Serializable {
}
