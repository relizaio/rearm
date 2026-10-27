/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * The outcome of a scoped settings resolution (task TEA-27). NOT_FOUND: no row at any scope and no
 * built-in default, which only a type without one (an SBOM profile of any name but default) can answer.
 */
public enum ScopedResolutionStatus { RESOLVED, CONFLICT, NOT_FOUND }
