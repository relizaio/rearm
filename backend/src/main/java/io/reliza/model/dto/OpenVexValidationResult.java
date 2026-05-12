/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.List;

public record OpenVexValidationResult(boolean valid, List<String> errors) {
    public static OpenVexValidationResult ok() { return new OpenVexValidationResult(true, List.of()); }
    public static OpenVexValidationResult fail(String... errs) { return new OpenVexValidationResult(false, List.of(errs)); }
}
