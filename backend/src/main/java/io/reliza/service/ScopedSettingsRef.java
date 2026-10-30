/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.UUID;

import io.reliza.model.SettingsScope;

/**
 * A channel's scoped settings that name an SBOM profile (task TEA-27): its scope and object, and how
 * a refusal names it ("component widget", "perspective Alpha", "the organization").
 */
public record ScopedSettingsRef(SettingsScope scope, UUID object, String label) {}
