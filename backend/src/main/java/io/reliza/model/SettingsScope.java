/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * Where a scoped settings row lives (task TEA-27): the organization (no object), a perspective
 * (ReARM Pro) or a component. Shared by every scoped settings type: SBOM profiles, TEA publishing
 * settings and, later, package settings.
 */
public enum SettingsScope { ORGANIZATION, PERSPECTIVE, COMPONENT }
