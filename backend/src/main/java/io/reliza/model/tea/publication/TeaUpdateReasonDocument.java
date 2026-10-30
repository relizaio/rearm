/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea.publication;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.reliza.model.tea.TeaCollectionUpdateReasonType;

/** The TEA 1.0.0 {@code collection-update-reason} of a frozen collection document (task TEA-5). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "type", "comment" })
public record TeaUpdateReasonDocument(TeaCollectionUpdateReasonType type, String comment) implements Serializable {
}
