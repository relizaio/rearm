/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea.publication;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A TEA 1.0.0 {@code artifact-format} of a frozen collection document (task TEA-5). A format the
 * TEA server hosts itself carries no {@code url}: clients then read it from the artifact download
 * endpoint, so the frozen document names no ReARM host. A signature stored outside ReARM is frozen as
 * {@code signatureUrl} (task TEA-20); a hosted signature carries none and is read from the signature endpoint.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "mediaType", "description", "url", "signatureUrl", "checksums" })
public record TeaArtifactFormatDocument(String mediaType, String description, String url, String signatureUrl,
		List<TeaChecksumDocument> checksums) implements Serializable {
}
