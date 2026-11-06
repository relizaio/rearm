/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * Whether a regulatory package kind is switched on at a scope (FDAW-18, B5): the stored switch of a
 * package settings row, which replaced the Boolean {@code enabled}. A kind is never on until someone
 * says so: an absent value on a non-FOLLOW row and the built-in default read DISABLED.
 */
public enum PackageKindState {
	ENABLED,
	DISABLED
}
