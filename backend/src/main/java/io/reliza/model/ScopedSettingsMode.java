/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * What a COMPONENT-scope scoped settings row does (task TEA-27): carry its own settings, or follow
 * one of its perspectives' row of the same type (and, for SBOM profiles, of the same name).
 */
public enum ScopedSettingsMode { OVERRIDE, FOLLOW_PERSPECTIVE }
