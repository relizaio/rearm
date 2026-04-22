/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.UUID;

import io.reliza.common.CommonVariables.AuthHeaderParse;

/**
 * Programmatic authentication context: the parsed auth header alongside the
 * effective org for the authenticated key. Exists so that callers needing the
 * org for a FREEFORM key (whose auth header does not embed it) can obtain it
 * without mutating the immutable {@link AuthHeaderParse}.
 */
public record ProgrammaticAuthContext(AuthHeaderParse ahp, UUID orgUuid) {}
